package za.co.cttrains.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.cttrains.app.data.LocalExtras
import za.co.cttrains.app.data.TrainRepository
import za.co.cttrains.app.widgets.TravelWidgets
import za.co.cttrains.core.*

data class SmartCardsState(
    val nearby: List<Pair<StationPosition, Int>> = emptyList(),
    val cards: List<SmartJourney> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val selected: SmartJourney? = null,
)

class SmartCardsModel(app: Application) : AndroidViewModel(app) {
    private val repository = TrainRepository(app)
    private val mutable = MutableStateFlow(SmartCardsState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var requestKey: String? = null
    private var profileId: String? = null
    private var generation = 0

    fun update(train: TrainState, extras: LocalExtras, now: Long, force: Boolean = false) {
        if (profileId != train.preferences.profileId) {
            pause()
            profileId = train.preferences.profileId
            mutable.value = SmartCardsState()
        }
        if (!extras.beta.smartCards) {
            pause()
            mutable.value = SmartCardsState()
            return
        }
        val position = train.currentPosition
        if (position == null || position.capturedAt !in (now - SmartCards.LOCATION_MAX_AGE)..(now + 30_000)) {
            pause()
            mutable.update { SmartCardsState(message = "Use your location to find nearby stations and Smart Cards.", selected = it.selected) }
            return
        }
        if (!position.accuracyMeters.isFinite() || position.accuracyMeters !in 0f..1500f ||
            !GeoPoint(position.latitude, position.longitude).valid) {
            pause()
            mutable.update { SmartCardsState(message = "Location is too approximate. Refresh your location or choose a station.", selected = it.selected) }
            return
        }
        val point = GeoPoint(position.latitude, position.longitude)
        val nearby = StationChoices.ranked(point, train.stations, train.catalog.stations)
            .map { it to NearbySuggestions.distance(point, WalkingRoutes.endpoint(it).point).toInt() }
            .filter { it.second <= 5000 }.take(3)
        val destination = SmartCards.destination(point, train.stations, train.catalog.stations,
            extras.spots, train.scheduled, train.preferences.routes, train.preferences.favoriteStations, now)
        mutable.update { current -> current.copy(nearby = nearby,
            cards = SmartCards.rank(current.cards.map { card ->
                val meters = nearby.firstOrNull { stationKey(it.first.name) == stationKey(card.query.from) }?.second
                if (meters == null) card else card.copy(accessMinutes = SmartCards.accessMinutes(meters.toDouble()))
            }, now)) }
        if (nearby.isEmpty() || destination == null) {
            pause()
            mutable.update { it.copy(cards = emptyList(), loading = false, message =
                if (nearby.isEmpty()) "No mapped train station within 5 km. Choose a station in Plan."
                else "Save Home with its usual station, or confirm rides to learn a frequent destination.") }
            return
        }
        val local = Instant.ofEpochMilli(now).atZone(CapeTownZone)
        val minute = (local.hour * 60 + local.minute) / 15 * 15
        val time = java.time.LocalTime.ofSecondOfDay(maxOf(180, minute) * 60L).toString()
        val date = local.toLocalDate().toString()
        val origins = nearby.filter { stationKey(it.first.name) != stationKey(destination.station.name) }
        val key = listOf(train.preferences.profileId, train.preferences.sourceId, date, time,
            destination.label, destination.station.name, origins.map { it.first.name }).toString()
        if (!force && requestKey == key) return
        pause()
        requestKey = key
        val version = generation
        mutable.update { it.copy(cards = emptyList(), loading = true, message = null) }
        job = viewModelScope.launch {
            try {
                val stored = repository.store.searches()
                val results = coroutineScope {
                    origins.map { (station, meters) -> async {
                        val query = SearchQuery(station.name, destination.station.name, date, time)
                        if (TravelRules.validationError(query, train.catalog, local.toLocalDate()) != null) return@async emptyList()
                        val result = try { repository.search(query, train.preferences.sourceId) }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            val saved = stored.filter {
                                it.sourceId == train.preferences.sourceId && it.query.date == date &&
                                    stationKey(it.query.from) == stationKey(query.from) && stationKey(it.query.to) == stationKey(query.to) &&
                                    it.fetchedAt in (now - CachePolicy.MAX_AGE_MILLIS)..now
                            }.maxByOrNull { it.fetchedAt } ?: return@async emptyList()
                            za.co.cttrains.app.data.SearchResult(saved, true)
                        }
                        result.entry.journeys.map { journey -> SmartJourney(result.entry.query, journey, destination.label,
                            result.entry.sourceId, result.entry.fetchedAt, result.offline,
                            SmartCards.accessMinutes(meters.toDouble())) }
                    } }.awaitAll().flatten()
                }
                val cards = SmartCards.rank(results, System.currentTimeMillis())
                if (version != generation) return@launch
                mutable.update { it.copy(cards = cards, loading = false, message =
                    if (cards.isEmpty()) "No upcoming train options found for this destination. Open Plan to try another time or station." else null) }
                repository.store.saveSmartCards(
                    SmartCardsSnapshot(train.preferences.profileId, position.capturedAt, System.currentTimeMillis(), cards))
                TravelWidgets.enqueue(getApplication())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == generation) mutable.update { it.copy(loading = false, message = "Smart Cards could not be refreshed. Try again or open Plan.") }
            }
        }
    }

    fun select(card: SmartJourney) {
        if (SmartCards.rank(listOf(card), System.currentTimeMillis()).isEmpty()) {
            mutable.update { it.copy(message = "That departure is no longer suggested. Refresh Smart Cards.") }
        } else mutable.update { it.copy(selected = card) }
    }

    fun dismiss() = mutable.update { it.copy(selected = null) }

    fun pause() {
        generation++
        job?.cancel()
        job = null
        requestKey = null
    }

    companion object { const val SNAPSHOT_KEY = "smart-cards" }
}
