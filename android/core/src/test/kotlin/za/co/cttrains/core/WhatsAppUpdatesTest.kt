package za.co.cttrains.core

import org.junit.Test
import org.junit.Assert.*

class WhatsAppUpdatesTest {
    private val now=1_790_923_200_000L
    private val channel=WhatsAppFeed("123456789@newsletter","Selected train channel",WhatsAppFeedKind.CHANNEL)
    @Test fun subscribedChannelIdentityIsReadFromGatewayRatherThanAssumed() {
        val feeds=WhatsAppUpdates.channels("""[{"id":"123456789@newsletter","name":"Actual account channel"},{"id":"invalid","name":"Unknown"}]""")
        assertEquals(1,feeds.size);assertEquals("Actual account channel",feeds.single().title)
    }
    @Test fun unixSecondsBecomePostedTimeAndUnknownTimeStaysUnknown() {
        val posts=WhatsAppUpdates.posts("""[{"id":"one","body":"Delayed train","timestamp":1790923200},{"id":"two","body":null,"timestamp":null}]""",channel,now)
        assertEquals(now,posts.first().postedAt);assertNull(posts.last().postedAt)
        assertTrue(posts.last().body.contains("Media-only"))
        assertTrue(WhatsAppUpdates.shareText(posts.last()).contains("Posted time not supplied"))
    }
    @Test fun foreignGroupFuturePostsOldPostsAndDuplicatesAreExcluded() {
        val group=WhatsAppFeed("123456789@g.us","Selected group",WhatsAppFeedKind.GROUP)
        val raw="""{"messages":[{"id":"a","chatId":"999999999@g.us","body":"Wrong group"},{"id":"b","chatId":"123456789@g.us","body":"Future","timestamp":1790933200},{"id":"c","chatId":"123456789@g.us","body":"Old","timestamp":1690923200},{"id":"d","chatId":"123456789@g.us","body":"Current","timestamp":1790923200},{"id":"d","chatId":"123456789@g.us","body":"Current","timestamp":1790923200}]}"""
        assertEquals(listOf("d"),WhatsAppUpdates.posts(raw,group,now).map { it.id })
    }
    @Test fun credentialsStayOutOfUrlsAndDebugStrings() {
        val c=WhatsAppConnection("https://gateway.example/api","test","private-key",feeds=listOf(channel))
        assertEquals(c,WhatsAppUpdates.validate(c));assertFalse(c.toString().contains("private-key"))
        for(url in listOf("http://gateway.example/api","https://key@gateway.example/api","https://gateway.example/api?key=secret","https://gateway.example/api#secret"))
            assertTrue(runCatching { WhatsAppUpdates.validate(c.copy(server=url)) }.isFailure)
        assertTrue(runCatching { WhatsAppUpdates.validate(c.copy(key="a\nb")) }.isFailure)
        assertTrue(runCatching { WhatsAppUpdates.validate(c.copy(feeds=listOf(channel,channel))) }.isFailure)
    }
    @Test fun setupNormalizesOnlyWebsiteOriginsAndReadsAccountUuids() {
        assertEquals("https://gateway.example/api",WhatsAppUpdates.apiBase(" https://gateway.example/ "))
        assertEquals("https://gateway.example/proxy/api",WhatsAppUpdates.apiBase("https://gateway.example/proxy/api/"))
        assertTrue(runCatching { WhatsAppUpdates.apiBase("http://gateway.example") }.isFailure)
        assertTrue(runCatching { WhatsAppUpdates.apiBase("https://user:secret@gateway.example") }.isFailure)
        val uuid="550e8400-e29b-41d4-a716-446655440000"
        val raw="""[{"id":"$uuid","name":"Transit","status":"ready"},{"id":"not-a-uuid","name":"Bad"}]"""
        val account=WhatsAppUpdates.sessions(raw).single()
        assertEquals(uuid,account.id);assertEquals("Transit",account.name);assertEquals("ready",account.status)
    }
}
