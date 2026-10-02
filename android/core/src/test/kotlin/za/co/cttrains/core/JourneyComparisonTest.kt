package za.co.cttrains.core

import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.*

class JourneyComparisonTest {
    private val date=LocalDate.parse("2026-10-02")
    private val a=GeoPoint(-33.9,18.4)
    private val b=GeoPoint(-33.9,18.5)
    private val route=BusRouteDocument("101","101 · Test route","https://www.myciti.org.za/en/timetables/route-timetables/?route=101")
    private fun snapshot(service: TransportService)=BusSnapshot(service,1,listOf(route),listOf(
        BusStopPoint("a","Home stop",a,listOf("101"),"Test"),BusStopPoint("b","Campus stop",b,listOf("101"),"Test")),emptyList(),emptyList(),"Test only")
    private fun timetable(service: TransportService)=BusTimetable(service,route,"Friday",1,listOf(
        BusTrip("out","101","Out","Friday",listOf(BusCall("a","Home stop","08:00"),BusCall("b","Campus stop","08:30"))),
        BusTrip("back","101","Back","Friday",listOf(BusCall("b","Campus stop","08:10"),BusCall("a","Home stop","08:40")))))
    @Test fun comparesFromSameClockIncludingWalkingBufferWaitingAndFinalWalk() {
        val start=JourneyComparison.requestedAt(date.toString(),"07:30")
        val bus=JourneyComparison.estimate(start,date.toString(),"08:00",30,10,7)
        val train=JourneyComparison.estimate(start,date.toString(),"07:55",25,5,10)
        assertEquals(67,bus.totalMinutes);assertEquals(15,bus.waitingMinutes);assertEquals(17,bus.walkingMinutes)
        assertEquals(60,train.totalMinutes);assertEquals(15,train.waitingMinutes)
    }
    @Test fun missedDepartureAndUnmappedWalkCannotWinComparison() {
        val start=JourneyComparison.requestedAt(date.toString(),"07:50")
        val missed=JourneyComparison.estimate(start,date.toString(),"08:00",30,8,3)
        assertEquals(false,missed.canCatch);assertNull(missed.totalMinutes)
        val unknown=JourneyComparison.estimate(start,date.toString(),"08:00",30,null,3)
        assertNull(unknown.canCatch);assertNull(unknown.totalMinutes);assertNull(unknown.walkingMinutes)
        assertNull(JourneyComparison.estimate(start,date.toString(),"08:00",30,0,null).totalMinutes)
    }
    @Test fun myCitiRoutesUseActualSharedStopIdsAndTripDirection() {
        val s=snapshot(TransportService.MYCITI)
        assertEquals(listOf(route),BusDiscovery.routes(s,a,b,date,emptyList(),500.0))
        val trips=BusDiscovery.journeys(timetable(s.service),s,a,b,date,"07:00",emptyList(),500.0)
        assertEquals(listOf("out"),trips.map { it.trip.id })
        assertEquals("a",trips.single().board!!.id);assertEquals("b",trips.single().alight!!.id)
        assertTrue(BusDiscovery.journeys(timetable(s.service),s,a,b,date.plusDays(1),"07:00",emptyList()).isEmpty())
        assertTrue(BusDiscovery.journeys(timetable(s.service),s,a,b,date,"08:01",emptyList()).isEmpty())
    }
    @Test fun surveyRouteCodeDoesNotInventGoldenTimetableConnection() {
        val s=snapshot(TransportService.GOLDEN_ARROW)
        assertTrue(BusDiscovery.routes(s,a,b,date,emptyList(),500.0).isEmpty())
        assertTrue(BusDiscovery.journeys(timetable(s.service),s,a,b,date,"07:00",emptyList(),500.0).isEmpty())
        val links=listOf(BusStopLink(s.service,route.id,"a","a",10),BusStopLink(s.service,route.id,"b","b",10))
        assertEquals(listOf(route),BusDiscovery.routes(s,a,b,date,links,500.0))
        assertEquals("out",BusDiscovery.journeys(timetable(s.service),s,a,b,date,"07:00",links,500.0).single().trip.id)
    }
    @Test fun expiredDocumentsAndFutureTripStartAreExcluded() {
        val s=snapshot(TransportService.MYCITI)
        assertTrue(BusDiscovery.routes(s.copy(routes=listOf(route.copy(validUntil="2026-10-01"))),a,b,date,emptyList()).isEmpty())
        val t=timetable(s.service).let { it.copy(trips=it.trips.map { t->t.copy(validFrom="2026-10-03") }) }
        assertTrue(BusDiscovery.journeys(t,s,a,b,date,"07:00",emptyList()).isEmpty())
    }
    @Test fun comparisonRespectsOnlyTheMatchingPersonalFareCorrection() {
        val rows=listOf(BusFareRow("route-fare",route.title,mapOf("Single" to 2500L,"Weekly" to 24000L)))
        val quote=JourneyComparison.singleFares(rows,route,mapOf(("route-fare" to "Single") to 2800L,("another-route" to "Single") to 1200L)).single()
        assertEquals(2800L,quote.cents);assertTrue(quote.personal);assertTrue(quote.label.contains("Your correction"))
    }
    @Test fun weeklyAndMonthlyPricesCannotBecomeCashFare() {
        val rows=listOf(BusFareRow("one",route.title,mapOf("Weekly" to 24000L,"Monthly" to 90000L,"Single" to 3000L,"10 ride" to 27000L),""),
            BusFareRow("other","Different route",mapOf("Single" to 1500L),""))
        assertEquals(listOf(3000L),JourneyComparison.singleFares(rows,route).map { it.cents })
    }
}
