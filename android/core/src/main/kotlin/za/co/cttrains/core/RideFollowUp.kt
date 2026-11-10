package za.co.cttrains.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.Serializable

@Serializable
enum class RideFollowUp { LEGACY, PENDING, NOTIFIED, DISMISSED }

@Serializable
enum class RideOutcome { LIKELY_ONBOARD, CONFIRMED, MISSED }

const val RIDE_GRACE_MS = 5 * 60_000L
const val BOARDING_LEAD_MS = 10 * 60_000L

fun ScheduledJourney.awaitingRideAnswer(now: Long): Boolean =
    rideOutcome == null && rideFollowUp in listOf(RideFollowUp.PENDING, RideFollowUp.NOTIFIED) &&
        now >= departureAt + RIDE_GRACE_MS

fun ScheduledJourney.canTrackBoarding(now: Long): Boolean =
    rideOutcome == null && now in departureAt - BOARDING_LEAD_MS until departureAt + RIDE_GRACE_MS

fun ScheduledJourney.recordRideAnswer(outcome: RideOutcome?, now: Long): ScheduledJourney? {
    val canAnswer = awaitingRideAnswer(now) || rideOutcome == RideOutcome.LIKELY_ONBOARD
    if (!canAnswer || outcome == RideOutcome.LIKELY_ONBOARD || (outcome == null && rideOutcome != null)) return null
    return copy(rideOutcome = outcome, rideFollowUp = RideFollowUp.DISMISSED)
}

data class RideSuggestion(val sample: ScheduledJourney, val date: LocalDate)

fun suggestRecurringRide(plans: List<ScheduledJourney>, now: Long, holidays: Set<String>): RideSuggestion? {
    val local = Instant.ofEpochMilli(now).atZone(CapeTownZone)
    data class Pattern(val from: String, val to: String, val weekday: Int, val time: String)
    fun ScheduledJourney.pattern() = Pattern(stationKey(query.from), stationKey(query.to),
        LocalDate.parse(query.date).dayOfWeek.value, journey.departure)

    return plans.filter { it.rideOutcome == RideOutcome.CONFIRMED && it.departureAt <= now }
        .groupBy { it.pattern() }.mapNotNull { (pattern, rides) ->
            if (rides.distinctBy { it.query.date }.size < 3 || pattern.weekday == 7) return@mapNotNull null
            val date = (0L..7L).asSequence().map { local.toLocalDate().plusDays(it) }.firstOrNull {
                it.dayOfWeek.value == pattern.weekday &&
                    it.atTime(LocalTime.parse(pattern.time)).isAfter(local.toLocalDateTime()) && it.toString() !in holidays
            } ?: return@mapNotNull null
            if (plans.any { it.query.date == date.toString() && it.pattern() == pattern }) return@mapNotNull null
            RideSuggestion(rides.maxBy { it.departureAt }, date)
        }.minWithOrNull(compareBy<RideSuggestion> { it.date }.thenBy { it.sample.journey.departure })
}
