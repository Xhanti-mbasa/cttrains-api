@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import za.co.cttrains.app.*
import za.co.cttrains.app.scheduling.WalkLearningService
import za.co.cttrains.core.*

@Composable fun DoorToDoorCard(train: TrainState,model: TrainViewModel,s: FeatureState,m: FeatureViewModel) {
    var picker by remember { mutableStateOf<Boolean?>(null) }
    var consent by remember { mutableStateOf(false) }
    var learning by remember { mutableStateOf<WalkingLeg?>(null) }
    val uri=LocalUriHandler.current
    val context=androidx.compose.ui.platform.LocalContext.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.locateOnce() }
    val learningActive by WalkLearningService.active.collectAsStateWithLifecycle()
    val learningPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants->if(grants.values.any { it }) learning?.let { WalkLearningService.start(context,it) } else m.message("Location permission is needed to learn a walk.");learning=null }
    fun choose(p: WalkEndpoint?,start: Boolean) {
        m.place(p,start)
        p?.let { WalkingRoutes.nearest(it.point,train.stations) }?.let { station->model.edit { if(start) it.copy(from=station.name) else it.copy(to=station.name) } }
        picker=null
    }
    val access=s.walks.firstOrNull { it.from==s.access && it.to.id=="station:${stationKey(train.from)}" }
    val egress=s.walks.firstOrNull { it.to==s.destination && it.from.id=="station:${stationKey(train.to)}" }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Door-to-door walking",style=MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick={ picker=true },modifier=Modifier.fillMaxWidth()) { Text("Start: ${s.access?.label ?: "At departure station"}") }
        OutlinedButton(onClick={ picker=false },modifier=Modifier.fillMaxWidth()) { Text("Finish: ${s.destination?.label ?: "At arrival station"}") }
        Text("Choose Home, Work, Campus or another marked spot. The closest mapped railway station is suggested; you can change it above.",style=MaterialTheme.typography.bodySmall)
        if(s.access!=null || s.destination!=null) {
            Button(onClick={ consent=true },enabled=!s.walkBusy) { Text(if(s.walkBusy) "Calculating walking routes…" else "Calculate walking routes") }
            if(s.walkBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.walkError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            listOf(s.access to train.from,s.destination to train.to).forEach { (p,station)->
                if(p!=null && s.walks.none { it.from==p || it.to==p }) train.stations.firstOrNull { it.name==station && it.mapped }?.let {
                    val meters=NearbySuggestions.distance(p.point,GeoPoint(it.latitude!!,it.longitude!!)).toInt()
                    Text("${p.label} ↔ $station · $meters m in a straight line · walking time unknown",style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        listOfNotNull(access,egress).forEach { leg ->
            val learned=WalkingRoutes.learned(leg,s.extras.walkSamples)
            Text("${leg.from.label} → ${leg.to.label} · ${leg.distanceMeters.toInt()} m · ${leg.minutes} min estimated",style=MaterialTheme.typography.bodyMedium)
            if(learned!=null) Text("Your observed median: $learned min · at least 3 walks",style=MaterialTheme.typography.labelSmall)
            Text("${leg.source} · checked ${checked(leg.fetchedAt)}${if(s.walksCached) " · includes saved routing data" else ""}",style=MaterialTheme.typography.labelSmall)
            if(s.extras.beta.walkLearning) TextButton(onClick={ learning=leg;val p=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION);if(android.os.Build.VERSION.SDK_INT>=33) p+=Manifest.permission.POST_NOTIFICATIONS;learningPermission.launch(p.toTypedArray()) }) { Text("Learn this walk · starts now") }
        }
        if(s.extras.beta.walkLearning) Text("Start within 100 m of the selected walking origin. Learning needs location accuracy within 60 m; only valid completed walks are retained.",style=MaterialTheme.typography.labelSmall)
        if(learningActive) TextButton(onClick={ WalkLearningService.stop(context) }) { Text("Stop learning this walk") }
        val journey=train.selectedJourney ?: train.result?.entry?.journeys?.firstOrNull()
        if(journey!=null && (s.access==null || access!=null) && (s.destination==null || egress!=null) && (access!=null || egress!=null)) {
            val query=train.result?.entry?.query ?: train.query
            val times=runCatching { WalkingRoutes.times(query,journey,access?.let { WalkingRoutes.learned(it,s.extras.walkSamples) ?: it.minutes } ?: 0,egress?.let { WalkingRoutes.learned(it,s.extras.walkSamples) ?: it.minutes } ?: 0,5) }.getOrNull()
            times?.let { t->val f=DateTimeFormatter.ofPattern("EEE HH:mm",Locale.ENGLISH).withZone(CapeTownZone)
                Text("Leave ${f.format(Instant.ofEpochMilli(t.leaveAt))} · arrive ${f.format(Instant.ofEpochMilli(t.arriveAt))}",style=MaterialTheme.typography.titleSmall)
                Text("Uses ${if(train.selectedJourney==null) "the first listed" else "the selected"} train and a 5-minute boarding buffer. Walking and train times are estimates, excluding unreported delays.",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(access!=null || egress!=null) TextButton(onClick={ uri.openUri("https://www.openstreetmap.org/fixthemap") }) { Text("Report a walking-map error") }
        listOf(TransportService.GOLDEN_ARROW,TransportService.MYCITI).filter { s.extras.beta.visible(it) }.forEach { service ->
            val from=s.access?.point ?: train.stations.firstOrNull { it.name==train.from && it.mapped }?.let { GeoPoint(it.latitude!!,it.longitude!!) }
            val to=s.destination?.point ?: train.stations.firstOrNull { it.name==train.to && it.mapped }?.let { GeoPoint(it.latitude!!,it.longitude!!) }
            val check=remember(s.snapshots[service],from,to) { BusConnections.direct(s.snapshots[service],from,to) }
            Text("${service.title}: ${check.message}",style=MaterialTheme.typography.bodySmall)
            check.routes.take(5).forEach { route -> TextButton(onClick={ m.chooseBus(service);model.edit { it.copy(requestedScreen="buses") };m.timetable(route,java.time.LocalDate.parse(train.date).dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)) }) { Text("Check route ${route.id} timetable") } }
            TextButton(onClick={ m.showTrainMap();model.search() },enabled=!train.loading && train.from.isNotBlank() && train.to.isNotBlank()) { Text("Find scheduled train alternative") }
        }
    }
    picker?.let { start -> ModalBottomSheet(onDismissRequest={ picker=null },sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(start) "Where do you leave from?" else "Where do you go after the station?",style=MaterialTheme.typography.titleLarge)
            TextButton(onClick={ choose(null,start) }) { Text("Just the station") }
            if(start) {
                TextButton(onClick={ permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text(if(train.locating) "Refreshing location…" else "Refresh my location") }
                train.currentPosition?.let { p->TextButton(onClick={ choose(WalkEndpoint("current","Current location",GeoPoint(p.latitude,p.longitude)),true) }) { Text("Use current location · accuracy ${p.accuracyMeters.toInt()} m") } }
            }
            Text("Mark a place on the map with a long press, or Map options → Mark map centre.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ picker=null;model.edit { it.copy(requestedScreen="map") } }) { Text("Open map to save a place") }
            LazyColumn(Modifier.heightIn(max=360.dp)) { items(s.extras.spots,key={ it.id }) { p->TextButton(onClick={ choose(WalkEndpoint("spot:${p.id}",p.name,p.point),start) }) { Text("${p.name} · ${p.tag.ifBlank { "Saved place" }}") } } }
        }
    } }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Calculate with OpenStreetMap routing?") },text={ Text("The FOSSGIS public walking service will receive both selected coordinates. Its server logs requests. Saved routes stay encrypted on this device and can be used offline; no Google API key or login is needed.") },confirmButton={ TextButton(onClick={ consent=false;m.walks(train) }) { Text("Calculate") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Cancel") } })
}
