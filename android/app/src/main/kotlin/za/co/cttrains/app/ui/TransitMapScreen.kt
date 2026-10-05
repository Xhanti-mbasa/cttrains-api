@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package za.co.cttrains.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlinx.coroutines.delay
import za.co.cttrains.app.maps.StreetMap
import za.co.cttrains.app.maps.MapViewport
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.app.FeatureState
import za.co.cttrains.app.maps.FeatureLayers
import za.co.cttrains.app.maps.MapStyles
import za.co.cttrains.app.maps.MapPin
import za.co.cttrains.app.location.CurrentPosition
import za.co.cttrains.core.*

@Composable
fun TransitMapScreen(state: TrainState, model: TrainViewModel, fullScreen: Boolean = false, onExpand: () -> Unit = {}, onClose: () -> Unit = {}, onPlan: () -> Unit, feature: FeatureState, features: FeatureViewModel, onBuses: ()->Unit) {
    var browser by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    val downloads by model.offlineMaps.state.collectAsState()
    var failed by remember(state.preferences.mapMode, state.preferences.offlineMapId,feature.extras.beta.mapStyle) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var allTransfers by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val transfers = state.selectedJourney?.let { TransferGuide.points(state.result?.entry?.query ?: state.query, it, state.stations, state.alerts, now) }.orEmpty()
    val savedArea = downloads.regions.firstOrNull { it.id == state.preferences.offlineMapId && it.complete } ?: downloads.regions.firstOrNull { it.complete }
    val useStreets = state.mapsReady && state.preferences.mapMode != "stations" && !failed && (state.preferences.mapMode == "online" || savedArea != null)
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val layers by produceState(FeatureLayers(),feature.snapshots,feature.extras.beta,feature.extras.spots,feature.watched,feature.focus,feature.pin,feature.mapRoute,feature.mapService,feature.walks,feature.selectedBus,feature.access,feature.destination,state.from,state.to,state.selectedJourney,state.routeTrace,now) {
        value=withContext(Dispatchers.Default) {
            val base=features.layers(now)
            val bus=feature.selectedBus
            val railNames=JourneyPresentation.railNames(state.selectedJourney,state.from,state.to)
            val matchingWalks=base.walks.filter { leg ->
                if(bus==null) (leg.from==feature.access && leg.to.id=="station:${stationKey(state.from)}") || (leg.to==feature.destination && leg.from.id=="station:${stationKey(state.to)}")
                else (leg.from==feature.access && leg.to.id=="bus:${bus.timetable.service.name}:${bus.board?.id}") || (leg.to==feature.destination && leg.from.id=="bus:${bus.timetable.service.name}:${bus.alight?.id}")
            }
            val endpoints=listOfNotNull(feature.access,feature.destination).map { MapPin("endpoint:${it.id}",it.label,it.point,null,"Selected journey endpoint") }
            val busPins=bus?.let { j->listOfNotNull(j.board,j.alight).map { MapPin("selected-bus:${it.id}",it.name,it.point,j.timetable.service,if(j.userMapped) "Your map association · not verified by operator" else "Published timetable stop") } }.orEmpty()
            val focused=feature.extras.beta.journeyFocus && (state.selectedJourney!=null || bus!=null || (state.from.isNotBlank() && state.to.isNotBlank()))
            val shapes=if(focused) base.shapes.filter { bus!=null && it.route==bus.timetable.route.id && it.source.contains("myciti",true) } else base.shapes
            val rail=if(bus==null) state.routeTrace?.legs?.flatten()?.flatMap { section->section.points.map { GeoPoint(it.latitude,it.longitude) } }.orEmpty().ifEmpty {
                state.stations.filter { it.mapped && stationKey(it.name) in railNames }.map { GeoPoint(it.latitude!!,it.longitude!!) }
            } else emptyList()
            val badges=JourneyPresentation.walking(matchingWalks)+(if(bus==null) JourneyPresentation.rail(state.selectedJourney,state.routeTrace,state.stations) else listOfNotNull(bus.board?.let { a->bus.alight?.let { b->LegBadge("bus:${bus.trip.id}",JourneyPresentation.midpoint(listOf(a.point,b.point)),"${bus.timetable.service.title} · ${bus.minutes} min","${bus.minutes} min") } }))
            base.copy(routeStations=state.stations,pins=((if(focused) emptyList() else base.pins)+endpoints+busPins+listOfNotNull(feature.pin)).distinctBy { it.id },shapes=shapes,walks=matchingWalks,badges=badges,
                fitPoints=if(bus!=null) busPins.map { it.point }+matchingWalks.flatMap { it.points } else if(matchingWalks.isNotEmpty()) rail+matchingWalks.flatMap { it.points } else base.fitPoints,
                fitName=if(bus!=null) "bus:${bus.trip.id}:${bus.boardIndex}:${bus.alightIndex}:${matchingWalks.size}" else if(matchingWalks.isNotEmpty()) "rail:${state.selectedJourney?.departure}:${matchingWalks.joinToString { it.key }}" else base.fitName)
        }
    }
    val focused=feature.extras.beta.journeyFocus && (state.selectedJourney!=null || feature.selectedBus!=null || (state.from.isNotBlank() && state.to.isNotBlank()))
    val railNames=JourneyPresentation.railNames(state.selectedJourney,state.from,state.to)
    val renderState=when {
        !feature.extras.beta.showTrains || (focused && feature.selectedBus!=null) -> state.copy(stations=emptyList(),selectedJourney=null,routeTrace=null)
        focused -> state.copy(stations=state.stations.filter { stationKey(it.name) in railNames })
        else -> state
    }
    val visibleTransfers=if(renderState.selectedJourney!=null) transfers else emptyList()
    val closest=state.currentPosition?.let { NearbySuggestions.station(GeoPoint(it.latitude,it.longitude),it.accuracyMeters,state.stations) }
    var lastAuto by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.mapsReady,feature.extras.beta.autoNearby) {
        if(state.mapsReady && feature.extras.beta.autoNearby && state.currentPosition==null &&
            (ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)) model.locateOnce()
    }
    LaunchedEffect(state.currentPosition?.capturedAt,feature.extras.beta.autoNearby) {
        if(feature.extras.beta.autoNearby) state.currentPosition?.let { features.focus(GeoPoint(it.latitude,it.longitude)) }
        if(feature.extras.beta.autoNearby && feature.access==null && feature.boardingStation==null && closest!=null && state.selectedJourney==null && (state.from.isBlank() || state.from==lastAuto)) { model.nearbyOrigin(closest.name);lastAuto=closest.name }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) model.locateOnce() else model.showMessage("Location is optional. You can select stations without it.")
    }
    fun refresh() { failed=false;retry++;model.refreshHome();features.refreshVisible() }
    fun centre(): GeoPoint? = if(useStreets) model.mapViewport.camera?.target?.let { GeoPoint(it.latitude,it.longitude) } else model.mapViewport.stationCenter
    fun google() {
        val start=feature.access?.point ?: state.stations.firstOrNull { it.name==state.from && it.mapped }?.let { GeoPoint(it.latitude!!,it.longitude!!) }
        val end=feature.destination?.point ?: state.stations.firstOrNull { it.name==state.to && it.mapped }?.let { GeoPoint(it.latitude!!,it.longitude!!) }
        val url=if(start!=null && end!=null) "https://www.google.com/maps/dir/?api=1&origin=${start.latitude},${start.longitude}&destination=${end.latitude},${end.longitude}&travelmode=transit"
            else centre()?.let { "https://www.google.com/maps/search/?api=1&query=${it.latitude},${it.longitude}" } ?: "https://www.google.com/maps/search/?api=1&query=Cape+Town"
        uri.openUri(url)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val homeMapHeight=(maxHeight-160.dp).coerceAtLeast(400.dp)
    PullToRefreshBox(isRefreshing=state.refreshing || feature.loading.isNotEmpty(),onRefresh={ if(!fullScreen) refresh() },modifier=Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().then(if (fullScreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier.verticalScroll(rememberScrollState())).padding(horizontal = if (fullScreen) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!fullScreen) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Where to next?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Cape Town & the Western Cape", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) model.locateOnce()
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }, enabled = !state.locating) {
                if (state.locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.MyLocation, "Locate me once")
            }
        }
        OutlinedButton(onClick = { browser = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
            Icon(Icons.Outlined.Search, null); Spacer(Modifier.width(12.dp)); Text("Search stations"); Spacer(Modifier.weight(1f)); Icon(Icons.Outlined.Tune, null)
        }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.FullscreenExit, "Close full-screen map") }
            Text("Journey map", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { browser = true }) { Icon(Icons.Outlined.Search, "Search stations") }
            IconButton(onClick = model::openAlerts) { Icon(Icons.Outlined.NotificationsActive, "Service alerts") }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={ optionsOpen=true }) { Icon(Icons.Outlined.Tune,null);Text("Map options") }
            IconButton(onClick=::refresh) { Icon(Icons.Outlined.Refresh,"Refresh map and schedules") }
            TextButton(onClick=::google) { Text("Open in Google") }
        }
        if(closest!=null && !fullScreen) {
            Text("Closest station: ${closest.name} · GPS accuracy ${state.currentPosition?.accuracyMeters?.toInt()} m",style=MaterialTheme.typography.labelSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                NearbySuggestions.destinations(closest.name,state.preferences.routes,state.preferences.favoriteStations).forEach { destination ->
                    SuggestionChip(onClick={ model.quickDestination(closest.name,destination) },label={ Text("To $destination") })
                }
            }
        }
        if (failed || (state.preferences.mapMode == "offline" && savedArea == null)) {
            Text(if (failed) "Street map unavailable. Station map is still usable." else "Download a street map first. Station positions remain available offline.", style = MaterialTheme.typography.bodySmall)
            if (failed) TextButton(onClick = { failed = false; retry++ }) { Text("Retry street map") }
        }
        if(state.selectedJourney!=null || feature.selectedBus!=null) za.co.cttrains.app.directions.DirectionsButton({ za.co.cttrains.app.directions.DirectionsPlans.selected(state,feature) },model::openDirections)
        Box(Modifier.then(if(fullScreen) Modifier.weight(1f) else Modifier.height(homeMapHeight)).fillMaxWidth()) {
            Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(if(fullScreen) 0.dp else 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                if (useStreets) key(state.preferences.mapMode, savedArea?.id, retry,feature.extras.beta.mapStyle) { StreetMap(renderState, if (state.preferences.mapMode == "offline") model.offlineMaps.definition(savedArea?.id) else null, model.mapViewport, visibleTransfers, model::selectStation, model::selectTransfer, { failed = true }, Modifier.fillMaxSize(),feature.extras.beta.mapStyle,layers,{ features.selectPin(it) },features::drop) }
                else OfflineStationMap(renderState.stations, renderState.selectedJourney, renderState.routeTrace, model.mapViewport, visibleTransfers, state.currentPosition, state.from, state.to, model::selectStation, model::selectTransfer,layers,{ features.selectPin(it) },features::drop)
            }
            Surface(modifier=Modifier.align(Alignment.TopStart).padding(12.dp).fillMaxWidth(if (fullScreen) .82f else .76f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), tonalElevation = 3.dp,onClick={ optionsOpen=true }) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(if (useStreets) if (state.preferences.mapMode == "offline") savedArea?.name ?: "Downloaded map" else MapStyles.titles[feature.extras.beta.mapStyle] ?: "Streets" else "Offline stops & stations", style = MaterialTheme.typography.labelLarge)
                    Text(if(feature.selectedBus!=null) "${feature.selectedBus.timetable.service.title} · ${feature.selectedBus.minutes} min" else if (state.selectedJourney == null) "${renderState.stations.count { it.mapped }+layers.pins.size} pins · tap for map options" else "${visibleTransfers.size} train changes · tap for map options", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!fullScreen) FilledTonalIconButton(onClick = { optionsOpen=false;onExpand() }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) { Icon(Icons.Outlined.Fullscreen, "Expand map to full screen") }
            if (state.traceLoading) LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp))
        }
        if (!fullScreen && transfers.isNotEmpty()) FilledTonalButton(onClick = { allTransfers = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.SyncAlt, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("${transfers.size} train ${if (transfers.size == 1) "change" else "changes"} · see trains & platforms")
        }
        if (!fullScreen && state.selectedJourney != null && state.result != null) {
            val journey = state.selectedJourney
            ElevatedCard(onClick = onPlan, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${journey.departure} → ${journey.arrival} · ${journey.durationMinutes} min", fontWeight = FontWeight.Bold)
                        Text("${state.result.entry.query.from} → ${state.result.entry.query.to}", style = MaterialTheme.typography.bodySmall)
                        Text("${journey.transfers} transfers · ${sourceLabel(state.result.entry.sourceId)}", style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Schedule and route details")
                }
            }
        } else if(!fullScreen) Button(onClick = onPlan, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Outlined.Route, null); Spacer(Modifier.width(10.dp)); Text("${state.from.ifBlank { "From" }} → ${state.to.ifBlank { "To" }}")
        }
        if(!fullScreen) TextButton(onClick=onPlan) { Text("Add Home, Work or Campus · walking times") }
        if (!fullScreen && state.selectedJourney != null) Text("Solid: mapped railway trace. Dashed: approximate link. Confirm trains and platforms at the station.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(layers.badges.isNotEmpty()) Text(layers.badges.joinToString("\n") { it.text },style=MaterialTheme.typography.bodySmall)
        val travelMinutes=feature.selectedBus?.minutes ?: state.selectedJourney?.durationMinutes
        if(travelMinutes!=null) Text("Travel: ${travelMinutes+layers.walks.sumOf { it.minutes }} min including mapped walks · excludes boarding buffer and unreported delays",style=MaterialTheme.typography.labelSmall)
        TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }, contentPadding = PaddingValues(0.dp)) { Text("© OpenStreetMap contributors${if (useStreets) " · OpenMapTiles" else ""} · stations: Wikidata", style = MaterialTheme.typography.labelSmall) }
        if(useStreets && feature.extras.beta.mapStyle=="aerial" && state.preferences.mapMode=="online") Text("© City of Cape Town · January 2025 aerial imagery · non-commercial use",style=MaterialTheme.typography.labelSmall)
    }
    } }
    if(optionsOpen) ModalBottomSheet(onDismissRequest={ optionsOpen=false },sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Map options",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick={ optionsOpen=false }) { Text("Collapse") } }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("online" to "Online", "offline" to "Offline", "stations" to "Stations").forEach { (mode, title) ->
                FilterChip(state.preferences.mapMode == mode, { model.setMapMode(mode) }, label = { Text(title) })
            }
            IconButton(onClick = { optionsOpen=false;downloadsOpen = true }) { Icon(Icons.Outlined.Download, "Manage offline maps") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(feature.extras.beta.showTrains,{ features.beta { it.copy(showTrains=!it.showTrains) } },label={ Text("Trains") })
            listOf(TransportService.GOLDEN_ARROW,TransportService.MYCITI).filter { feature.extras.beta.enabled(it) }.forEach { service ->
                FilterChip(feature.extras.beta.visible(service),{ features.beta { if(service==TransportService.GOLDEN_ARROW) it.copy(showGoldenArrow=!it.showGoldenArrow) else it.copy(showMyCiti=!it.showMyCiti) } },label={ Text(service.title) })
            }
            if(feature.extras.beta.goldenArrow || feature.extras.beta.myCiti) TextButton(onClick=onBuses) { Text("Bus schedules") }
            if(feature.mapRoute!=null) TextButton(onClick={ features.allMapStops() }) { Text("All service stops") }
        }
        Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Focus on selected journey",style=MaterialTheme.typography.titleSmall);Text("Hide unrelated stops and pins. Keep your endpoints and changes.",style=MaterialTheme.typography.bodySmall) };Switch(feature.extras.beta.journeyFocus,{ v->features.beta { it.copy(journeyFocus=v) } }) }
        if(feature.extras.beta.visible(TransportService.GOLDEN_ARROW)) Text("Golden Arrow survey · current operation unverified",style=MaterialTheme.typography.labelSmall)
        if(state.preferences.mapMode=="online") Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            MapStyles.titles.forEach { (id,title) -> FilterChip(feature.extras.beta.mapStyle==id,{ features.beta { it.copy(mapStyle=id) } },label={ Text(title) }) }
        }
            TextButton(onClick={ optionsOpen=false;val p=centre();if(p!=null) features.drop(p) else features.message("Wait for the map to load, or press and hold your chosen position.") }) { Text("Mark map centre · Home, Work or another place") }
            Text("You can also press and hold a point on the map to mark its exact position.",style=MaterialTheme.typography.bodySmall)
        }
    }
    if (browser) MapSearchSheet(state,layers,features,onDismiss={ browser=false }) { model.selectStation(it);browser=false }
    feature.pin?.let { PinSheet(it,feature,features) { service -> features.chooseBus(service);features.selectPin(null);onBuses() } }
    val featureArea=layers.fitPoints.takeIf { it.size>=2 && layers.fitName!=null }?.let { p -> MapArea(layers.fitName!!,MapBounds(p.minOf { it.longitude }-.005,p.minOf { it.latitude }-.005,p.maxOf { it.longitude }+.005,p.maxOf { it.latitude }+.005),10,14) }
    if (downloadsOpen) MapDownloadsSheet(state, model,featureArea) { downloadsOpen = false }
    val selectedTransfer = transfers.firstOrNull { it.number == state.selectedTransfer }
    if (allTransfers || selectedTransfer != null) TransferDetailsSheet(if (allTransfers) transfers else listOfNotNull(selectedTransfer), { allTransfers = false; model.selectTransfer(null) }, { allTransfers = false; model.selectTransfer(null); model.openAlerts() })

    state.selectedStation?.let { name ->
        ModalBottomSheet(onDismissRequest = { model.selectStation(null) }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Railway station · scheduled services", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { model.toggleStation(name) }) { Icon(if (name in state.preferences.favoriteStations) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Toggle favourite station") }
                }
                Text("Choose a destination to see listed trains. Check Updates & delays for published notices; confirm missing platform numbers at the station.", style = MaterialTheme.typography.bodyMedium)
                transfers.filter { stationKey(it.station) == stationKey(name) }.forEach { point -> OutlinedButton(onClick = { model.selectTransfer(point.number) }) { Text("Change ${point.number} · train ${point.nextTrain} · ${point.boardingLabel}") } }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { model.edit { it.copy(from = name, selectedStation = null) }; onPlan() }, modifier = Modifier.weight(1f)) { Text("Start here") }
                    Button(onClick = { model.edit { it.copy(to = name, selectedStation = null) }; onPlan() }, modifier = Modifier.weight(1f)) { Text("Go here") }
                }
                TextButton(onClick = { val p = state.stations.firstOrNull { it.name == name && it.mapped }; uri.openUri(if (p != null) "https://www.openstreetmap.org/?mlat=${p.latitude}&mlon=${p.longitude}#map=16/${p.latitude}/${p.longitude}" else "https://www.openstreetmap.org/search?query=" + android.net.Uri.encode("$name railway station, Western Cape, South Africa")) }) { Text("Open station in OpenStreetMap") }
            }
        }
    }
}

private fun legColor(index: Int): Color = listOf(Color(0xFF1478B8), Color(0xFFD77721), Color(0xFF148777))[index % 3]
fun sourceLabel(id: String): String = when (id) { "cttrains-api" -> "CT Trains via Transit API"; else -> "CT Trains" }

private class Projection(stations: List<StationPosition>, trace: JourneyTrace?, val size: IntSize) {
    private val mapped = stations.filter { it.mapped } + trace?.legs?.flatten()?.flatMap { it.points }?.map { StationPosition("Track", it.latitude, it.longitude) }.orEmpty()
    private val minLat = mapped.minOfOrNull { it.latitude!! } ?: -34.2
    private val maxLat = mapped.maxOfOrNull { it.latitude!! } ?: -33.6
    private val minLon = mapped.minOfOrNull { it.longitude!! } ?: 18.4
    private val maxLon = mapped.maxOfOrNull { it.longitude!! } ?: 19.1
    private val factor = cos(Math.toRadians((minLat + maxLat) / 2))
    private val units = min(size.width * .82 / ((maxLon - minLon).coerceAtLeast(.01) * factor), size.height * .74 / (maxLat - minLat).coerceAtLeast(.01))
    fun point(lat: Double, lon: Double): Offset = Offset((size.width / 2 + (lon - (minLon + maxLon) / 2) * factor * units).toFloat(), (size.height / 2 - (lat - (minLat + maxLat) / 2) * units).toFloat())
    fun coordinate(point: Offset)=GeoPoint((minLat+maxLat)/2-(point.y-size.height/2)/units,(minLon+maxLon)/2+(point.x-size.width/2)/(factor*units))
}

@Composable
internal fun OfflineStationMap(stations: List<StationPosition>, journey: Journey?, trace: JourneyTrace?, viewport: MapViewport, transfers: List<TransferPoint>, location: CurrentPosition?, from: String, to: String, onStation: (String) -> Unit, onTransfer: (Int) -> Unit,features: FeatureLayers,onPin: (MapPin)->Unit,onDrop: (GeoPoint)->Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(journey) { mutableFloatStateOf(if (viewport.stationJourney == journey) viewport.stationZoom else 1f) }
    var pan by remember(journey) { mutableStateOf(if (viewport.stationJourney == journey) Offset(viewport.stationPanX, viewport.stationPanY) else Offset.Zero) }
    SideEffect { viewport.stationJourney = journey; viewport.stationZoom = zoom; viewport.stationPanX = pan.x; viewport.stationPanY = pan.y }
    val transform = rememberTransformableState { delta, movement, _ -> zoom = (zoom * delta).coerceIn(1f, 16f); pan += Offset(movement.x / size.width.coerceAtLeast(1), movement.y / size.height.coerceAtLeast(1)) }
    val routeNames = journey?.legs?.flatMap { it.stops }?.map { stationKey(it.name) }?.toSet()
    val extent = if(features.fitPoints.isNotEmpty()) features.fitPoints.map { StationPosition("Route",it.latitude,it.longitude) } else if(routeNames==null) stations+features.pins.map { StationPosition(it.name,it.point.latitude,it.point.longitude) } else stations.filter { stationKey(it.name) in routeNames }
    val projection = remember(extent, trace, size) { Projection(extent, trace, size) }
    SideEffect { if(size.width>0 && size.height>0) viewport.stationCenter=projection.coordinate(Offset(size.width/2f,size.height/2f)-Offset(pan.x*size.width,pan.y*size.height)/zoom) }
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val labelColor = MaterialTheme.colorScheme.onSurface
    val selectedNames = remember(journey) { journey?.legs?.flatMap { it.stops }?.map { stationKey(it.name) }?.toSet().orEmpty() }
    val paint = remember(labelColor) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(255, (labelColor.red * 255).toInt(), (labelColor.green * 255).toInt(), (labelColor.blue * 255).toInt()); textSize = with(density) { 11.dp.toPx() } } }
    fun projected(lat: Double, lon: Double): Offset {
        val middle = Offset(size.width / 2f, size.height / 2f)
        return (projection.point(lat, lon) - middle) * zoom + middle + Offset(pan.x * size.width, pan.y * size.height)
    }
    LaunchedEffect(features.focus,size) { features.focus?.let { p -> if(p!=viewport.stationFocus && size.width>0 && size.height>0) { zoom=6f;val middle=Offset(size.width/2f,size.height/2f);val offset=(middle-projection.point(p.latitude,p.longitude))*zoom;pan=Offset(offset.x/size.width,offset.y/size.height);viewport.stationFocus=p } } }
    val shapePaths by produceState<List<Pair<Path,Color>>>(emptyList(),projection,features.shapes) {
        value=withContext(Dispatchers.Default) {
            features.shapes.map { shape->val path=Path();shape.points.forEachIndexed { i,p->val xy=projection.point(p.latitude,p.longitude);if(i==0) path.moveTo(xy.x,xy.y) else path.lineTo(xy.x,xy.y) };path to Color(android.graphics.Color.parseColor(if(shape.source.startsWith("Saved directions")) "#1478B8" else if(shape.source.contains("myciti")) TransportService.MYCITI.color else TransportService.GOLDEN_ARROW.color)) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.transformable(transform)
            .semantics { contentDescription = "Rail station map. Use Search stations for an accessible station list." }
            .pointerInput(stations, transfers, journey, trace, size, zoom, pan,features.pins) {
                detectTapGestures(onLongPress={ tap -> val middle=Offset(size.width/2f,size.height/2f);val original=(tap-middle-Offset(pan.x*size.width,pan.y*size.height))/zoom+middle;onDrop(projection.coordinate(original)) },onTap={ tap ->
                    val transfer = transfers.mapNotNull { t -> t.position?.let { t to projected(it.latitude!!, it.longitude!!) } }.minByOrNull { (it.second - tap).getDistance() }
                    if (transfer != null && (transfer.second - tap).getDistance() < with(density) { 26.dp.toPx() }) { onTransfer(transfer.first.number); return@detectTapGestures }
                    val pin=features.pins.minByOrNull { (projected(it.point.latitude,it.point.longitude)-tap).getDistance() }
                    if(pin!=null && (projected(pin.point.latitude,pin.point.longitude)-tap).getDistance()<with(density) { 24.dp.toPx() }) { onPin(pin);return@detectTapGestures }
                    val closest = stations.filter { it.mapped }.minByOrNull { (projected(it.latitude!!, it.longitude!!) - tap).getDistance() }
                    if (closest != null && (projected(closest.latitude!!, closest.longitude!!) - tap).getDistance() < with(density) { 24.dp.toPx() }) onStation(closest.name)
                })
            }) {
            val grid = 44.dp.toPx()
            var x = 0f; while (x < this.size.width) { drawLine(outline.copy(alpha = .3f), Offset(x, 0f), Offset(x, this.size.height)); x += grid }
            var y = 0f; while (y < this.size.height) { drawLine(outline.copy(alpha = .3f), Offset(0f, y), Offset(this.size.width, y)); y += grid }
            features.walks.forEach { leg -> val path=Path();leg.points.forEachIndexed { i,p->val xy=projected(p.latitude,p.longitude);if(i==0) path.moveTo(xy.x,xy.y) else path.lineTo(xy.x,xy.y) };drawPath(path,Color(0xFF8B5BC2),style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),5.dp.toPx())))) }
            withTransform({ translate(pan.x*size.width,pan.y*size.height);scale(zoom,zoom,Offset(size.width/2f,size.height/2f)) }) {
                shapePaths.forEach { (path,color)->drawPath(path,color.copy(alpha=.6f),style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()/zoom)) }
            }
            features.pins.forEach { pin -> val point=projected(pin.point.latitude,pin.point.longitude);drawCircle(Color.White,6.dp.toPx(),point);drawCircle(Color(android.graphics.Color.parseColor(pin.service?.color ?: "#8b5bc2")),4.dp.toPx(),point) }
            journey?.legs?.forEachIndexed { index, leg ->
                val sections = trace?.legs?.getOrNull(index) ?: JourneyPlanning.mappedSegments(leg,features.routeStations.ifEmpty { stations }).map { points -> RouteSection(points.map { TrackPoint(it.latitude!!, it.longitude!!) }, false) }
                sections.forEach { section ->
                    val path = Path(); section.points.forEachIndexed { n, p -> val point = projected(p.latitude, p.longitude); if (n == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y) }
                    drawPath(path, legColor(index), style = androidx.compose.ui.graphics.drawscope.Stroke(if (section.followsRail) 4.dp.toPx() else 3.dp.toPx(), pathEffect = if (section.followsRail) null else PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 5.dp.toPx()))))
                }
            }
            val occupied = mutableListOf<android.graphics.RectF>()
            val transferNames = transfers.map { stationKey(it.station) }.toSet()
            stations.filter { it.mapped }.sortedBy { it.name != from && it.name != to }.forEach { station ->
                val point = projected(station.latitude!!, station.longitude!!)
                val selected = station.name == from || station.name == to || stationKey(station.name) in selectedNames
                drawCircle(surface, if (selected) 7.dp.toPx() else 4.5.dp.toPx(), point)
                drawCircle(if (station.name == to) legColor(1) else primary, if (selected) 5.dp.toPx() else 2.5.dp.toPx(), point)
                if ((selected || station.name in listOf("Cape Town", "Bellville", "Fish Hoek", "Wellington", "Strand")) && stationKey(station.name) !in transferNames) {
                    val x = point.x + 8.dp.toPx(); val y = point.y - 5.dp.toPx()
                    val rect = android.graphics.RectF(x - 2, y - paint.textSize, x + paint.measureText(station.name) + 4, y + 4)
                    if (occupied.none { android.graphics.RectF.intersects(it, rect) }) { occupied.add(rect); drawContext.canvas.nativeCanvas.drawText(station.name, x, y, paint) }
                }
            }
            val numberPaint = android.graphics.Paint(paint).apply { color = android.graphics.Color.WHITE; textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = 14.dp.toPx() }
            val pinPaint = android.graphics.Paint(paint).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
            transfers.forEach { change -> change.position?.let { p ->
                val point = projected(p.latitude!!, p.longitude!!)
                drawCircle(Color.White, 15.dp.toPx(), point); drawCircle(Color(0xFF713B00), 12.dp.toPx(), point)
                drawContext.canvas.nativeCanvas.drawText("${change.number}", point.x, point.y - (numberPaint.ascent() + numberPaint.descent()) / 2, numberPaint)
                val labels = listOf("Change ${change.number} · ${change.station}", "Train ${change.nextTrain} · ${change.boardingLabel}")
                val longest = labels.maxOf { pinPaint.measureText(it) }
                val x = (point.x + 18.dp.toPx()).coerceAtMost(size.width - longest - 8.dp.toPx()).coerceAtLeast(4.dp.toPx())
                val y = (point.y - 22.dp.toPx()).coerceAtLeast(28.dp.toPx())
                drawRoundRect(surface.copy(alpha = .96f), Offset(x - 5.dp.toPx(), y - pinPaint.textSize - 4.dp.toPx()), androidx.compose.ui.geometry.Size(longest + 10.dp.toPx(), 36.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                labels.forEachIndexed { n, label -> drawContext.canvas.nativeCanvas.drawText(label, x, y + n * 14.dp.toPx(), pinPaint) }
            } }
            features.badges.filter { zoom>=1.5f }.forEach { badge ->
                val at=projected(badge.point.latitude,badge.point.longitude)
                val labelPaint=android.graphics.Paint(paint).apply { textSize=(if(zoom<3) 9 else 11).dp.toPx();typeface=android.graphics.Typeface.DEFAULT }
                val width=labelPaint.measureText(badge.compact)+10.dp.toPx()
                val left=(at.x-width/2).coerceIn(4.dp.toPx(),(size.width-width-4.dp.toPx()).coerceAtLeast(4.dp.toPx()))
                val top=at.y-32.dp.toPx()
                val bounds=android.graphics.RectF(left,top,left+width,top+21.dp.toPx())
                if(top>=0 && bounds.bottom<size.height && occupied.none { android.graphics.RectF.intersects(it,bounds) }) {
                    occupied+=bounds
                    drawRoundRect(surface.copy(alpha=.96f),Offset(left,top),androidx.compose.ui.geometry.Size(width,21.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                    drawContext.canvas.nativeCanvas.drawText(badge.compact,left+5.dp.toPx(),top+14.dp.toPx(),labelPaint)
                }
            }
            location?.let { val point = projected(it.latitude, it.longitude); drawCircle(Color(0xFF1478B8).copy(alpha = .15f), 14.dp.toPx(), point); drawCircle(Color(0xFF1478B8), 6.dp.toPx(), point) }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalIconButton(onClick = { zoom = (zoom + .5f).coerceAtMost(16f) }) { Icon(Icons.Outlined.Add, "Zoom in") }
            FilledTonalIconButton(onClick = { zoom = (zoom - .5f).coerceAtLeast(1f) }) { Icon(Icons.Outlined.Remove, "Zoom out") }
            FilledTonalIconButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Icon(Icons.Outlined.CenterFocusStrong, if (journey == null) "Show all stations" else "Fit this journey") }
        }
    }
}

@Composable
private fun StationBrowser(state: TrainState, model: TrainViewModel, onDismiss: () -> Unit, onStation: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val names = remember(state.catalog.stations, state.preferences.favoriteStations, query) {
        state.catalog.stations.filter { it.contains(query.trim(), true) }.sortedWith(compareBy<String> { it !in state.preferences.favoriteStations }.thenBy { it })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).imePadding()) {
            Text("Find a station", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(query, { query = it }, label = { Text("Station name") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), singleLine = true)
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                if (names.isEmpty()) item { Text("No station matches that name.", Modifier.padding(20.dp)) }
                items(names, key = { it }) { name ->
                    ListItem(headlineContent = { TextButton(onClick = { onStation(name) }) { Text(name) } }, leadingContent = { Icon(Icons.Outlined.Train, null) },
                        trailingContent = { IconButton(onClick = { model.toggleStation(name) }) { Icon(if (name in state.preferences.favoriteStations) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Toggle favourite $name") } })
                }
            }
        }
    }
}
