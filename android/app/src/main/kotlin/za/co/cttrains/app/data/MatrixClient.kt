package za.co.cttrains.app.data

import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import za.co.cttrains.core.*

data class RoomMessage(val id: String,val sender: String,val at: Long,val text: String,val report: CommunityReport? = null,val update: SealedUpdate? = null,val presence: NearbyPresence?=null)

/** Opt-in hosted Matrix transport. Credentials never enter a URL, log, portable profile or trip file. */
class MatrixClient {
    private val http=SourceHttp(redirects=false)
    private val json=Json { ignoreUnknownKeys=true }
    fun server(raw: String): String {
        val u=raw.trim().toHttpUrl()
        require(u.isHttps && u.username.isBlank() && u.password.isBlank() && u.query==null && u.fragment==null && u.encodedPath=="/") { "Enter an HTTPS homeserver address without a path." }
        return u.toString().trimEnd('/')
    }
    private suspend fun call(base: String,path: List<String>,method: String="GET",body: JsonObject?=null,session: MatrixSession?=null,query: Map<String,String> = emptyMap()): JsonObject {
        val url=server(base).toHttpUrl().newBuilder()
        (listOf("_matrix","client","v3")+path).forEach(url::addPathSegment)
        query.forEach { (k,v)->url.addQueryParameter(k,v) }
        val b=Request.Builder().url(url.build())
        if (session!=null) { require(server(session.server)==server(base)); b.header("Authorization","Bearer ${session.token}") }
        if (method!="GET") b.method(method,(body ?: buildJsonObject{}).toString().toRequestBody("application/json".toMediaType()))
        return json.parseToJsonElement(http.bytes(b.build(),2*1024*1024,setOf(url.build().host)).toString(Charsets.UTF_8)).jsonObject
    }
    suspend fun login(base: String,user: String,password: String): MatrixSession {
        require(user.length in 1..300 && password.length in 1..1024)
        val flows=call(base,listOf("login"))["flows"]?.jsonArray.orEmpty()
        require(flows.any { it.jsonObject["type"]?.jsonPrimitive?.content=="m.login.password" }) { "This server requires SSO. Use an access token obtained in its trusted client." }
        val result=call(base,listOf("login"),"POST",buildJsonObject {
            put("type","m.login.password");put("identifier",buildJsonObject { put("type","m.id.user");put("user",user) });put("password",password);put("initial_device_display_name","CT Trains")
        })
        return validateSession(MatrixSession(server(base),result.getValue("user_id").jsonPrimitive.content,result.getValue("access_token").jsonPrimitive.content,result["device_id"]?.jsonPrimitive?.content.orEmpty()))
    }
    suspend fun token(base: String,token: String): MatrixSession = validateSession(MatrixSession(server(base),"",token.trim(),""))
    private suspend fun validateSession(s: MatrixSession): MatrixSession {
        require(s.token.length in 1..8192 && !s.token.any { it.isWhitespace() || it.isISOControl() })
        val who=call(s.server,listOf("account","whoami"),session=s)
        val id=who.getValue("user_id").jsonPrimitive.content
        require(Regex("@[^\\s]{1,250}:[^\\s]{1,250}").matches(id) && (s.userId.isBlank() || s.userId==id))
        return s.copy(userId=id,deviceId=who["device_id"]?.jsonPrimitive?.content ?: s.deviceId)
    }
    suspend fun logout(s: MatrixSession) { call(s.server,listOf("logout"),"POST",session=s) }
    suspend fun join(s: MatrixSession,room: String): String {
        require(Regex("[!#][^\\s]{1,250}:[^\\s]{1,250}").matches(room))
        val id=call(s.server,listOf("join",room),"POST",session=s).getValue("room_id").jsonPrimitive.content
        plainRoom(s,id);return id
    }
    suspend fun create(s: MatrixSession,name: String,private: Boolean): String {
        val b=buildJsonObject {
            put("name",name.take(100));put("preset",if(private) "private_chat" else "public_chat");put("visibility",if(private) "private" else "public")
            put("initial_state",buildJsonArray { add(buildJsonObject { put("type","m.room.history_visibility");put("state_key","");put("content",buildJsonObject { put("history_visibility","joined") }) }) })
        }
        return call(s.server,listOf("createRoom"),"POST",b,s).getValue("room_id").jsonPrimitive.content
    }
    suspend fun invite(s: MatrixSession,room: String,user: String) {
        require(Regex("@[^\\s]{1,250}:[^\\s]{1,250}").matches(user));plainRoom(s,room,true)
        call(s.server,listOf("rooms",room,"invite"),"POST",buildJsonObject { put("user_id",user) },s)
    }
    suspend fun plainRoom(s: MatrixSession,room: String,private: Boolean=false) {
        val url=server(s.server).toHttpUrl().newBuilder().addPathSegments("_matrix/client/v3/rooms").addPathSegment(room).addPathSegment("state").build()
        val events=json.parseToJsonElement(http.bytes(Request.Builder().url(url).header("Authorization","Bearer ${s.token}").build(),1024*1024,setOf(url.host)).toString(Charsets.UTF_8)).jsonArray
        require(events.none { it.jsonObject["type"]?.jsonPrimitive?.content=="m.room.encryption" }) { "This Matrix room uses encryption requiring a full Matrix client. Open it in Element instead." }
        if(private) require(events.any { it.jsonObject["type"]?.jsonPrimitive?.content=="m.room.join_rules" && it.jsonObject["content"]?.jsonObject?.get("join_rule")?.jsonPrimitive?.content=="invite" }) { "Trip sharing requires an invite-only room." }
    }
    suspend fun send(s: MatrixSession,room: String,text: String,report: CommunityReport?=null,update: SealedUpdate?=null,transaction: String=UUID.randomUUID().toString(),presence: NearbyPresence?=null) {
        require(text.length in 1..2000);plainRoom(s,room,update!=null)
        val content=buildJsonObject {
            put("msgtype","m.text");put("body",text)
            report?.let { put("za.co.cttrains.report",json.encodeToJsonElement(it)) }
            update?.let { put("za.co.cttrains.trip_update",json.encodeToJsonElement(it)) }
            presence?.let { put("za.co.cttrains.nearby",json.encodeToJsonElement(NearbyPeople.validate(it))) }
        }
        call(s.server,listOf("rooms",room,"send","m.room.message",transaction),"PUT",content,s)
    }
    suspend fun messages(s: MatrixSession,room: String,private: Boolean=false): List<RoomMessage> {
        plainRoom(s,room,private)
        val r=call(s.server,listOf("rooms",room,"messages"),session=s,query=mapOf("dir" to "b","limit" to "100"))
        return r["chunk"]?.jsonArray.orEmpty().mapNotNull { e -> runCatching {
            val o=e.jsonObject; if(o["type"]?.jsonPrimitive?.content!="m.room.message") return@runCatching null
            val c=o.getValue("content").jsonObject
            val text=c["body"]?.jsonPrimitive?.content.orEmpty().take(2000)
            RoomMessage(o.getValue("event_id").jsonPrimitive.content,o.getValue("sender").jsonPrimitive.content,o.getValue("origin_server_ts").jsonPrimitive.long,text,
                c["za.co.cttrains.report"]?.let { runCatching { CommunityReports.validate(json.decodeFromJsonElement<CommunityReport>(it)) }.getOrNull() },
                c["za.co.cttrains.trip_update"]?.let { runCatching { json.decodeFromJsonElement<SealedUpdate>(it) }.getOrNull() },
                c["za.co.cttrains.nearby"]?.let { runCatching { NearbyPeople.validate(json.decodeFromJsonElement<NearbyPresence>(it)) }.getOrNull() })
        }.getOrNull() }.take(100)
    }
}
