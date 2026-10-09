@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package za.co.cttrains.app.ui

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.BuildConfig
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.core.*
import androidx.navigation.compose.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import za.co.cttrains.app.scheduling.openStreetMapStationUrl
import za.co.cttrains.app.directions.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

private val FullDate = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val FetchTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH).withZone(CapeTownZone)

@Composable
fun TrainApp(state: TrainState, model: TrainViewModel) {
    val features: za.co.cttrains.app.FeatureViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val feature by features.state.collectAsStateWithLifecycle()
    val liveFix by remember { DirectionsRuntime.state.map { if(it.running && it.session?.paused!=true) it.fix else null }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue=null)
    var directionPositionShown by remember { mutableStateOf(false) }
    LaunchedEffect(liveFix) {
        if(liveFix!=null) {
            liveFix?.let { model.directionsPosition(za.co.cttrains.app.location.CurrentPosition(it.point.latitude,it.point.longitude,it.accuracy.toFloat(),it.at)) }
            directionPositionShown=true
        } else if(directionPositionShown) {
            model.directionsPosition(null)
            directionPositionShown=false
        }
    }
    val navigation = rememberNavController()
    var toolsOpen by remember { mutableStateOf(false) }
    val backStack by navigation.currentBackStackEntryAsState()
    val screen = backStack?.destination?.route ?: "map"
    val fullMap = screen == "map/fullscreen" || screen=="directions/map"
    val snackbar = remember { SnackbarHostState() }
    fun go(route: String) { navigation.navigate(route) { popUpTo(navigation.graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true } }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.dismissMessage() } }
    LaunchedEffect(feature.message) { feature.message?.let { snackbar.showSnackbar(it);features.dismissMessage() } }
    LaunchedEffect(feature.error) { feature.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.requestedScreen) { state.requestedScreen?.let { go(it); model.navigationHandled() } }
    Scaffold(
        topBar = { if (!fullMap) Column { CenterAlignedTopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Train, null, tint = MaterialTheme.colorScheme.primary)
                Text("CT Trains", fontWeight = FontWeight.Bold)
            }
        }, actions = {
            IconButton(onClick = { go("alerts") }) { Icon(Icons.Outlined.NotificationsActive, "Service alerts") }
            TextButton(onClick = { toolsOpen = true }) { Icon(Icons.Outlined.MoreHoriz, null);Text("Tools") }
        });DirectionsCompactBanner { go("directions") } } },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (!fullMap) NavigationBar {
            listOf(Triple("map", "Map", Icons.Outlined.Map), Triple("plan", "Plan", Icons.Outlined.Route),
                Triple("saved", "Saved", Icons.Outlined.StarBorder), Triple("upcoming", "Journeys", Icons.AutoMirrored.Outlined.EventNote),
                Triple("settings", "Settings", Icons.Outlined.Settings)).forEach { (route, title, icon) ->
                NavigationBarItem(selected = screen == route, onClick = { if(route=="map") features.allMapStops();go(route) }, icon = { Icon(icon, null) }, label = { Text(title) })
            }
        } },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 840.dp
            NavHost(navigation, startDestination = "map", modifier = Modifier.fillMaxSize(), enterTransition={ EnterTransition.None },exitTransition={ ExitTransition.None },popEnterTransition={ EnterTransition.None },popExitTransition={ ExitTransition.None }) {
                composable("map") { TransitMapScreen(state, model, onExpand = { navigation.navigate("map/fullscreen") { launchSingleTop = true } }, onPlan = { go("plan") },feature=feature,features=features,onBuses={ go("buses") }) }
                composable("map/fullscreen") { TransitMapScreen(state, model, fullScreen = true, onClose = { navigation.popBackStack() }, onPlan = { go("plan") },feature=feature,features=features,onBuses={ go("buses") }) }
                composable("plan") { PlanScreen(state, model, wide,feature,features) { journey -> model.selectJourney(journey);features.showTrainMap(); go("map") } }
                composable("saved") { SavedScreen(state, model,feature,features,{ go("places") }) { go("plan") } }
                composable("places") { SavedAddressesScreen(state,model,feature,features) { go("plan") } }
                composable("upcoming") { UpcomingScreen(state, model) }
                composable("settings") { AboutScreen(state, model,feature,features,{ go("buses") },{ go("trips") },{ go("community") },{ go("whatsapp") },{ go("directions") },{ go("app-updates") }) }
                composable("budget") { BudgetScreen(state, model) { go("plan") } }
                composable("alerts") { AlertsScreen(state, model) }
                composable("buses") { BusScreen(feature,features,{ go("trips") }) { service,route -> model.selectJourney(null);features.showBusMap(service,route);go("map") } }
                composable("trips") { SafetyTripsScreen(state,feature,features) }
                composable("whatsapp") { za.co.cttrains.app.whatsapp.WhatsAppScreen({ go("community") },{ go("trips") }) }
                composable("community") { CommunityScreen(state,feature,features,model) { go("settings") } }
                composable("directions") { DirectionsScreen({ go("plan") },{ navigation.navigate("directions/map") { launchSingleTop=true } }) }
                composable("directions/map") { DirectionsMapScreen(state,model,feature) { navigation.popBackStack() } }
                composable("app-updates") { za.co.cttrains.app.updates.UpdatesScreen() }
            }
        }
    }
    if (toolsOpen) TransitToolsSheet({ toolsOpen = false }, { toolsOpen = false; go("alerts") }, { toolsOpen = false; go("budget") },{ toolsOpen=false;go("buses") },{ toolsOpen=false;go("trips") },{ toolsOpen=false;go("community") },{ toolsOpen=false;go("whatsapp") },{ toolsOpen=false;go("directions") },{ toolsOpen=false;go("app-updates") })
    ImportedFiles(feature,features)
}

@Composable
private fun PlanScreen(state: TrainState, model: TrainViewModel, wide: Boolean, feature: za.co.cttrains.app.FeatureState, features: za.co.cttrains.app.FeatureViewModel, onMap: (Journey) -> Unit) {
    JourneyPlanner(state,model,feature,features,{ model.edit { it.copy(requestedScreen="settings") } })
}

@Composable
private fun TrainPlanScreen(state: TrainState, model: TrainViewModel, wide: Boolean, feature: za.co.cttrains.app.FeatureState, features: za.co.cttrains.app.FeatureViewModel, onMap: (Journey) -> Unit) {
    var stationPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var datePicker by rememberSaveable { mutableStateOf(false) }
    var timePicker by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val form: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your next journey", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Cape Town, one station at a time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PickerField("From", state.from.ifBlank { "Choose a station" }, Icons.Outlined.TripOrigin) { stationPicker = "From" }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalIconButton(onClick = { model.edit { it.copy(from = it.to, to = it.from) } }) { Icon(Icons.Outlined.SwapVert, "Swap stations") }
                    }
                    PickerField("To", state.to.ifBlank { "Choose a station" }, Icons.Outlined.Place) { stationPicker = "To" }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PickerField("Travel date", LocalDate.parse(state.date).format(FullDate), Icons.Outlined.CalendarMonth, Modifier.weight(1f)) { datePicker = true }
                        PickerField("Depart after", state.time, Icons.Outlined.Schedule, Modifier.weight(1f)) { timePicker = true }
                    }
                    DoorToDoorCard(state,model,feature,features)
                    SourceSelector(state, model)
                    Button(onClick = { model.search() }, enabled = !state.loading && state.catalog.stations.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        if (state.loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) }
                        else Icon(Icons.Outlined.Search, null)
                        Spacer(Modifier.width(10.dp))
                        Text(if (state.loading) "Finding trains…" else "Find trains", style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.from.isNotBlank() && state.to.isNotBlank() && state.from != state.to) {
                        TextButton(onClick = { model.toggleRoute() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Icon(if (state.isSaved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, null)
                            Spacer(Modifier.width(8.dp)); Text(if (state.isSaved) "Route saved" else "Save this route")
                        }
                    }
                }
            }
            TransitTools(model::openAlerts, model::openBudget)
            state.stationNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.error != null) {
                Notice(state.error, Icons.Outlined.ErrorOutline, error = true)
                TextButton(onClick = { uri.openUri("https://cttrains.co.za/train-form.php") }) { Text("Open CT Trains website") }
            }
            if (state.result == null && !state.loading && state.error == null) {
                Notice("Search scheduled journeys, see where to change trains, and save the routes you use most.", Icons.Outlined.Route)
                Text("Schedules from cttrains.co.za. Times may change; check service and platform details at the station.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(380.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { form() }
            Box(Modifier.weight(1f)) { Results(state, model, onMap) }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { form() }
            resultItems(state, model, onMap)
        }
    }
    stationPicker?.let { which ->
        StationSheet(which, state.catalog.stations, if (which == "From") state.to else state.from, onDismiss = { stationPicker = null }) { station ->
            model.edit { if (which == "From") it.copy(from = station) else it.copy(to = station) }
            stationPicker = null
        }
    }
    if (datePicker) TravelDatePicker(state.date, state.catalog.holidays, onDismiss = { datePicker = false }) { value -> model.edit { it.copy(date = value) }; datePicker = false }
    if (timePicker) DepartureTimePicker(state.time, onDismiss = { timePicker = false }) { value -> model.edit { it.copy(time = value) }; timePicker = false }
}

@Composable
private fun PickerField(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Results(state: TrainState, model: TrainViewModel, onMap: (Journey) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { resultItems(state, model, onMap) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resultItems(state: TrainState, model: TrainViewModel, onMap: (Journey) -> Unit) {
    if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    state.result?.let { result ->
        val entry = result.entry
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${entry.journeys.size} ${if (entry.journeys.size == 1) "journey" else "journeys"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { model.search() }) { Icon(Icons.Outlined.Refresh, "Refresh schedules") }
                }
                Text("${entry.query.from} → ${entry.query.to} · ${LocalDate.parse(entry.query.date).format(FullDate)}", style = MaterialTheme.typography.bodyMedium)
                if (result.restoredPlan) Notice("Saved journey · fetched ${FetchTime.format(Instant.ofEpochMilli(entry.fetchedAt))}. Refresh to check the current timetable.", Icons.AutoMirrored.Outlined.EventNote)
                else if (result.offline) Notice("Saved timetable · fetched ${FetchTime.format(Instant.ofEpochMilli(entry.fetchedAt))}. Couldn’t refresh; service may have changed.", Icons.Outlined.CloudOff)
                else Text("Fetched ${FetchTime.format(Instant.ofEpochMilli(entry.fetchedAt))} · Scheduled times", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Source: ${sourceLabel(entry.sourceId)}", style = MaterialTheme.typography.labelMedium)
                entry.fares?.let { Text(it, style = MaterialTheme.typography.bodySmall); Text("Fare information from ${sourceLabel(entry.sourceId)} · retrieved ${FetchTime.format(Instant.ofEpochMilli(entry.fetchedAt))}", style = MaterialTheme.typography.labelSmall) }
            }
        }
        if (entry.journeys.isEmpty()) item {
            EmptyState(Icons.Outlined.Train, "No journeys found", "Try an earlier departure time, another date, or different stations.")
        }
        val fastest = entry.journeys.minOfOrNull { it.durationMinutes }
        items(entry.journeys, key = { journey -> entry.query.toString() + journey.toString() }) { journey ->
            JourneyCard(entry.query, journey, fastest == journey.durationMinutes, model, onMap)
        }
        if (entry.journeys.isNotEmpty()) item { Text("Connection times are included in the total. Confirm your platform at the station; these are timetable results, not live train positions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun JourneyCard(query: SearchQuery, journey: Journey, fastest: Boolean, model: TrainViewModel, onMap: (Journey) -> Unit) {
    var expanded by rememberSaveable(query, journey) { mutableStateOf(false) }
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var schedule by remember { mutableStateOf(false) }
    if (schedule) JourneyScheduleSheet(query, journey, model) { schedule = false }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.animateContentSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${journey.departure} – ${journey.arrival}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (fastest) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) { Text("Fastest", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Text("${journey.durationMinutes} min · ${if (journey.transfers == 0) "Direct" else "${journey.transfers} ${if (journey.transfers == 1) "change" else "changes"}"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            journey.legs.forEachIndexed { index, leg ->
                if (index > 0) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.TransferWithinAStation, null, modifier = Modifier.size(18.dp))
                            Text("Change at ${leg.departure.name} · ${journey.connectionMinutes(index)} min", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) { Icon(Icons.Outlined.Train, null, Modifier.padding(8.dp).size(20.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(leg.line, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Train ${leg.train} · ${leg.departure.time}–${leg.arrival.time}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${leg.departure.name} → ${leg.arrival.name}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                AnimatedVisibility(expanded) {
                    Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        leg.stops.forEachIndexed { stopIndex, stop ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                Text(stop.time, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                                Text(stop.name + (stop.platform?.let { " · Platform $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, fontWeight = if (stopIndex == 0 || stopIndex == leg.stops.lastIndex) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { onMap(journey) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("View map") }
                Button(onClick = { schedule = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.EventAvailable, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Schedule") }
            }
            TextButton(onClick = { uri.openUri(openStreetMapStationUrl(query.from)) }) { Text("Open departure station in OpenStreetMap") }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
                    Text(if (expanded) "Hide stops" else "See all stops")
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                IconButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, journeyShareText(query, journey)) }
                    context.startActivity(Intent.createChooser(intent, "Share journey"))
                }) { Icon(Icons.Outlined.Share, "Share journey departing at ${journey.departure}") }
            }
        }
    }
}

@Composable
private fun StationSheet(label: String, stations: List<String>, other: String, onDismiss: () -> Unit, onSelected: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(search, stations) { stations.filter { it.contains(search.trim(), ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding()) {
            Text("${if (label == "From") "Departure" else "Arrival"} station", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search stations") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (filtered.isEmpty()) item { Text("No matching stations.", Modifier.padding(16.dp)) }
                items(filtered, key = { it }) { station ->
                    ListItem(headlineContent = { Text(station) }, leadingContent = { Icon(Icons.Outlined.Train, null) }, supportingContent = if (station == other) ({ Text("Already selected for the other station") }) else null, modifier = Modifier.clickable(enabled = station != other, role = Role.Button) { onSelected(station) })
                }
            }
        }
    }
}

@Composable
internal fun TravelDatePicker(date: String, holidays: List<String>, onDismiss: () -> Unit, trainOnly: Boolean=true, onSelected: (String) -> Unit) {
    val today = LocalDate.now(CapeTownZone)
    val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = today.year..TravelRules.lastAllowedDate(today).year, selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
            val selected = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
            return selected >= today && selected <= TravelRules.lastAllowedDate(today) && (!trainOnly || (selected.dayOfWeek.value != 7 && selected.toString() !in holidays))
        }
    })
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = {
        picker.selectedDateMillis?.let { onSelected(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
    }, enabled = picker.selectedDateMillis?.let { picker.selectableDates.isSelectableDate(it) }==true) { Text("Choose date") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }) { DatePicker(state = picker,showModeToggle=false) }
}

@Composable
internal fun DepartureTimePicker(time: String, onDismiss: () -> Unit, trainOnly: Boolean=true, onSelected: (String) -> Unit) {
    var hour by rememberSaveable { mutableIntStateOf(time.substringBefore(":").toInt().coerceIn(if(trainOnly) 3 else 0,23)) }
    var minute by rememberSaveable { mutableIntStateOf(time.substringAfter(":").toInt()/15*15) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Depart after") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("24-hour time · 15-minute intervals", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeDropdown("Hour", (if(trainOnly) 3..23 else 0..23).toList(), hour, Modifier.weight(1f)) { hour = it }
                TimeDropdown("Minute", listOf(0, 15, 30, 45), minute, Modifier.weight(1f)) { minute = it }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSelected(String.format(Locale.ROOT, "%02d:%02d", hour, minute)) }) { Text("Choose time") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TimeDropdown(label: String, values: List<Int>, selected: Int, modifier: Modifier, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }, modifier) {
        OutlinedTextField(value = selected.toString().padStart(2, '0'), onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(value.toString().padStart(2, '0')) }, onClick = { onSelected(value); expanded = false }) }
        }
    }
}

@Composable
private fun SavedScreen(state: TrainState, model: TrainViewModel,feature: za.co.cttrains.app.FeatureState,features: za.co.cttrains.app.FeatureViewModel,onPlaces: ()->Unit,onPlan: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().widthIn(max = 700.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Saved places",style=MaterialTheme.typography.headlineMedium);
            OutlinedButton(onClick=onPlaces,modifier=Modifier.fillMaxWidth()) { Text("Home, Work & School · manage addresses") }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { za.co.cttrains.core.SavedAddresses.labels.forEach { label->val spot=feature.extras.spots.firstOrNull { za.co.cttrains.core.SavedAddresses.role(it)==label };AssistChip(onClick={ if(spot==null) onPlaces() else { features.place(za.co.cttrains.core.WalkEndpoint("spot:${spot.id}",spot.name,spot.point),false,spot.preferredStation);spot.preferredStation?.let { station->model.edit { it.copy(to=station) } };onPlan() } },label={ Text(if(spot==null) "Set $label" else label) }) } }
        }
        item { Text("Favourite stations", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (state.preferences.favoriteStations.isEmpty()) item { Text("Star a station from the map or station search to keep it here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.preferences.favoriteStations, key = { "station-$it" }) { name ->
            ListItem(headlineContent = { TextButton(onClick = { model.edit { it.copy(from = name) }; onPlan() }) { Text(name) } }, leadingContent = { Icon(Icons.Outlined.Star, null) }, trailingContent = { IconButton(onClick = { model.toggleStation(name) }) { Icon(Icons.Outlined.StarBorder, "Remove favourite $name") } })
        }
        item { Text("Your regular routes", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item { Text("Pick a route, choose your time, and go.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.preferences.routes.isEmpty()) item { EmptyState(Icons.Outlined.BookmarkBorder, "A shortcut for your commute", "Save a route from the Plan tab and it will appear here.") }
        items(state.preferences.routes, key = { it.toString() }) { route ->
            Card(shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ListItem(headlineContent = { Text(route.from, fontWeight = FontWeight.Bold) }, supportingContent = { Text("to ${route.to}") }, leadingContent = { Icon(Icons.Outlined.Route, null) }, trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) }, modifier = Modifier.weight(1f).clickable(role = Role.Button) { model.useRoute(route); onPlan() })
                    IconButton(onClick = { model.toggleRoute(route) }) { Icon(Icons.Outlined.BookmarkRemove, "Remove saved route from ${route.from} to ${route.to}") }
                }
            }
        }
    }
}

@Composable
private fun AboutScreen(state: TrainState, model: TrainViewModel,feature: za.co.cttrains.app.FeatureState,features: za.co.cttrains.app.FeatureViewModel,onBuses: ()->Unit,onTrips: ()->Unit,onCommunity: ()->Unit,onWhatsApp: ()->Unit,onDirections: ()->Unit,onUpdates: ()->Unit) {
    val uri = LocalUriHandler.current
    var confirmClear by remember { mutableStateOf(false) }
    var confirmProfile by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).widthIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Your app, your data", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Notice("An independent, community app for Cape Town rail commuters. Timetables come from CT Trains. This app is not affiliated with CT Trains, PRASA or Metrorail.", Icons.Outlined.Train)
        FeatureStatus(feature)
        FilledTonalButton(onClick=onDirections,modifier=Modifier.fillMaxWidth()) { Text("Journey directions & Live Updates") }
        OutlinedButton(onClick=onUpdates,modifier=Modifier.fillMaxWidth()) { Text("App updates · GitHub") }
        BetaSettings(feature,features,onBuses,onTrips,onCommunity,onWhatsApp)
        ProfileSettings(feature,features)
        HostedSettings(feature,features)
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("System", "Light", "Dark").forEach { theme -> FilterChip(selected = state.preferences.theme == theme, onClick = { model.setTheme(theme) }, label = { Text(theme) }) }
                }
                if (Build.VERSION.SDK_INT >= 31) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Use device colours"); Text("Material You wallpaper palette", style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = state.preferences.dynamicColor, onCheckedChange = model::setDynamicColor)
                }
            }
        }
        Card(shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Timetables & privacy", style = MaterialTheme.typography.titleLarge)
                Text("Your last 12 searches are stored on this device for up to 14 days. If a refresh fails, the same route, date and departure time can use its saved result. Saved routes stay until you remove them.")
                Text("An anonymous UUID identifies this installation locally. Personal records and optional account tokens are encrypted with Android Keystore in Room. Public station positions and scheduling timestamps remain readable inside the private database. No analytics or device identifiers. Google profile and hosted Matrix accounts are optional and independent.", style = MaterialTheme.typography.bodyMedium)
                Text("Searches send the route, date, time and IP to their timetable source. Online maps reveal the viewed area and IP to OpenFreeMap or City of Cape Town imagery hosting. Explicit place searches go to OpenStreetMap Nominatim or Photon. Offline map mode disables map tile requests; timetable and hosted communication checks have their own controls.", style = MaterialTheme.typography.bodyMedium)
                Text("Locate me samples Android location while the app is open. Active directions run locally with a visible notification for up to four hours; no GPS history is saved or uploaded. Separate, optional live sharing runs for at most two hours and sends encrypted updates to your private Matrix room. Public reports go to the selected community room. Friend positions disappear from the map after five minutes without a fresh update.", style = MaterialTheme.typography.bodyMedium)
                Text("Calendar handoff opens your calendar app for review. The app cannot read your calendar. Optional Health Connect step totals are read only after separate permission; no health data is uploaded. Trip files reveal the itinerary you review before sharing. Encrypted profile backups are stored only at the file location you choose.", style = MaterialTheme.typography.bodyMedium)
                Text("Downloaded maps and map-area bounds are public cache data and are not encrypted. Manage and delete them from the Map download button.", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("online" to "Online", "offline" to "Offline", "stations" to "Stations").forEach { (mode, title) -> FilterChip(state.preferences.mapMode == mode, { model.setMapMode(mode) }, label = { Text(title) }) } }
                OutlinedButton(onClick = model::forgetLocation) { Text("Forget sampled location") }
                OutlinedButton(onClick = { confirmProfile = true }) { Icon(Icons.Outlined.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text("Delete all local personal data") }
                OutlinedButton(onClick = { confirmClear = true }) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Clear cached timetables") }
            }
        }
        SourceSelector(state, model)
        Text("Check before you travel", style = MaterialTheme.typography.titleLarge)
        Text("Timetables are not live tracking. Open Updates & delays for published service and platform notices; reports may be incomplete or late. The finder supports today through the end of next month and does not list Sunday or public-holiday services.")
        OutlinedButton(onClick = model::openAlerts) { Text("Updates & delays") }
        OutlinedButton(onClick = { uri.openUri("https://cttrains.co.za/train-form.php") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Visit CT Trains") }
        TextButton(onClick = { uri.openUri("https://github.com/Xhanti-mbasa") }) { Text("Built by Xhanti-mbasa · GitHub",style=MaterialTheme.typography.labelMedium) }
        Text("CT Trains for Android · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear saved timetables?") }, text = { Text("You’ll need an internet connection for your next search. Your favourite routes will stay saved.") }, confirmButton = { TextButton(onClick = { model.clearCache(); confirmClear = false }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    if (confirmProfile) AlertDialog(onDismissRequest = { confirmProfile = false }, title = { Text("Delete your local profile?") }, text = { Text("Favourites, corrections, journeys, reminders, personal spots/photos, trip files, account sessions and saved timetables will be removed. Public offline maps are managed separately. External backup files and calendar events remain at their chosen locations.") }, confirmButton = { TextButton(onClick = { features.reset();model.clearPersonalData(); confirmProfile = false }) { Text("Delete local data") } }, dismissButton = { TextButton(onClick = { confirmProfile = false }) { Text("Cancel") } })
}

@Composable
private fun Notice(text: String, icon: ImageVector, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SourceSelector(state: TrainState, model: TrainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Timetable source", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.repository.sources.forEach { source -> FilterChip(selected = state.preferences.sourceId == source.id, onClick = { model.setSource(source.id) }, label = { Text(source.label) }) }
        }
        Text(if (model.repository.sources.size > 1) "The API and direct source both use CT Trains. Results stay separate; switching clears the current result." else "Community timetable from CT Trains. Check source and fetch date before travelling.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
