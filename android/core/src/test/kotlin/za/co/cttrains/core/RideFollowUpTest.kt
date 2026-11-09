package za.co.cttrains.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RideFollowUpTest {
    private fun plan(date: String, outcome: RideOutcome? = null): ScheduledJourney = ScheduledJourney(
        date, "profile", SearchQuery("Bellville", "Cape Town", date, "07:30"),
        Journey(listOf(Leg("Northern", "1", listOf(Stop("Bellville", "07:32"), Stop("Cape Town", "08:09"))))),
        "cttrains-web", 1L, 1L, reminderToken = "revision",
        rideFollowUp = RideFollowUp.PENDING, rideOutcome = outcome,
    )

    @Test fun followUpWaitsAndOnlyRecordsOneAnswer() {
        val item = plan("2026-11-09")
        val eligible = item.departureAt + RIDE_GRACE_MS
        assertFalse(item.awaitingRideAnswer(eligible - 1))
        assertTrue(item.awaitingRideAnswer(eligible))
        assertNull(item.recordRideAnswer(RideOutcome.CONFIRMED, eligible - 1))
        val confirmed = requireNotNull(item.recordRideAnswer(RideOutcome.CONFIRMED, eligible))
        assertEquals(RideOutcome.CONFIRMED, confirmed.rideOutcome)
        assertNull(confirmed.recordRideAnswer(RideOutcome.MISSED, eligible))
        val dismissed = requireNotNull(item.recordRideAnswer(null, eligible))
        assertNull(dismissed.rideOutcome)
        assertFalse(dismissed.awaitingRideAnswer(eligible))
        assertFalse(item.copy(rideFollowUp = RideFollowUp.LEGACY).awaitingRideAnswer(eligible))
    }

    @Test fun likelyBoardingCanBeCorrectedAndDoesNotTriggerFollowUp() {
        val item = plan("2026-11-09", RideOutcome.LIKELY_ONBOARD)
        assertFalse(item.awaitingRideAnswer(item.departureAt + RIDE_GRACE_MS))
        assertEquals(RideOutcome.MISSED, item.recordRideAnswer(RideOutcome.MISSED, item.departureAt)?.rideOutcome)
        assertEquals(RideOutcome.CONFIRMED, item.recordRideAnswer(RideOutcome.CONFIRMED, item.departureAt)?.rideOutcome)
    }

    @Test fun predictionsUseOnlyConfirmedRidesOnDifferentDates() {
        val first = plan("2026-10-19", RideOutcome.CONFIRMED)
        val second = plan("2026-10-26", RideOutcome.CONFIRMED)
        val third = plan("2026-11-02", RideOutcome.LIKELY_ONBOARD)
        val next = plan("2026-11-09")
        val now = next.departureAt - 60_000
        assertNull(suggestRecurringRide(listOf(first, first.copy(id = "duplicate"), second, third), now, emptySet()))
        val confirmed = listOf(first, second, third.copy(rideOutcome = RideOutcome.CONFIRMED))
        assertEquals(LocalDate.parse("2026-11-09"), suggestRecurringRide(confirmed, now, emptySet())?.date)
        assertNull(suggestRecurringRide(confirmed + next, now, emptySet()))
        assertEquals(LocalDate.parse("2026-11-16"), suggestRecurringRide(confirmed, now, setOf(next.query.date))?.date)
    }
}
