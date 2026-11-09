package za.co.cttrains.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class TransitPlanningTest {
    private val query = SearchQuery("Unibell", "Salt River", "2026-10-01", "17:00")
    private val leg = Leg("Central", "9006", listOf(Stop("Unibell", "17:20"), Stop("Salt River", "17:50")))
    private fun plan(reminder: Int? = 15) = ScheduledJourney("id", "local-uuid", query, Journey(listOf(leg)), "cttrains-web", 1, 1, reminder, 20, 5)
    private val now = LocalDate.parse(query.date).atTime(LocalTime.of(16, 30)).atZone(CapeTownZone).toInstant().toEpochMilli()

    @Test fun `departure uses Cape Town timezone independent of device timezone`() {
        assertEquals(Instant.parse("2026-10-01T15:20:00Z").toEpochMilli(), plan().departureAt)
    }
    @Test fun `leave by subtracts user estimate and buffer`() { assertEquals(25 * 60_000L, plan().departureAt - plan().leaveAt) }
    @Test fun `reminders have deterministic lead time`() { assertEquals(15 * 60_000L, plan().departureAt - plan().reminderAt!!) }
    @Test fun `no notification is a valid explicit choice`() { assertNull(plan(null).reminderAt); assertNull(JourneyPlanning.validationError(plan(null), now)) }
    @Test fun `past departure and already passed reminder are rejected`() {
        assertNotNull(JourneyPlanning.validationError(plan(), plan().departureAt))
        assertNotNull(JourneyPlanning.validationError(plan(60), now))
    }
    @Test fun `custom values have bounded ranges`() {
        for (item in listOf(plan(0), plan(1441), plan().copy(accessMinutes = -1), plan().copy(bufferMinutes = 121))) assertNotNull(JourneyPlanning.validationError(item, now))
    }
    @Test fun `arrival rolls into the next date for overnight train`() {
        val night = plan().copy(journey = Journey(listOf(leg.copy(stops = listOf(Stop("Unibell", "23:50"), Stop("Salt River", "00:10"))))))
        assertEquals(Instant.parse("2026-10-01T22:10:00Z").toEpochMilli(), night.arrivalAt)
    }
    @Test fun `same journey replaces its saved plan without creating duplicate reminders`() {
        assertEquals(plan().identity, plan(30).copy(id = "different-id", accessMinutes = 30).identity)
        assertNotEquals(plan().identity, plan().copy(sourceId = "cttrains-api").identity)
    }
    @Test fun `unknown station positions split route connections`() {
        val line = leg.copy(stops = listOf(Stop("A", "17:00"), Stop("B", "17:01"), Stop("Unknown", "17:02"), Stop("C", "17:03"), Stop("D", "17:04")))
        val stations = listOf("A", "B", "C", "D").map { StationPosition(it, -33.9, 18.5) }
        assertEquals(listOf(listOf("A", "B"), listOf("C", "D")), JourneyPlanning.mappedSegments(line, stations).map { it.map { s -> s.name } })
    }
    @Test fun `station keys handle source punctuation without invented positions`() { assertEquals(stationKey("Simon's Town"), stationKey("Simonstown")); assertFalse(StationPosition("Unknown").mapped) }
    @Test fun `cache isolation includes data source`() {
        val entry = CachedSearch(query, listOf(Journey(listOf(leg))), now)
        assertEquals(2, CachePolicy.prune(listOf(entry, entry.copy(sourceId = "cttrains-api")), now).size)
    }
    @Test fun `API result must describe requested source and query`() {
        val entry = CachedSearch(query, listOf(Journey(listOf(leg))), now, sourceId = "cttrains-api")
        assertEquals(entry, validateTransitEntry(entry, query, "cttrains-api", now))
        assertThrows(ScheduleFormatException::class.java) { validateTransitEntry(entry, query.copy(to = "Cape Town"), "cttrains-api", now) }
        assertThrows(ScheduleFormatException::class.java) { validateTransitEntry(entry, query, "cttrains-web", now) }
    }
    @Test fun `API rejects broken transfer and empty stop lists`() {
        val empty = CachedSearch(query, listOf(Journey(listOf(leg.copy(stops = emptyList())))), now, sourceId = "cttrains-api")
        assertThrows(ScheduleFormatException::class.java) { validateTransitEntry(empty, query, "cttrains-api", now) }
        val broken = empty.copy(journeys = listOf(Journey(listOf(leg, leg.copy(stops = listOf(Stop("Elsewhere", "18:00"), Stop("Salt River", "18:20")))))))
        assertThrows(ScheduleFormatException::class.java) { validateTransitEntry(broken, query, "cttrains-api", now) }
    }
    @Test fun `API cannot attach a different destination or future fetch timestamp`() {
        val entry = CachedSearch(query, listOf(Journey(listOf(leg.copy(stops = listOf(Stop("Unibell", "17:20"), Stop("Elsewhere", "17:50")))))), now, sourceId = "cttrains-api")
        assertThrows(ScheduleFormatException::class.java) { validateTransitEntry(entry, query, "cttrains-api", now) }
        assertThrows(ScheduleFormatException::class.java) { validateTransitEntry(entry.copy(journeys = emptyList(), fetchedAt = now + 120_000), query, "cttrains-api", now) }
    }
}
