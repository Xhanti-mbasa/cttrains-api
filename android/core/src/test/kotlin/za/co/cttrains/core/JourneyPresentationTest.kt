package za.co.cttrains.core

import org.junit.Test
import org.junit.Assert.*

class JourneyPresentationTest {
    private fun file(name: String)=javaClass.getResource("/$name")!!.readText()
    @Test fun focusKeepsEndsAndTransfersAndHidesIntermediateClutter() {
        val j=Journey(listOf(Leg("A","1",listOf(Stop("Unibell","07:00"),Stop("Bellville","07:10"),Stop("Mutual","07:30"))),Leg("B","2",listOf(Stop("Mutual","07:35"),Stop("Salt River","07:50")))))
        assertEquals(setOf("unibell","mutual","saltriver"),JourneyPresentation.railNames(j,"Unibell","Salt River"))
        assertEquals(50,j.durationMinutes)
        assertEquals(5,j.connectionMinutes(1))
    }
    @Test fun busPlanningUsesExactIdsAndOrderedTimedStops() {
        val r=BusRouteDocument("101","Route 101","https://www.myciti.org.za/")
        val t=BusSources.myCitiTimetable(file("myciti-101.html"),r,"Friday",1000)
        assertEquals(2,BusPlanning.choices(t,"CIVIC","LWBKT","05:00").size)
        assertTrue(BusPlanning.choices(t.copy(trips=t.trips.map { it.copy(validFrom="2026-10-05") }),"CIVIC","LWBKT","05:00",java.time.LocalDate.parse("2026-10-02")).isEmpty())
        assertEquals(1,BusPlanning.choices(t,"CIVIC","LWBKT","06:00").size)
        assertTrue(BusPlanning.choices(t,"LWBKT","CIVIC","05:00").isEmpty())
        assertTrue(BusPlanning.choices(t,"civic","LWBKT","05:00").isEmpty())
        assertTrue(BusPlanning.choices(t,"CIVIC","NZRTH","05:00").isEmpty())
    }
    @Test fun walkBadgeUsesRoutedDistanceAndCeilingTime() {
        val a=WalkEndpoint("a","Home",GeoPoint(-33.927,18.462));val b=WalkEndpoint("b","Station",GeoPoint(-33.928,18.459))
        val leg=WalkingRoutes.parse(file("walking-route.json"),a,b,1000)
        val badge=JourneyPresentation.walking(listOf(leg)).single()
        assertEquals("Walk · 313 m · 5 min",badge.text)
        assertTrue(badge.point.valid)
    }
    @Test fun suggestionsRejectOutOfRegionAndDoNotInventHouseNumbers() {
        val raw="""{"features":[{"geometry":{"type":"Point","coordinates":[18.45,-33.93]},"properties":{"osm_id":1,"osm_type":"W","street":"Albert Road","city":"Cape Town"}},{"geometry":{"type":"Point","coordinates":[0,0]},"properties":{"name":"Wrong region"}}]}"""
        val places=PhotonPlaces.parse(raw)
        assertEquals(1,places.size);assertEquals("Albert Road, Cape Town",places.single().label)
        assertFalse(places.single().label.contains("331"))
    }
}
