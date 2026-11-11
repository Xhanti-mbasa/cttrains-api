package za.co.cttrains.core

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil

data class SmartDestination(val label: String, val station: StationPosition)

@Serializable
data class SmartJourney(
    val query: SearchQuery,
    val journey: Journey,
    val destinationLabel: String,
    val sourceId: String,
    val fetchedAt: Long,
    val cached: Boolean,
    val accessMinutes: Int,
) {
    val departureAt: Long get() = JourneyComparison.requestedAt(query.date, journey.departure)
    val arrivalAt: Long get() = departureAt + journey.durationMinutes * 60_000L
    val id: String get() = "$sourceId|${query.date}|${query.from}|${query.to}|" +
        journey.legs.joinToString { "${it.train}:${it.departure.time}" }
}

@Serializable
data class SmartCardsSnapshot(
    val profileId: String,
    val locationAt: Long,
    val updatedAt: Long,
    val cards: List<SmartJourney>,
)

object SmartCards {
    const val LOCATION_MAX_AGE = 5 * 60_000L
    const val WIDGET_MAX_AGE = 30 * 60_000L

    fun accessMinutes(distanceMeters: Double): Int =
        ceil(distanceMeters * 1.4 / 75.0).toInt().coerceIn(0, 120)

    fun destination(
        point: GeoPoint,
        stations: List<StationPosition>,
        available: List<String>,
        places: List<MarkedSpot>,
        plans: List<ScheduledJourney>,
        routes: List<SavedRoute>,
        favorites: List<String>,
        now: Long,
    ): SmartDestination? {
        val ranked = StationChoices.ranked(point, stations, available)
        val closest = ranked.firstOrNull() ?: return null
        val lookup = ranked.associateBy { stationKey(it.name) }
        fun savedPlace(place: MarkedSpot): SmartDestination? {
            val station = StationChoices.candidates(
                WalkEndpoint("spot:${place.id}", place.name, place.point),
                stations, available, 5000, place.preferredStation,
            ).firstOrNull() ?: return null
            return SmartDestination(place.name, station)
        }
        val home = places.firstOrNull { it.tag.equals("home", true) || it.name.equals("home", true) }
        if (home != null && NearbySuggestions.distance(point, home.point) > 300) return savedPlace(home)
        if (home != null) {
            places.firstOrNull { it.tag.equals("work", true) || it.name.equals("work", true) }
                ?.let { savedPlace(it) }?.let { return it }
        }
        val local = Instant.ofEpochMilli(now).atZone(CapeTownZone)
        val minute = local.hour * 60 + local.minute
        val frequent = plans.filter {
            it.rideOutcome == RideOutcome.CONFIRMED && it.departureAt <= now &&
                stationKey(it.query.to) != stationKey(closest.name)
        }.groupBy { stationKey(it.query.to) }.mapValues { (_, rides) ->
            rides.distinctBy { it.query.date }.sumOf { ride ->
                val time = Instant.ofEpochMilli(ride.departureAt).atZone(CapeTownZone)
                val difference = abs(minute - (time.hour * 60 + time.minute))
                10 + (if (time.dayOfWeek == local.dayOfWeek) 4 else 0) +
                    (if (minOf(difference, 1440 - difference) <= 120) 5 else 0)
            }
        }.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val candidates = frequent.map { it.key } +
            routes.filter { stationKey(it.from) == stationKey(closest.name) }.map { stationKey(it.to) } +
            favorites.map(::stationKey)
        return candidates.asSequence().filter { it != stationKey(closest.name) }
            .mapNotNull { lookup[it] }.firstOrNull()?.let { SmartDestination(it.name, it) }
    }

    fun rank(cards: List<SmartJourney>, now: Long): List<SmartJourney> {
        val today = Instant.ofEpochMilli(now).atZone(CapeTownZone).toLocalDate().toString()
        return cards.filter {
            it.query.date == today && it.fetchedAt in (now - CachePolicy.MAX_AGE_MILLIS)..now &&
                it.journey.legs.isNotEmpty() && stationKey(it.query.from) != stationKey(it.query.to) &&
                it.departureAt >= now + (it.accessMinutes + 5) * 60_000L
        }.sortedWith(compareBy<SmartJourney> { it.arrivalAt }
            .thenBy { it.accessMinutes }.thenBy { it.journey.transfers }.thenBy { it.departureAt })
            .distinctBy { card -> card.query.to + "|" + card.journey.legs.joinToString { "${it.train}:${it.arrival.time}" } }
            .take(3)
    }

    fun nextTrip(plans: List<ScheduledJourney>, now: Long): ScheduledJourney? = plans.filter {
        it.departureAt > now && it.rideOutcome != RideOutcome.MISSED
    }.minByOrNull { it.departureAt }
}
