package za.co.cttrains.core

import java.time.LocalTime

/**
 * The CT Trains source answers "from A to B, the next few trains after a 15-minute slot" (four per page).
 * These helpers page through a whole service day and build station boards from those answers.
 */
object RailDay {
    const val FIRST_SLOT = "03:00"
    private const val LAST_SLOT = 23 * 60 + 45

    private fun minute(time: String) = LocalTime.parse(time).toSecondOfDay() / 60
    private fun slot(minute: Int) = LocalTime.of(minute / 60, minute % 60).toString()

    /** Rounds a clock time down to the source's 15-minute slot, never before 03:00. */
    fun slotAtOrBefore(time: String): String = slot((minute(time) / 15 * 15).coerceIn(180, LAST_SLOT))

    /**
     * The slot to ask for next, or null when the day is done. It always moves forward, so paging ends.
     * A page whose trains all leave within the current slot advances by one slot.
     */
    fun nextSlot(current: String, page: List<Journey>): String? {
        val start = minute(current)
        val later = page.map { minute(it.departure) }.filter { it >= start }
        if (later.isEmpty()) return null
        val next = (later.max() / 15 * 15).let { if (it <= start) start + 15 else it }
        return if (next > LAST_SLOT) null else slot(next)
    }

    /** One chronological list per day, without the repeats that overlapping pages return. */
    fun merge(pages: List<List<Journey>>): List<Journey> = pages.flatten()
        .distinctBy { j -> j.legs.joinToString("|") { "${it.train}@${it.departure.time}>${it.arrival.name}" } }
        .sortedWith(compareBy({ minute(it.departure) }, { minute(it.arrival) }))
}

/** A train leaving a station, as far as one search can see it. */
data class StationDeparture(
    val train: String,
    val line: String,
    val time: String,
    val platform: String?,
    /** Stops from this station onward. */
    val stops: List<Stop>,
    /** True when the search followed this train to its line end without changing trains. */
    val throughToEnd: Boolean,
) {
    val towards: String get() = stops.last().name
}

object StationBoard {
    /** Ends of the lines reaching each terminus (ordered as cttrains.co.za's line pages). */
    private val southern = listOf("Cape Town", "Woodstock", "Salt River", "Observatory", "Mowbray", "Rosebank", "Rondebosch", "Newlands",
        "Claremont", "Harfield Road", "Kenilworth", "Wynberg", "Wittebome", "Plumstead", "Steurhof", "Dieprivier", "Heathfield", "Retreat",
        "Steenberg", "Lakeside", "False Bay", "Muizenberg", "St James", "Kalk Bay", "Fish Hoek", "Sunny Cove", "Glencairn", "Simonstown")
    private val capeFlats = listOf("Cape Town", "Woodstock", "Salt River", "Koeberg Road", "Maitland", "Ndabeni", "Pinelands", "Langa",
        "Bonteheuwel", "Heideveld", "Nyanga", "Philippi", "Athlone", "Hazendal", "Netreg", "Lavistown", "Lansdowne", "Crawford", "Ottery",
        "Southfield", "Heathfield", "Retreat")
    private val central = listOf("Cape Town", "Woodstock", "Salt River", "Koeberg Road", "Maitland", "Ndabeni", "Mutual", "Esplanade",
        "Pinelands", "Langa", "Bonteheuwel", "Netreg", "Heideveld", "Lavistown", "Nyanga", "Philippi", "Belhar", "Unibell", "Pentech",
        "Lentegeur", "Stock Road", "Sarepta", "Mitchells Plain", "Mandalay", "Bellville", "Kapteinsklip", "Nolungile", "Nonkqubela",
        "Khayelitsha", "Kuyasa", "Chris Hani")
    private val northern = listOf("Cape Town", "Woodstock", "Salt River", "Koeberg Road", "Maitland", "Ndabeni", "Mutual", "Woltemade",
        "Thornton", "Goodwood", "Vasco", "Monte Vista", "De Grendel", "Century City", "Kentemade", "Elsies River", "Tygerberg", "Parow",
        "Akasia Park", "Avondale", "Bellville", "Stikland", "Kuils River", "Blackheath", "Brackenfell", "Kraaifontein", "Eerste River",
        "Eikenfontein", "Faure", "Firgrove", "Somerset West", "Strand", "Lynedoch", "Van Der Stel", "Vlottenburg", "Stellenbosch",
        "Dal Josafat", "Mbekweni", "Paarl", "Huguenot", "Wellington", "Muldersvlei", "Klapmuts", "Oosterzee", "Meltonrose", "Du Toit")
    private val termini: Map<String, List<List<String>>> = mapOf(
        "Cape Town" to listOf(southern, capeFlats, central, northern),
        "Simonstown" to listOf(southern),
        "Retreat" to listOf(southern, capeFlats),
        "Chris Hani" to listOf(central),
        "Kapteinsklip" to listOf(central),
        "Bellville" to listOf(central, northern),
        "Strand" to listOf(northern),
        "Wellington" to listOf(northern),
        "Muldersvlei" to listOf(northern),
    )

    /** Line ends worth asking about for this station, spelled as the source's catalogue spells them. */
    fun destinations(station: String, catalog: List<String>): List<String> {
        val key = stationKey(station)
        val known = catalog.associateBy(::stationKey)
        return termini.filter { (end, lines) -> stationKey(end) != key && lines.any { line -> line.any { stationKey(it) == key } } }
            .keys.mapNotNull { known[stationKey(it)] }
    }

    /** First-leg trains leaving [station], one entry per train, earliest first. */
    fun departures(station: String, journeys: List<Journey>): List<StationDeparture> {
        val key = stationKey(station)
        return journeys.mapNotNull { journey ->
            val leg = journey.legs.first()
            val start = leg.stops.indexOfFirst { stationKey(it.name) == key }
            if (start < 0 || start == leg.stops.lastIndex) return@mapNotNull null
            val stops = leg.stops.drop(start)
            StationDeparture(leg.train, leg.line, stops.first().time, stops.first().platform, stops, journey.legs.size == 1)
        }.groupBy { trainKey(it.train) to it.time }.values
            // The same train seen in several searches: keep the longest view of where it goes.
            .map { same -> same.maxWith(compareBy({ it.throughToEnd }, { it.stops.size })) }
            .sortedWith(compareBy({ LocalTime.parse(it.time) }, { it.train }))
    }

    /** Scheduled departures from [now] up to [minutes] later (default: the next hour). */
    fun within(departures: List<StationDeparture>, now: LocalTime, minutes: Long = 60): List<StationDeparture> {
        val start = now.withSecond(0).withNano(0)
        val end = start.plusMinutes(minutes)
        val wraps = end.isBefore(start)
        return departures.filter { val t = LocalTime.parse(it.time); !t.isBefore(start) && (wraps || t.isBefore(end)) }
    }

    /** "T2501", "2501" and " t 2501 " all mean train 2501. Returns null for anything that isn't a train number. */
    fun trainKey(input: String): String? = input.trim().removePrefix("T").removePrefix("t").trim().takeIf { it.matches(Regex("\\d{2,6}")) }?.trimStart('0')?.ifEmpty { "0" }

    fun find(train: String, departures: List<StationDeparture>): StationDeparture? {
        val key = trainKey(train) ?: return null
        return departures.firstOrNull { trainKey(it.train) == key }
    }
}
