package za.co.cttrains.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.cttrains.app.data.BoardResult
import za.co.cttrains.app.data.TrainRepository
import za.co.cttrains.app.data.stationBoard
import za.co.cttrains.core.*

data class TrainLookup(val number: String, val station: String, val wholeDay: Boolean, val found: StationDeparture?, val message: String?)

data class StationBoardState(
    val station: String? = null,
    val board: BoardResult? = null,
    /** Scheduled departures in the next hour; null until the first board loads. */
    val nextHour: List<StationDeparture>? = null,
    val loading: Boolean = false,
    val issue: String? = null,
    val searching: Boolean = false,
    val lookup: TrainLookup? = null,
)

/** Station departure board, next-hour counter and train-number lookup, built from published schedules only. */
class StationBoardModel(app: Application) : AndroidViewModel(app) {
    private val trains = TrainRepository(app)
    private val mutable = MutableStateFlow(StationBoardState())
    val state = mutable.asStateFlow()
    private var boardJob: Job? = null
    private var lookupJob: Job? = null

    private fun closed(catalog: StationCatalog, date: LocalDate): String? =
        if (date.dayOfWeek.value == 7 || date.toString() in catalog.holidays) "No trains are listed on Sundays or public holidays." else null

    /** Loads trains leaving [station] in the next hour; reuses a board under 5 minutes old. */
    fun load(station: String, catalog: StationCatalog, sourceId: String, force: Boolean = false) {
        val current = mutable.value
        val fresh = current.board?.takeIf { it.station == station && System.currentTimeMillis() - it.fetchedAt < 300_000L }
        if (!force && (fresh != null || current.loading && current.station == station)) return
        if (station !in catalog.stations) return
        boardJob?.cancel()
        val clock = LocalDateTime.now(CapeTownZone)
        mutable.update { it.copy(station = station, loading = true, issue = null, board = it.board?.takeIf { b -> b.station == station }, nextHour = it.nextHour.takeIf { _ -> it.station == station }) }
        closed(catalog, clock.toLocalDate())?.let { reason -> mutable.update { it.copy(loading = false, nextHour = emptyList(), issue = reason) }; return }
        boardJob = viewModelScope.launch {
            try {
                val now = clock.toLocalTime()
                val start = RailDay.slotAtOrBefore(now.toString().take(5))
                val until = now.plusMinutes(60).let { if (it.isBefore(now)) "23:59" else it.toString().take(5) }
                val board = trains.stationBoard(station, catalog, clock.toLocalDate().toString(), sourceId, start, until)
                val next = StationBoard.within(board.departures, now)
                mutable.update { it.copy(board = board, nextHour = next, loading = false,
                    issue = when {
                        board.checked.isEmpty() -> "Train times for $station couldn’t be checked. Try again."
                        board.failed.isNotEmpty() -> "Some directions couldn’t be checked (${board.failed.joinToString()}). The count may be low."
                        else -> null
                    }) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(loading = false, issue = "Train times for $station couldn’t be checked. Try again.") }
            }
        }
    }

    /** Finds a train by number among trains leaving [station] today, from now or across the whole day. */
    fun findTrain(number: String, station: String, catalog: StationCatalog, sourceId: String, wholeDay: Boolean) {
        val key = StationBoard.trainKey(number)
        if (key == null) { mutable.update { it.copy(lookup = TrainLookup(number, station, wholeDay, null, "Enter a train number, such as 2501.")) }; return }
        if (station !in catalog.stations) { mutable.update { it.copy(lookup = TrainLookup(number, station, wholeDay, null, "Choose a station first.")) }; return }
        val clock = LocalDateTime.now(CapeTownZone)
        closed(catalog, clock.toLocalDate())?.let { reason -> mutable.update { it.copy(lookup = TrainLookup(key, station, wholeDay, null, reason)) }; return }
        // Already on the loaded board: no new requests.
        mutable.value.board?.takeIf { it.station == station }?.let { board ->
            StationBoard.find(key, board.departures)?.let { hit -> mutable.update { it.copy(lookup = TrainLookup(key, station, wholeDay, hit, null)) }; return }
        }
        lookupJob?.cancel()
        mutable.update { it.copy(searching = true, lookup = null) }
        lookupJob = viewModelScope.launch {
            try {
                val start = if (wholeDay) RailDay.FIRST_SLOT else RailDay.slotAtOrBefore(clock.toLocalTime().toString().take(5))
                val board = trains.stationBoard(station, catalog, clock.toLocalDate().toString(), sourceId, start, "23:59",
                    pagesPerEnd = if (wholeDay) 24 else 10) { StationBoard.find(key, it) != null }
                val hit = StationBoard.find(key, board.departures)
                mutable.update { it.copy(searching = false, lookup = TrainLookup(key, station, wholeDay, hit, when {
                    hit != null -> null
                    board.checked.isEmpty() -> "Train times for $station couldn’t be checked. Try again."
                    wholeDay -> "Train $key isn’t listed as leaving $station today. Check the number, or try the station where it starts."
                    else -> "Train $key isn’t listed as leaving $station after ${start}. Try “Whole day” or another station."
                })) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update { it.copy(searching = false, lookup = TrainLookup(key, station, wholeDay, null, "Train times couldn’t be checked. Try again.")) }
            }
        }
    }

    fun clearLookup() = mutable.update { it.copy(lookup = null) }
}
