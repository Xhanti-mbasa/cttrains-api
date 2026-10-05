package za.co.cttrains.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import za.co.cttrains.app.data.Preferences
import za.co.cttrains.app.data.SearchResult
import za.co.cttrains.app.data.TrainRepository
import za.co.cttrains.core.*
import java.util.UUID
import za.co.cttrains.app.location.CurrentPosition
import za.co.cttrains.app.location.ForegroundLocation
import za.co.cttrains.app.scheduling.JourneyReminders
import za.co.cttrains.app.scheduling.notificationsAllowed
import za.co.cttrains.app.maps.*

data class TrainState(
    val from: String = "", val to: String = "",
    val date: String = LocalDate.now(CapeTownZone).toString(),
    val time: String = TravelRules.defaultTime(LocalTime.now(CapeTownZone)),
    val catalog: StationCatalog = StationCatalog(emptyList()),
    val preferences: Preferences = Preferences(),
    val stationNotice: String? = null,
    val loading: Boolean = false,
    val result: SearchResult? = null,
    val error: String? = null,
    val message: String? = null,
    val stations: List<StationPosition> = emptyList(),
    val selectedStation: String? = null,
    val selectedJourney: Journey? = null,
    val scheduled: List<ScheduledJourney> = emptyList(),
    val currentPosition: CurrentPosition? = null,
    val locating: Boolean = false,
    val requestedScreen: String? = null,
    val mapsReady: Boolean = false,
    val alerts: AlertSnapshot? = null, val alertsLoading: Boolean = false, val alertsOffline: Boolean = false, val alertsError: String? = null,
    val routeTrace: JourneyTrace? = null, val traceLoading: Boolean = false, val refreshing: Boolean=false, val selectedTransfer: Int? = null,
) {
    val query: SearchQuery get() = SearchQuery(from, to, date, time)
    val isSaved: Boolean get() = SavedRoute(from, to) in preferences.routes
}

class TrainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = TrainRepository(application)
    private val mutableState = MutableStateFlow(TrainState())
    val state = mutableState.asStateFlow()
    private val storageErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> showMessage("Local storage could not be opened. You can reset local data in Settings.") }
    private var searchJob: Job? = null
    private var locationJob: Job? = null
    private var locationExpiryJob: Job? = null
    private val reminders = JourneyReminders(application)
    val offlineMaps = OfflineMaps(application)
    val mapViewport = MapViewport()
    private var requestedMapMode: String? = null
    private var alertsJob: Job? = null

    init {
        viewModelScope.launch(storageErrors) {
            var restored = false
            repository.preferences.collect { preferences ->
                val mapMode = requestedMapMode ?: preferences.mapMode
                if (preferences.mapMode == requestedMapMode) requestedMapMode = null
                MapEngine.mode(mapMode)
                val visiblePreferences = preferences.copy(mapMode = mapMode)
                if (repository.sources.none { it.id == preferences.sourceId }) {
                    repository.updatePreferences { it.copy(sourceId = "cttrains-web") }
                    return@collect
                }
                mutableState.update { current ->
                    val previous = preferences.lastQuery
                    if (!restored && previous != null) {
                        restored = true
                        // Reuse stations and time; an old travel date must not become today's result.
                        current.copy(preferences = visiblePreferences, from = previous.from, to = previous.to, time = previous.time, mapsReady = true)
                    } else { restored = true; current.copy(preferences = visiblePreferences, mapsReady = true) }
                }
            }
        }
        viewModelScope.launch(storageErrors) {
            val initial = runCatching { repository.storedCatalog() }.getOrNull() ?: repository.bundledCatalog()
            mutableState.update { it.copy(catalog = initial) }
            try {
                val requestedSource = repository.preferences.first().sourceId
                val current = repository.refreshCatalog(if (repository.sources.any { it.id == requestedSource }) requestedSource else "cttrains-web")
                mutableState.update { it.copy(catalog = current, stationNotice = null) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.update { it.copy(stationNotice = "Using a saved station list. Connect to refresh it.") }
            }
        }
        viewModelScope.launch(storageErrors) { repository.store.stations.collect { stations -> mutableState.update { it.copy(stations = stations) } } }
        viewModelScope.launch(storageErrors) { repository.store.journeys.collect { journeys -> mutableState.update { it.copy(scheduled = journeys) } } }
        viewModelScope.launch(storageErrors) { repository.store.initialize(); reminders.restoreMissing(repository.store.journeys.first()) }
        viewModelScope.launch(storageErrors) {
            val saved = runCatching { repository.savedAlerts() }.getOrNull()
            mutableState.update { it.copy(alerts = saved, alertsOffline = saved != null) }
            refreshAlerts()
        }
        viewModelScope.launch(storageErrors) {
            state.map { it.selectedJourney to it.stations }.distinctUntilChanged().collectLatest { (journey, stations) ->
                mutableState.update { it.copy(routeTrace = null, traceLoading = journey != null, selectedTransfer = null) }
                if (journey != null) {
                    val trace = runCatching { repository.trace(journey, stations) }.getOrElse { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        null
                    }
                    mutableState.update { if (it.selectedJourney == journey) it.copy(routeTrace = trace, traceLoading = false) else it }
                }
            }
        }
    }

    fun edit(transform: (TrainState) -> TrainState) {
        searchJob?.cancel()
        mutableState.update { transform(it).copy(result = null, selectedJourney = null, error = null, loading = false) }
    }

    fun useRoute(route: SavedRoute) = edit { it.copy(from = route.from, to = route.to, date = LocalDate.now(CapeTownZone).toString()) }
    fun openMap()=mutableState.update { it.copy(requestedScreen="map") }
    fun openDirections()=mutableState.update { it.copy(requestedScreen="directions") }
    fun openAppUpdates()=mutableState.update { it.copy(requestedScreen="app-updates") }
    fun directionsPosition(point: CurrentPosition?)=mutableState.update { it.copy(currentPosition=point) }
    fun presentPlannedJourney(query: SearchQuery,journey: Journey,checked: Long,cached: Boolean,fares: String?,sourceId: String=state.value.preferences.sourceId,openMap: Boolean=true) {
        searchJob?.cancel()
        mutableState.update { it.copy(from=query.from,to=query.to,
            result=SearchResult(CachedSearch(query,listOf(journey),checked,fares,sourceId),cached),selectedJourney=journey,
            requestedScreen=if(openMap) "map" else it.requestedScreen,loading=false,error=null) }
    }

    fun refreshHome() {
        if(state.value.refreshing) return
        refreshAlerts()
        if(state.value.result!=null) performSearch(state.value.selectedJourney!=null,state.value.selectedJourney)
        viewModelScope.launch(storageErrors) {
            mutableState.update { it.copy(refreshing=true) }
            try { val catalog=repository.refreshCatalog(state.value.preferences.sourceId);mutableState.update { it.copy(catalog=catalog,stationNotice=null) } }
            catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;mutableState.update { it.copy(stationNotice="Refresh unavailable. Using the saved station list.") } }
            finally { mutableState.update { it.copy(refreshing=false) } }
        }
    }
    fun search() = performSearch(false)
    private fun performSearch(autoSelect: Boolean,previous: Journey?=null) {
        val current = state.value
        val error = TravelRules.validationError(current.query, current.catalog, LocalDate.now(CapeTownZone))
        if (error != null) { mutableState.update { it.copy(error = error) }; return }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(storageErrors) {
            mutableState.update { it.copy(loading = true, error = null, result = null, selectedJourney = null) }
            try {
                val result = repository.search(current.query, current.preferences.sourceId)
                repository.updatePreferences { it.copy(lastQuery = current.query) }
                mutableState.update { it.copy(loading = false, result = result, selectedJourney = if(autoSelect) result.entry.journeys.firstOrNull { it==previous } ?: result.entry.journeys.firstOrNull() else null) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val message = if (error is ScheduleFormatException) error.message else "Couldn’t reach CT Trains. Check your connection and try again."
                mutableState.update { it.copy(loading = false, error = message) }
            }
        }
    }

    fun toggleRoute(route: SavedRoute = SavedRoute(state.value.from, state.value.to)) = viewModelScope.launch(storageErrors) {
        if (route.from.isBlank() || route.to.isBlank() || route.from == route.to) return@launch
        repository.updatePreferences { prefs ->
            if (route in prefs.routes) prefs.copy(routes = prefs.routes - route)
            else prefs.copy(routes = (prefs.routes + route).takeLast(20))
        }
    }

    fun setTheme(theme: String) = viewModelScope.launch(storageErrors) { repository.updatePreferences { it.copy(theme = theme) } }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch(storageErrors) { repository.updatePreferences { it.copy(dynamicColor = enabled) } }
    fun clearCache() = viewModelScope.launch(storageErrors) {
        searchJob?.cancel()
        repository.clearCache()
        mutableState.update { it.copy(result = null, loading = false, message = "Cached timetables cleared.") }
    }
    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun selectStation(name: String?) = mutableState.update { it.copy(selectedStation = name) }
    fun selectTransfer(number: Int?) = mutableState.update { it.copy(selectedTransfer = number, selectedStation = null) }
    fun openAlerts() = mutableState.update { it.copy(requestedScreen = "alerts") }
    fun refreshAlerts() {
        if (alertsJob?.isActive == true) return
        alertsJob = viewModelScope.launch(storageErrors) {
            mutableState.update { it.copy(alertsLoading = true, alertsError = null) }
            try {
                val snapshot = repository.refreshAlerts()
                mutableState.update { it.copy(alerts = snapshot, alertsLoading = false, alertsOffline = false) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val saved = state.value.alerts?.takeIf { ServiceAlerts.usable(it, System.currentTimeMillis()) }
                mutableState.update { it.copy(alerts = saved, alertsLoading = false, alertsOffline = saved != null,
                    alertsError = if (error is ScheduleFormatException) error.message else "Couldn’t refresh CT Trains alerts. Check your connection or try again later.") }
            }
        }
    }
    fun selectJourney(journey: Journey?) = mutableState.update { it.copy(selectedJourney = journey, selectedTransfer = null) }
    fun toggleStation(name: String) = viewModelScope.launch(storageErrors) {
        repository.updatePreferences { prefs -> prefs.copy(favoriteStations = if (name in prefs.favoriteStations) prefs.favoriteStations - name else (prefs.favoriteStations + name).takeLast(30)) }
    }
    fun setMapMode(mode: String) = viewModelScope.launch(storageErrors) {
        if (mode !in listOf("online", "offline", "stations")) return@launch
        requestedMapMode = mode
        if (mode != "online") offlineMaps.pauseAll()
        MapEngine.mode(mode)
        repository.updatePreferences { it.copy(mapMode = mode) }
    }
    fun downloadMap(journey: Journey? = null) = viewModelScope.launch(storageErrors) {
        if (journey != null && state.value.traceLoading) return@launch showMessage("Wait for the route trace to load before downloading this journey.")
        val area = MapPlanning.area(state.value.stations, journey, if (journey == state.value.selectedJourney) state.value.routeTrace else null) ?: return@launch showMessage("Choose a mapped journey first.")
        requestedMapMode = "online"; MapEngine.mode("online")
        repository.updatePreferences { it.copy(mapMode = "online") }
        if (requestedMapMode != null && requestedMapMode != "online") return@launch
        val style = za.co.cttrains.app.data.FeatureStore(getApplication()).extras().beta.mapStyle
        offlineMaps.create(area, style)
    }
    fun resumeMap(id: Long) = viewModelScope.launch(storageErrors) {
        requestedMapMode = "online"; MapEngine.mode("online")
        repository.updatePreferences { it.copy(mapMode = "online") }
        if (requestedMapMode != null && requestedMapMode != "online") return@launch
        offlineMaps.resume(id)
    }
    fun downloadArea(area: MapArea) = viewModelScope.launch(storageErrors) {
        requestedMapMode="online";MapEngine.mode("online");repository.updatePreferences { it.copy(mapMode="online") }
        offlineMaps.create(area,za.co.cttrains.app.data.FeatureStore(getApplication()).extras().beta.mapStyle)
    }
    fun useOfflineMap(id: Long) = viewModelScope.launch(storageErrors) {
        requestedMapMode = "offline"
        offlineMaps.pauseAll(); MapEngine.mode("offline")
        repository.updatePreferences { it.copy(mapMode = "offline", offlineMapId = id) }
    }
    fun setFareCorrection(route: SavedRoute, ticket: String, cents: Long?) = viewModelScope.launch(storageErrors) {
        if (route.from.isBlank() || route.to.isBlank() || route.from == route.to || TripBudget.tickets.none { it.id == ticket } || (cents != null && cents !in 0..10_000_000)) return@launch
        repository.updatePreferences { p ->
            val kept = p.fareCorrections.filterNot { it.from == route.from && it.to == route.to && it.ticketId == ticket }
            p.copy(fareCorrections = (if (cents == null) kept else kept + FareCorrection(route.from, route.to, ticket, cents, System.currentTimeMillis())).takeLast(100))
        }
    }
    fun setSource(id: String) = viewModelScope.launch(storageErrors) {
        if (repository.sources.none { it.id == id }) return@launch
        edit { it.copy(preferences = it.preferences.copy(sourceId = id)) }
        repository.updatePreferences { it.copy(sourceId = id) }
    }
    fun locateOnce() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch(storageErrors) {
            mutableState.update { it.copy(locating = true) }
            try {
                val point = ForegroundLocation.sample(getApplication())?.takeIf { it.capturedAt<=System.currentTimeMillis()+30_000 && System.currentTimeMillis()-it.capturedAt<=2*60_000 }
                locationExpiryJob?.cancel()
                mutableState.update { it.copy(currentPosition = point, locating = false, message = if (point == null) "Location is unavailable. Station search still works." else "Location sampled once. Accuracy about ${point.accuracyMeters.toInt()} m.") }
                if (point != null) locationExpiryJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(5 * 60_000L)
                    mutableState.update { if (it.currentPosition == point) it.copy(currentPosition = null) else it }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.update { it.copy(locating = false, message = "Couldn’t get your location. Check location services and try again.") }
            }
        }
    }
    fun forgetLocation() { locationJob?.cancel(); locationExpiryJob?.cancel(); mutableState.update { it.copy(currentPosition = null, locating = false) } }
    fun nearbyOrigin(name: String) { if(state.value.from!=name) edit { it.copy(from=name) } }
    fun quickDestination(origin: String,destination: String) {
        edit { it.copy(from=origin,to=destination,date=LocalDate.now(CapeTownZone).toString(),time=TravelRules.defaultTime(LocalTime.now(CapeTownZone))) }
        performSearch(true)
    }
    fun showMessage(message: String) = mutableState.update { it.copy(message = message) }
    fun openBudget() = mutableState.update { it.copy(requestedScreen = "budget") }
    fun openCommunity() = mutableState.update { it.copy(requestedScreen = "community") }
    fun schedule(query: SearchQuery, journey: Journey, reminder: Int?, access: Int, buffer: Int, leaveFrom: String, severity: String, backups: List<BackupReminder>, onResult: (String?) -> Unit): Job = viewModelScope.launch(storageErrors) {
        try {
            if (reminder != null && !notificationsAllowed(getApplication())) { onResult("Enable notifications, or save this journey without an app reminder."); return@launch }
            val prefs = repository.preferences.first()
            val result = state.value.result?.entry
            val item = ScheduledJourney(UUID.randomUUID().toString(), prefs.profileId, query, journey,
                result?.sourceId ?: prefs.sourceId, result?.fetchedAt ?: System.currentTimeMillis(), System.currentTimeMillis(), reminder, access, buffer, result?.fares,
                leaveFrom.trim(), "leave", severity, if (reminder == null) emptyList() else backups, UUID.randomUUID().toString())
            val error = JourneyPlanning.validationError(item, System.currentTimeMillis())
            if (error != null) { onResult(error); return@launch }
            val saved = repository.store.saveJourney(item)
            reminders.schedule(saved)
            showMessage("Journey saved${if (reminder != null) " with a $reminder minute reminder" else " without an app reminder"}.")
            onResult(null)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { onResult("Couldn’t save this journey. Please try again.") }
    }
    fun removeJourney(id: String) = viewModelScope.launch(storageErrors) { reminders.cancel(id); repository.store.deleteJourney(id) }
    fun stopJourneyReminders(id: String) = viewModelScope.launch(storageErrors) { repository.store.updateJourney(id) { it.copy(remindersStopped = true) }; reminders.cancel(id) }
    fun openScheduled(id: String?, screen: String = "upcoming") = viewModelScope.launch(storageErrors) {
        if (id == null) return@launch
        val item = repository.store.journey(id) ?: return@launch
        searchJob?.cancel()
        mutableState.update { it.copy(from = item.query.from, to = item.query.to, date = item.query.date, time = item.query.time,
            result = SearchResult(CachedSearch(item.query, listOf(item.journey), item.fetchedAt, item.fares, item.sourceId), true, restoredPlan = true),
            selectedJourney = item.journey, loading = false, requestedScreen = screen) }
    }
    fun navigationHandled() = mutableState.update { it.copy(requestedScreen = null) }
    fun clearPersonalData() = viewModelScope.launch(storageErrors) {
        searchJob?.cancel(); alertsJob?.cancel(); forgetLocation()
        try {
            reminders.cancelAll(); repository.store.clearPersonalData()
            mapViewport.reset()
            mutableState.update { it.copy(from = "", to = "", result = null, selectedJourney = null, selectedStation = null, selectedTransfer = null, alerts = null, alertsLoading = false, alertsError = null, alertsOffline = false, loading = false, error = null, message = "Local profile, favourites, journeys and cache deleted. A new anonymous profile was created.") }
        } catch (_: Exception) { showMessage("Couldn’t finish deleting local data. Please try again.") }
    }
}
