package za.co.cttrains.core

import org.junit.Assert.*
import org.junit.Test

class LabTimingTest {
    private val date = "2026-11-12"
    private fun at(time: String) = JourneyComparison.requestedAt(date, time)
    private fun journey(dep: String, arr: String) = Journey(listOf(Leg("Central", "0117", listOf(Stop("Mitchells Plain", dep), Stop("Cape Town", arr)))))
    private fun plan(id: String, dep: String, profile: String = "p", outcome: RideOutcome? = null) =
        ScheduledJourney(id, profile, SearchQuery("Mitchells Plain", "Cape Town", date, "06:45"), journey(dep, "08:03"), "cttrains-web", 0, 0,
            accessMinutes = 7, bufferMinutes = 4, rideOutcome = outcome)

    @Test fun `reachability classifies spare walking minutes`() {
        val dep = at("07:12")
        assertEquals(WalkFeasibility.REACHABLE, LabTiming.reachability(dep, 7, 4, at("07:01")).feasibility)
        assertEquals(4, LabTiming.reachability(dep, 7, 4, at("07:01")).spareMinutes)
        assertEquals(WalkFeasibility.TIGHT, LabTiming.reachability(dep, 7, 4, at("07:04")).feasibility)
        assertEquals(WalkFeasibility.TIGHT, LabTiming.reachability(dep, 7, 4, at("07:05")).feasibility)
        assertEquals(WalkFeasibility.NOT_WALKABLE, LabTiming.reachability(dep, 7, 4, at("07:08")).feasibility)
        assertEquals(WalkFeasibility.DEPARTED, LabTiming.reachability(dep, 7, 4, at("07:12")).feasibility)
    }

    @Test fun `leave time subtracts access and buffer like saved plans`() {
        val r = LabTiming.reachability(at("07:12"), 7, 4, at("06:00"))
        assertEquals(at("07:01"), r.leaveAt)
        assertEquals(plan("a", "07:12").leaveAt, r.leaveAt)
        assertEquals("07:01", LabTiming.clock(r.leaveAt))
    }

    @Test fun `labels never imply live tracking`() {
        assertEquals("Reachable · 4 min spare", LabTiming.reachabilityLabel(LabTiming.reachability(at("07:12"), 7, 4, at("07:01"))))
        assertEquals("Not at walking pace · 3 min short", LabTiming.reachabilityLabel(LabTiming.reachability(at("07:12"), 7, 4, at("07:08"))))
        assertEquals("~08:03", LabTiming.arrival("08:03"))
        assertEquals("51 min", LabTiming.duration(51)); assertEquals("1 h 12", LabTiming.duration(72)); assertEquals("2 h 05", LabTiming.duration(125))
        assertEquals("direct", LabTiming.changes(0)); assertEquals("1 change", LabTiming.changes(1)); assertEquals("2 changes", LabTiming.changes(2))
    }

    @Test fun `next plan ignores answered, departed and other profiles`() {
        val items = listOf(plan("late", "08:00"), plan("other", "07:00", profile = "q"), plan("done", "07:05", outcome = RideOutcome.CONFIRMED),
            plan("gone", "06:30"), plan("next", "07:12"))
        assertEquals("next", LabTiming.nextPlan(items, "p", at("06:45"))?.id)
        assertNull(LabTiming.nextPlan(items, "p", at("09:00")))
    }

    @Test fun `upcoming keeps only future departures in order`() {
        val list = LabTiming.upcoming(listOf(journey("07:41", "08:32"), journey("06:58", "08:10"), journey("07:12", "08:03")), date, at("07:00"))
        assertEquals(listOf("07:12", "07:41"), list.map { it.departure })
    }

    @Test fun `ages and staleness are explicit`() {
        val now = at("08:00")
        assertEquals("just now", LabTiming.age(now - 10_000, now))
        assertEquals("16 min ago", LabTiming.age(at("07:44"), now))
        assertEquals("2 h ago", LabTiming.age(at("06:00"), now))
        assertFalse(LabTiming.stale(at("07:45"), now)); assertTrue(LabTiming.stale(at("07:00"), now))
        assertEquals("Thu 12 Nov", LabTiming.day(date))
    }
}
