package za.co.cttrains.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

val CapeTownZone: ZoneId = ZoneId.of("Africa/Johannesburg")

@Serializable
data class SearchQuery(val from: String, val to: String, val date: String, val time: String) {
    val websiteDate: String get() = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}

@Serializable
data class Stop(val name: String, val time: String, val platform: String? = null)

@Serializable
data class Leg(val line: String, val train: String, val stops: List<Stop>) {
    val departure: Stop get() = stops.first()
    val arrival: Stop get() = stops.last()
}

@Serializable
data class Journey(val legs: List<Leg>) {
    val departure: String get() = legs.first().departure.time
    val arrival: String get() = legs.last().arrival.time
    val transfers: Int get() = legs.size - 1
    // End-to-end duration includes the time spent changing trains.
    val durationMinutes: Int get() = minutesBetween(departure, arrival)
    fun connectionMinutes(index: Int): Int = minutesBetween(legs[index - 1].arrival.time, legs[index].departure.time)
}

fun minutesBetween(from: String, to: String): Int {
    val a = LocalTime.parse(from).toSecondOfDay() / 60
    val b = LocalTime.parse(to).toSecondOfDay() / 60
    return (b - a + 1440) % 1440
}

@Serializable
data class StationCatalog(val stations: List<String>, val holidays: List<String> = emptyList())

@Serializable
data class SavedRoute(val from: String, val to: String)

@Serializable
data class CachedSearch(val query: SearchQuery, val journeys: List<Journey>, val fetchedAt: Long, val fares: String? = null, val sourceId: String = "cttrains-web")

object CachePolicy {
    const val MAX_ENTRIES = 12
    const val MAX_AGE_MILLIS = 14L * 24 * 60 * 60 * 1000
    fun prune(entries: List<CachedSearch>, now: Long): List<CachedSearch> = entries
        .filter { it.fetchedAt <= now && now - it.fetchedAt <= MAX_AGE_MILLIS }
        .sortedByDescending { it.fetchedAt }.distinctBy { it.sourceId to it.query }.take(MAX_ENTRIES)
}

object TravelRules {
    fun lastAllowedDate(today: LocalDate): LocalDate = today.plusMonths(1).withDayOfMonth(today.plusMonths(1).lengthOfMonth())

    fun validationError(query: SearchQuery, catalog: StationCatalog, today: LocalDate): String? {
        if (query.from !in catalog.stations || query.to !in catalog.stations) return "Choose a departure and arrival station."
        if (query.from == query.to) return "Choose two different stations."
        val date = runCatching { LocalDate.parse(query.date) }.getOrNull() ?: return "Choose a valid travel date."
        if (date < today || date > lastAllowedDate(today)) return "Choose a date from today to the end of next month."
        if (date.dayOfWeek.value == 7 || query.date in catalog.holidays) return "CT Trains does not list services on Sundays or public holidays. Choose another date."
        val time = runCatching { LocalTime.parse(query.time) }.getOrNull() ?: return "Choose a valid departure time."
        if (time < LocalTime.of(3, 0) || time.minute % 15 != 0) return "Choose a departure time from 03:00, in 15-minute steps."
        return null
    }

    fun defaultTime(now: LocalTime): String {
        val minutes = now.toSecondOfDay() / 60
        val rounded = ((minutes + 14) / 15 * 15).coerceIn(180, 1425)
        return LocalTime.of(rounded / 60, rounded % 60).format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}

fun journeyShareText(query: SearchQuery, journey: Journey): String = buildString {
    append("${query.from} → ${query.to}\n${query.date} · ${journey.departure}–${journey.arrival} · ${journey.durationMinutes} min\n")
    journey.legs.forEachIndexed { i, leg ->
        if (i > 0) append("Change at ${leg.departure.name} · ${journey.connectionMinutes(i)} min connection\n")
        append("${leg.line} · Train ${leg.train}\n${leg.departure.time} ${leg.departure.name} → ${leg.arrival.time} ${leg.arrival.name}\n")
    }
    append("Scheduled times, not live tracking. Confirm platform and service at the station.\nhttps://cttrains.co.za/train-form.php")
}
