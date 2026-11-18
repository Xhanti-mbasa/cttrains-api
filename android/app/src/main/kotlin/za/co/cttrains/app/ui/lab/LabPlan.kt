@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package za.co.cttrains.app.ui.lab

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import za.co.cttrains.app.FeatureState
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.app.GoldenTimetableCandidate
import za.co.cttrains.app.JourneyPlannerModel
import za.co.cttrains.app.PlannerOption
import za.co.cttrains.app.PlannerRequest
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.app.ui.ModeOrder
import za.co.cttrains.app.ui.TravelDatePicker
import za.co.cttrains.app.ui.money
import za.co.cttrains.app.ui.JourneyStationPicker
import za.co.cttrains.app.ui.SaveJourneyPlace
import za.co.cttrains.core.*

/** Lab names for the three services, so chips read as modes rather than operator brands. */
internal fun TransportService.labMode() = when (this) {
    TransportService.PRASA -> LabMode.TRAIN
    TransportService.MYCITI -> LabMode.MYCITI
    TransportService.GOLDEN_ARROW -> LabMode.GOLDEN_ARROW
}

/** Minutes walked from the trip's start to its first stop, when the walk was calculated. */
internal fun PlannerOption.accessMinutes(from: WalkEndpoint?): Int? =
    if (service == TransportService.PRASA && from?.id?.startsWith("station:") == true && stationKey(from.label) == stationKey(board)) 0
    else walks.firstOrNull { it.from == from }?.minutes

/** Leave time: departure minus the known walk minus the same 5-minute boarding buffer saved plans use. */
internal fun PlannerOption.leaveAt(from: WalkEndpoint?): Long? = accessMinutes(from)?.let { estimate.departureAt - (it + 5) * 60_000L }

/**
 * Plan: one search for every enabled service, using the same planner engine as the regular edition
 * (rail, MyCiTi and Golden Arrow sections, walking estimates, fares). Only the presentation is new.
 */
@Composable
fun LabPlanScreen(
    state: TrainState, model: TrainViewModel, feature: FeatureState, features: FeatureViewModel,
    planner: JourneyPlannerModel, onOpenTrip: (PlannerOption) -> Unit,
) {
    val c = LocalLab.current
    val result by planner.state.collectAsState()
    var picker by remember { mutableStateOf<Boolean?>(null) }
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }
    var stationPicker by remember { mutableStateOf<Boolean?>(null) }
    var savePlace by remember { mutableStateOf<Boolean?>(null) }
    var timeMode by rememberSaveable { mutableStateOf("auto") }
    val allDay = timeMode == "all"
    var visibleCount by remember(result.request) { mutableIntStateOf(24) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var consent by remember { mutableStateOf(false) }
    var enable by remember { mutableStateOf<TransportService?>(null) }
    var walking by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf(TransportService.PRASA.name)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { now = System.currentTimeMillis(); delay(60_000) }
        }
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { model.refreshPlanDate(); onPauseOrDispose {} }
    LaunchedEffect(state.currentPosition, state.stations, feature.access, feature.boardingStation, feature.extras.beta.autoNearby) {
        if (feature.extras.beta.autoNearby && feature.access == null && feature.boardingStation == null) model.defaultNearbyOrigin()
    }

    val from = feature.access ?: state.stations.firstOrNull { it.mapped && stationKey(it.name) == stationKey(state.from) }?.let(WalkingRoutes::endpoint)
    val to = feature.destination ?: state.stations.firstOrNull { it.mapped && stationKey(it.name) == stationKey(state.to) }?.let(WalkingRoutes::endpoint)
    val services = ModeOrder.filter { it.name in selected }.toSet()
    val board = if (feature.access == null) state.from else feature.boardingStation
    val arrive = if (feature.destination == null) state.to else feature.arrivalStation
    val request = result.request
    // Results stay when you come back from a trip; they clear only when the question changes.
    val stale = request != null && (request.from != from || request.to != to || request.date != state.date || (timeMode == "depart" && request.time != state.time) || request.allDay != allDay || request.nextAvailable != (timeMode == "auto") ||
        request.services != services || request.walking != walking || request.boardingStation != board || request.arrivalStation != arrive)
    LaunchedEffect(stale) { if (stale) planner.clear() }
    val localNow = Instant.ofEpochMilli(now).atZone(CapeTownZone)
    val today = localNow.toLocalDate().toString()
    val currentTime = if (state.date == today) localNow.toLocalTime().withSecond(0).withNano(0) else LocalTime.MIDNIGHT
    val canSearch = from != null && to != null && from != to && services.isNotEmpty() && !result.loading
    fun search() {
        if (from != null && to != null) {
            model.refreshPlanDate()
            val current = model.state.value
            val clock = java.time.LocalDateTime.now(CapeTownZone)
            val time = when {
                allDay -> "00:00"
                timeMode == "depart" -> current.time
                current.date == clock.toLocalDate().toString() -> clock.toLocalTime().withSecond(0).withNano(0).toString()
                else -> "00:00"
            }
            planner.find(PlannerRequest(from, to, current.date, time, services, walking, 5000, board, arrive, allDay, nextAvailable = timeMode == "auto"), current)
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(c.ground).testTag("lab-plan"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Plan", style = LabType.title, color = c.ink, modifier = Modifier.semantics { heading() }) }
        item {
            Column(Modifier.fillMaxWidth().background(c.surface, LabCardShape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PlaceField("From", from?.label, "Where are you starting?", Modifier.testTag("lab-plan-from")) { picker = true }
                        PlaceField("To", to?.label, "Where are you going?", Modifier.testTag("lab-plan-to")) { picker = false }
                    }
                    FilledTonalIconButton(onClick = {
                        features.swapPlaces(); model.edit { it.copy(from = it.to, to = it.from) }
                    }, Modifier.size(LabTouch), enabled = from != null || to != null,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = c.chip, contentColor = c.ink)) {
                        Icon(Icons.Outlined.SwapVert, "Swap start and destination")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(true, { datePicker = true }, label = { Text(if (state.date == today) "Today" else LabTiming.day(state.date)) },
                        leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = LabTouch).testTag("lab-plan-date"))
                    val timeLabel = when (timeMode) {
                        "all" -> "All-day departures"
                        "depart" -> "Depart after ${state.time}"
                        else -> "Depart after $currentTime"
                    }
                    FilterChip(true, { timePicker = true }, label = { Text(timeLabel) },
                        leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = LabTouch).testTag("lab-time-depart"))
                }
                if (TransportService.PRASA in services && feature.destination == null) {
                    state.stations.firstOrNull { it.mapped && stationKey(it.name) == arrive?.let(::stationKey) }?.let { station ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StationChoices.nearbyPlaces(station, feature.extras.spots).take(3).forEach { spot ->
                                AssistChip(onClick = { features.place(WalkEndpoint("spot:${spot.id}", spot.name, spot.point), false, station.name) },
                                    label = { Text("Continue to ${spot.name}") }, modifier = Modifier.testTag("final-spot:${spot.id}"))
                            }
                        }
                    }
                }
                if (feature.access != null && !feature.access.id.startsWith("spot:"))
                    TextButton(onClick = { savePlace = true }) { Text("Save start as a place") }
                if (feature.destination != null && !feature.destination.id.startsWith("spot:"))
                    TextButton(onClick = { savePlace = false }) { Text("Save destination as a place") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeOrder.forEach { service ->
                        val on = feature.extras.beta.enabled(service) || service == TransportService.PRASA
                        FilterChip(selected = service.name in selected && on, onClick = {
                            when {
                                !on -> enable = service
                                service.name in selected && selected.size > 1 -> selected = selected - service.name
                                service.name !in selected -> selected = selected + service.name
                            }
                        }, label = { Text(if (on) service.labMode().label else "${service.labMode().label} · off") },
                            modifier = Modifier.heightIn(min = LabTouch).testTag("lab-mode-${service.name}"))
                    }
                    FilterChip(walking, { if (walking) walking = false else consent = true }, label = { Text(if (walking) "Walking times on" else "Walking times") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = LabTouch))
                }
                LabPrimaryButton(if (result.loading) "Finding trips…" else "Find trips", { search() }, Modifier.fillMaxWidth().testTag("lab-plan-search"), enabled = canSearch)
            }
        }
        if (allDay) item {
            Text("Penelope · All-day departures", style = LabType.bodyStrong, color = c.ink)
            Text("Browse published departures for the selected date, including earlier services. Bus searches cover direct trips, within the available timetable data.", style = LabType.small, color = c.muted)
        }
        if (request == null && !result.loading) {
            if (state.preferences.routes.isNotEmpty()) {
                item { LabSectionTitle("Your routes") }
                items(state.preferences.routes.take(4), key = { "r-${it.from}-${it.to}" }) { route ->
                    ActionRow(Icons.Outlined.Route, "${route.from} → ${route.to}", "Tap to fill in and search") {
                        features.place(null, true); features.place(null, false); model.useRoute(route); model.refreshPlanDate()
                    }
                }
            }
            item {
                Text("Published timetables, today to the end of next month. Sunday and public-holiday train services are not listed. " +
                    "Every time here is a schedule, not a live train position.", style = LabType.small, color = c.muted)
            }
        }
        if (result.loading) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.ink)
                Text(result.progress.ifBlank { "Checking timetables…" }, style = LabType.body, color = c.muted)
            }
        }
        result.error?.let { error -> item { LabStateCard("Couldn’t plan", "This trip couldn’t be checked", error, "Try again" to { search() }) } }
        val all = result.sections.values.flatMap { it.options }
        val fastest = all.filter { !allDay }.filter { it.estimate.totalMinutes != null }.minByOrNull { it.estimate.totalMinutes!! }?.id
        if (allDay) {
            val chronological = all.sortedWith(compareBy<PlannerOption> { it.estimate.departureAt }.thenBy { it.id })
            items(chronological.take(visibleCount), key = { it.id }) { option ->
                OptionCard(option, request?.from, false, past = option.estimate.departureAt <= now, allDay = true) { onOpenTrip(option) }
            }
            if (chronological.size > visibleCount) item {
                LabSecondaryButton("Show more departures (${chronological.size - visibleCount} remaining)", { visibleCount += 24 }, Modifier.fillMaxWidth().testTag("lab-more-departures"))
            }
        }
        ModeOrder.forEach { service ->
            val section = result.sections[service] ?: return@forEach
            if (!allDay) {
                if (result.sections.size > 1) item(key = "h-${service.name}") { LabSectionTitle(service.labMode().label) }
                items(section.options, key = { it.id }) { option ->
                    OptionCard(option, request?.from, option.id == fastest, past = option.estimate.departureAt <= now) { onOpenTrip(option) }
                }
            }
            if (section.options.isEmpty() && section.candidates.isEmpty()) item(key = "e-${service.name}") {
                LabStateCard(service.labMode().label, "No ${service.labMode().label} trip found",
                    section.issue ?: if(allDay) "No journeys were returned for this date. Missing source coverage does not prove there is no service." else "Nothing you can catch is listed after ${request?.time ?: state.time}.",
                    "Choose another time" to { timePicker = true })
            } else section.issue?.let { note -> item(key = "n-${service.name}") { Text(note, style = LabType.small, color = c.muted) } }
            items(section.candidates, key = { "golden:${it.timetable.route.id}" }) { candidate ->
                GoldenDepartureCard(candidate, allDay, request?.date == today, currentTime)
            }
        }
        if (result.sections.isNotEmpty()) item {
            val count = all.size + result.sections.values.sumOf { section -> section.candidates.sumOf { it.departures.size } }
            Text(if(allDay) "Showing ${count} published options returned by this search. Timetable coverage and service calendars may be incomplete." else "Fastest counts only complete, timed trips. Times are published schedules; confirm platforms at the station.",
                style = LabType.small, color = c.muted)
        }
    }

    picker?.let { start ->
        val preferred = (if (start) feature.access else feature.destination)
            ?.takeIf { TransportService.PRASA in services }
        LabPlacePicker(state, model, feature, features, start, onDismiss = { picker = null },
            station = preferred?.let { (if (start) board else arrive) ?: "Automatic" },
            onStation = { picker = null; stationPicker = start }) { endpoint ->
            if (start) model.keepOrigin()
            if (endpoint.id.startsWith("station:")) {
                features.place(null, start)
                model.edit { if (start) it.copy(from = endpoint.label) else it.copy(to = endpoint.label) }
            } else {
                features.place(endpoint, start)
                val chosen = if(start) features.state.value.boardingStation else features.state.value.arrivalStation
                val suggestion = chosen ?: StationChoices.ranked(endpoint.point, state.stations, state.catalog.stations).firstOrNull()?.name
                suggestion?.let { name -> model.edit { if(start) it.copy(from = name) else it.copy(to = name) } }
                if (TransportService.PRASA in services && endpoint.id.startsWith("spot:") && chosen == null) stationPicker = start
            }
            picker = null
        }
    }
    stationPicker?.let { start ->
        (if(start) from else to)?.let { endpoint ->
            JourneyStationPicker(endpoint, state, feature, start, if(start) board else arrive, { stationPicker = null }) { name, remember ->
                features.chooseStation(name, start, remember)
                val suggestion = name ?: StationChoices.ranked(endpoint.point, state.stations, state.catalog.stations).firstOrNull()?.name
                suggestion?.let { value -> model.edit { if(start) it.copy(from = value) else it.copy(to = value) } }
                stationPicker = null
            }
        }
    }
    savePlace?.let { start ->
        (if(start) feature.access else feature.destination)?.let { endpoint ->
            SaveJourneyPlace(endpoint, feature.busy, { savePlace = null }) { name, tag ->
                features.saveJourneyPlace(endpoint, start, name, tag); savePlace = null
            }
        }
    }
    if (datePicker) TravelDatePicker(state.date, state.catalog.holidays, { datePicker = false }, trainOnly = services == setOf(TransportService.PRASA)) {
        model.chooseDate(it); datePicker = false
    }
    if (timePicker) PlanTimePicker(
        time = if (timeMode == "depart") LocalTime.parse(state.time) else currentTime,
        currentTime = currentTime,
        mode = timeMode,
        onDismiss = { timePicker = false },
        onAuto = { timeMode = "auto"; timePicker = false },
        onAllDay = { timeMode = "all"; timePicker = false },
        onSelected = { model.chooseTime(it); timeMode = "depart"; timePicker = false },
    )
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("Calculate walking times?") },
        text = { Text("FOSSGIS / OSRM receives your start, destination and nearby stop coordinates to check footpaths. Results are cached on this phone. Transit times stay published schedules.") },
        confirmButton = { TextButton(onClick = { walking = true; consent = false }) { Text("Calculate") } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Not now") } })
    enable?.let { service ->
        AlertDialog(onDismissRequest = { enable = null }, title = { Text("Turn on ${service.labMode().label}?") },
            text = { Text("${service.labMode().label} is a beta source of published bus timetables and stops. Bus times are schedules; confirm current operation and boarding points.") },
            confirmButton = { TextButton(onClick = {
                features.beta { if (service == TransportService.MYCITI) it.copy(myCiti = true) else it.copy(goldenArrow = true) }
                selected = selected + service.name; enable = null
            }) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { enable = null }) { Text("Cancel") } })
    }
}

@Composable
private fun PlaceField(label: String, value: String?, hint: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalLab.current
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), shape = LabControlShape, color = c.ground) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(label, style = LabType.small, color = c.muted)
            Text(value ?: hint, style = LabType.bodyStrong, color = if (value == null) c.muted else c.ink)
        }
    }
}

@Composable
private fun GoldenDepartureCard(candidate: GoldenTimetableCandidate, allDay: Boolean, isToday: Boolean, currentTime: LocalTime) {
    val c = LocalLab.current
    val uri = LocalUriHandler.current
    var visible by remember(candidate) { mutableIntStateOf(if (allDay) 8 else 4) }
    LabCard(Modifier.testTag("lab-golden-timetable")) {
        ModeBadge(LabMode.GOLDEN_ARROW)
        Text(candidate.timetable.route.title, style = LabType.bodyStrong, color = c.ink)
        Text(if (candidate.cached) "Saved timetable · published times" else "Published timetable times", style = LabType.small, color = c.muted)
        for (option in candidate.departures.take(visible)) {
            val past = allDay && isToday && LocalTime.parse(option.departure.time) <= currentTime
            Text("${option.departure.time} ${option.departure.name} → ${option.arrival.time} ${option.arrival.name}" +
                if (past) " · Departed" else "", style = LabType.body, color = if (past) c.muted else c.ink)
        }
        if (visible < candidate.departures.size)
            LabSecondaryButton("Show more times", { visible += 8 }, Modifier.fillMaxWidth())
        if (candidate.departures.isEmpty()) Text("No timed departures match this search.", style = LabType.small, color = c.muted)
        LabSecondaryButton("Open Golden Arrow timetable", { runCatching { uri.openUri(candidate.timetable.route.url) } }, Modifier.fillMaxWidth())
    }
}

/** One trip. The whole card opens it; nothing on it needs a precise tap. */
@Composable
internal fun OptionCard(option: PlannerOption, from: WalkEndpoint?, fastest: Boolean, past: Boolean = false, allDay: Boolean = false, onOpen: () -> Unit) {
    val c = LocalLab.current
    val dep = LabTiming.clock(option.estimate.departureAt)
    val arrival = option.estimate.arrivalAt?.let { LabTiming.clock(it) }
        ?: option.rail?.arrival ?: option.bus?.arrival?.time
    val leave = option.leaveAt(from)
    LabCard(Modifier.clickableRow("Open trip leaving $dep") { onOpen() }.testTag("lab-option"), emphasised = fastest) {
        // Each timing item gets the card's width. A trailing unweighted duration used to
        // consume the Row first, squeezing arrival digits into a tall, overlapping column.
        Text(dep, modifier = Modifier.fillMaxWidth(), style = LabType.timeMedium, color = c.ink, softWrap = false)
        arrival?.let {
            Text("Arrive ${LabTiming.arrival(it)}", modifier = Modifier.fillMaxWidth(), style = LabType.small, color = c.muted)
        }
        Text(listOfNotNull(option.estimate.totalMinutes?.takeUnless { allDay }?.let { LabTiming.duration(it) } ?: "${LabTiming.duration(option.rideMinutes)} on board",
            option.rail?.let { LabTiming.changes(it.transfers) }).joinToString(" · "), modifier = Modifier.fillMaxWidth(), style = LabType.bodyStrong, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            option.accessMinutes(from)?.takeIf { it > 0 }?.let { ModeBadge(LabMode.WALK, "$it min") }
            val rail = option.rail
            if (rail != null) rail.legs.forEachIndexed { i, leg ->
                if (i > 0) Surface(shape = LabControlShape, color = c.ink) {
                    Text("Change · ${leg.departure.name}", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = LabType.small, color = c.surface, fontWeight = FontWeight.Bold)
                }
                ModeBadge(LabMode.TRAIN, leg.line.ifBlank { null })
            } else option.bus?.let { ModeBadge(option.service.labMode(), it.timetable.route.title.take(28)) }
        }
        if (past) Text("Departed · scheduled time has passed", style = LabType.bodyStrong, color = c.signal)
        Text("${option.board} → ${option.alight}", style = LabType.small, color = c.muted)
        Text(if(past) "Past departure · view itinerary for reference" else leave?.let { "Leave by ${LabTiming.clock(it)}" } ?: "Turn on walking times for a leave time", style = LabType.small,
            color = if (leave != null) c.signal else c.muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Text(option.fares.firstOrNull()?.let { money(it.cents) } ?: "Fare: confirm", style = LabType.small, color = c.ink)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted)
        }
        if (option.cached) StaleChip("Saved timetable · checked ${LabTiming.age(option.fetchedAt, System.currentTimeMillis())}")
    }
}

/** Where to: your places, current location, stations, stops and (optionally) addresses. */
@Composable
internal fun LabPlacePicker(state: TrainState, model: TrainViewModel, feature: FeatureState, features: FeatureViewModel, start: Boolean,
                            onDismiss: () -> Unit, station: String? = null, onStation: () -> Unit = {}, onSelect: (WalkEndpoint) -> Unit) {
    val c = LocalLab.current
    var query by remember { mutableStateOf("") }
    var online by rememberSaveable { mutableStateOf(true) }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var issue by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) model.locateOnce() else { waiting = false; issue = "Location is off. Search for where you are instead." }
    }
    LaunchedEffect(waiting, state.currentPosition) {
        val p = state.currentPosition
        if (waiting && p != null) { waiting = false; onSelect(WalkEndpoint("current", "Current location", GeoPoint(p.latitude, p.longitude))) }
    }
    val saved = remember(feature.extras.spots) { feature.extras.spots.map { WalkEndpoint("spot:${it.id}", it.name, it.point) } }
    val places = remember(state.stations, saved, feature.snapshots, feature.extras.beta) {
        val rail = state.stations.filter { it.mapped }.map(WalkingRoutes::endpoint)
        val bus = feature.snapshots.filterKeys(feature.extras.beta::enabled).flatMap { (service, source) ->
            source.stops.map { WalkEndpoint("bus:${service.name}:${it.id}", it.name, it.point) } }
        saved + rail + bus
    }
    val ranked = remember(places, query) {
        if (query.isBlank()) emptyList() else places.mapNotNull { p ->
            val note = feature.extras.spots.firstOrNull { p.id == "spot:${it.id}" }?.note.orEmpty()
            listOfNotNull(FuzzyPlaces.score(query, p.label), note.takeIf { it.isNotBlank() }?.let { FuzzyPlaces.score(query, it) })
                .maxOrNull()?.let { p to it + if (p.id.startsWith("spot:")) 60 else 0 }
        }
            .sortedByDescending { it.second }.distinctBy { it.first.id }.take(30)
    }
    val exact = ranked.any { it.second >= 1000 }
    LaunchedEffect(query, online, exact) {
        suggestions = emptyList(); issue = null
        if (online && !exact && query.trim().length >= 3) {
            delay(450)
            try { suggestions = features.placeSuggestions(query) } catch (e: Exception) {
                if (e is CancellationException) throw e
                issue = "Address search is unavailable right now. Stations, stops and your places still work."
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.ground) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(if (start) "Where are you starting?" else "Where are you going?", style = LabType.route, color = c.ink)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(query, { query = it.take(120) }, placeholder = { Text("Station, stop, place or address") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = LabControlShape,
                    modifier = Modifier.fillMaxWidth().testTag("lab-place-search"))
            }
            if (station != null) item {
                ActionRow(Icons.Outlined.Train, if (start) "Boarding station" else "Arrival station", station,
                    modifier = Modifier.testTag(if (start) "boarding-station" else "arrival-station"), onClick = onStation)
            }
            if (start) item {
                ActionRow(Icons.Outlined.MyLocation, if (waiting) "Finding your location…" else "Current location", "Nearest stations and stops are chosen for you") {
                    val p = state.currentPosition
                    if (p != null) onSelect(WalkEndpoint("current", "Current location", GeoPoint(p.latitude, p.longitude)))
                    else { waiting = true; permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                }
            }
            if (query.isBlank() && saved.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    saved.forEach { p -> AssistChip(onClick = { onSelect(p) }, label = { Text(p.label) },
                        leadingIcon = { Icon(Icons.Outlined.Place, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = LabTouch)) }
                }
            }
            if (query.isBlank()) item {
                val favourites = state.preferences.favoriteStations.mapNotNull { name -> state.stations.firstOrNull { it.mapped && stationKey(it.name) == stationKey(name) } }
                if (favourites.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    favourites.forEach { st -> AssistChip(onClick = { onSelect(WalkingRoutes.endpoint(st)) }, label = { Text(st.name) },
                        leadingIcon = { Icon(Icons.Outlined.Star, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = LabTouch)) }
                }
            }
            issue?.let { item { Text(it, style = LabType.small, color = c.muted) } }
            items(ranked, key = { it.first.id }) { (p, _) ->
                ActionRow(when { p.id.startsWith("spot:") -> Icons.Outlined.Place; p.id.startsWith("bus:") -> Icons.Outlined.DirectionsBus; else -> Icons.Outlined.Train },
                    p.label, when { p.id.startsWith("spot:") -> "Your place"; p.id.startsWith("bus:") -> "Bus stop"; else -> "Train station" }) { onSelect(p) }
            }
            items(suggestions, key = { "a-${it.id}" }) { p ->
                ActionRow(Icons.Outlined.Map, p.label, "Address") { onSelect(WalkEndpoint("place:${p.id}", p.label.take(100), p.point)) }
            }
            if (query.trim().length >= 3 && ranked.isEmpty() && suggestions.isEmpty() && issue == null) item {
                Text(if (online) "Searching…" else "No station, stop or place matches.", style = LabType.small, color = c.muted)
            }
            item {
                TextButton(onClick = { online = !online }, Modifier.heightIn(min = LabTouch)) {
                    Text(if (online) "Address search is on · turn off" else "Turn on address search", color = c.ink)
                }
                if (online) Text("Typed addresses go to Photon (OpenStreetMap data), limited to the Cape Town area.", style = LabType.small, color = c.muted)
            }
        }
    }
}
