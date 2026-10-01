@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package za.co.cttrains.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.nativeCanvas
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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import kotlin.math.cos
import kotlin.math.min
import za.co.cttrains.app.BuildConfig
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.app.location.CurrentPosition
import za.co.cttrains.app.scheduling.googleMapsJourneyUrl
import za.co.cttrains.core.*

@Composable
fun TransitMapScreen(state: TrainState, model: TrainViewModel, onPlan: () -> Unit) {
    var browser by rememberSaveable { mutableStateOf(false) }
    var confirmGoogle by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) model.locateOnce() else model.showMessage("Location is optional. You can select stations without it.")
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                if (BuildConfig.MAPS_CONFIGURED && state.preferences.googleMapsEnabled) GoogleStationMap(state, model::selectStation)
                else OfflineStationMap(state.stations, state.selectedJourney, state.currentPosition, state.from, state.to, model::selectStation)
            }
            Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), tonalElevation = 3.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(if (BuildConfig.MAPS_CONFIGURED && state.preferences.googleMapsEnabled) "Google Maps · station atlas" else "Offline station map", style = MaterialTheme.typography.labelLarge)
                    Text("${state.stations.count { it.mapped }} mapped stations · tap to choose", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (BuildConfig.MAPS_CONFIGURED && !state.preferences.googleMapsEnabled) FilledTonalButton(onClick = { confirmGoogle = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text("Enable Google street map") }
        }
        if (state.selectedJourney != null && state.result != null) {
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
        } else Button(onClick = onPlan, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Outlined.Route, null); Spacer(Modifier.width(10.dp)); Text("${state.from.ifBlank { "From" }} → ${state.to.ifBlank { "To" }}")
        }
        Text("Wikidata station positions · connections between stops, not track or walking directions.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    }
    if (browser) StationBrowser(state, model, onDismiss = { browser = false }) { model.selectStation(it); browser = false }
    if (confirmGoogle) AlertDialog(onDismissRequest = { confirmGoogle = false }, title = { Text("Use Google street maps?") }, text = { Text("The street map loads data from Google. Your local profile and favourites stay on this device. Location is optional and is sampled only when you tap Locate me.") }, confirmButton = { TextButton(onClick = { model.setGoogleMaps(true); confirmGoogle = false }) { Text("Enable") } }, dismissButton = { TextButton(onClick = { confirmGoogle = false }) { Text("Keep offline map") } })
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
                Text("Choose a destination to see the next listed trains. Platform, live delay and closure information are not supplied by this timetable.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { model.edit { it.copy(from = name, selectedStation = null) }; onPlan() }, modifier = Modifier.weight(1f)) { Text("Start here") }
                    Button(onClick = { model.edit { it.copy(to = name, selectedStation = null) }; onPlan() }, modifier = Modifier.weight(1f)) { Text("Go here") }
                }
                TextButton(onClick = { uri.openUri("https://www.google.com/maps/search/?api=1&query=" + android.net.Uri.encode("$name railway station, Western Cape, South Africa")) }) { Text("Open station in Google Maps") }
            }
        }
    }
}

@Composable
private fun GoogleStationMap(state: TrainState, onStation: (String) -> Unit) {
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(-33.95, 18.58), 10f) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectedJourney, loaded) {
        val journey = state.selectedJourney
        if (loaded && journey != null) {
            val names = journey.legs.flatMap { it.stops }.map { stationKey(it.name) }.toSet()
            val points = state.stations.filter { it.mapped && stationKey(it.name) in names }
            if (points.size > 1) camera.animate(CameraUpdateFactory.newLatLngBounds(LatLngBounds.builder().apply { points.forEach { include(it.latLng) } }.build(), 96))
        }
    }
    GoogleMap(Modifier.fillMaxSize(), cameraPositionState = camera, onMapLoaded = { loaded = true },
        properties = MapProperties(isMyLocationEnabled = false), uiSettings = MapUiSettings(mapToolbarEnabled = false, myLocationButtonEnabled = false, zoomControlsEnabled = false)) {
        state.stations.filter { it.mapped }.forEach { station ->
            key(station.name) {
                val hue = when (station.name) { state.from -> BitmapDescriptorFactory.HUE_AZURE; state.to -> BitmapDescriptorFactory.HUE_ORANGE; else -> BitmapDescriptorFactory.HUE_CYAN }
                Marker(state = rememberTransitMarkerState(station.latLng), title = station.name,
                    icon = BitmapDescriptorFactory.defaultMarker(hue), onClick = { onStation(station.name); true })
            }
        }
        state.selectedJourney?.legs?.forEachIndexed { index, leg ->
            JourneyPlanning.mappedSegments(leg, state.stations).forEach { points ->
                Polyline(points = points.map { it.latLng }, color = legColor(index), width = 10f, pattern = listOf(Dash(20f), Gap(10f)))
            }
        }
        state.currentPosition?.let { point ->
            Marker(state = rememberTransitMarkerState(LatLng(point.latitude, point.longitude)), title = "Your sampled location", snippet = "Accuracy about ${point.accuracyMeters.toInt()} m", icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
        }
    }
}
@Composable
private fun rememberTransitMarkerState(position: LatLng): MarkerState {
    val state = remember { MarkerState(position) }
    SideEffect { state.position = position }
    return state
}
private val StationPosition.latLng: LatLng get() = LatLng(latitude!!, longitude!!)
private fun legColor(index: Int): Color = listOf(Color(0xFF1478B8), Color(0xFFD77721), Color(0xFF148777))[index % 3]
fun sourceLabel(id: String): String = when (id) { "cttrains-api" -> "CT Trains via Transit API"; else -> "CT Trains" }

private class Projection(stations: List<StationPosition>, val size: IntSize) {
    private val mapped = stations.filter { it.mapped }
    private val minLat = mapped.minOfOrNull { it.latitude!! } ?: -34.2
    private val maxLat = mapped.maxOfOrNull { it.latitude!! } ?: -33.6
    private val minLon = mapped.minOfOrNull { it.longitude!! } ?: 18.4
    private val maxLon = mapped.maxOfOrNull { it.longitude!! } ?: 19.1
    private val factor = cos(Math.toRadians((minLat + maxLat) / 2))
    private val units = min(size.width * .82 / ((maxLon - minLon).coerceAtLeast(.01) * factor), size.height * .74 / (maxLat - minLat).coerceAtLeast(.01))
    fun point(lat: Double, lon: Double): Offset = Offset((size.width / 2 + (lon - (minLon + maxLon) / 2) * factor * units).toFloat(), (size.height / 2 - (lat - (minLat + maxLat) / 2) * units).toFloat())
}

@Composable
private fun OfflineStationMap(stations: List<StationPosition>, journey: Journey?, location: CurrentPosition?, from: String, to: String, onStation: (String) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { delta, movement, _ -> zoom = (zoom * delta).coerceIn(1f, 6f); pan += movement }
    val projection = remember(stations, size) { Projection(stations, size) }
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val labelColor = MaterialTheme.colorScheme.onSurface
    val selectedNames = remember(journey) { journey?.legs?.flatMap { it.stops }?.map { stationKey(it.name) }?.toSet().orEmpty() }
    val paint = remember(labelColor) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(255, (labelColor.red * 255).toInt(), (labelColor.green * 255).toInt(), (labelColor.blue * 255).toInt()); textSize = with(density) { 11.dp.toPx() } } }
    fun projected(lat: Double, lon: Double): Offset {
        val middle = Offset(size.width / 2f, size.height / 2f)
        return (projection.point(lat, lon) - middle) * zoom + middle + pan
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.transformable(transform)
            .semantics { contentDescription = "Rail station map. Use Search stations for an accessible station list." }
            .pointerInput(stations, size, zoom, pan) {
                detectTapGestures { tap ->
                    val closest = stations.filter { it.mapped }.minByOrNull { (projected(it.latitude!!, it.longitude!!) - tap).getDistance() }
                    if (closest != null && (projected(closest.latitude!!, closest.longitude!!) - tap).getDistance() < with(density) { 24.dp.toPx() }) onStation(closest.name)
                }
            }) {
            val grid = 44.dp.toPx()
            var x = 0f; while (x < this.size.width) { drawLine(outline.copy(alpha = .3f), Offset(x, 0f), Offset(x, this.size.height)); x += grid }
            var y = 0f; while (y < this.size.height) { drawLine(outline.copy(alpha = .3f), Offset(0f, y), Offset(this.size.width, y)); y += grid }
            journey?.legs?.forEachIndexed { index, leg ->
                JourneyPlanning.mappedSegments(leg, stations).forEach { points -> points.zipWithNext().forEach { (a, b) ->
                    drawLine(legColor(index), projected(a.latitude!!, a.longitude!!), projected(b.latitude!!, b.longitude!!), strokeWidth = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 5.dp.toPx())))
                } }
            }
            stations.filter { it.mapped }.forEach { station ->
                val point = projected(station.latitude!!, station.longitude!!)
                val selected = station.name == from || station.name == to || stationKey(station.name) in selectedNames
                drawCircle(surface, if (selected) 7.dp.toPx() else 4.5.dp.toPx(), point)
                drawCircle(if (station.name == to) legColor(1) else primary, if (selected) 5.dp.toPx() else 2.5.dp.toPx(), point)
                if (selected || station.name in listOf("Cape Town", "Bellville", "Fish Hoek", "Wellington", "Strand")) drawContext.canvas.nativeCanvas.drawText(station.name, point.x + 8.dp.toPx(), point.y - 5.dp.toPx(), paint)
            }
            location?.let { val point = projected(it.latitude, it.longitude); drawCircle(Color(0xFF1478B8).copy(alpha = .15f), 14.dp.toPx(), point); drawCircle(Color(0xFF1478B8), 6.dp.toPx(), point) }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalIconButton(onClick = { zoom = (zoom + .5f).coerceAtMost(6f) }) { Icon(Icons.Outlined.Add, "Zoom in") }
            FilledTonalIconButton(onClick = { zoom = (zoom - .5f).coerceAtLeast(1f) }) { Icon(Icons.Outlined.Remove, "Zoom out") }
            FilledTonalIconButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Icon(Icons.Outlined.CenterFocusStrong, "Show all stations") }
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
