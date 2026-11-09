package za.co.cttrains.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class DoorToDoorTest {
    private val a=WalkEndpoint("home","Home",GeoPoint(-33.927,18.462))
    private val b=WalkEndpoint("station","Station",GeoPoint(-33.928,18.459))
    private val now=1_790_899_200_000L
    private fun raw()=javaClass.getResourceAsStream("/walking-route.json")!!.bufferedReader().readText()
    private fun leg()=WalkingRoutes.parse(raw(),a,b,now)
    @Test fun walkingRouteUsesLonLatAndVerifiedGeometry() {
        assertTrue(WalkingRoutes.url(a,b).contains("18.462,-33.927;18.459,-33.928"))
        val l=leg();assertEquals(313.2,l.distanceMeters,.1);assertEquals(250.5,l.durationSeconds,.1);assertEquals(5,l.minutes)
        assertTrue(l.points.all { it.latitude<0 && it.longitude>18 })
    }
    @Test fun missingOrFarSnappedRouteHasNoInventedTime() {
        assertThrows(IllegalArgumentException::class.java) { WalkingRoutes.parse("{\"code\":\"NoRoute\"}",a,b,now) }
        assertThrows(IllegalArgumentException::class.java) { WalkingRoutes.parse(raw(),a.copy(point=GeoPoint(-34.5,19.0)),b,now) }
        assertThrows(IllegalArgumentException::class.java) { WalkingRoutes.url(a,b.copy(point=GeoPoint(-35.0,19.0))) }
    }
    @Test fun doorTimesIncludeWalkingBufferAndMidnight() {
        val q=SearchQuery("A","B","2026-10-02","23:00")
        val j=Journey(listOf(Leg("Line","1",listOf(Stop("A","23:55"),Stop("B","00:15")))))
        val t=WalkingRoutes.times(q,j,10,12,5)
        assertEquals("2026-10-02T23:40",Instant.ofEpochMilli(t.leaveAt).atZone(CapeTownZone).toLocalDateTime().toString())
        assertEquals("2026-10-03T00:27",Instant.ofEpochMilli(t.arriveAt).atZone(CapeTownZone).toLocalDateTime().toString())
    }
    @Test fun learningRequiresThreeWalksAndRejectsVehicleSpeed() {
        val l=leg();val samples=listOf(300,420,360).map { WalkingRoutes.sample(l,it,now) }
        assertNull(WalkingRoutes.learned(l,samples.take(2)));assertEquals(6,WalkingRoutes.learned(l,samples))
        assertThrows(IllegalArgumentException::class.java) { WalkingRoutes.sample(l,20,now) }
        assertNull(WalkingRoutes.learned(l.copy(to=b.copy(id="another")),samples))
    }
    @Test fun busAlternativesRequirePublishedStopMembership() {
        val r=BusRouteDocument("101","101","https://www.myciti.org.za/en/timetables/route-timetables/?route=101")
        val s=BusSnapshot(TransportService.MYCITI,now,listOf(r),listOf(BusStopPoint("a","A",a.point,listOf("101"),"operator"),BusStopPoint("b","B",b.point,listOf("101"),"operator")),emptyList(),emptyList(),"Published")
        assertEquals(listOf(r),BusConnections.direct(s,a.point,b.point).routes)
        assertTrue(BusConnections.direct(s,a.point,GeoPoint(-34.1,18.9)).message.contains("No published"))
        assertTrue(BusConnections.direct(s.copy(service=TransportService.GOLDEN_ARROW),a.point,b.point).routes.isEmpty())
        assertTrue(BusConnections.direct(null,a.point,b.point).routes.isEmpty())
    }
    @Test fun nearbyPresenceIsCoarseFreshAndRevocable() {
        val p=NearbyPeople.create(a.point,now);assertEquals(GeoPoint(-33.93,18.46),p.point)
        assertEquals(1,NearbyPeople.visible(listOf("friend" to p,"self" to p),"self",a.point,now).size)
        assertTrue(NearbyPeople.visible(listOf("friend" to p),"self",a.point,now+NearbyPeople.TTL+1).isEmpty())
        val stop=NearbyPresence(sentAt=now+1000,expiresAt=now+1000,active=false)
        assertTrue(NearbyPeople.visible(listOf("friend" to p,"friend" to stop),"self",a.point,now+1000).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { NearbyPeople.validate(p.copy(point=a.point)) }
        assertTrue(NearbyPeople.visible(listOf("friend" to p),"self",GeoPoint(-34.3,19.1),now).isEmpty())
    }
}
