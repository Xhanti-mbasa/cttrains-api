package za.co.cttrains.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class SharedTripsTest {
    private val now=LocalDate.parse("2026-10-02").atTime(7,0).atZone(CapeTownZone).toInstant().toEpochMilli()
    private val query=SearchQuery("Unibell","Salt River","2026-10-02","07:00")
    private val journey=Journey(listOf(Leg("Central","9006",listOf(Stop("Unibell","07:18","1"),Stop("Mutual","07:35","2"))),Leg("Northern","3204",listOf(Stop("Mutual","07:40","3"),Stop("Salt River","07:50")))))
    private fun trip()=TripFiles.fromJourney(query,journey,"Traveller",10,5,"Home","Campus",12,now,now)
    private fun link()=CircleLink(id="circle",room="!private:matrix.test",ownerId="@traveller:matrix.test",secret=PortableCrypto.secret(),expiresAt=now+2*60*60_000L,trip=trip())
    private fun update(l: CircleLink)=FriendUpdate(l.id,7,now,l.expiresAt,"At boarding stop",GeoPoint(-33.9,18.6),30f,"Unibell")
    @Test fun `shared itinerary includes access buffer changes exact boarding platforms and final walk`() {
        val t=trip();assertEquals("leave",t.steps.first().kind);assertEquals("07:03",java.time.Instant.ofEpochMilli(t.steps.first().at).atZone(CapeTownZone).toLocalTime().toString())
        assertEquals("3",t.steps.first { it.kind=="transfer" }.platform);assertEquals("08:02",java.time.Instant.ofEpochMilli(t.steps.last().at).atZone(CapeTownZone).toLocalTime().toString())
        assertEquals(t,TripFiles.parse(TripFiles.json.encodeToString(t)));assertTrue(TripFiles.text(t).contains("Platform 3"));assertTrue(TripFiles.text(t).contains("South African time"))
    }
    @Test fun `unknown platforms remain unknown instead of copied from alighting stop`() {
        val j=journey.copy(legs=journey.legs.mapIndexed { i,leg->if(i==1) leg.copy(stops=leg.stops.map { it.copy(platform=null) }) else leg })
        val t=TripFiles.fromJourney(query,j,"Traveller",10,5,"Home","Campus",0,now,null)
        assertNull(t.steps.first { it.kind=="transfer" }.platform);assertTrue(TripFiles.text(t).contains("Platform unknown"))
    }
    @Test fun `unknown versions fields deep documents invalid platforms and backward steps are rejected`() {
        val raw=TripFiles.json.encodeToString(trip())
        assertThrows(IllegalArgumentException::class.java) { TripFiles.parse(raw.replace("\"version\":1","\"version\":9")) }
        assertThrows(Exception::class.java) { TripFiles.parse(raw.dropLast(1)+",\"tracking\":true}") }
        assertThrows(IllegalArgumentException::class.java) { TripFiles.document("[".repeat(33)+"]".repeat(33)) }
        assertThrows(IllegalArgumentException::class.java) { TripFiles.validate(trip().copy(steps=trip().steps.reversed())) }
        assertThrows(IllegalArgumentException::class.java) { TripFiles.validate(trip().copy(steps=trip().steps.map { it.copy(platform="guess") })) }
    }
    @Test fun `portable backup survives key independence and rejects wrong password or changed ciphertext`() {
        val password="test-only password".toCharArray();val encrypted=PortableCrypto.encrypt("{\"profile\":\"saved\"}",password)
        assertEquals("{\"profile\":\"saved\"}",PortableCrypto.decrypt(encrypted,password))
        assertThrows(Exception::class.java) { PortableCrypto.decrypt(encrypted,"wrong password".toCharArray()) }
        val e=TripFiles.json.decodeFromString<PortableEnvelope>(encrypted);val bytes=PortableCrypto.decode(e.ciphertext).apply { this[0]=(this[0].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { PortableCrypto.decrypt(TripFiles.json.encodeToString(e.copy(ciphertext=PortableCrypto.encode(bytes))),password) }
        assertThrows(IllegalArgumentException::class.java) { PortableCrypto.encrypt("profile","short".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { PortableCrypto.decrypt(TripFiles.json.encodeToString(e.copy(iterations=2_000_000)),password) }
    }
    @Test fun `backup salts and nonces are random for identical profile and password`() {
        val password="test-only password".toCharArray();assertNotEquals(PortableCrypto.encrypt("profile",password),PortableCrypto.encrypt("profile",password))
    }
    @Test fun `private updates require the intended sender room circle and key`() {
        val l=link();val u=update(l);val sealed=CircleUpdates.seal(l,u)
        assertEquals(u,CircleUpdates.open(l,sealed,l.ownerId,now,0))
        assertThrows(Exception::class.java) { CircleUpdates.open(l,sealed,"@other:matrix.test",now,0) }
        assertThrows(Exception::class.java) { CircleUpdates.open(l.copy(room="!other:matrix.test"),sealed,l.ownerId,now,0) }
        assertThrows(Exception::class.java) { CircleUpdates.open(l.copy(secret=PortableCrypto.secret()),sealed,l.ownerId,now,0) }
        assertThrows(Exception::class.java) { CircleUpdates.open(l,sealed.copy(circleId="other"),l.ownerId,now,0) }
    }
    @Test fun `replayed stale future expired and inaccurate update fields are rejected`() {
        val l=link();val u=update(l)
        fun check(value: FriendUpdate,time: Long=now,last: Long=0) { assertThrows(Exception::class.java) { CircleUpdates.open(l,CircleUpdates.seal(l,value),l.ownerId,time,last) } }
        check(u,last=7);check(u,time=now+6*60_000);check(u.copy(sentAt=now+31_000));check(u,time=l.expiresAt)
        check(u.copy(point=GeoPoint(100.0,18.6)));check(u.copy(accuracy=null));check(u.copy(phase="Automatically boarded"))
    }
    @Test fun `capability import has a bounded expiry and requires complete itinerary`() {
        val l=link();assertEquals(l,CircleUpdates.validate(l,now))
        assertThrows(IllegalArgumentException::class.java) { CircleUpdates.validate(l.copy(expiresAt=now),now) }
        assertThrows(IllegalArgumentException::class.java) { CircleUpdates.validate(l.copy(expiresAt=now+7*60*60_000L),now) }
    }
    @Test fun `reports count distinct hosted accounts with newest observation per account`() {
        val r=CommunityReport(id="1",service=TransportService.PRASA,stop="Bellville",route="3204",direction="Salt River",observedAt=now,category=ReportCategory.DELAYED)
        fun received(report: CommunityReport,sender: String,id: String,hosted: Boolean=true)=ReceivedReport(report,sender,id,now,hosted)
        val rows=listOf(received(r,"@a:server","1"),received(r,"@a:server","2"),received(r,"@b:server","3"),received(r,"Imported","4",false),received(r.copy(observedAt=now-61*60_000),"@c:server","5"))
        assertEquals(2,CommunityReports.accounts(rows,r,now));assertEquals(1,CommunityReports.accounts(rows+received(r.copy(observedAt=now+1000,category=ReportCategory.CANCELLED),"@a:server","6"),r,now+1000))
        assertEquals(0,CommunityReports.accounts(rows,r.copy(route="9006"),now));assertTrue(CommunityReports.text(r).contains("Service: Trains"));assertFalse(CommunityReports.fresh(r,now+61*60_000))
    }
}
