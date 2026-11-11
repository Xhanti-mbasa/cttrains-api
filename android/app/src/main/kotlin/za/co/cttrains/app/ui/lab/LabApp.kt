@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package za.co.cttrains.app.ui.lab

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.app.SmartCardsModel
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.app.directions.DirectionsCompactBanner
import za.co.cttrains.app.directions.DirectionsMapScreen
import za.co.cttrains.app.directions.DirectionsRuntime
import za.co.cttrains.app.directions.DirectionsScreen
import za.co.cttrains.app.ui.*
import za.co.cttrains.core.GeoPoint
import za.co.cttrains.core.WalkEndpoint

/** The three top-level places in the Lab edition. Everything else opens from one of them. */
private enum class LabPlace(val route: String, val title: String, val icon: ImageVector) {
    NOW("now", "Now", Icons.Outlined.Schedule),
    PLAN("plan", "Plan", Icons.Outlined.Route),
    MINE("mine", "Mine", Icons.Outlined.BookmarkBorder),
}

/** Routes requested by the shared view models, mapped to the Lab place they belong to. */
private fun placeOf(route: String): LabPlace = when {
    route == "now" || route.startsWith("map") || route == "alerts" || route == "ride-departures" -> LabPlace.NOW
    route == "plan" || route == "budget" -> LabPlace.PLAN
    else -> LabPlace.MINE
}

private val Titles = mapOf(
    "alerts" to "Updates & delays", "budget" to "Fares & budget", "buses" to "Buses · beta", "trips" to "Rides & safety trips",
    "places" to "Your places", "upcoming" to "Saved trips & reminders", "settings" to "Privacy & data", "developer" to "Developer options",
    "whatsapp" to "WhatsApp updates", "community" to "Community", "directions" to "Directions", "app-updates" to "App updates",
    "ride-departures" to "Your ride", "widgets" to "Widgets & cards",
)

/**
 * Root of the CT Trains Lab edition, chosen by [za.co.cttrains.app.BuildConfig.EXPERIMENTAL_EDITION].
 * It keeps every shared side effect of the regular root (smart suggestions, directions position,
 * update checks, developer session refresh, imports and ride prompts) and only changes structure.
 */
@Composable
fun LabApp(state: TrainState, model: TrainViewModel) {
    val developer: za.co.cttrains.app.developer.DeveloperModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val dev by developer.state.collectAsStateWithLifecycle()
    val updates: za.co.cttrains.app.updates.UpdatesModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val features: FeatureViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val feature by features.state.collectAsStateWithLifecycle()
    val smart: SmartCardsModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val smartState by smart.state.collectAsStateWithLifecycle()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { updates.checkAtLaunch(); onPauseOrDispose {} }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { developer.refresh(); kotlinx.coroutines.delay(60_000) }
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(60_000) }
        }
    }
    LaunchedEffect(now, state.currentPosition, state.stations, state.catalog, state.scheduled,
        state.preferences, feature.ready, feature.extras.spots, feature.extras.beta.smartCards) {
        if (feature.ready && state.preferences.profileId.isNotBlank()) smart.update(state, feature.extras, now)
    }
    LaunchedEffect(smartState.selected?.id, feature.ready) {
        smartState.selected?.takeIf { feature.ready }?.let { card ->
            val point = state.currentPosition?.let { GeoPoint(it.latitude, it.longitude) }
            features.place(point?.let { WalkEndpoint("current", "Current location", it) }, true, card.query.from)
            val place = feature.extras.spots.firstOrNull { it.name == card.destinationLabel }
            features.place(place?.let { WalkEndpoint("spot:${it.id}", it.name, it.point) }, false, card.query.to)
            model.presentPlannedJourney(card.query, card.journey, card.fetchedAt, card.cached, null, card.sourceId, false)
        }
    }
    val liveFix by remember { DirectionsRuntime.state.map { if (it.running && it.session?.paused != true) it.fix else null }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    var directionPositionShown by remember { mutableStateOf(false) }
    LaunchedEffect(liveFix) {
        val fix = liveFix
        if (fix != null) {
            model.directionsPosition(za.co.cttrains.app.location.CurrentPosition(fix.point.latitude, fix.point.longitude, fix.accuracy.toFloat(), fix.at))
            directionPositionShown = true
        } else if (directionPositionShown) {
            model.directionsPosition(null)
            directionPositionShown = false
        }
    }

    val navigation = rememberNavController()
    val backStack by navigation.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "now"
    val fullMap = route == "map/fullscreen" || route == "directions/map"
    val snackbar = remember { SnackbarHostState() }
    fun go(target: String) {
        navigation.navigate(target) { popUpTo(navigation.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    fun push(target: String) { navigation.navigate(target) { launchSingleTop = true } }
    /** Map and alerts belong to Now: open them on Now's stack so each tab keeps its own history. */
    fun openInPlace(target: String) { if (placeOf(target) == LabPlace.NOW && route != "now") { go("now"); push(target) } else push(target) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.dismissMessage() } }
    LaunchedEffect(feature.message) { feature.message?.let { snackbar.showSnackbar(it); features.dismissMessage() } }
    LaunchedEffect(feature.error) { feature.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.requestedScreen) {
        state.requestedScreen?.let { requested ->
            if (requested in setOf("now", "plan", "mine")) go(requested) else push(requested)
            model.navigationHandled()
        }
    }
    val c = LocalLab.current
    val topLevel = route in setOf("now", "plan", "mine")

    Scaffold(
        containerColor = c.ground,
        topBar = {
            if (!fullMap) Column {
                if (!topLevel) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (!navigation.popBackStack()) go("now") }, Modifier.testTag("lab-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = c.ink)
                    }
                    Text(Titles[route] ?: "", style = LabType.route, color = c.ink)
                } else Spacer(Modifier.statusBarsPadding())
                DirectionsCompactBanner { push("directions") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!fullMap) NavigationBar(containerColor = c.surface) {
                val current = placeOf(route)
                LabPlace.entries.forEach { place ->
                    NavigationBarItem(
                        selected = current == place,
                        onClick = { if (place == LabPlace.NOW) features.allMapStops(); go(place.route) },
                        icon = { Icon(place.icon, null) },
                        label = { Text(place.title, fontWeight = if (current == place) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = c.ink, selectedTextColor = c.ink, indicatorColor = c.chip,
                            unselectedIconColor = c.muted, unselectedTextColor = c.muted),
                        modifier = Modifier.testTag("lab-tab-${place.route}"),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(navigation, startDestination = "now", modifier = Modifier.fillMaxSize(),
                enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
                composable("now") {
                    LabNowScreen(state, model, feature, features, smartState, smart, now,
                        onMap = { features.allMapStops(); push("map") }, onPlan = { go("plan") }, onAlerts = { push("alerts") },
                        onPlaces = { push("places") }, onSettings = { push("settings") })
                }
                composable("map") {
                    TransitMapScreen(state, model, onExpand = { push("map/fullscreen") }, onPlan = { go("plan") },
                        feature = feature, features = features, onBuses = { push("buses") }, smart = smart)
                }
                composable("map/fullscreen") {
                    TransitMapScreen(state, model, fullScreen = true, onClose = { navigation.popBackStack() }, onPlan = { go("plan") },
                        feature = feature, features = features, onBuses = { push("buses") })
                }
                composable("plan") { JourneyPlanner(state, model, feature, features, { push("settings") }, smart) }
                composable("mine") {
                    LabMineScreen(state, model, feature, now, onOpen = ::openInPlace,
                        onPlanRoute = { r -> model.useRoute(r); go("plan") }, onPlanFrom = { name -> model.edit { it.copy(from = name) }; go("plan") },
                        onDeveloper = { push("developer") })
                }
                composable("settings") { LabSettingsScreen(state, model, feature, features, onOpen = ::openInPlace, onDeveloperLock = developer::lock) }
                composable("widgets") { LabWidgetsScreen(feature, features) }
                composable("places") { SavedAddressesScreen(state, model, feature, features) { go("plan") } }
                composable("upcoming") { UpcomingScreen(state, model) }
                composable("developer") { za.co.cttrains.app.developer.DeveloperScreen(developer, feature, features, model) { push("settings") } }
                composable("budget") { BudgetScreen(state, model) { go("plan") } }
                composable("alerts") { AlertsScreen(state, model) }
                composable("buses") {
                    BusScreen(feature, features, { push("trips") }) { service, busRoute ->
                        model.selectJourney(null); features.showBusMap(service, busRoute); push("map")
                    }
                }
                composable("trips") { SafetyTripsScreen(state, feature, features, model) }
                composable("ride-departures") {
                    LabDeparturesScreen(state, model, now, onMap = { journey ->
                        model.selectJourney(journey); features.showTrainMap(); push("map")
                    }, onAlerts = { push("alerts") })
                }
                composable("whatsapp") { za.co.cttrains.app.whatsapp.WhatsAppScreen({ push("community") }, { push("trips") }) }
                composable("community") { CommunityScreen(state, feature, features, model) { push("settings") } }
                composable("directions") { DirectionsScreen({ go("plan") }, { push("directions/map") }) }
                composable("directions/map") { DirectionsMapScreen(state, model, feature) { navigation.popBackStack() } }
                composable("app-updates") { za.co.cttrains.app.updates.UpdatesScreen(updates) }
            }
        }
    }
    za.co.cttrains.app.updates.LaunchUpdateNotices(updates, dev.policy) { push("app-updates") }
    ImportedFiles(feature, features)
    PendingRidePrompt(state, model)
    smartState.selected?.takeIf { feature.ready }?.let { card ->
        JourneyScheduleSheet(card.query, card.journey, model, initialAccess = card.accessMinutes,
            initialLeaveFrom = "Near ${card.query.from}") { smart.dismiss() }
    }
}
