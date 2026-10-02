package za.co.cttrains.app.whatsapp

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID
import java.util.concurrent.TimeUnit
import za.co.cttrains.app.data.*
import za.co.cttrains.core.*

class WhatsAppReader(context: Context) {
    private val store=LocalTransitStore.get(context)
    private val json=Json { ignoreUnknownKeys=true;encodeDefaults=true }
    private val http=SourceHttp(redirects=false)
    val connection=store.observeLocal(CONFIG).map { it?.takeIf(String::isNotBlank)?.let { raw -> runCatching { WhatsAppUpdates.validate(json.decodeFromString<WhatsAppConnection>(raw)) }.getOrNull() } }
    val inbox=store.observeLocal(CACHE).map { it?.takeIf(String::isNotBlank)?.let { raw -> runCatching { json.decodeFromString<WhatsAppInbox>(raw) }.getOrNull() } ?: WhatsAppInbox() }
    suspend fun config()=store.readLocal(CONFIG)?.takeIf(String::isNotBlank)?.let { runCatching { WhatsAppUpdates.validate(json.decodeFromString<WhatsAppConnection>(it)) }.getOrNull() }
    suspend fun saved()=store.readLocal(CACHE)?.takeIf(String::isNotBlank)?.let { runCatching { json.decodeFromString<WhatsAppInbox>(it) }.getOrNull() } ?: WhatsAppInbox()
    suspend fun save(c: WhatsAppConnection) { WhatsAppUpdates.validate(c);store.writeLocal(CONFIG,json.encodeToString(c.copy(identity=UUID.randomUUID().toString()))) }
    suspend fun disconnect() { store.writeLocal(CONFIG,"");store.writeLocal(CACHE,"") }
    private suspend fun get(c: WhatsAppConnection,path: List<String>,params: Map<String,String> = emptyMap()): String {
        val url=c.server.trimEnd('/').toHttpUrl().newBuilder()
        path.forEach { url.addPathSegment(it) };params.forEach { (k,v) -> url.addQueryParameter(k,v) }
        return http.bytes(Request.Builder().url(url.build()).header("X-API-Key",c.key).get().build(),512*1024,setOf(c.server.toHttpUrl().host)).toString(Charsets.UTF_8)
    }
    suspend fun channels(): List<WhatsAppFeed> { val c=config() ?: error("Configure OpenWA first");return WhatsAppUpdates.channels(get(c,listOf("sessions",c.session,"channels"))) }
    suspend fun sessions(server: String,key: String): List<WhatsAppSession> {
        val c=WhatsAppUpdates.validate(WhatsAppConnection(WhatsAppUpdates.apiBase(server),"setup",key.trim()))
        return WhatsAppUpdates.sessions(get(c,listOf("sessions"),mapOf("limit" to "100","offset" to "0")))
    }
    suspend fun check(c: WhatsAppConnection) {
        val row=Json.parseToJsonElement(get(c,listOf("sessions",c.session))).jsonObject
        require(row["id"]?.jsonPrimitive?.content==c.session) { "Unexpected linked account" }
        require(row["status"]?.jsonPrimitive?.content.equals("ready",true)) { "Start and link this account in the OpenWA dashboard first." }
    }
    suspend fun refresh()=gate.withLock {
        val c=config()?.takeIf { it.enabled && it.feeds.isNotEmpty() } ?: return@withLock
        val old=saved();val now=System.currentTimeMillis()
        if(now<old.retryAt || now-old.checkedAt in 0..45_000) return@withLock
        val posts=old.posts.filter { p -> c.feeds.any { it.id==p.feed.id } && now-p.fetchedAt in 0..30L*24*60*60_000 }.toMutableList()
        var issue: String?=null;var retryAt=0L
        for(feed in c.feeds) {
            if(config()?.run { !enabled || identity!=c.identity }!=false) return@withLock
            try {
                val path=if(feed.kind==WhatsAppFeedKind.CHANNEL) listOf("sessions",c.session,"channels",feed.id,"messages") else listOf("sessions",c.session,"messages")
                val params=if(feed.kind==WhatsAppFeedKind.CHANNEL) mapOf("limit" to "50") else mapOf("chatId" to feed.id,"limit" to "50","offset" to "0","inlineMedia" to "false")
                val fresh=withContext(Dispatchers.Default) { WhatsAppUpdates.posts(get(c,path,params),feed,now) }
                posts.removeAll { it.feed.id==feed.id };posts+=fresh
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                issue=when((e as? HttpStatusException)?.status) {
                    501->"This OpenWA engine cannot read channels. Channel reading needs whatsapp-web.js; check the gateway configuration."
                    401,403->"The gateway rejected access. Check the session and a Reader API key permitted for this feed."
                    429->"The gateway requested a pause. Background checks are delayed for 15 minutes."
                    else->"Some WhatsApp feeds could not be refreshed. Dated saved posts remain available."
                }
                if((e as? HttpStatusException)?.status==429) { retryAt=now+15*60_000L;break }
            }
        }
        if(config()?.run { enabled && identity==c.identity }==true) store.writeLocal(CACHE,json.encodeToString(WhatsAppInbox(posts.distinctBy { it.feed.id+":"+it.id }.sortedByDescending { it.postedAt ?: 0 }.take(200),now,issue,retryAt)))
    }
    companion object {
        private const val CONFIG="wa-alpha-config";private const val CACHE="wa-alpha-cache"
        private val gate=Mutex()
        fun configure(context: Context,c: WhatsAppConnection?) {
            val work=WorkManager.getInstance(context)
            if(c?.run { enabled && background && feeds.isNotEmpty() }==true) work.enqueueUniquePeriodicWork("whatsapp-alpha-reader",ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<WhatsAppReaderWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            else work.cancelUniqueWork("whatsapp-alpha-reader")
        }
    }
}
class WhatsAppReaderWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        try { WhatsAppReader(applicationContext).refresh() } catch(e: Exception) { if(e is CancellationException) throw e }
        return Result.success() // The optional reader never fails transit refresh or planner work.
    }
}
