package za.co.cttrains.app.developer

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import za.co.cttrains.app.data.SecureCodec
import za.co.cttrains.core.*

/** Only the public HTTPS endpoint is compiled into the APK. No root code, invitation hashes or write token. */
class ControlClient {
    private val http=OkHttpClient.Builder().followRedirects(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
    suspend fun request(path: String, token: String="", body: JsonObject?=null): JsonObject=withContext(Dispatchers.IO) {
        val b=Request.Builder().url("https://cttrains-developer-control.xhantimbasa10.workers.dev$path")
        if(token.isNotBlank()) b.header("Authorization","Bearer $token")
        if(body!=null) b.post(body.toString().toRequestBody("application/json".toMediaType()))
        val call=http.newCall(b.build())
        val cancel=currentCoroutineContext()[Job]?.invokeOnCompletion { if(it!=null) call.cancel() }
        try { call.execute().use { r ->
            if(r.code==401) throw AccessExpired()
            require(r.isSuccessful) { if(r.code==403) "Owner access is required, or this control is retired." else if(r.code==429) "Too many attempts. Wait a minute." else "Developer service is unavailable. Try again later." }
            val source=r.body?.source() ?: error("No server response.")
            require(!source.request(65537)) { "Server response is too large." }
            TripFiles.json.parseToJsonElement(source.readUtf8()).jsonObject
        } } finally { cancel?.dispose() }
    }
}
class AccessExpired: Exception("Code is invalid, used, expired, or access has been revoked.")
class DeveloperStore(c: Context) {
    private val prefs=c.getSharedPreferences("developer-private",Context.MODE_PRIVATE)
    private val codec=SecureCodec()
    fun session(): DeveloperSession?=prefs.getString("session",null)?.let { runCatching { TripFiles.json.decodeFromString<DeveloperSession>(codec.decrypt(it)) }.getOrNull() }
    fun session(v: DeveloperSession?) { prefs.edit().apply { if(v==null) remove("session") else putString("session",codec.encrypt(TripFiles.json.encodeToString(v))) }.commit() }
    fun log(event: String) { // Allowlisted events only: never locations, entered codes, tokens, URLs or raw exception messages.
        if(event !in setOf("unlock","locked","verified","network-unavailable","access-revoked","owner-action","screen-toggle","diagnostics","policy-check","update-check","update-failed","update-verified","search-start","search-failed")) return
        val rows=(logs()+"${System.currentTimeMillis()} · $event").takeLast(60)
        prefs.edit().putString("events",rows.joinToString("\n")).apply()
    }
    fun logs()=prefs.getString("events","").orEmpty().lines().filter { it.isNotBlank() }
    fun clearLogs() { prefs.edit().remove("events").apply() }
}
data class DeveloperState(val verified: Boolean=false,val session: DeveloperSession?=null,val busy: Boolean=false,val error: String?=null,
    val policy: ControlPolicy=ControlPolicy(),val disabled: Set<String> = emptySet(),val codes: List<String> = emptyList(),val access: String="",val events: List<String> = emptyList())
class DeveloperModel(app: Application): AndroidViewModel(app) {
    private val store=DeveloperStore(app)
    private val client=ControlClient()
    private val mutable=MutableStateFlow(DeveloperState())
    val state=mutable.asStateFlow()
    private var checking=false
    fun refresh() { if(checking) return;checking=true;viewModelScope.launch {
        mutable.update { it.copy(verified=false,codes=emptyList()) }
        try {
            val s=withContext(Dispatchers.IO) { store.session() }
            if(s!=null && s.valid(System.currentTimeMillis()/1000)) {
                try { val v=client.request("/session",s.token);val verified=s.copy(role=v["role"]!!.jsonPrimitive.content,expires=v["expires"]!!.jsonPrimitive.long)
                    mutable.update { it.copy(verified=true,session=verified,error=null) };store.log("verified")
                } catch(e: Exception) { if(e is CancellationException) throw e;if(e is AccessExpired) { store.session(null);store.log("access-revoked") };mutable.update { it.copy(session=null,disabled=emptySet()) } }
            } else { store.session(null);mutable.update { it.copy(session=null,disabled=emptySet()) } }
            try { val p=TripFiles.json.decodeFromJsonElement<ControlPolicy>(client.request("/policy"));mutable.update { it.copy(policy=p) } }
            catch(e: Exception) { if(e is CancellationException) throw e }

        } finally { checking=false;mutable.update { it.copy(events=store.logs()) } }
    } }
    fun unlock(code: String)=action {
        val v=client.request("/redeem",body=buildJsonObject { put("code",code) })
        val s=TripFiles.json.decodeFromJsonElement<DeveloperSession>(v);require(s.valid(System.currentTimeMillis()/1000))
        store.session(s);store.log("unlock");mutable.update { it.copy(session=s,verified=true,codes=emptyList()) }
    }
    fun lock() { store.session(null);store.log("locked");mutable.update { DeveloperState(policy=it.policy,events=store.logs()) } }
    fun toggle(route: String) { if(!state.value.verified) return;store.log("screen-toggle");mutable.update { it.copy(disabled=if(route in it.disabled) it.disabled-route else it.disabled+route) } }
    fun owner(path: String,body: JsonObject)=action {
        val s=state.value.session;require(state.value.verified && s?.role=="owner" && s.valid(System.currentTimeMillis()/1000))
        val v=client.request("/owner/$path",s!!.token,body);store.log("owner-action")
        mutable.update { it.copy(codes=v["codes"]?.jsonArray?.map { row->row.jsonPrimitive.content } ?: emptyList(),access=if(path=="access") v.toString() else it.access) }
        if(path=="policy") refresh()
    }
    fun hideCodes() { mutable.update { it.copy(codes=emptyList()) } }
    fun clearLogs() { store.clearLogs();mutable.update { it.copy(events=emptyList()) } }
    fun diagnostics(): String {
        val app=getApplication<Application>();val am=app.getSystemService(android.app.ActivityManager::class.java)
        val info=android.app.ActivityManager.MemoryInfo();am.getMemoryInfo(info);val mem=android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(mem)
        store.log("diagnostics")
        return "CT Trains ${za.co.cttrains.app.BuildConfig.VERSION_NAME}\nAndroid API ${android.os.Build.VERSION.SDK_INT}\nApp PSS ${mem.totalPss/1024} MB\nSystem low memory ${info.lowMemory}\nFree app storage ${android.os.StatFs(app.filesDir.path).availableBytes/1024/1024} MB\n\nDeveloper events (no trip or account data)\n"+store.logs().joinToString("\n")
    }
    private fun action(block: suspend ()->Unit) { if(state.value.busy) return;viewModelScope.launch {
        mutable.update { it.copy(busy=true,error=null,codes=emptyList()) }
        try { block() } catch(e: Exception) { if(e is CancellationException) throw e;if(e is AccessExpired) lock();store.log("network-unavailable");mutable.update { it.copy(error=if(e is AccessExpired) e.message else "Could not complete the server action. Check access and connection, then try again.") } }
        finally { mutable.update { it.copy(busy=false,events=store.logs()) } }
    } }
}

object DeveloperLog {
    fun event(c: Context,name: String) {
        val store=DeveloperStore(c)
        if(store.session()?.valid(System.currentTimeMillis()/1000)==true) store.log(name)
    }
}
