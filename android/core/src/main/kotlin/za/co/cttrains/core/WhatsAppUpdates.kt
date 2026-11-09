package za.co.cttrains.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

@Serializable enum class WhatsAppFeedKind { CHANNEL,GROUP }
@Serializable data class WhatsAppFeed(val id: String,val title: String,val kind: WhatsAppFeedKind,val service: TransportService=TransportService.PRASA)
@Serializable data class WhatsAppConnection(val server: String,val session: String,val key: String,val enabled: Boolean=true,val background: Boolean=false,val feeds: List<WhatsAppFeed> = emptyList(),val identity: String="") {
    override fun toString()="WhatsAppConnection(credentials=redacted, enabled=$enabled, feeds=${feeds.size})"
}
@Serializable data class WhatsAppPost(val id: String,val feed: WhatsAppFeed,val body: String,val postedAt: Long?,val fetchedAt: Long)
@Serializable data class WhatsAppInbox(val posts: List<WhatsAppPost> = emptyList(),val checkedAt: Long=0,val issue: String?=null,val retryAt: Long=0)
data class WhatsAppSession(val id: String,val name: String,val status: String)

object WhatsAppUpdates {
    fun apiBase(value: String): String {
        val text=value.trim().trimEnd('/');val uri=URI(text)
        require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null && text.length<=495)
        return if(uri.path.isNullOrEmpty()) "$text/api" else text
    }
    fun sessions(raw: String): List<WhatsAppSession> {
        require(raw.toByteArray().size<=512*1024)
        return Json.parseToJsonElement(raw).jsonArray.take(100).mapNotNull { row -> runCatching {
            val o=row.jsonObject;val id=o.getValue("id").jsonPrimitive.content
            require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(id))
            WhatsAppSession(id,o["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(100),o["status"]?.jsonPrimitive?.contentOrNull.orEmpty().take(30))
        }.getOrNull() }.distinctBy { it.id }
    }
    fun validate(c: WhatsAppConnection): WhatsAppConnection {
        val u=URI(c.server)
        require(u.scheme=="https" && u.host!=null && u.userInfo==null && u.query==null && u.fragment==null && c.server.length<=500)
        require(Regex("[A-Za-z0-9_-]{1,100}").matches(c.session))
        require(c.key.length in 1..500 && c.key.none { it.isISOControl() })
        require(c.feeds.size<=5 && c.feeds.map { it.id }.distinct().size==c.feeds.size)
        c.feeds.forEach(::validateFeed)
        return c
    }
    fun validateFeed(f: WhatsAppFeed): WhatsAppFeed {
        require(f.title.length in 1..100)
        require(Regex(if(f.kind==WhatsAppFeedKind.CHANNEL) "[0-9]{5,30}@newsletter" else "[0-9]{5,30}(?:-[0-9]{1,20})?@g\\.us").matches(f.id))
        return f
    }
    fun channels(raw: String): List<WhatsAppFeed> {
        require(raw.toByteArray().size<=512*1024)
        return Json.parseToJsonElement(raw).jsonArray.take(100).mapNotNull { item -> runCatching {
            val o=item.jsonObject
            validateFeed(WhatsAppFeed(o.getValue("id").jsonPrimitive.content,o.getValue("name").jsonPrimitive.content.take(100),WhatsAppFeedKind.CHANNEL))
        }.getOrNull() }.distinctBy { it.id }
    }
    fun posts(raw: String,feed: WhatsAppFeed,now: Long): List<WhatsAppPost> {
        require(raw.toByteArray().size<=512*1024);validateFeed(feed)
        val root=Json.parseToJsonElement(raw)
        val rows=if(feed.kind==WhatsAppFeedKind.CHANNEL) root.jsonArray else root.jsonObject.getValue("messages").jsonArray
        return rows.take(50).mapNotNull { item -> runCatching {
            val o=item.jsonObject
            if(feed.kind==WhatsAppFeedKind.GROUP) require(o["chatId"]?.jsonPrimitive?.content==feed.id)
            val id=o["id"]?.jsonPrimitive?.content.orEmpty();require(id.length in 1..250 && id.none(Char::isISOControl))
            val stamp=o["timestamp"]?.jsonPrimitive?.longOrNull?.takeIf { it>0 && it<Long.MAX_VALUE/1000 }?.times(1000)
            require(stamp==null || stamp<=now+30_000 && now-stamp<=30L*24*60*60_000)
            val body=o["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(6000).ifBlank { "Media-only post · open WhatsApp to view the attachment" }
            WhatsAppPost(id,feed,body,stamp,now)
        }.getOrNull() }.distinctBy { it.feed.id+":"+it.id }
    }
    fun shareText(post: WhatsAppPost)="${post.feed.service.title} · WhatsApp observation\n${post.feed.title}\n"+
        (post.postedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(CapeTownZone).toString() } ?: "Posted time not supplied")+"\n\n${post.body}\n\nUnverified observation. Confirm current service with the operator."
}
