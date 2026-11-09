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
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import za.co.cttrains.app.*
import za.co.cttrains.core.*

@Composable fun BusPlanner(train: TrainState,model: TrainViewModel,s: FeatureState,m: FeatureViewModel,service: TransportService,onMap: ()->Unit) {
    LaunchedEffect(service) { if(s.bus!=service) m.chooseBus(service) }
    val snapshot=s.snapshots[service]
    val timetable=s.timetable?.takeIf { it.service==service }
    var query by rememberSaveable(service) { mutableStateOf("") }
    var fromId by rememberSaveable(service,timetable?.route?.id) { mutableStateOf("") }
    var toId by rememberSaveable(service,timetable?.route?.id) { mutableStateOf("") }
    var dateText by rememberSaveable(train.date) { mutableStateOf(train.date) }
    var timeText by rememberSaveable(train.time) { mutableStateOf(train.time) }
    var search by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var pinQuery by remember { mutableStateOf("") }
    var boardPin by remember(service,timetable?.route?.id,fromId) { mutableStateOf<BusStopPoint?>(null) }
    var alightPin by remember(service,timetable?.route?.id,toId) { mutableStateOf<BusStopPoint?>(null) }
    var consent by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }
    val uri=LocalUriHandler.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.locateOnce() }
    val day=runCatching { LocalDate.parse(dateText).dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase) }.getOrNull()
    val date=runCatching { LocalDate.parse(dateText) }.getOrNull()
    val after=runCatching { LocalTime.parse(timeText) }.getOrNull()
    val fromCall=timetable?.trips?.flatMap { it.calls }?.firstOrNull { it.id==fromId }
    val toCall=timetable?.trips?.flatMap { it.calls }?.firstOrNull { it.id==toId }
    fun linked(call: BusCall?)=s.extras.busStopLinks.lastOrNull { it.service==service && it.routeId==timetable?.route?.id && it.callId==call?.id }?.let { link -> snapshot?.stops?.firstOrNull { it.id==link.stopId } }
    val board=if(service==TransportService.MYCITI && snapshot!=null && fromCall!=null) BusPlanning.stop(fromCall,snapshot) else boardPin ?: linked(fromCall)
    val alight=if(service==TransportService.MYCITI && snapshot!=null && toCall!=null) BusPlanning.stop(toCall,snapshot) else alightPin ?: linked(toCall)
    val check=remember(snapshot,s.access,s.destination) { BusConnections.direct(snapshot,s.access?.point,s.destination?.point) }
    val calls=remember(timetable) { timetable?.trips?.flatMap { it.calls }?.distinctBy { it.id }.orEmpty() }
    val choices=remember(timetable,fromId,toId,train.time,train.date) { if(timetable!=null && after!=null) BusPlanning.choices(timetable,fromId,toId,train.time,date) else emptyList() }
    LaunchedEffect(fromId,toId,train.time,train.date,timetable) { search=false }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Plan with ${service.title}",style=MaterialTheme.typography.headlineSmall);Text("Choose a published route and actual timed boarding / alighting stops. Bus transfers are not automatically verified.",style=MaterialTheme.typography.bodySmall);FeatureStatus(s) }
        item {
            OutlinedButton(onClick={ picker="start" },modifier=Modifier.fillMaxWidth()) { Text("From: ${s.access?.label ?: "At boarding stop"}") }
            TextButton(onClick={ m.swapPlaces();model.edit { it.copy(from=it.to,to=it.from) };val old=fromId;fromId=toId;toId=old;val pin=boardPin;boardPin=alightPin;alightPin=pin }) { Text("Swap From and To") }
            OutlinedButton(onClick={ picker="finish" },modifier=Modifier.fillMaxWidth()) { Text("To: ${s.destination?.label ?: "At alighting stop"}") }
            OutlinedButton(onClick={ datePicker=true },modifier=Modifier.fillMaxWidth()) { Text("Travel date · $dateText") }
            OutlinedButton(onClick={ timePicker=true },modifier=Modifier.fillMaxWidth()) { Text("Bus departs after · $timeText") }
            if(date==null || after==null) Text("Enter a valid date and 24-hour time.",color=MaterialTheme.colorScheme.error)
            TextButton(onClick={ m.refresh(service,true) },enabled=service !in s.loading) { Text("Refresh ${service.title} data") }
            Text(check.message,style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ m.showTrainMap();model.edit { it.copy(requestedScreen="plan") };m.message("Select Trains above to plan a published rail alternative.") }) { Text("Train alternative available in Trains tab") }
        }
        item { OutlinedTextField(query,{ query=it.take(100) },label={ Text("Find a published bus route") },singleLine=true,modifier=Modifier.fillMaxWidth()) }
        val nearby=check.routes.map { it.id }.toSet()
        val routes=snapshot?.routes.orEmpty().filter { query.isNotBlank() && (it.title.contains(query,true) || it.id.contains(query,true)) || query.isBlank() && (nearby.isEmpty() || it.id in nearby) }.take(50)
        if(timetable==null) {
            if(routes.isEmpty()) item { Text("No route catalogue available. Open the operator source; mapped survey stops alone do not provide scheduled trips.") }
            items(routes,key={ it.id }) { route -> OutlinedCard(onClick={ if(day!=null && date!=null && route.validFor(date)) m.timetable(route,day) else m.message("This document is not valid for that date.") }) { Column(Modifier.padding(12.dp)) { Text(route.title);Text("Load ${day ?: "selected day"} timetable",style=MaterialTheme.typography.labelSmall) } } }
        } else {
            item {
                Text(timetable.route.title,style=MaterialTheme.typography.titleMedium)
                Text("${timetable.day} pattern · ${if(s.timetableCached) "saved" else "checked"} ${checked(timetable.fetchedAt)}",style=MaterialTheme.typography.labelSmall)
                TextButton(onClick={ m.chooseBus(service) }) { Text("Change route") }
                if(timetable.day!=day && day!=null) TextButton(onClick={ m.timetable(timetable.route,day) }) { Text("Load $day timetable") }
                OutlinedButton(onClick={ picker="board" },modifier=Modifier.fillMaxWidth()) { Text("Board: ${fromCall?.name ?: "Choose timed stop"}") }
                OutlinedButton(onClick={ picker="alight" },modifier=Modifier.fillMaxWidth()) { Text("Get off: ${toCall?.name ?: "Choose timed stop"}") }
                if(service==TransportService.GOLDEN_ARROW) {
                    Text("PDF timetable points cannot be automatically matched to surveyed pins. You may associate each timed point with a map stop yourself; the association stays unverified.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={ pinQuery="";picker="boardPin" },enabled=fromCall!=null) { Text("Boarding map point: ${board?.name ?: "not selected"}") }
                    TextButton(onClick={ pinQuery="";picker="alightPin" },enabled=toCall!=null) { Text("Alighting map point: ${alight?.name ?: "not selected"}") }
                } else if((fromCall!=null && board==null) || (toCall!=null && alight==null)) Text("The operator did not publish coordinates for this timed stop. Walking and map geometry remain unavailable.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ consent=true },enabled=!s.walkBusy && (s.access!=null && board!=null || s.destination!=null && alight!=null)) { Text(if(s.walkBusy) "Calculating walks…" else "Calculate access & final walk") }
                if(s.walkBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                s.walkError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                s.walks.filter { it.from.id=="bus:${service.name}:${alight?.id}" || it.to.id=="bus:${service.name}:${board?.id}" }.forEach { leg -> Text("${leg.from.label} → ${leg.to.label} · ${leg.distanceMeters.toInt()} m · ${leg.minutes} min estimated",style=MaterialTheme.typography.bodySmall) }
                Button(onClick={ search=true },enabled=fromId.isNotBlank() && toId.isNotBlank() && date!=null && after!=null && timetable.day==day && timetable.route.validFor(date)) { Text("Find ${service.title} routes") }
                TextButton(onClick={ m.favoriteBus(timetable.route,timetable.day) }) { Text("Save / unsave this route for regular refresh") }
                TextButton(onClick={ uri.openUri(timetable.route.url) }) { Text("Check operator timetable & holiday exceptions") }
            }
            if(search && choices.isEmpty()) item { Text("No published direct ${service.title} trip joins these timed stops in that order after ${train.time}. Try another route, an earlier time or a train alternative. No connection is assumed.") }
            if(search) items(choices.take(50),key={ it.first.id+":"+it.second.first }) { (trip,indices) ->
                val j=BusJourney(timetable,trip,indices.first,indices.second,board,alight,service==TransportService.GOLDEN_ARROW)
                Card { Column(Modifier.padding(16.dp)) {
                    Text("${j.departure.time} → ${j.arrival.time} · ${j.minutes} min",style=MaterialTheme.typography.titleMedium)
                    Text("${j.departure.name} → ${j.arrival.name}");Text("Direction ${trip.direction} · ${trip.notes}",style=MaterialTheme.typography.bodySmall)
                    val access=s.walks.firstOrNull { it.from==s.access && it.to.id=="bus:${service.name}:${board?.id}" }
                    val egress=s.walks.firstOrNull { it.to==s.destination && it.from.id=="bus:${service.name}:${alight?.id}" }
                    if((s.access==null || access!=null) && (s.destination==null || egress!=null)) {
                        val leave=LocalTime.parse(j.departure.time).minusMinutes(((access?.minutes ?: 0)+5).toLong());val arrive=LocalTime.parse(j.arrival.time).plusMinutes((egress?.minutes ?: 0).toLong())
                        Text("Leave $leave · arrive $arrive · 5-minute boarding buffer",style=MaterialTheme.typography.bodySmall)
                    } else Text("Calculate the selected walks for a door-to-door arrival estimate.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={ model.selectJourney(null);m.selectBus(j);onMap() },enabled=board!=null || alight!=null) { Text("View journey map") }
                    za.co.cttrains.app.directions.DirectionsButton({ za.co.cttrains.app.directions.DirectionsPlans.bus(j,JourneyComparison.requestedAt(dateText,j.departure.time),s.walks,timetable.fetchedAt) }) { model.selectJourney(null);m.selectBus(j);model.openDirections() }
                } }
            }
        }
        item { TextButton(onClick={ uri.openUri(if(service==TransportService.MYCITI) BusSources.MYCITI_DOWNLOADS else BusSources.GABS) }) { Text("Open ${service.title} source") } }
    }
    if(datePicker) TravelDatePicker(dateText,emptyList(),{ datePicker=false },trainOnly=false) { value->dateText=value;model.edit { it.copy(date=value) };datePicker=false }
    if(timePicker) DepartureTimePicker(timeText,{ timePicker=false },trainOnly=false) { value->timeText=value;model.edit { it.copy(time=value) };timePicker=false }
    picker?.let { which -> ModalBottomSheet(onDismissRequest={ picker=null },sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.padding(20.dp)) {
            Text(when(which) { "start"->"Where do you leave from?";"finish"->"Where do you go after the bus?";"board"->"Timed boarding stop";"alight"->"Timed alighting stop";else->"Choose surveyed map stop · unverified association" },style=MaterialTheme.typography.titleLarge)
            if(which in listOf("start","finish")) {
                val start=which=="start"
                TextButton(onClick={ m.place(null,start);picker=null }) { Text("Just the bus stop") }
                if(start) { TextButton(onClick={ permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Refresh my location") };train.currentPosition?.let { p->TextButton(onClick={ m.place(WalkEndpoint("current","Current location",GeoPoint(p.latitude,p.longitude)),true);picker=null }) { Text("Use current location · accuracy ${p.accuracyMeters.toInt()} m") } } }
                LazyColumn(Modifier.heightIn(max=400.dp)) { items(s.extras.spots,key={ it.id }) { spot->ListItem(headlineContent={ Text(spot.name) },supportingContent={ Text(spot.tag) },modifier=Modifier.clickable { m.place(WalkEndpoint("spot:${spot.id}",spot.name,spot.point),start);picker=null }) } }
            } else {
                OutlinedTextField(pinQuery,{ pinQuery=it.take(100) },label={ Text("Find stop") },singleLine=true,modifier=Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max=400.dp)) {
                    if(which in listOf("board","alight")) items(calls.filter { it.name.contains(pinQuery,true) },key={ it.id }) { call -> ListItem(headlineContent={ Text(call.name) },supportingContent={ Text("Timetable ID ${call.id}") },modifier=Modifier.clickable { if(which=="board") fromId=call.id else toId=call.id;pinQuery="";picker=null }) }
                    else items(snapshot?.stops.orEmpty().filter { it.name.contains(pinQuery,true) }.sortedBy { stop->val p=if(which=="boardPin") s.access?.point else s.destination?.point;p?.let { NearbySuggestions.distance(it,stop.point) } ?: 0.0 }.take(100),key={ it.id }) { stop -> ListItem(headlineContent={ Text(stop.name) },supportingContent={ Text("Surveyed point · current operation unverified") },modifier=Modifier.clickable { if(which=="boardPin") { boardPin=stop;m.confirmBusStop(timetable!!.route,fromCall!!,stop) } else { alightPin=stop;m.confirmBusStop(timetable!!.route,toCall!!,stop) };picker=null }) }
                }
            }
        }
    } }
    if(consent) AlertDialog(onDismissRequest={ consent=false },title={ Text("Calculate bus access walks?") },text={ Text("FOSSGIS / OpenStreetMap routing receives the selected coordinates. Routes are saved encrypted for offline use. Timetable-to-map associations you select for Golden Arrow remain unverified.") },confirmButton={ TextButton(onClick={ consent=false;m.busWalks(board,alight) }) { Text("Calculate") } },dismissButton={ TextButton(onClick={ consent=false }) { Text("Cancel") } })
}
