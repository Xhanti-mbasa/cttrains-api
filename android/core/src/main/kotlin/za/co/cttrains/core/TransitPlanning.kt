package za.co.cttrains.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.Serializable

@Serializable
data class StationPosition(val name: String, val latitude: Double? = null, val longitude: Double? = null) {
    val mapped: Boolean get() = latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

@Serializable
data class ScheduledJourney(
    val id: String, val profileId: String, val query: SearchQuery, val journey: Journey,
    val sourceId: String, val fetchedAt: Long, val createdAt: Long,
    val reminderMinutes: Int? = null, val accessMinutes: Int = 20, val bufferMinutes: Int = 5, val fares: String? = null,
) {
    val departureAt: Long get() = LocalDate.parse(query.date).atTime(LocalTime.parse(journey.departure))
        .atZone(CapeTownZone).toInstant().toEpochMilli()
    val arrivalAt: Long get() = departureAt + journey.durationMinutes * 60_000L
    val leaveAt: Long get() = departureAt - (accessMinutes + bufferMinutes) * 60_000L
    val reminderAt: Long? get() = reminderMinutes?.let { departureAt - it * 60_000L }
    val identity: String get() = "$sourceId|${query.from}|${query.to}|${query.date}|${journey.legs.joinToString { "${it.train}:${it.departure.time}" }}"
}

object JourneyPlanning {
    fun validationError(item: ScheduledJourney, now: Long): String? = when {
        item.journey.legs.isEmpty() || item.journey.legs.any { it.stops.size < 2 } -> "Choose a complete journey."
        item.departureAt <= now -> "This train has already departed. Choose a future journey."
        item.accessMinutes !in 0..360 || item.bufferMinutes !in 0..120 -> "Use 0–360 travel minutes and 0–120 buffer minutes."
        item.reminderMinutes != null && item.reminderMinutes !in 1..1440 -> "Choose a reminder from 1 to 1440 minutes before departure."
        item.reminderAt != null && item.reminderAt!! <= now -> "That reminder time has passed. Choose a shorter reminder or no notification."
        else -> null
    }

    /** Splits at unknown positions instead of drawing an invented route over gaps. */
    fun mappedSegments(leg: Leg, stations: List<StationPosition>): List<List<StationPosition>> {
        val lookup = stations.associateBy { stationKey(it.name) }
        val segments = mutableListOf<List<StationPosition>>()
        var segment = mutableListOf<StationPosition>()
        leg.stops.forEach { stop ->
            val station = lookup[stationKey(stop.name)]
            if (station?.mapped == true) segment.add(station)
            else { if (segment.size > 1) segments.add(segment); segment = mutableListOf() }
        }
        if (segment.size > 1) segments.add(segment)
        return segments
    }
}

fun stationKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

fun validateTransitEntry(entry: CachedSearch, query: SearchQuery, sourceId: String, now: Long): CachedSearch {
    if (entry.query != query || entry.sourceId != sourceId || entry.fetchedAt <= 0 || entry.fetchedAt > now + 60_000)
        throw ScheduleFormatException("The transit API returned a different query or source.")
    val time = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    entry.journeys.forEach { journey ->
        if (journey.legs.isEmpty() || journey.legs.any { leg -> leg.line.isBlank() || leg.train.isBlank() || leg.stops.size < 2 || leg.stops.any { it.name.isBlank() || !time.matches(it.time) } } ||
            journey.legs.zipWithNext().any { (a, b) -> stationKey(a.arrival.name) != stationKey(b.departure.name) } ||
            stationKey(journey.legs.first().departure.name) != stationKey(query.from) ||
            stationKey(journey.legs.last().arrival.name) != stationKey(query.to))
            throw ScheduleFormatException("The transit API returned an incomplete journey.")
    }
    return entry.copy(journeys = entry.journeys.distinct().filter { it.departure >= query.time }.sortedBy { it.departure })
}
