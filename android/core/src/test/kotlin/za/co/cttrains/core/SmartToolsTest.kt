package za.co.cttrains.core

import org.junit.Assert.*
import org.junit.Test

class SmartToolsTest {
    private val query = SearchQuery("A", "B", "2026-10-01", "17:00")
    private val train = Journey(listOf(Leg("Central", "1", listOf(Stop("A", "17:20"), Stop("B", "17:50")))))
    private fun plan() = ScheduledJourney("id", "profile", query, train, "cttrains-web", 1, 1, 0, 20, 5,
        leaveFrom = "Campus", reminderBasis = "leave", backupReminders = listOf(BackupReminder(3, "gentle"), BackupReminder(5, "urgent")), reminderToken = "v1")

    @Test fun `first leave reminder includes building access time and boarding buffer`() {
        val item = plan()
        assertEquals(item.departureAt - 25 * 60_000L, item.reminderAt)
        assertNull(JourneyPlanning.validationError(item, item.leaveAt - 1000))
    }
    @Test fun `early leave reminder lead is additional to access time`() { assertEquals(plan().leaveAt - 10 * 60_000L, plan().copy(reminderMinutes = 10).reminderAt) }
    @Test fun `dismissal schedules backup relative to dismissal time`() {
        val item = plan(); val at = item.leaveAt - 1000
        val next = JourneyPlanning.afterDismiss(item, 0, "v1", at)!!
        assertEquals(1, next.reminderStage); assertEquals(at + 3 * 60_000L, next.pendingReminderAt); assertEquals("gentle", next.activeSeverity)
    }
    @Test fun `each subsequent backup uses its own delay and severity`() {
        val next = JourneyPlanning.afterDismiss(plan().copy(reminderStage = 1), 1, "v1", plan().leaveAt)!!
        assertEquals(2, next.reminderStage); assertEquals(plan().leaveAt + 5 * 60_000L, next.pendingReminderAt); assertEquals("urgent", next.activeSeverity)
    }
    @Test fun `stale or duplicate notification dismissal cannot advance the chain`() {
        assertNull(JourneyPlanning.afterDismiss(plan(), 0, "old-version", 1))
        assertNull(JourneyPlanning.afterDismiss(plan().copy(reminderStage = 1), 0, "v1", 1))
    }
    @Test fun `acknowledged and disabled reminders never create backups`() {
        assertNull(plan().copy(remindersStopped = true).pendingReminderAt)
        assertNull(JourneyPlanning.afterDismiss(plan().copy(remindersStopped = true), 0, "v1", 1))
        assertNull(JourneyPlanning.afterDismiss(plan().copy(reminderMinutes = null), 0, "v1", 1))
    }
    @Test fun `end of chain and backup after departure stop notifications`() {
        assertTrue(JourneyPlanning.afterDismiss(plan().copy(reminderStage = 2), 2, "v1", 1)!!.remindersStopped)
        assertTrue(JourneyPlanning.afterDismiss(plan(), 0, "v1", plan().departureAt - 1000)!!.remindersStopped)
    }
    @Test fun `invalid backup lengths counts and severity are rejected`() {
        val cases = listOf(plan().copy(backupReminders = listOf(BackupReminder(0))), plan().copy(backupReminders = listOf(BackupReminder(61))), plan().copy(backupReminders = List(4) { BackupReminder(1) }), plan().copy(reminderSeverity = "override-dnd"))
        cases.forEach { assertNotNull(JourneyPlanning.validationError(it, it.leaveAt - 1000)) }
    }
    @Test fun `source fare parser preserves five labelled ticket prices`() {
        val prices = TripBudget.sourcePrices("Fares: Single: R12; Return R24; Weekly (Mon-Fri): R70; Weekly (Mon–Sat): R80; Monthly R220")
        assertEquals(mapOf("single" to 1200L, "return" to 2400L, "weekly5" to 7000L, "weekly6" to 8000L, "monthly" to 22000L), prices)
    }
    @Test fun `unknown and malformed fares are never invented`() {
        assertTrue(TripBudget.sourcePrices(null).isEmpty()); assertTrue(TripBudget.sourcePrices("3 trains, 25 minutes, R99 unknown charge").isEmpty())
        assertTrue(TripBudget.sourcePrices("Single R12.999").isEmpty())
    }
    @Test fun `currency arithmetic keeps exact cents`() {
        assertEquals(1234L, TripBudget.parseCents("R12,34")); assertEquals(1234L, TripBudget.parseCents("12.34"))
        assertEquals(7404L, TripBudget.total(1234, 3, 2))
    }
    @Test fun `negative excessive and fractional cent prices are rejected`() {
        listOf("-1", "12.345", "100001", "NaN", "1e4", "1,000.00").forEach { assertNull(TripBudget.parseCents(it)) }
        assertThrows(IllegalArgumentException::class.java) { TripBudget.total(1200, 0, 1) }
    }
    @Test fun `local price corrections never leak across routes or ticket types`() {
        val values = listOf(FareCorrection("A", "B", "single", 1400, 1))
        assertEquals(1400L, TripBudget.correction(values, SavedRoute("A", "B"), "single")?.cents)
        assertNull(TripBudget.correction(values, SavedRoute("B", "A"), "single")); assertNull(TripBudget.correction(values, SavedRoute("A", "B"), "return"))
    }
    @Test fun `offline route bounds use only real mapped stations`() {
        val stations = listOf(StationPosition("A", -33.9, 18.4), StationPosition("B", -33.95, 18.5), StationPosition("Unknown"))
        val area = MapPlanning.area(stations, train)!!
        assertEquals(14, area.maxZoom); assertTrue(area.bounds.west < 18.4 && area.bounds.east > 18.5)
        assertTrue(MapPlanning.estimatedTiles(area) in 1..MapPlanning.MAX_TILES)
    }
    @Test fun `empty station maps and unsupported bounds do not create downloads`() {
        assertNull(MapPlanning.area(emptyList()))
        assertThrows(IllegalArgumentException::class.java) { MapPlanning.estimatedTiles(MapArea("bad", MapBounds(18.0, -34.0, 17.0, -33.0), 10, 14)) }
    }
    @Test fun `tile estimates enforce a bounded download area`() {
        assertTrue(MapPlanning.estimatedTiles(MapArea("huge", MapBounds(10.0, -40.0, 40.0, -10.0), 10, 14)) > MapPlanning.MAX_TILES)
    }
}
