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
        assertEquals(listOf(route),BusDiscovery.goldenCandidates(s,a,b,date,500.0))
        assertTrue(BusDiscovery.routes(s,a,b,date,emptyList(),500.0).isEmpty())
        assertTrue(BusDiscovery.goldenCandidates(s,a,b,date.plusDays(3),500.0).isEmpty())
        assertTrue(BusDiscovery.goldenCandidates(s,a,GeoPoint(-34.2,18.8),date,500.0).isEmpty())
    }
}
