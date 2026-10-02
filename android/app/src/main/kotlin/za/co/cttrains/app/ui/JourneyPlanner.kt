@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import za.co.cttrains.app.*
import za.co.cttrains.core.*

@Composable fun JourneyPlanner(train: TrainState,model: TrainViewModel,s: FeatureState,m: FeatureViewModel,onSettings: ()->Unit) {
    val planner: JourneyPlannerModel=androidx.lifecycle.viewmodel.compose.viewModel()
    val result by planner.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf("PRASA") }
    var selected by rememberSaveable { mutableStateOf(TransportService.values().map { it.name }) }
    var picker by remember { mutableStateOf<Boolean?>(null) }
    var consent by remember { mutableStateOf(false) }
    var onlineWalks by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var maxWalk by rememberSaveable { mutableStateOf(1500) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var scheduled by remember { mutableStateOf<PlannerOption?>(null) }
    var date by rememberSaveable(train.date) { mutableStateOf(train.date) }
    var time by rememberSaveable(train.time) { mutableStateOf(train.time) }
    val from=s.access ?: train.stations.firstOrNull { it.mapped && stationKey(it.name)==stationKey(train.from) }?.let(WalkingRoutes::endpoint)
    val to=s.destination ?: train.stations.firstOrNull { it.mapped && stationKey(it.name)==stationKey(train.to) }?.let(WalkingRoutes::endpoint)
    val services=if(mode=="compare") TransportService.values().filter { it.name in selected }.toSet() else setOf(TransportService.valueOf(mode))
    LaunchedEffect(from,to,date,time,mode,selected,onlineWalks,maxWalk,train.preferences.sourceId,s.extras.beta.goldenArrow,s.extras.beta.myCiti) { planner.clear() }
    fun runSearch() { if(from!=null && to!=null) planner.find(PlannerRequest(from,to,date,time,services,onlineWalks,maxWalk),train) }
    if(manual && mode!="compare" && mode!="PRASA") {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick={ manual=false }) { Text("Back to journey planner") }
            BusPlanner(train,model,s,m,TransportService.valueOf(mode)) { model.openMap() }
        }
    } else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("Plan your journey",style=MaterialTheme.typography.titleLarge)
            ModeOfTransport(mode) { mode=it;manual=false }
            if(mode=="compare") FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                TransportService.values().forEach { service -> FilterChip(service.name in selected,{ selected=if(service.name in selected) selected-service.name else selected+service.name },label={ Text(service.title) }) }
            }
        }
        item {
            JourneyPlaceField("From",from?.label ?: "Choose your start") { picker=true }
            JourneyPlaceField("To",to?.label ?: "Choose your destination") { picker=false }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(date,{ date=it.take(10);if(runCatching { LocalDate.parse(date) }.isSuccess) model.edit { it.copy(date=date) } },label={ Text("Travel date") },singleLine=true,modifier=Modifier.weight(1f))
                OutlinedTextField(time,{ time=it.take(5);if(runCatching { LocalTime.parse(time) }.isSuccess) model.edit { it.copy(time=time) } },label={ Text("Leave after") },singleLine=true,modifier=Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick={ if(onlineWalks) onlineWalks=false else consent=true }) { Text(if(onlineWalks) "Walking estimates: on" else "Enable walking estimates") }
                TextButton(onClick={ advanced=!advanced }) { Text(if(advanced) "Less" else "Options") }
            }
            if(advanced) {
                Text("Maximum walking distance per leg",style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf(500,1000,1500,3000,5000).forEach { distance -> FilterChip(maxWalk==distance,{ maxWalk=distance },label={ Text("${distance} m") }) } }
                SourceSelector(train,model)
                Text("Comparison starts at the same leave-after time and includes walks, waiting and a five-minute boarding buffer. Without a mapped walk, the door-to-door time stays unknown.",style=MaterialTheme.typography.bodySmall)
            }
            val valid=from!=null && to!=null && from!=to && runCatching { LocalDate.parse(date);LocalTime.parse(time) }.isSuccess && services.isNotEmpty()
            Button(onClick={ runSearch() },enabled=valid && !result.loading,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                Text(if(result.loading) "Finding routes…" else if(mode=="compare") "Compare routes" else when(mode) { "PRASA"->"Find trains";"MYCITI"->"Find MyCiTi routes";else->"Find Golden Arrow routes" })
            }
            if(!onlineWalks) Text("Timetables can be checked now. Enable walking estimates to calculate access and final walks.",style=MaterialTheme.typography.bodySmall)
        }
        if(result.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth());Text(result.progress,style=MaterialTheme.typography.labelSmall) }
        result.error?.let { error -> item { Text(error,color=MaterialTheme.colorScheme.error) } }
        val complete=result.sections.values.flatMap { it.options }.filter { it.estimate.totalMinutes!=null && it.service!=TransportService.GOLDEN_ARROW }
        val fastest=if(!result.loading) complete.minByOrNull { it.estimate.totalMinutes!! }?.id else null
        services.forEach { service ->
            val section=result.sections[service]
            if(!s.extras.beta.enabled(service)) item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text("${service.title} is disabled",style=MaterialTheme.typography.titleMedium);TextButton(onClick=onSettings) { Text("Enable in Beta features") } } }
            } else if(section!=null) {
                item { Text(service.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
                if(section.options.isEmpty()) item {
                    Text(section.issue ?: "No supported journey found.",style=MaterialTheme.typography.bodyMedium)
                    if(service!=TransportService.PRASA) TextButton(onClick={ mode=service.name;manual=true;m.chooseBus(service) }) { Text("Manual bus setup") }
                }
                items(section.options.take(if(mode=="compare") 3 else 12),key={ it.id }) { option ->
                    PlannerJourneyCard(option,option.id==fastest,s,model,m,onSchedule={
                        model.presentPlannedJourney(option.query!!,option.rail!!,option.fetchedAt,option.cached,option.fares.firstOrNull()?.let { "Single ${money(it.cents)}" },option.sourceId,false);scheduled=option
                    },onView={
                        if(option.rail!=null) { m.showTrainMap();m.usePlannedWalks(option.walks);model.presentPlannedJourney(option.query!!,option.rail,option.fetchedAt,option.cached,option.fares.firstOrNull()?.let { "Single ${money(it.cents)}" },option.sourceId) }
                        else { model.selectJourney(null);m.selectBus(option.bus!!);m.usePlannedWalks(option.walks);model.openMap() }
                    })
                }
                if(section.options.isNotEmpty() && section.issue!=null) item { Text(section.issue,style=MaterialTheme.typography.labelSmall) }
            }
        }
        if(result.sections.isNotEmpty()) item { Text("Fastest found applies only to complete, timed routes checked here. Bus transfers and unconfirmed survey points are excluded from that claim.",style=MaterialTheme.typography.bodySmall) }
        if(mode!="compare" && mode!="PRASA" && s.extras.beta.enabled(TransportService.valueOf(mode))) item { TextButton(onClick={ manual=true;m.chooseBus(TransportService.valueOf(mode)) }) { Text("Manual bus setup · boarding & drop-off stops") } }
    }
    picker?.let { start -> JourneyPlacePicker(train,model,s,m,start,{ picker=null }) { endpoint ->
        if(endpoint.id.startsWith("station:")) { m.place(null,start);model.edit { if(start) it.copy(from=endpoint.label) else it.copy(to=endpoint.label) } }
        else { m.place(endpoint,start);WalkingRoutes.nearest(endpoint.point,train.stations)?.let { station -> model.edit { if(start) it.copy(from=station.name) else it.copy(to=station.name) } } }
        picker=null
    } }
    scheduled?.let { option -> JourneyScheduleSheet(option.query!!,option.rail!!,model,initialAccess=option.walks.firstOrNull { it.from==from }?.minutes ?: if(from?.id?.startsWith("station:")==true) 0 else 20,initialLeaveFrom=from?.label.orEmpty()) { scheduled=null } }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Calculate mapped walking routes?") },text={ Text("FOSSGIS / OSRM receives the selected start, destination and candidate stop coordinates. This checks foot paths and caches them locally. Published transit times remain scheduled estimates.") },confirmButton={ TextButton(onClick={ onlineWalks=true;consent=false }) { Text("Enable estimates") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Keep timetable only") } })
}

@Composable internal fun ModeOfTransport(mode: String,onMode: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    val label=if(mode=="compare") "Compare" else TransportService.valueOf(mode).title
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick={ open=true },modifier=Modifier.fillMaxWidth()) { Text("Mode of transport · $label") }
        DropdownMenu(open,{ open=false }) {
            TransportService.values().forEach { service -> DropdownMenuItem(text={ Text(service.title) },onClick={ onMode(service.name);open=false }) }
            DropdownMenuItem(text={ Text("Compare") },onClick={ onMode("compare");open=false })
        }
    }
}

@Composable private fun JourneyPlaceField(label: String,value: String,onClick: ()->Unit) {
    OutlinedCard(onClick=onClick,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(label,style=MaterialTheme.typography.labelSmall);Text(value,style=MaterialTheme.typography.bodyLarge) } }
}

@Composable private fun JourneyPlacePicker(train: TrainState,model: TrainViewModel,s: FeatureState,m: FeatureViewModel,start: Boolean,onDismiss: ()->Unit,onSelect: (WalkEndpoint)->Unit) {
    var query by remember { mutableStateOf("") }
    var online by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var issue by remember { mutableStateOf<String?>(null) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants -> if(grants.values.any { it }) model.locateOnce() }
    val places=remember(train.stations,s.extras.spots,s.snapshots,s.extras.beta.goldenArrow,s.extras.beta.myCiti) {
        val saved=s.extras.spots.map { WalkEndpoint("spot:${it.id}",it.name,it.point) }
        val rail=train.stations.filter { it.mapped }.map(WalkingRoutes::endpoint)
        val bus=s.snapshots.filterKeys(s.extras.beta::enabled).flatMap { (service,source) -> source.stops.map { WalkEndpoint("bus:${service.name}:${it.id}",it.name,it.point) } }
        saved+rail+bus
    }
    val local=remember(places,query) { places.asSequence().filter { it.label.contains(query,true) }.take(40).toList() }
    LaunchedEffect(query,online) {
        suggestions=emptyList();issue=null
        if(online && query.trim().length>=3) { delay(850);try { suggestions=m.placeSuggestions(query) } catch(e: Exception) { if(e is CancellationException) throw e;issue="Online suggestions unavailable. Saved stops and places are still searchable." } }
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {
                Text(if(start) "Where do you leave from?" else "Where do you want to go?",style=MaterialTheme.typography.titleLarge)
                OutlinedTextField(query,{ query=it.take(120) },label={ Text("Search places, stations or stops") },singleLine=true,modifier=Modifier.fillMaxWidth())
                TextButton(onClick={ online=!online }) { Text(if(online) "Online suggestions on · Photon" else "Enable online address suggestions") }
                if(online) Text("Typed searches are sent to Photon. Results use OpenStreetMap data.",style=MaterialTheme.typography.labelSmall)
                if(start) train.currentPosition?.let { p -> TextButton(onClick={ onSelect(WalkEndpoint("current","Current location",GeoPoint(p.latitude,p.longitude))) }) { Text("Current location · accuracy ${p.accuracyMeters.toInt()} m") } }
                if(start) TextButton(onClick={ permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Refresh my location") }
                issue?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            }
            items(suggestions,key={ "place:${it.id}" }) { p -> ListItem(headlineContent={ Text(p.label) },supportingContent={ Text("Online place") },modifier=Modifier.clickable { onSelect(WalkEndpoint("place:${p.id}",p.label.take(100),p.point)) }) }
            items(local,key={ it.id }) { p -> ListItem(headlineContent={ Text(p.label) },supportingContent={ Text(if(p.id.startsWith("spot:")) "Saved place" else if(p.id.startsWith("station:")) "Train station" else "Bus stop") },modifier=Modifier.clickable { onSelect(p) }) }
        }
    }
}

@Composable internal fun PlannerJourneyCard(option: PlannerOption,fastest: Boolean,s: FeatureState,model: TrainViewModel,m: FeatureViewModel,onSchedule: ()->Unit,onView: ()->Unit) {
    var details by rememberSaveable(option.id) { mutableStateOf(false) }
    var selectFare by remember { mutableStateOf(false) }
    var fare by remember(option.id) { mutableStateOf(option.fares.firstOrNull()) }
    val clock=remember { DateTimeFormatter.ofPattern("HH:mm").withZone(CapeTownZone) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        if(fastest) Text("Fastest complete option found",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        Text(option.estimate.totalMinutes?.let { "$it min door to door" } ?: "${option.rideMinutes} min on board · walking time unknown",style=MaterialTheme.typography.titleMedium)
        Text("${option.board} → ${option.alight}",style=MaterialTheme.typography.bodyMedium)
        Text("Board ${clock.format(Instant.ofEpochMilli(option.estimate.departureAt))}${option.estimate.arrivalAt?.let { " · arrive ${clock.format(Instant.ofEpochMilli(it))}" }.orEmpty()}",style=MaterialTheme.typography.bodySmall)
        Text("Walk ${option.estimate.walkingMinutes?.let { "$it min" } ?: "unknown"} · wait ${option.estimate.waitingMinutes?.let { "$it min + 5 min buffer" } ?: "unknown"}",style=MaterialTheme.typography.bodySmall)
        Text(fare?.let { "Fare ${money(it.cents)} · ${it.label}" } ?: "Single fare unknown",style=MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=onView) { Text("View map") }
            TextButton(onClick={ details=!details }) { Text(if(details) "Hide details" else "Trip details") }
            if(option.service==TransportService.PRASA) TextButton(onClick=onSchedule) { Text("Save & reminders") }
            if(option.service!=TransportService.PRASA) TextButton(onClick={ selectFare=true }) { Text("Choose fare") }
        }
        if(details) {
            option.walks.forEach { leg -> PlannerWalk(leg,s,m) }
            option.rail?.legs?.forEach { leg -> Text("Train ${leg.train} · ${leg.departure.name} ${leg.departure.time} → ${leg.arrival.name} ${leg.arrival.time}",style=MaterialTheme.typography.bodySmall) }
            option.bus?.let { j -> Text("${j.timetable.route.title}\nBoard ${j.departure.name} · get off ${j.arrival.name}",style=MaterialTheme.typography.bodySmall) }
            Text("${if(option.cached) "Includes saved data" else "Checked"} · ${checked(option.fetchedAt)}",style=MaterialTheme.typography.labelSmall)
            if(option.notice.isNotBlank()) Text(option.notice,style=MaterialTheme.typography.bodySmall)
        }
    } }
    if(selectFare) ModalBottomSheet(onDismissRequest={ selectFare=false },sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        val rows=s.snapshots[option.service]?.fares.orEmpty()
        var filter by remember { mutableStateOf("") }
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item { Text("Confirm the fare for this journey",style=MaterialTheme.typography.titleLarge);Text("Choose the actual distance band, payment product and travel period. A pass price is not a single trip fare.",style=MaterialTheme.typography.bodySmall);OutlinedTextField(filter,{ filter=it },label={ Text("Find route / fare band") },modifier=Modifier.fillMaxWidth()) }
            items(rows.filter { it.title.contains(filter,true) }.take(50)) { row ->
                Text(row.title,style=MaterialTheme.typography.titleMedium)
                row.products.filterKeys { !Regex("weekly|monthly|[0-9]+.?ride",RegexOption.IGNORE_CASE).containsMatchIn(it) }.forEach { (name,published) ->
                    val correction=s.extras.priceCorrections.lastOrNull { it.service==option.service && it.fareId==row.id && it.product==name }
                    val cents=correction?.cents ?: published
                    TextButton(onClick={ fare=RouteFare(cents,"${if(correction!=null) "Your correction" else "Selected product"} · ${row.title} · $name",correction!=null);selectFare=false }) { Text("$name · ${money(cents)}${if(correction!=null) " · Your price" else ""}") }
                }
            }
            items(s.extras.priceCorrections.filter { it.service==option.service && Regex("cash|single",RegexOption.IGNORE_CASE).containsMatchIn(it.product) }) { c -> TextButton(onClick={ fare=RouteFare(c.cents,"Your confirmed quote · ${c.fareId.substringAfter("personal:")}",true);selectFare=false }) { Text("${c.product} · ${money(c.cents)}") } }
            if(rows.isEmpty()) item { Text("No published fare products are cached. Add your confirmed single price in Bus schedules → Fares.") }
        }
    }
}

@Composable private fun PlannerWalk(leg: WalkingLeg,s: FeatureState,m: FeatureViewModel) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val active by za.co.cttrains.app.scheduling.WalkLearningService.active.collectAsStateWithLifecycle()
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(grants[Manifest.permission.ACCESS_FINE_LOCATION]==true || grants[Manifest.permission.ACCESS_COARSE_LOCATION]==true)
            za.co.cttrains.app.scheduling.WalkLearningService.start(context,leg)
        else m.message("Location permission is needed to learn a walk.")
    }
    Text("Walk ${leg.from.label} → ${leg.to.label} · ${leg.distanceMeters.toInt()} m · ${leg.minutes} min",style=MaterialTheme.typography.bodySmall)
    WalkingRoutes.learned(leg,s.extras.walkSamples)?.let { Text("Your observed median: $it min",style=MaterialTheme.typography.labelSmall) }
    if(s.extras.beta.walkLearning) {
        TextButton(onClick={
            if(active) za.co.cttrains.app.scheduling.WalkLearningService.stop(context)
            else permission.launch((listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)+if(android.os.Build.VERSION.SDK_INT>=33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray())
        }) { Text(if(active) "Stop learning walk" else "Learn this walk · starts now") }
    }
}
