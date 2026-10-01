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
import kotlinx.coroutines.launch
import za.co.cttrains.app.data.Preferences
import za.co.cttrains.app.data.SearchResult
import za.co.cttrains.app.data.TrainRepository
import za.co.cttrains.core.*

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
) {
    val query: SearchQuery get() = SearchQuery(from, to, date, time)
    val isSaved: Boolean get() = SavedRoute(from, to) in preferences.routes
}

class TrainViewModel(application: Application) : AndroidViewModel(application) {
    val repository = TrainRepository(application)
    private val mutableState = MutableStateFlow(TrainState())
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            var restored = false
            repository.preferences.collect { preferences ->
                mutableState.update { current ->
                    val previous = preferences.lastQuery
                    if (!restored && previous != null) {
                        restored = true
                        // Reuse stations and time; an old travel date must not become today's result.
                        current.copy(preferences = preferences, from = previous.from, to = previous.to, time = previous.time)
                    } else { restored = true; current.copy(preferences = preferences) }
                }
            }
        }
        viewModelScope.launch {
            val initial = repository.storedCatalog() ?: repository.bundledCatalog()
            mutableState.update { it.copy(catalog = initial) }
            try {
                val current = repository.refreshCatalog()
                mutableState.update { it.copy(catalog = current, stationNotice = null) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                mutableState.update { it.copy(stationNotice = "Using a saved station list. Connect to refresh it.") }
            }
        }
    }

    fun edit(transform: (TrainState) -> TrainState) {
        searchJob?.cancel()
        mutableState.update { transform(it).copy(result = null, error = null, loading = false) }
    }

    fun useRoute(route: SavedRoute) = edit { it.copy(from = route.from, to = route.to, date = LocalDate.now(CapeTownZone).toString()) }

    fun search() {
        val current = state.value
        val error = TravelRules.validationError(current.query, current.catalog, LocalDate.now(CapeTownZone))
        if (error != null) { mutableState.update { it.copy(error = error) }; return }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null, result = null) }
            try {
                val result = repository.search(current.query)
                repository.updatePreferences { it.copy(lastQuery = current.query) }
                mutableState.update { it.copy(loading = false, result = result) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val message = if (error is ScheduleFormatException) error.message else "Couldn’t reach CT Trains. Check your connection and try again."
                mutableState.update { it.copy(loading = false, error = message) }
            }
        }
    }

    fun toggleRoute(route: SavedRoute = SavedRoute(state.value.from, state.value.to)) = viewModelScope.launch {
        if (route.from.isBlank() || route.to.isBlank() || route.from == route.to) return@launch
        repository.updatePreferences { prefs ->
            if (route in prefs.routes) prefs.copy(routes = prefs.routes - route)
            else prefs.copy(routes = (prefs.routes + route).takeLast(20))
        }
    }

    fun setTheme(theme: String) = viewModelScope.launch { repository.updatePreferences { it.copy(theme = theme) } }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { repository.updatePreferences { it.copy(dynamicColor = enabled) } }
    fun clearCache() = viewModelScope.launch {
        searchJob?.cancel()
        repository.clearCache()
        mutableState.update { it.copy(result = null, loading = false, message = "Cached timetables cleared.") }
    }
    fun dismissMessage() = mutableState.update { it.copy(message = null) }
}
