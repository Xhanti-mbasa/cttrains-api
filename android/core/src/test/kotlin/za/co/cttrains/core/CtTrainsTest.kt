package za.co.cttrains.core

import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CtTrainsTest {
    private fun fixture(name: String): String = javaClass.getResource("/$name")!!.readText()
    private val catalog get() = CtTrainsParser.catalog(fixture("catalog.html"))
    private val query = SearchQuery("Unibell", "Salt River", "2026-10-02", "07:00")
    private val today = LocalDate.of(2026, 10, 1)

    @Test fun `reads live station options and holiday policy`() {
        assertEquals(102, catalog.stations.size)
        assertTrue("Unibell" in catalog.stations)
        assertTrue("Simonstown" in catalog.stations)
        assertFalse("" in catalog.stations)
        assertTrue("2026-08-10" in catalog.holidays)
    }

    @Test fun `direct journeys retain every stop and train number`() {
        val journeys = CtTrainsParser.journeys(fixture("direct.html"))
        assertEquals(4, journeys.size)
        val first = journeys.first()
        assertEquals(0, first.transfers)
        assertEquals("2505", first.legs.single().train)
        assertEquals("Cape Town", first.legs.single().departure.name)
        assertEquals("Bellville", first.legs.single().arrival.name)
        assertTrue(first.legs.single().stops.size > 2)
    }

    @Test fun `transfer journey includes wait time and both trains`() {
        val journeys = CtTrainsParser.journeys(fixture("transfer.html"))
        assertEquals(4, journeys.size)
        val first = journeys.first()
        assertEquals("07:18", first.departure)
        assertEquals("07:50", first.arrival)
        assertEquals(32, first.durationMinutes)
        assertEquals(1, first.transfers)
        assertEquals(5, first.connectionMinutes(1))
        assertEquals(listOf("9006", "3204"), first.legs.map { it.train })
        assertEquals("Mutual", first.legs[0].arrival.name)
        assertEquals("Mutual", first.legs[1].departure.name)
        assertEquals("Belhar", first.legs[0].stops[1].name)
    }

    @Test fun `empty results differ from an unexpected upstream page`() {
        assertTrue(CtTrainsParser.journeys("<p>No train schedules found for the selected stations and date.</p>").isEmpty())
        assertTrue(CtTrainsParser.journeys("<p>There are no trains available.</p>").isEmpty())
        assertTrue(CtTrainsParser.journeys("<p>No valid itineraries found.</p>").isEmpty())
        assertThrows(ScheduleFormatException::class.java) { CtTrainsParser.journeys("<html><h1>Server maintenance</h1></html>") }
    }

    @Test fun `broken train markup does not silently become a valid journey`() {
        val html = fixture("transfer.html").replace("Train number: 9006", "Service identifier removed")
        assertThrows(ScheduleFormatException::class.java) { CtTrainsParser.journeys(html) }
    }

    @Test fun `invalid time and broken station connection fail closed`() {
        assertThrows(ScheduleFormatException::class.java) { CtTrainsParser.journeys(fixture("transfer.html").replace("07:18", "25:18")) }
        assertThrows(ScheduleFormatException::class.java) { CtTrainsParser.journeys(fixture("transfer.html").replaceFirst("Mutual</span>", "Unknown</span>")) }
    }

    @Test fun `date format matches website form`() { assertEquals("02/10/2026", query.websiteDate) }

    @Test fun `earlier departures returned by the site are not shown after requested time`() {
        assertTrue(CtTrainsParser.journeys(fixture("transfer.html"), "23:45").isEmpty())
        assertTrue(CtTrainsParser.journeys(fixture("transfer.html"), "07:30").all { it.departure >= "07:30" })
    }

    @Test fun `fares are extracted from source and never invented`() {
        assertEquals("Fares: Single R12, Return R24.", CtTrainsParser.fares("<p>Fares: Single R12, Return R24.</p>"))
        assertNull(CtTrainsParser.fares("<p>No fare data</p>"))
    }

    @Test fun `rejects unknown and identical stations`() {
        assertNotNull(TravelRules.validationError(query.copy(from = "Unknown"), catalog, today))
        assertNotNull(TravelRules.validationError(query.copy(to = "Unibell"), catalog, today))
        assertNull(TravelRules.validationError(query, catalog, today))
    }

    @Test fun `allows dates only through end of next month including year boundary`() {
        assertEquals(LocalDate.of(2027, 1, 31), TravelRules.lastAllowedDate(LocalDate.of(2026, 12, 31)))
        assertNotNull(TravelRules.validationError(query.copy(date = "2026-09-30"), catalog, today))
        assertNotNull(TravelRules.validationError(query.copy(date = "2026-12-01"), catalog, today))
        assertNull(TravelRules.validationError(query.copy(date = "2026-11-30"), catalog, today))
    }

    @Test fun `Sunday and advertised public holidays are rejected`() {
        assertNotNull(TravelRules.validationError(query.copy(date = "2026-10-04"), catalog, today))
        assertNotNull(TravelRules.validationError(query.copy(date = "2026-12-16"), catalog, LocalDate.of(2026, 12, 1)))
    }

    @Test fun `default departure obeys website intervals without wrapping midnight`() {
        assertEquals("03:00", TravelRules.defaultTime(LocalTime.of(1, 0)))
        assertEquals("07:15", TravelRules.defaultTime(LocalTime.of(7, 1)))
        assertEquals("07:15", TravelRules.defaultTime(LocalTime.of(7, 15)))
        assertEquals("23:45", TravelRules.defaultTime(LocalTime.of(23, 59)))
        assertNotNull(TravelRules.validationError(query.copy(time = "07:12"), catalog, today))
        assertNotNull(TravelRules.validationError(query.copy(time = "02:45"), catalog, today))
    }

    @Test fun `durations support crossing midnight`() { assertEquals(20, minutesBetween("23:50", "00:10")) }

    @Test fun `cache is bounded fresh and deduplicated by the full query`() {
        val now = CachePolicy.MAX_AGE_MILLIS + 1000
        val entries = (0..20).map { CachedSearch(query.copy(time = "${(it % 20 + 3).toString().padStart(2, '0')}:00"), emptyList(), now - it) }
        val selected = CachePolicy.prune(entries + entries + CachedSearch(query, emptyList(), 0), now)
        assertEquals(12, selected.size)
        assertEquals(12, selected.map { it.query }.toSet().size)
        assertTrue(selected.none { it.fetchedAt == 0L })
        val nextDate = CachedSearch(query.copy(date = "2026-10-03"), emptyList(), now)
        assertEquals(2, CachePolicy.prune(listOf(CachedSearch(query, emptyList(), now), nextDate), now).size)
    }

    @Test fun `cached journey serialization round trips transfers`() {
        val entry = CachedSearch(query, CtTrainsParser.journeys(fixture("transfer.html")), 1000)
        assertEquals(entry, Json.decodeFromString<CachedSearch>(Json.encodeToString(entry)))
    }

    @Test fun `sharing uses all train legs and distinguishes scheduled times`() {
        val text = journeyShareText(query, CtTrainsParser.journeys(fixture("transfer.html")).first())
        assertTrue(text.contains("Train 9006"))
        assertTrue(text.contains("Train 3204"))
        assertTrue(text.contains("5 min connection"))
        assertTrue(text.contains("32 min"))
        assertTrue(text.contains("Scheduled times"))
    }
}
