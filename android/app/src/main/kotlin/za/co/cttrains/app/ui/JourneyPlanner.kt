@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package za.co.cttrains.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import za.co.cttrains.app.*
import za.co.cttrains.core.*

@Composable fun JourneyPlanner(train: TrainState,model: TrainViewModel,s: FeatureState,m: FeatureViewModel,onSettings: ()->Unit) {
    val planner: JourneyPlannerModel=androidx.lifecycle.viewmodel.compose.viewModel()
    val directions: za.co.cttrains.app.directions.DirectionsModel=androidx.lifecycle.viewmodel.compose.viewModel()
    val result by planner.state.collectAsStateWithLifecycle()
    // Any mix of services; choosing all of them shows every plan, grouped into jumpable sections.
    var selected by rememberSaveable(s.plannerMode) { mutableStateOf(ModeOrder.map { it.name }.filter { s.plannerMode=="compare" || it==s.plannerMode }.ifEmpty { listOf(TransportService.PRASA.name) }) }
    var manualService by rememberSaveable { mutableStateOf<String?>(null) }
    var openCandidates by rememberSaveable { mutableStateOf(listOf<String>()) }
    val listState=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val sectionIndex=remember { mutableMapOf<TransportService,Int>() }
    val navOffset=with(LocalDensity.current) { 60.dp.roundToPx() }
    var picker by remember { mutableStateOf<Boolean?>(null) }
    var stationPicker by remember { mutableStateOf<Boolean?>(null) }
    var savePlace by remember { mutableStateOf<Boolean?>(null) }
    var finalStation by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf(false) }
    var onlineWalks by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var maxWalk by rememberSaveable { mutableStateOf(1500) }
    var autoWalk by rememberSaveable { mutableStateOf(true) }
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }
    var scheduled by remember { mutableStateOf<PlannerOption?>(null) }
    var date by rememberSaveable(train.date) { mutableStateOf(train.date) }
    var time by rememberSaveable(train.time) { mutableStateOf(train.time) }
    val from=s.access ?: train.stations.firstOrNull { it.mapped && stationKey(it.name)==stationKey(train.from) }?.let(WalkingRoutes::endpoint)
    val to=s.destination ?: train.stations.firstOrNull { it.mapped && stationKey(it.name)==stationKey(train.to) }?.let(WalkingRoutes::endpoint)
    val services=ModeOrder.filter { it.name in selected }.toSet()
    val mode=if(services.size==1) services.first().name else "compare"
    val rail=TransportService.PRASA in services
    val board=if(s.access==null) train.from else s.boardingStation
    val arrive=if(s.destination==null) train.to else s.arrivalStation
    LaunchedEffect(from,to,board,arrive,date,time,mode,selected,onlineWalks,maxWalk,autoWalk,train.preferences.sourceId,s.extras.beta.goldenArrow,s.extras.beta.myCiti) { planner.clear() }
    fun runSearch() { if(from!=null && to!=null) planner.find(PlannerRequest(from,to,date,time,services,onlineWalks,if(autoWalk) 5000 else maxWalk,board,arrive),train) }
    val manualBus=manualService?.let { name -> TransportService.values().firstOrNull { it.name==name } }?.takeIf { it!=TransportService.PRASA }
    if(manualBus!=null) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick={ manualService=null }) { Text("Back to journey planner") }
            BusPlanner(train,model,s,m,manualBus) { model.openMap() }
        }
    } else LazyColumn(Modifier.fillMaxSize().testTag("journey-planner"),state=listState,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("Plan your journey",style=MaterialTheme.typography.titleLarge)
            ModeOfTransport(services) { chosen -> selected=ModeOrder.filter { it in chosen }.map { it.name } }
        }
        item {
            JourneyPlaceField("From",from?.label ?: "Choose your start") { picker=true }
            if(rail && s.access!=null) JourneyStationField(true,board,from,train,{ stationPicker=true },{ m.place(null,true) })
            UsualStops(s.access,services,s,m)
            if(s.access!=null && !s.access.id.startsWith("spot:")) TextButton(onClick={ savePlace=true }) { Text("Save start as Home, Work or another place") }
        }
        item {
            TextButton(onClick={ m.swapPlaces();model.edit { it.copy(from=it.to,to=it.from) } },enabled=from!=null && to!=null,modifier=Modifier.fillMaxWidth()) { Text("Swap From and To") }
        }
        item {
            JourneyPlaceField("To",to?.label ?: "Choose your destination") { picker=false }
            if(rail && s.destination!=null) JourneyStationField(false,arrive,to,train,{ stationPicker=false },{ m.place(null,false) })
            UsualStops(s.destination,services,s,m)
            if(s.destination!=null && !s.destination.id.startsWith("spot:")) TextButton(onClick={ savePlace=false }) { Text("Save destination as Home, Work or another place") }
            if(rail && s.destination==null) train.stations.firstOrNull { it.mapped && stationKey(it.name)==arrive?.let(::stationKey) }?.let { station ->
                val nearby=remember(station,s.extras.spots) { StationChoices.nearbyPlaces(station,s.extras.spots) }
                Text("After ${station.name}",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=8.dp))
                if(nearby.isNotEmpty()) {
                    Text("Walk to a saved place",style=MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { nearby.take(3).forEach { spot ->
                        AssistChip(onClick={ m.place(WalkEndpoint("spot:${spot.id}",spot.name,spot.point),false,station.name) },label={ Text("${spot.name} · ${stationDistance(station,spot.point)}") },modifier=Modifier.testTag("final-spot:${spot.id}"))
                    } }
                }
                TextButton(onClick={ finalStation=station.name;picker=false }) { Text("Choose final address or saved place") }
                if(nearby.isNotEmpty()) Text("Distances above are straight-line proximity. Walking estimates use mapped paths.",style=MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick={ datePicker=true },modifier=Modifier.weight(1f)) { Column { Text("Travel date",style=MaterialTheme.typography.labelSmall);Text(date) } }
                OutlinedButton(onClick={ timePicker=true },modifier=Modifier.weight(1f)) { Column { Text("Leave after",style=MaterialTheme.typography.labelSmall);Text(time) } }
            }
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                TextButton(onClick={ if(onlineWalks) onlineWalks=false else consent=true }) { Text(if(onlineWalks) "Walking estimates: on" else "Enable walking estimates") }
                TextButton(onClick={ advanced=!advanced }) { Text(if(advanced) "Less" else "Options") }
            }
            if(advanced) {
                Text("How far are you willing to walk to a stop?",style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(autoWalk,{ autoWalk=true },label={ Text("Auto estimate") })
                    listOf(500,1000,1500,3000,5000).forEach { distance -> FilterChip(!autoWalk && maxWalk==distance,{ autoWalk=false;maxWalk=distance },label={ Text("${distance} m") }) }
                }
                Text(if(autoWalk) "We look for nearby usable stops within 5 km, calculate each mapped walk and rank the journeys. You can set a shorter limit." else "This limit applies separately to the walk before boarding and after getting off.",style=MaterialTheme.typography.bodySmall)
                if(TransportService.PRASA in services) SourceSelector(train,model)
                Text("Comparison starts at the same leave-after time and includes walks, waiting and a five-minute boarding buffer. Without a mapped walk, the door-to-door time stays unknown.",style=MaterialTheme.typography.bodySmall)
            }
            val valid=from!=null && to!=null && from!=to && runCatching { LocalDate.parse(date);LocalTime.parse(time) }.isSuccess && services.isNotEmpty()
            Button(onClick={ runSearch() },enabled=valid && !result.loading,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
                Text(if(result.loading) "Finding routes…" else findLabel(services))
            }
            if(!onlineWalks) Text("Timetables can be checked now. Enable walking estimates to calculate access and final walks.",style=MaterialTheme.typography.bodySmall)
        }
        // Fixed items above: header, from, swap, to, date & search. Counted so section chips can jump.
        var index=5
        sectionIndex.clear()
        if(result.loading) { item { LinearProgressIndicator(Modifier.fillMaxWidth());Text(result.progress,style=MaterialTheme.typography.labelSmall) };index++ }
        result.error?.let { error -> item { Text(error,color=MaterialTheme.colorScheme.error) };index++ }
        val complete=result.sections.values.flatMap { it.options }.filter { it.estimate.totalMinutes!=null && it.service!=TransportService.GOLDEN_ARROW }
        val fastest=if(!result.loading) complete.minByOrNull { it.estimate.totalMinutes!! }?.id else null
        if(services.size>1 && (result.sections.isNotEmpty() || result.loading)) {
            stickyHeader(key="planner-sections") {
                PlannerSectionBar(services.toList(),result.sections,s,onTop={ scope.launch { listState.animateScrollToItem(0) } }) { service ->
                    sectionIndex[service]?.let { target -> scope.launch { listState.animateScrollToItem(target,-navOffset) } }
                }
            }
            index++
        }
        services.forEach { service ->
            val section=result.sections[service]
            if(!s.extras.beta.enabled(service)) {
                sectionIndex[service]=index
                item(key="section:${service.name}") {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { PlannerSectionTitle(service,null);Text("${service.title} is off on this phone.",style=MaterialTheme.typography.bodyMedium);Button(onClick={ m.beta { if(service==TransportService.MYCITI) it.copy(myCiti=true) else it.copy(goldenArrow=true) } },modifier=Modifier.testTag("turn-on:${service.name}")) { Text("Turn on ${service.title}") } } }
                }
                index++
            } else if(section!=null) {
                sectionIndex[service]=index
                item(key="section:${service.name}") { PlannerSectionTitle(service,section.options.size) }
                index++
                if(section.options.isEmpty()) {
                    item {
                        Text(if(section.candidates.isNotEmpty()) "No exact bus times could be confirmed for these places." else section.issue ?: "No journey found for these places and time.",style=MaterialTheme.typography.bodyMedium)
                        if(service!=TransportService.PRASA) TextButton(onClick={ manualService=service.name;m.chooseBus(service) }) { Text("Manual bus setup") }
                    }
                    index++
                }
                if(section.candidates.isNotEmpty()) {
                    item(key="candidates:${service.name}") {
                        val open=service.name in openCandidates
                        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("${section.candidates.size} ${service.title} ${if(section.candidates.size==1) "route passes" else "routes pass"} near both places",style=MaterialTheme.typography.titleSmall)
                            Text(section.candidates.take(4).joinToString("\n") { "• "+it.timetable.route.title },style=MaterialTheme.typography.bodySmall)
                            if(open) section.issue?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
                            TextButton(onClick={ openCandidates=if(open) openCandidates-service.name else openCandidates+service.name },modifier=Modifier.testTag("candidates:${service.name}")) { Text(if(open) "Hide timetables" else "Show timetables") }
                        } }
                    }
                    index++
                }
                if(service.name in openCandidates) items(section.candidates,key={ "candidate:${it.timetable.route.id}" }) { candidate ->
                    val timetable=candidate.timetable
                    val uri=LocalUriHandler.current
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(timetable.route.title,style=MaterialTheme.typography.titleMedium)
                        Text("Possible route · surveyed corridor near both places",style=MaterialTheme.typography.labelMedium)
                        Text("${timetable.day} · ${if(candidate.cached) "Saved timetable" else "Checked"} ${checked(timetable.fetchedAt)}",style=MaterialTheme.typography.labelSmall)
                        Text("${timetable.trips.count { trip->trip.calls.lastOrNull()?.time.orEmpty()>=time }} published trips remaining in this timetable. Your roadside boarding time is not supplied.",style=MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick={ manualService=service.name;m.chooseBus(service);m.timetable(timetable.route,timetable.day) }) { Text("Choose published timed stops") }
                            TextButton(onClick={ uri.openUri(timetable.route.url) }) { Text("View operator PDF") }
                        }
                    } }
                }
                if(service.name in openCandidates) index+=section.candidates.size
                items(section.options,key={ it.id }) { option ->
                    PlannerJourneyCard(option,option.id==fastest,s,model,m,onDirections={ directions.preview({ za.co.cttrains.app.directions.DirectionsPlans.option(option,train,s) }) {
                        if(option.rail!=null) { m.showTrainMap();m.usePlannedWalks(option.walks);model.presentPlannedJourney(option.query!!,option.rail,option.fetchedAt,option.cached,null,option.sourceId,false) }
                        else { model.selectJourney(null);m.selectBus(option.bus!!);m.usePlannedWalks(option.walks) }
                        model.openDirections()
                    } },onSchedule={
                        model.presentPlannedJourney(option.query!!,option.rail!!,option.fetchedAt,option.cached,option.fares.firstOrNull()?.let { "Single ${money(it.cents)}" },option.sourceId,false);scheduled=option
                    },onView={
                        if(option.rail!=null) { m.showTrainMap();m.usePlannedWalks(option.walks);model.presentPlannedJourney(option.query!!,option.rail,option.fetchedAt,option.cached,option.fares.firstOrNull()?.let { "Single ${money(it.cents)}" },option.sourceId) }
                        else { model.selectJourney(null);m.selectBus(option.bus!!);m.usePlannedWalks(option.walks);model.openMap() }
                    })
                }
                index+=section.options.size
                if(section.options.isNotEmpty() && section.issue!=null) { item { Text(section.issue,style=MaterialTheme.typography.labelSmall) };index++ }
            }
        }
        if(result.sections.isNotEmpty()) item { Text("Fastest found applies only to complete, timed routes checked here. Bus transfers and unconfirmed survey points are excluded from that claim.",style=MaterialTheme.typography.bodySmall) }
        if(mode!="compare" && mode!="PRASA" && s.extras.beta.enabled(TransportService.valueOf(mode))) item { TextButton(onClick={ manualService=mode;m.chooseBus(TransportService.valueOf(mode)) }) { Text("Manual bus setup · boarding & drop-off stops") } }
    }
    picker?.let { start -> JourneyPlacePicker(train,model,s,m,start,{ picker=null;finalStation=null }) { endpoint ->
        if(endpoint.id.startsWith("station:")) { m.place(null,start);model.edit { if(start) it.copy(from=endpoint.label) else it.copy(to=endpoint.label) } }
        else {
            val explicit=if(!start) finalStation else null
            m.place(endpoint,start,explicit)
            val chosen=if(start) m.state.value.boardingStation else m.state.value.arrivalStation
            val suggestion=chosen ?: StationChoices.ranked(endpoint.point,train.stations,train.catalog.stations).firstOrNull()?.name
            suggestion?.let { name -> model.edit { if(start) it.copy(from=name) else it.copy(to=name) } }
            if(rail && chosen==null && endpoint.id.startsWith("spot:")) stationPicker=start
        }
        finalStation=null
        picker=null
    } }
    stationPicker?.let { start ->
        val endpoint=if(start) from else to
        if(endpoint!=null) JourneyStationPicker(endpoint,train,s,start,if(start) board else arrive,{ stationPicker=null }) { name,remember ->
            m.chooseStation(name,start,remember)
            val suggestion=name ?: StationChoices.ranked(endpoint.point,train.stations,train.catalog.stations).firstOrNull()?.name
            suggestion?.let { value -> model.edit { if(start) it.copy(from=value) else it.copy(to=value) } }
            stationPicker=null
        }
    }
    savePlace?.let { start -> (if(start) s.access else s.destination)?.let { endpoint ->
        SaveJourneyPlace(endpoint,s.busy,{ savePlace=null }) { name,tag -> m.saveJourneyPlace(endpoint,start,name,tag);savePlace=null }
    } }
    scheduled?.let { option -> JourneyScheduleSheet(option.query!!,option.rail!!,model,initialAccess=option.walks.firstOrNull { it.from==from }?.minutes ?: if(from?.id?.startsWith("station:")==true) 0 else 20,initialLeaveFrom=from?.label.orEmpty(),accessEndpoint=from,initialWalk=option.walks.firstOrNull { it.from==from }) { scheduled=null } }
    if(datePicker) TravelDatePicker(date,train.catalog.holidays,{ datePicker=false },trainOnly=mode=="PRASA") { date=it;model.edit { state->state.copy(date=it) };datePicker=false }
    if(timePicker) DepartureTimePicker(time,{ timePicker=false },trainOnly=mode=="PRASA") { time=it;model.edit { state->state.copy(time=it) };timePicker=false }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Calculate mapped walking routes?") },text={ Text("FOSSGIS / OSRM receives the selected start, destination and candidate stop coordinates. This checks foot paths and caches them locally. Published transit times remain scheduled estimates.") },confirmButton={ TextButton(onClick={ onlineWalks=true;consent=false }) { Text("Enable estimates") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Keep timetable only") } })
}

/** Display order for the mode-of-transport filter. */
internal val ModeOrder=listOf(TransportService.PRASA,TransportService.MYCITI,TransportService.GOLDEN_ARROW)

internal fun findLabel(services: Set<TransportService>): String=when {
    services.size==ModeOrder.size -> "Find all routes"
    services.size==1 -> when(services.first()) { TransportService.PRASA->"Find trains";TransportService.MYCITI->"Find MyCiTi routes";TransportService.GOLDEN_ARROW->"Find Golden Arrow routes" }
    else -> "Find ${ModeOrder.filter { it in services }.joinToString(" + ") { it.title }} routes"
}

@Composable internal fun ServiceDot(service: TransportService,size: androidx.compose.ui.unit.Dp=10.dp) {
    Box(Modifier.size(size).background(Color(android.graphics.Color.parseColor(service.color)),CircleShape))
}

@Composable internal fun ModeOfTransport(selected: Set<TransportService>,onChange: (Set<TransportService>)->Unit) {
    val all=selected.size==ModeOrder.size
    Column(Modifier.fillMaxWidth().padding(top=6.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text("Mode of transport",style=MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(all,{ onChange(ModeOrder.toSet()) },label={ Text("All") },modifier=Modifier.testTag("mode:all"))
            ModeOrder.forEach { service ->
                // Keep at least one service chosen.
                FilterChip(service in selected,{ val next=if(service in selected) selected-service else selected+service;if(next.isNotEmpty()) onChange(next) },
                    label={ Text(service.title) },leadingIcon={ ServiceDot(service) },modifier=Modifier.testTag("mode:${service.name}"))
            }
        }
        Text(when { all->"Every train, MyCiTi and Golden Arrow plan is shown, grouped by service."
            selected.size>1->"All plans for the chosen services are shown, grouped by service."
            else->"Choose more than one to see plans side by side." },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun PlannerSectionBar(services: List<TransportService>,sections: Map<TransportService,PlannerSection>,s: FeatureState,onTop: ()->Unit,onJump: (TransportService)->Unit) {
    Surface(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=8.dp).testTag("planner-sections"),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            AssistChip(onClick=onTop,label={ Text("↑ Trip") })
            services.forEach { service ->
                val count=sections[service]?.options?.size
                val label=when { !s.extras.beta.enabled(service)->"${service.title} · off";count==null->"${service.title} · …";else->"${service.title} · $count" }
                AssistChip(onClick={ onJump(service) },label={ Text(label) },leadingIcon={ ServiceDot(service) },modifier=Modifier.testTag("jump:${service.name}"))
            }
        }
    }
}

@Composable private fun PlannerSectionTitle(service: TransportService,count: Int?) {
    Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ServiceDot(service,12.dp)
        Text(service.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
        if(count!=null) Text(if(count==1) "1 plan" else "$count plans",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun JourneyPlaceField(label: String,value: String,onClick: ()->Unit) {
    OutlinedCard(onClick=onClick,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(label,style=MaterialTheme.typography.labelSmall);Text(value,style=MaterialTheme.typography.bodyLarge) } }
}

@Composable internal fun JourneyPlacePicker(train: TrainState,model: TrainViewModel,s: FeatureState,m: FeatureViewModel,start: Boolean,onDismiss: ()->Unit,onSelect: (WalkEndpoint)->Unit) {
    var query by remember { mutableStateOf("") }
    var online by rememberSaveable { mutableStateOf(true) }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var issue by remember { mutableStateOf<String?>(null) }
    var waitingForLocation by remember { mutableStateOf(false) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(grants.values.any { it }) model.locateOnce() else { waitingForLocation=false;issue="Location is off. Search for where you are instead." }
    }
    // Current location: once a fix arrives, use it; the planner finds the nearest stations and stops itself.
    LaunchedEffect(waitingForLocation,train.currentPosition) {
        val p=train.currentPosition
        if(waitingForLocation && p!=null) { waitingForLocation=false;onSelect(WalkEndpoint("current","Current location",GeoPoint(p.latitude,p.longitude))) }
    }
    val saved=remember(s.extras.spots) { s.extras.spots.map { WalkEndpoint("spot:${it.id}",it.name,it.point) } }
    val places=remember(train.stations,saved,s.snapshots,s.extras.beta.goldenArrow,s.extras.beta.myCiti) {
        val rail=train.stations.filter { it.mapped }.map(WalkingRoutes::endpoint)
        val bus=s.snapshots.filterKeys(s.extras.beta::enabled).flatMap { (service,source) -> source.stops.map { WalkEndpoint("bus:${service.name}:${it.id}",it.name,it.point) } }
        saved+rail+bus
    }
    val ranked=remember(places,query,s.extras.spots) {
        if(query.isBlank()) emptyList() else places.mapNotNull { p ->
            val spot=s.extras.spots.firstOrNull { p.id=="spot:${it.id}" }
            val best=listOfNotNull(FuzzyPlaces.score(query,p.label),spot?.let { FuzzyPlaces.score(query,it.tag) },spot?.note?.takeIf(String::isNotBlank)?.let { FuzzyPlaces.score(query,it) }).maxOrNull()
            best?.let { p to it+(if(spot!=null) 60 else 0) }
        }.sortedByDescending { it.second }.distinctBy { it.first.id }.take(40)
    }
    // Addresses and suburbs (e.g. "Bella Vista") come from OpenStreetMap unless a place already matches exactly.
    val exact=ranked.any { it.second>=1000 }
    LaunchedEffect(query,online,exact) {
        suggestions=emptyList();issue=null
        if(online && !exact && query.trim().length>=3) {
            delay(450)
            try { suggestions=m.placeSuggestions(query) } catch(e: Exception) { if(e is CancellationException) throw e;issue="Address search is unavailable right now. Stations, stops and your places still work." }
        }
    }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {
                Text(if(start) "Where are you?" else "Where do you want to go?",style=MaterialTheme.typography.titleLarge)
                OutlinedTextField(query,{ query=it.take(120) },label={ Text("Search places, stations or stops") },placeholder={ Text("e.g. Bella Vista, Bellville, Home") },singleLine=true,modifier=Modifier.fillMaxWidth())
                FilledTonalButton(onClick={
                    val p=train.currentPosition
                    if(p!=null) onSelect(WalkEndpoint("current","Current location",GeoPoint(p.latitude,p.longitude)))
                    else { waitingForLocation=true;permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) }
                },enabled=!waitingForLocation,modifier=Modifier.fillMaxWidth().padding(top=8.dp).testTag("use-current-location")) { Text(if(waitingForLocation) "Finding your location…" else "Use current location") }
                if(query.isBlank() && saved.isNotEmpty()) {
                    Text("Your places",style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(top=8.dp))
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { saved.forEach { p -> AssistChip(onClick={ onSelect(p) },label={ Text(p.label) },modifier=Modifier.testTag("quick-place:${p.id}")) } }
                }
                issue?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            }
            items(ranked,key={ it.first.id }) { (p,_) -> ListItem(headlineContent={ Text(p.label) },supportingContent={ Text(placeKind(p)) },modifier=Modifier.clickable { onSelect(p) }) }
            items(suggestions,key={ "place:${it.id}" }) { p ->
                val hint=remember(p.id,s.snapshots,train.stations) { nearbyHint(p.point,train,s) }
                ListItem(headlineContent={ Text(p.label) },supportingContent={ Text(hint.ifBlank { "Address" }) },modifier=Modifier.clickable { onSelect(WalkEndpoint("place:${p.id}",p.label.take(100),p.point)) })
            }
            if(query.trim().length>=3 && ranked.isEmpty() && suggestions.isEmpty() && issue==null) item { Text(if(online) "Searching…" else "No match among stations, stops and your places.",style=MaterialTheme.typography.bodySmall) }
            item {
                TextButton(onClick={ online=!online }) { Text(if(online) "Address search on · turn off" else "Turn on address search for suburbs and streets") }
                if(online) Text("Typed searches go to Photon (OpenStreetMap data), limited to the Cape Town area.",style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun placeKind(p: WalkEndpoint)=when {
    p.id.startsWith("spot:")->"Your place"
    p.id.startsWith("station:")->"Train station"
    p.id.startsWith("bus:")->"${TransportService.values().firstOrNull { p.id.startsWith("bus:${it.name}:") }?.title ?: "Bus"} stop"
    else->"Place"
}

/** e.g. "Near Bellville station 1.2 km · Erica Square (Golden Arrow) 300 m" */
private fun nearbyHint(point: GeoPoint,train: TrainState,s: FeatureState): String {
    val station=StationChoices.ranked(point,train.stations,train.catalog.stations).firstOrNull()?.let { "${it.name} station ${stationDistance(it,point)}" }
    val stops=s.snapshots.filterKeys(s.extras.beta::enabled).mapNotNull { (service,data) -> NearbyStops.nearest(point,data.stops,1,1500.0).firstOrNull()?.let { (stop,meters) -> "${stop.name} (${service.title}) $meters m" } }
    val all=listOfNotNull(station)+stops
    return if(all.isEmpty()) "" else "Near "+all.joinToString(" · ")
}

/** Lets a saved place use a chosen stop for each bus operator instead of "nearest". */
@Composable private fun UsualStops(place: WalkEndpoint?,services: Set<TransportService>,s: FeatureState,m: FeatureViewModel) {
    val spot=place?.let { p -> s.extras.spots.firstOrNull { p.id=="spot:${it.id}" } } ?: return
    var choosing by remember { mutableStateOf<TransportService?>(null) }
    services.filter { it!=TransportService.PRASA && s.extras.beta.enabled(it) }.forEach { service ->
        val usual=spot.preferredStops[service.name]?.let { id -> s.snapshots[service]?.stops?.firstOrNull { it.id==id } }
        TextButton(onClick={ choosing=service },modifier=Modifier.testTag("usual-stop:${service.name}")) {
            Text(if(usual!=null) "Usual ${service.title} stop · ${usual.name}" else "Choose your usual ${service.title} stop for ${spot.name}")
        }
    }
    choosing?.let { service ->
        val near=remember(service,spot.point,s.snapshots[service]) { NearbyStops.nearest(spot.point,s.snapshots[service]?.stops.orEmpty(),10,3000.0) }
        ModalBottomSheet(onDismissRequest={ choosing=null },sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                item { Text("Usual ${service.title} stop for ${spot.name}",style=MaterialTheme.typography.titleLarge);Text("Closest first. Trips from or to ${spot.name} will use this stop.",style=MaterialTheme.typography.bodySmall) }
                if(near.isEmpty()) item { Text("No ${service.title} stops within 3 km yet. Run one ${service.title} search to load its stops.",style=MaterialTheme.typography.bodyMedium) }
                items(near,key={ it.first.id }) { (stop,meters) -> ListItem(headlineContent={ Text(stop.name) },supportingContent={ Text("$meters m away") },modifier=Modifier.clickable { m.choosePreferredStop(spot.id,service,stop.id);choosing=null }) }
                item { TextButton(onClick={ m.choosePreferredStop(spot.id,service,null);choosing=null }) { Text("Automatic · nearest suitable stop") } }
            }
        }
    }
}

@Composable internal fun PlannerJourneyCard(option: PlannerOption,fastest: Boolean,s: FeatureState,model: TrainViewModel,m: FeatureViewModel,onDirections: ()->Unit={},onSchedule: ()->Unit,onView: ()->Unit) {
    var details by rememberSaveable(option.id) { mutableStateOf(false) }
    var repeatEdit by remember { mutableStateOf<CommuteRoutine?>(null) }
    var selectFare by remember { mutableStateOf(false) }
    var fare by remember(option.id) { mutableStateOf(option.fares.firstOrNull()) }
    val clock=remember { DateTimeFormatter.ofPattern("HH:mm").withZone(CapeTownZone) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        if(fastest) Text("Fastest complete option found",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        Text("${clock.format(Instant.ofEpochMilli(option.estimate.departureAt))}${option.estimate.arrivalAt?.let { " → "+clock.format(Instant.ofEpochMilli(it)) }.orEmpty()}  ·  ${option.estimate.totalMinutes?.let { "$it min" } ?: "${option.rideMinutes} min on board"}",style=MaterialTheme.typography.titleMedium)
        Text("${option.board} → ${option.alight}",style=MaterialTheme.typography.bodyMedium)
        Text(listOfNotNull(option.estimate.walkingMinutes?.let { "Walk $it min" },fare?.let { money(it.cents) }).joinToString(" · ").ifBlank { option.service.title },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=onView) { Text("View map") }
            TextButton(onClick=onDirections,modifier=Modifier.testTag("journey-directions")) { Text("Directions") }
            TextButton(onClick={ details=!details }) { Text(if(details) "Hide details" else "Trip details") }
        }
        if(details) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ShareJourneyButton(m) { val state=model.state.value;JourneySharing.itinerary(za.co.cttrains.app.directions.DirectionsPlans.option(option,state,s),option.service,s.extras.localName,System.currentTimeMillis()) }
                if(option.service==TransportService.PRASA) TextButton(onClick=onSchedule) { Text("Save & reminders") }
                if(option.service!=TransportService.PRASA) TextButton(onClick={ val b=option.bus!!;repeatEdit=CommuteRoutine(java.util.UUID.randomUUID().toString(),"${s.access?.label ?: option.board} → ${s.destination?.label ?: option.alight}".take(100),SearchQuery(option.board,option.alight,java.time.Instant.ofEpochMilli(option.estimate.departureAt).atZone(CapeTownZone).toLocalDate().toString(),b.departure.time),setOf(1,2,3,4,5),option.service.name,option.walks.firstOrNull { it.to.id.startsWith("bus:") }?.minutes ?: 0,5,origin=s.access ?: b.board?.let { WalkEndpoint("bus:${option.service.name}:${it.id}",it.name.take(100),it.point) },destination=s.destination ?: b.alight?.let { WalkEndpoint("bus:${option.service.name}:${it.id}",it.name.take(100),it.point) },createdAt=System.currentTimeMillis(),service=option.service,busRoute=b.timetable.route,boardCallId=b.departure.id,alightCallId=b.arrival.id) }) { Text("Repeat journey") }
                if(option.service!=TransportService.PRASA) TextButton(onClick={ selectFare=true }) { Text("Choose fare") }
            }
            Text("Walk ${option.estimate.walkingMinutes?.let { "$it min" } ?: "unknown"} · wait ${option.estimate.waitingMinutes?.let { "$it min + 5 min buffer" } ?: "unknown"}",style=MaterialTheme.typography.bodySmall)
            Text(fare?.let { "Fare ${money(it.cents)} · ${it.label}" } ?: "Single fare unknown",style=MaterialTheme.typography.bodySmall)
            option.walks.forEach { leg -> PlannerWalk(leg,s,m) }
            option.rail?.legs?.forEach { leg -> Text("Train ${leg.train} · ${leg.departure.name} ${leg.departure.time} → ${leg.arrival.name} ${leg.arrival.time}",style=MaterialTheme.typography.bodySmall) }
            option.bus?.let { j -> Text("${j.timetable.route.title}\nBoard ${j.departure.name} · get off ${j.arrival.name}",style=MaterialTheme.typography.bodySmall) }
            Text("${if(option.cached) "Includes saved data" else "Checked"} · ${checked(option.fetchedAt)}",style=MaterialTheme.typography.labelSmall)
            if(option.notice.isNotBlank()) Text(option.notice,style=MaterialTheme.typography.bodySmall)
        }
    } }
    repeatEdit?.let { RoutineEditor(it,m) { repeatEdit=null } }
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
