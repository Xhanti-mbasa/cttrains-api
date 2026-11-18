package za.co.cttrains.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import za.co.cttrains.core.*

/** The result of paging one station pair through part or all of a day. */
data class DayResult(val journeys: List<Journey>, val fetchedAt: Long, val fares: String?, val sourceId: String, val offline: Boolean, val pages: Int, val complete: Boolean)

/**
 * Pages the source (four trains per answer) from [start] until the last train of the day, reusing
 * answers saved in the last 15 minutes. Requests are spaced out to stay polite to the community source.
 */
suspend fun TrainRepository.searchDay(from: String, to: String, date: String, sourceId: String, start: String = RailDay.FIRST_SLOT,
    maxPages: Int = 30, until: String? = null, onPage: suspend (Int) -> Unit = {}): DayResult {
    var slot: String? = RailDay.slotAtOrBefore(start)
    val pages = mutableListOf<List<Journey>>()
    var fetchedAt = Long.MAX_VALUE; var fares: String? = null; var offline = false; var requested = 0
    while (slot != null && pages.size < maxPages) {
        currentCoroutineContext().ensureActive()
        val query = SearchQuery(from, to, date, slot)
        val fresh = store.cached(query, sourceId)?.takeIf { System.currentTimeMillis() - it.fetchedAt in 0..900_000L }
        val result = fresh?.let { SearchResult(it, true) } ?: try {
            if (requested++ > 0) delay(300)
            search(query, sourceId)
        } catch (e: Exception) {
            // Keep the trains already found; only a failed first page means nothing could be checked.
            if (e is CancellationException || pages.isEmpty()) throw e
            break
        }
        pages += result.entry.journeys
        fetchedAt = minOf(fetchedAt, result.entry.fetchedAt)
        fares = fares ?: result.entry.fares
        offline = offline || result.offline
        onPage(pages.size)
        val next = RailDay.nextSlot(slot, result.entry.journeys)
        if (until != null && next != null && next > until) { slot = null; break }
        slot = next
    }
    return DayResult(RailDay.merge(pages), if (fetchedAt == Long.MAX_VALUE) System.currentTimeMillis() else fetchedAt, fares, sourceId, offline, pages.size, slot == null)
}

data class BoardResult(val station: String, val date: String, val departures: List<StationDeparture>, val checked: List<String>, val failed: List<String>, val fetchedAt: Long, val offline: Boolean)

/**
 * Trains leaving [station] between [start] and [until], found by asking for trips to each line end
 * through that station. A train that turns back before a line end appears only when a search reaches it.
 */
suspend fun TrainRepository.stationBoard(station: String, catalog: StationCatalog, date: String, sourceId: String,
    start: String, until: String, pagesPerEnd: Int = 3, stopWhen: (List<StationDeparture>) -> Boolean = { false }): BoardResult {
    val journeys = mutableListOf<Journey>()
    val checked = mutableListOf<String>(); val failed = mutableListOf<String>()
    var fetchedAt = Long.MAX_VALUE; var offline = false
    for (end in StationBoard.destinations(station, catalog.stations)) {
        try {
            val day = searchDay(station, end, date, sourceId, start, pagesPerEnd, until)
            journeys += day.journeys; checked += end
            fetchedAt = minOf(fetchedAt, day.fetchedAt); offline = offline || day.offline
        } catch (e: Exception) { if (e is CancellationException) throw e; failed += end }
        if (stopWhen(StationBoard.departures(station, journeys))) break
    }
    return BoardResult(station, date, StationBoard.departures(station, journeys), checked, failed,
        if (fetchedAt == Long.MAX_VALUE) System.currentTimeMillis() else fetchedAt, offline)
}
