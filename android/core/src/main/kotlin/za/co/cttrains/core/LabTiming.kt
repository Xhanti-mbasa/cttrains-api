package za.co.cttrains.core

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Whether a scheduled departure can be reached on foot from the moment [LabTiming.reachability] is asked. */
enum class WalkFeasibility { REACHABLE, TIGHT, NOT_WALKABLE, DEPARTED }

data class Reachability(val feasibility: WalkFeasibility, val spareMinutes: Int, val leaveAt: Long)

/**
 * Presentation rules for the CT Trains Lab edition. Pure and additive: no existing planning,
 * reminder or timetable behaviour depends on this object. Every time is a published schedule.
 */
object LabTiming {
    /** Fewer spare minutes than this is shown as Tight. */
    const val TIGHT_MARGIN = 3
    private val Clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(CapeTownZone)
    private val Day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun reachability(departureAt: Long, accessMinutes: Int, bufferMinutes: Int, now: Long): Reachability {
        require(accessMinutes >= 0 && bufferMinutes >= 0)
        val leaveAt = departureAt - (accessMinutes + bufferMinutes) * 60_000L
        val untilDeparture = Math.floorDiv(departureAt - now, 60_000L).toInt()
        val spare = untilDeparture - accessMinutes
        val feasibility = when {
            departureAt <= now -> WalkFeasibility.DEPARTED
            spare < 0 -> WalkFeasibility.NOT_WALKABLE
            spare < TIGHT_MARGIN -> WalkFeasibility.TIGHT
            else -> WalkFeasibility.REACHABLE
        }
        return Reachability(feasibility, spare, leaveAt)
    }

    /** Word first; the number explains it. Never claims a live position. */
    fun reachabilityLabel(r: Reachability): String = when (r.feasibility) {
        WalkFeasibility.REACHABLE -> "Reachable · ${r.spareMinutes} min spare"
        WalkFeasibility.TIGHT -> "Tight · ${minutes(r.spareMinutes)} spare"
        WalkFeasibility.NOT_WALKABLE -> "Not at walking pace · ${minutes(-r.spareMinutes)} short"
        WalkFeasibility.DEPARTED -> "Scheduled departure has passed"
    }

    /** The saved plan that leaves next for this profile and has not been answered as caught or missed. */
    fun nextPlan(items: List<ScheduledJourney>, profileId: String, now: Long): ScheduledJourney? =
        items.filter { it.profileId == profileId && it.rideOutcome == null && it.departureAt > now }.minByOrNull { it.departureAt }

    /** Departures on [date] that have not yet left at [now], in timetable order. */
    fun upcoming(journeys: List<Journey>, date: String, now: Long): List<Journey> =
        journeys.filter { it.legs.isNotEmpty() && JourneyComparison.requestedAt(date, it.departure) > now }
            .sortedBy { JourneyComparison.requestedAt(date, it.departure) }

    fun clock(epochMillis: Long): String = Clock.format(Instant.ofEpochMilli(epochMillis))

    /** Arrivals are always approximate: the source publishes a schedule, not a tracked position. */
    fun arrival(time: String): String = "~$time"

    fun duration(totalMinutes: Int): String {
        require(totalMinutes >= 0)
        return if (totalMinutes < 60) "$totalMinutes min" else "${totalMinutes / 60} h ${"%02d".format(totalMinutes % 60)}"
    }

    fun changes(transfers: Int): String = when (transfers) { 0 -> "direct"; 1 -> "1 change"; else -> "$transfers changes" }

    fun age(fetchedAt: Long, now: Long): String {
        val minutes = Math.floorDiv(now - fetchedAt, 60_000L)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} d ago"
        }
    }

    /** A snapshot older than this is labelled stale wherever its times are shown. */
    const val STALE_AFTER = 30 * 60_000L
    fun stale(fetchedAt: Long, now: Long): Boolean = now - fetchedAt > STALE_AFTER

    fun day(date: String): String = LocalDate.parse(date).format(Day)

    private fun minutes(n: Int) = "$n min"
}
