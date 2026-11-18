package za.co.cttrains.core

import org.junit.Test
import org.junit.Assert.*

class GoldenContinuationTest {
    private val doc=BusRouteDocument("003001","BELHAR-CAPE TOWN","https://www.gabs.co.za/Pdf/Bpdf/test.pdf","2026-09-28",pdf=true)
    @Test fun continuationNotesApplyToEarlierPagesAndTableBlocksStaySeparate() {
        val text="""CAPE TOWN - BELHAR
MONDAYS TO FRIDAYS EFFECTIVE DATE: 2026/09/28 TIMETABLE NUMBER: 0030 01
| CAPE TOWN | 08:00a | 08:00b |
| VIA ROAD | via | via |
| BELHAR | 09:00a | 09:10b |
|---------------------------|
| CAPE TOWN | 12:00 | -- |
| BELHAR | 13:00 | -- |
SUNDAYS
"""+'\u000c'+"""CAPE TOWN - BELHAR
| CAPE TOWN | 10:00 | -- |
| BELHAR | 11:00 | -- |
a - Mondays,Tuesdays,Wednesdays,Thursdays
b - Fridays
"""
        val friday=GoldenTimetables.parse(text,doc,"Friday",1)
        assertEquals(listOf("08:00","12:00"),friday.trips.map { it.calls.first().time })
        assertEquals(listOf("09:10","13:00"),friday.trips.map { it.calls.last().time })
        assertTrue(friday.trips.all { it.calls.size==2 })
        val sunday=GoldenTimetables.parse(text,doc,"Sunday",1)
        assertEquals("10:00",sunday.trips.single().calls.first().time)
    }
    @Test fun invalidClocksAndUnsupportedServiceDaySpansAreExcluded() {
        val text="""CAPE TOWN - BELHAR
MONDAYS TO FRIDAYS EFFECTIVE DATE: 2026/09/28 TIMETABLE NUMBER: 0030 01
| CAPE TOWN | 00:00 | 25:00 |
| BELHAR | 23:30 | 26:00 |
"""
        assertTrue(GoldenTimetables.parse(text,doc,"Friday",1).trips.isEmpty())
    }
    @Test fun corridorsFindCandidateTimetablesWithoutMakingAStopTimeJoin() {
        val a=GeoPoint(-33.94,18.63);val b=GeoPoint(-33.93,18.46)
        val route=doc.copy(validUntil="2026-10-04")
        val shape=BusShape("BMB2","BMB2: BELHAR to CITY",listOf(a,b),"Government survey")
        val s=BusSnapshot(TransportService.GOLDEN_ARROW,1,listOf(route),emptyList(),listOf(shape),emptyList(),"Survey only")
        val date=java.time.LocalDate.parse("2026-10-02")
        assertEquals(listOf(route),BusDiscovery.goldenCandidates(s,a,b,date,500.0).map { it.document })
        assertEquals(listOf(route),BusDiscovery.goldenCandidates(s.copy(shapes=emptyList()),a,b,date,500.0,"Unibell","Cape Town").map { it.document })
        assertTrue(BusDiscovery.routes(s,a,b,date,emptyList(),500.0).isEmpty())
        assertTrue(BusDiscovery.goldenCandidates(s,a,b,date.plusDays(3),500.0).isEmpty())
        assertTrue(BusDiscovery.goldenCandidates(s,a,GeoPoint(-34.2,18.8),date,500.0).isEmpty())
    }
    @Test fun savedDestinationUsesSurveyDirectionWithoutInventingItsStopTime() {
        val a=GeoPoint(-33.94,18.63)
        val b=GeoPoint(-33.93,18.46)
        val shape=BusShape("BMB1","BMB1: CITY GOLDEN ACRE D LANE to BELHAR",listOf(b,a),"Survey")
        val date=java.time.LocalDate.parse("2026-10-02")
        val snapshot=BusSnapshot(TransportService.GOLDEN_ARROW,1,listOf(doc),emptyList(),listOf(shape),emptyList(),"Survey only")
        val match=BusDiscovery.goldenCandidates(snapshot,a,b,date,500.0,"Unibell","").single()
        assertEquals("capetown",match.arrivalArea)
        val city=BusCall("capetown","CAPE TOWN","10:00")
        val belhar=BusCall("belhar","BELHAR","08:30")
        val delft=BusCall("delft","EINDHOVEN","10:30")
        val towardsCity=BusTrip("west","0030","city","Friday",listOf(belhar,city))
        val awayFromCity=BusTrip("east","0030","delft","Friday",listOf(city.copy(time="09:00"),belhar.copy(time="10:00"),delft))
        val table=BusTimetable(TransportService.GOLDEN_ARROW,doc,"Friday",1,listOf(towardsCity,awayFromCity))
        val result=BusDiscovery.goldenDepartures(table,"Unibell","",date,"08:30",match).single()
        assertEquals("west",result.trip.id)
        assertEquals("08:30",result.departure.time)
        assertEquals("CAPE TOWN",result.arrival.name)
        assertNull(result.alight)
    }

}
