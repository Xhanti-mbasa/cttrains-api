@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.FileProvider
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import za.co.cttrains.app.*
import za.co.cttrains.app.data.*
import za.co.cttrains.app.maps.*
import za.co.cttrains.core.*

private val Stamp=DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(CapeTownZone)
internal fun checked(at: Long)=if(at<=0) "not checked" else Stamp.format(Instant.ofEpochMilli(at))+" SAST"
internal fun money(cents: Long)="R"+String.format(Locale.ENGLISH,"%.2f",cents/100.0)
internal fun shareText(context: android.content.Context,text: String) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Share")) }

@Composable private fun Toggle(title: String,note: String,checked: Boolean,changed: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title,fontWeight=FontWeight.Bold);Text(note,style=MaterialTheme.typography.bodySmall) };Switch(checked,changed,Modifier.semantics { contentDescription=title }) }
}
@Composable internal fun FeatureStatus(s: FeatureState) {
    if(s.busy || s.timetableLoading || s.loading.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
    s.error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
}

@Composable fun BetaSettings(s: FeatureState,m: FeatureViewModel,onBuses: ()->Unit,onTrips: ()->Unit,onCommunity: ()->Unit) {
    Card { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Beta features",style=MaterialTheme.typography.titleLarge)
        Text("Choose each operator independently. Bus times are published schedules, not live vehicle tracking.")
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(12.dp)) {
            Toggle("Golden Arrow","Published PDFs and fares; surveyed map stops may be out of date.",s.extras.beta.goldenArrow) { v->m.beta { it.copy(goldenArrow=v) } }
            if(s.extras.beta.goldenArrow) TextButton(onClick={ m.chooseBus(TransportService.GOLDEN_ARROW);onBuses() }) { Text("Open Golden Arrow") }
        } }
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(12.dp)) {
            Toggle("MyCiTi","Operator’s stops, route shapes, day schedules and fare tables.",s.extras.beta.myCiti) { v->m.beta { it.copy(myCiti=v) } }
            if(s.extras.beta.myCiti) TextButton(onClick={ m.chooseBus(TransportService.MYCITI);onBuses() }) { Text("Open MyCiTi") }
        } }
        Text("Bus refresh interval · ${s.extras.beta.busRefreshMinutes} minutes")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf(15,30,60,120,240).forEach { minutes -> FilterChip(s.extras.beta.busRefreshMinutes==minutes,{ m.beta { it.copy(busRefreshMinutes=minutes) } },label={ Text("${minutes}m") }) } }
        Text("Android schedules checks when a connection is available. Last checked dates remain visible; a failed refresh keeps the saved version.",style=MaterialTheme.typography.bodySmall)
        Toggle("Nearby station suggestions","Use location while the map is open. Approximate location may be too broad for a station recommendation.",s.extras.beta.autoNearby) { v->m.beta { it.copy(autoNearby=v) } }
        Toggle("Hosted community & live trips","Optional Matrix account. Public reports are unverified; location sharing requires a separate start action.",s.extras.beta.hostedSharing) { v->m.beta { it.copy(hostedSharing=v) } }
        Toggle("Learn walking times","Start an individual walk to measure its duration in the background. No raw location history is saved.",s.extras.beta.walkLearning) { v->m.beta { it.copy(walkLearning=v) } }
        Toggle("Nearby users","Optional approximate presence in your selected Matrix service room. Requires a separate sharing start action.",s.extras.beta.nearbyUsers) { v->m.beta { it.copy(nearbyUsers=v) } }
        OutlinedButton(onClick=onTrips) { Text("Safety trip files") }
        OutlinedButton(onClick=onCommunity) { Text("Community reports & friends") }
    } }
}

@Composable fun ProfileSettings(s: FeatureState,m: FeatureViewModel) {
    var name by remember(s.extras.localName) { mutableStateOf(s.extras.localName) }
    var password by remember { mutableStateOf("") }
    var automatic by remember { mutableStateOf(false) }
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri->if(uri!=null) m.export(uri,password,automatic) }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->if(uri!=null) m.inspectBackup(uri,password) }
    Card { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Local profile & backup",style=MaterialTheme.typography.titleLarge)
        Text("A name is optional. It labels shared trips; it is not an online account.")
        OutlinedTextField(name,{ name=it.take(80) },label={ Text("Local profile name") },singleLine=true,modifier=Modifier.fillMaxWidth())
        TextButton(onClick={ m.localName(name) }) { Text("Save name") }
        Text("Choose an encrypted backup in Documents or another folder. This file can survive uninstalling. After reinstalling, select it again to restore; private app storage is removed on uninstall.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(password,{ password=it.take(256) },label={ Text("Backup password · at least 10 characters") },visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Toggle("Keep this file updated","Keep the password encrypted on this installation and update the file as local data changes. Remember the password for reinstalling.",automatic) { automatic=it }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={ create.launch("CTTrains-profile.ctprofile") },enabled=password.length>=10 && !s.busy) { Text("Save backup") }
            OutlinedButton(onClick={ open.launch(arrayOf("application/octet-stream","application/json","*/*")) },enabled=password.length>=10 && !s.busy) { Text("Restore file") }
            TextButton(onClick={ m.pauseBackup() }) { Text("Pause auto backup") }
        }
        Text("Includes preferences, saved journeys, trip files, pins/photos and cached schedules. Sign-in sessions are excluded. Restored reminders and live sharing stay paused. Map downloads can be downloaded again.",style=MaterialTheme.typography.bodySmall)
        GoogleProfileSettings(m)
    } }
    s.backup?.let { p -> AlertDialog(onDismissRequest=m::dismissImport,title={ Text("Replace local profile?") },text={ Text("Backup: ${checked(p.createdAt)}\n${p.plans.size} saved journeys and ${p.searches.size} timetable searches. This replaces current preferences and personal records. Sign-in sessions and pending reports will be cleared.") },confirmButton={ TextButton(onClick={ m.restore() }) { Text("Restore profile") } },dismissButton={ TextButton(onClick=m::dismissImport) { Text("Cancel") } }) }
}

@Composable fun MapSearchSheet(train: TrainState,layers: FeatureLayers,m: FeatureViewModel,onDismiss: ()->Unit,onStation: (String)->Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.padding(20.dp).imePadding()) {
            Text("Find a stop or place",style=MaterialTheme.typography.headlineSmall)
            OutlinedTextField(q,{ q=it.take(150) },label={ Text("Stations, bus stops and saved spots") },modifier=Modifier.fillMaxWidth())
            TextButton(onClick={ m.searchPlace(q);onDismiss() },enabled=q.trim().length>=3) { Text("Search places online · OpenStreetMap") }
            LazyColumn(Modifier.heightIn(max=420.dp)) {
                items(train.catalog.stations.filter { it.contains(q,true) }.take(100),key={ "rail:$it" }) { name->ListItem(headlineContent={ Text(name) },supportingContent={ Text("Train station") },modifier=Modifier.clickable { onStation(name) }) }
                items(layers.pins.filter { it.name.contains(q,true) }.take(100),key={ it.id }) { pin->ListItem(headlineContent={ Text(pin.name) },supportingContent={ Text(pin.service?.title ?: "Personal pin") },modifier=Modifier.clickable { m.selectPin(pin);onDismiss() }) }
            }
        }
    }
}

@Composable fun PinSheet(pin: MapPin,s: FeatureState,m: FeatureViewModel,onBus: (TransportService)->Unit) {
    val spot=s.extras.spots.firstOrNull { it.id==pin.spotId }
    var edit by remember(pin.id) { mutableStateOf(pin.id=="new") }
    var name by remember(pin.id) { mutableStateOf(pin.name) }
    var tag by remember(pin.id) { mutableStateOf(spot?.tag.orEmpty()) }
    var note by remember(pin.id) { mutableStateOf(spot?.note.orEmpty()) }
    var service by remember(pin.id) { mutableStateOf(spot?.service ?: pin.service) }
    var selectedPhoto by remember(pin.id) { mutableStateOf<Uri?>(null) }
    val context=LocalContext.current
    val cameraUri=remember(pin.id) { val f=File(context.cacheDir,"shared").apply { mkdirs() };FileProvider.getUriForFile(context,"${context.packageName}.files",File(f,"camera-${java.util.UUID.randomUUID()}.jpg")) }
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { selectedPhoto=it }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { if(it) selectedPhoto=cameraUri }
    val bitmap by produceState<android.graphics.Bitmap?>(null,spot?.photo) { value=spot?.photo?.let { m.files.bitmap(it) } }
    ModalBottomSheet(onDismissRequest={ m.selectPin(null) },sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()).imePadding(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(pin.name,style=MaterialTheme.typography.headlineSmall)
            Text("${pin.service?.title ?: "Personal location"} · ${pin.detail}",style=MaterialTheme.typography.bodySmall)
            Text(String.format(Locale.ENGLISH,"%.6f, %.6f",pin.point.latitude,pin.point.longitude),style=MaterialTheme.typography.labelSmall)
            pin.service?.takeIf { it!=TransportService.PRASA && s.extras.beta.enabled(it) }?.let { service -> TextButton(onClick={ onBus(service) }) { Text("${service.title} schedules & fares") } }
            bitmap?.let { Image(it.asImageBitmap(),"Your saved stop photo",Modifier.fillMaxWidth().heightIn(max=220.dp)) }
            if(spot!=null) { Text("${spot.tag}\n${spot.note}");TextButton(onClick={ edit=!edit }) { Text("Edit personal spot") };TextButton(onClick={ m.removeSpot(spot.id) }) { Text("Remove personal spot") } }
            if(!edit && !pin.id.startsWith("friend:")) OutlinedButton(onClick={ edit=true }) { Text("Save as a personal spot") }
            if(edit) {
                OutlinedTextField(name,{ name=it.take(100) },label={ Text("Spot name") },modifier=Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { FilterChip(service==null,{ service=null },label={ Text("Personal") });TransportService.values().forEach { v->FilterChip(service==v,{ service=v },label={ Text(v.title) }) } }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("Home","Work","Campus","Friend","Custom").forEach { label->FilterChip(tag==label,{ tag=label;service=null },label={ Text(label) }) } }
                OutlinedTextField(tag,{ tag=it.take(80) },label={ Text("Tag · stop, campus, home…") },modifier=Modifier.fillMaxWidth())
                OutlinedTextField(note,{ note=it.take(500) },label={ Text("Your note") },modifier=Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={ choose.launch("image/*") }) { Text(if(selectedPhoto==null) "Add photo" else "Photo selected") }
                    TextButton(onClick={ runCatching { camera.launch(cameraUri) }.onFailure { m.message("No camera app available. Choose an existing photo.") } }) { Text("Take photo") }
                }
                Text("Stored only on this device and your chosen encrypted backup. These pins do not change official stop data.",style=MaterialTheme.typography.bodySmall)
                Button(onClick={ m.saveSpot(name,tag,service,note,selectedPhoto) },enabled=name.isNotBlank() && !s.busy) { Text("Save spot locally") }
            }
        }
    }
}

@Composable fun BusScreen(s: FeatureState,m: FeatureViewModel,onTrip: ()->Unit,onMap: (TransportService,String?)->Unit) {
    val enabled=listOf(TransportService.GOLDEN_ARROW,TransportService.MYCITI).filter { s.extras.beta.enabled(it) }
    LaunchedEffect(enabled) { if(s.bus !in enabled && enabled.isNotEmpty()) m.chooseBus(enabled.first()) }
    var q by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf(LocalDate.now(CapeTownZone).dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)) }
    var tab by rememberSaveable { mutableStateOf("Routes") }
    val source=s.snapshots[s.bus]
    val uri=LocalUriHandler.current
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Bus schedules · beta",style=MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { enabled.forEach { service->FilterChip(s.bus==service,{ m.chooseBus(service) },label={ Text(service.title) }) } }
        if(enabled.isEmpty()) { Text("Enable Golden Arrow or MyCiTi in Settings → Beta features.");return@Column }
        FeatureStatus(s)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("Routes","Fares").forEach { v->FilterChip(tab==v,{ tab=v },label={ Text(v) }) };TextButton(onClick={ m.refresh(s.bus) },enabled=s.bus !in s.loading) { Text("Check source") } }
        Text("Catalog: ${checked(source?.catalogueCheckedAt ?: 0)} · Fares: ${checked(source?.faresCheckedAt ?: 0)}",style=MaterialTheme.typography.labelSmall)
        if(source!=null) { Text(source.mapNote,style=MaterialTheme.typography.bodySmall);if(source.notes.isNotEmpty()) Text(source.notes.joinToString("\n"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        if(tab=="Fares") BusFares(s,m,source) else {
            OutlinedTextField(q,{ q=it },label={ Text("Search route or stop name") },singleLine=true,modifier=Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { java.time.DayOfWeek.values().forEach { v->val title=v.name.lowercase().replaceFirstChar(Char::uppercase);FilterChip(day==title,{ day=title },label={ Text(title.take(3)) }) } }
            s.timetable?.let { t -> TextButton(onClick=onTrip) { Text("Save/share this bus trip") };Text(if(s.timetableCached) "Using saved ${t.day} timetable · ${checked(t.fetchedAt)}" else "Published ${t.day} pattern · checked ${checked(t.fetchedAt)}",style=MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val t=s.timetable
                if(t!=null && t.day==day) {
                    item { Text(t.route.title,fontWeight=FontWeight.Bold);Text("Holiday exceptions, road changes and boarding points must be checked with the operator. No automatic rail–bus transfer matching.",style=MaterialTheme.typography.bodySmall);TextButton(onClick={ uri.openUri(t.route.url) }) { Text("Open published timetable") };TextButton(onClick={ onMap(s.bus,t.route.id) }) { Text(if(s.bus==TransportService.MYCITI) "View this route on map" else "View surveyed Golden Arrow map") } }
                    if(t.trips.isEmpty()) item { Text("No supported timed trips were found for $day. Read the source document; untimed ‘via’ points are not given estimated times.") }
                    items(t.trips.take(250),key={ it.id }) { trip->Card { Column(Modifier.padding(12.dp)) { Text("${trip.calls.first().time} → ${trip.calls.last().time} · ${trip.direction}",fontWeight=FontWeight.Bold);trip.calls.forEach { call->Text("${call.time} · ${call.name}${call.platform?.let { " · Platform $it" }.orEmpty()}",style=MaterialTheme.typography.bodySmall) };if(trip.notes.isNotBlank()) Text(trip.notes,style=MaterialTheme.typography.labelSmall) } } }
                } else {
                    items(source?.routes.orEmpty().filter { it.title.contains(q,true) || it.id.contains(q,true) },key={ it.id }) { route -> Card { Column(Modifier.padding(12.dp)) {
                        Text(route.title,fontWeight=FontWeight.Bold);Text("Effective ${route.validFrom ?: "date not supplied"}${route.validUntil?.let { " through $it" }.orEmpty()}",style=MaterialTheme.typography.labelSmall)
                        FlowRow { TextButton(onClick={ m.timetable(route,day) },enabled=!s.busy) { Text("Load $day times") };TextButton(onClick={ m.favoriteBus(route,day) }) { Text(if(BusFavorite(s.bus,route.id,day) in s.extras.busFavorites) "Unsave" else "Save for refresh") };TextButton(onClick={ onMap(s.bus,route.id) }) { Text(if(s.bus==TransportService.MYCITI) "Map route" else "Survey map") };TextButton(onClick={ uri.openUri(route.url) }) { Text("Source") } }
                    } } }
                }
            }
        }
        TextButton(onClick={ uri.openUri(if(s.bus==TransportService.GOLDEN_ARROW) BusSources.GABS else BusSources.MYCITI_DOWNLOADS) }) { Text("Operator timetable website") }
    }
}

@Composable private fun BusFares(s: FeatureState,m: FeatureViewModel,source: BusSnapshot?) {
    var chosen by remember(s.bus) { mutableStateOf<BusFareRow?>(null) }
    var product by remember(s.bus) { mutableStateOf("") }
    var price by remember(s.bus) { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var filter by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Published products · ${source?.fareDate ?: "effective date not supplied"}")
        Text("MyCiTi uses fare distance bands, time and payment product. Golden Arrow publishes route-specific products. A weekly or monthly product is not a cash single fare. Choose the published product; the map does not infer a price from distance.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(filter,{ filter=it.take(100) },label={ Text("Find fare route / distance band") },modifier=Modifier.fillMaxWidth())
        val rows=source?.fares.orEmpty().filter { it.title.contains(filter,true) }
        if(rows.size>50) Text("Showing the first 50 of ${rows.size} products. Enter a route name to narrow the list.",style=MaterialTheme.typography.labelSmall)
        rows.take(50).forEach { row -> Card { Column(Modifier.padding(12.dp)) { Text(row.title,fontWeight=FontWeight.Bold);row.products.forEach { (name,cents)->
            val correction=s.extras.priceCorrections.firstOrNull { it.service==s.bus && it.fareId==row.id && it.product==name }
            TextButton(onClick={ chosen=row;product=name;price=((correction?.cents ?: cents)/100.0).toString() }) { Text("$name · ${money(cents)}${correction?.let { " · Your price ${money(it.cents)}" }.orEmpty()}") }
        };if(row.rules.isNotBlank()) Text(row.rules,style=MaterialTheme.typography.labelSmall) } } }
        Text("Your budget",style=MaterialTheme.typography.titleLarge)
        OutlinedTextField(chosen?.title ?: "Personal route/product",{ chosen=BusFareRow("personal:"+it.take(100),it.take(100),emptyMap()) },label={ Text("Route or fare band") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(product,{ product=it.take(100) },label={ Text("Product · single, weekly…") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(price,{ price=it.take(12) },label={ Text("Price in rand") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(quantity,{ quantity=it.filter(Char::isDigit).take(4) },label={ Text("Number of these products") },modifier=Modifier.fillMaxWidth())
        val cents=price.toBigDecimalOrNull()?.let { runCatching { it.movePointRight(2).longValueExact() }.getOrNull() }?.takeIf { it in 0..10_000_000 }
        val count=quantity.toIntOrNull()?.takeIf { it in 1..1000 }
        if(cents!=null && count!=null) Text("Budget: ${money(cents*count)}",style=MaterialTheme.typography.headlineSmall)
        Button(onClick={ m.correctFare(s.bus,chosen?.id ?: "personal",product,cents) },enabled=cents!=null && product.isNotBlank()) { Text("Save personal fare correction") }
        Text("Corrections stay on this device; they do not overwrite operator prices.",style=MaterialTheme.typography.bodySmall)
        s.extras.priceCorrections.filter { it.service==s.bus }.forEach { c -> Row(verticalAlignment=Alignment.CenterVertically) { Text("${c.product} · ${money(c.cents)}\n${c.fareId.substringAfter("personal:")}",Modifier.weight(1f));TextButton(onClick={ m.correctFare(c.service,c.fareId,c.product,null) }) { Text("Remove") } } }
    }
}

@Composable fun SafetyTripsScreen(train: TrainState,s: FeatureState,m: FeatureViewModel) {
    var owner by remember(s.extras.localName) { mutableStateOf(s.extras.localName.ifBlank { "Traveller" }) }
    var origin by rememberSaveable { mutableStateOf("Home / office / campus") }
    var destination by rememberSaveable { mutableStateOf("Destination") }
    var access by rememberSaveable { mutableStateOf("10") }
    var buffer by rememberSaveable { mutableStateOf("5") }
    var walk by rememberSaveable { mutableStateOf("10") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now(CapeTownZone).toString()) }
    var review by remember { mutableStateOf<SafetyTrip?>(null) }
    var busId by remember(s.timetable) { mutableStateOf(s.timetable?.trips?.firstOrNull()?.id) }
    val context=LocalContext.current
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->if(uri!=null) m.importFile(uri) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Safety trip files",style=MaterialTheme.typography.headlineMedium)
        Text("Share a planned itinerary, then your friend can import it. Quick Share/Nearby Share, WhatsApp and other file apps appear in Android’s share sheet. A trip file does not contain live location.")
        FeatureStatus(s)
        OutlinedButton(onClick={ open.launch(arrayOf("*/*")) }) { Text("Import trip / invitation / report") }
        OutlinedTextField(owner,{ owner=it.take(80) },label={ Text("Name on shared trip") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(origin,{ origin=it.take(180) },label={ Text("Leave from · shared with your friend") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(destination,{ destination=it.take(180) },label={ Text("Final building / destination") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(access,{ access=it.filter(Char::isDigit).take(3) },label={ Text("Minutes to boarding stop · your estimate") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(buffer,{ buffer=it.filter(Char::isDigit).take(3) },label={ Text("Boarding buffer · minutes") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(walk,{ walk=it.filter(Char::isDigit).take(3) },label={ Text("Minutes from final stop to destination") },modifier=Modifier.fillMaxWidth())
        val a=access.toIntOrNull();val b=buffer.toIntOrNull();val w=walk.toIntOrNull();val valid=a!=null && a in 0..360 && b!=null && b in 0..120 && w!=null && w in 0..360 && owner.isNotBlank()
        val journey=train.selectedJourney
        if(journey!=null) Button(onClick={ runCatching { TripFiles.fromJourney(train.result?.entry?.query ?: train.query,journey,owner,a!!,b!!,origin,destination,w!!,System.currentTimeMillis(),train.result?.entry?.fetchedAt) }.onSuccess { review=it }.onFailure { m.message("Check trip date and estimated access times.") } },enabled=valid) { Text("Review selected train trip") }
        else Text("Choose a train journey in Plan to create its safety file.",style=MaterialTheme.typography.bodySmall)
        s.timetable?.let { t ->
            Text("Bus trip · ${t.service.title}",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(date,{ date=it.take(10) },label={ Text("Travel date · YYYY-MM-DD · ${t.day}") },modifier=Modifier.fillMaxWidth())
            t.trips.take(100).forEach { trip->FilterChip(busId==trip.id,{ busId=trip.id },label={ Text("${trip.calls.first().time} · ${trip.direction.take(70)}") }) }
            val trip=t.trips.firstOrNull { it.id==busId }
            Button(onClick={ runCatching {
                val d=LocalDate.parse(date);require(d.dayOfWeek.name.equals(t.day,true) && t.route.validFor(d) && (trip?.validFrom==null || d>=LocalDate.parse(trip.validFrom)));val calls=trip!!.calls
                val base=d.atStartOfDay(CapeTownZone).toInstant().toEpochMilli();fun at(time: String)=base+LocalTime.parse(time).toSecondOfDay()*1000L
                val steps=listOf(TripStep("leave",null,origin,at(calls.first().time)-(a!!+b!!)*60_000L),TripStep("walk",null,"Reach ${calls.first().name}",at(calls.first().time)-b*60_000L),TripStep("board",t.service,calls.first().name,at(calls.first().time),calls.first().platform,trip.route),TripStep("alight",t.service,calls.last().name,at(calls.last().time),calls.last().platform,trip.route),TripStep("arrive",null,destination,at(calls.last().time)+w!!*60_000L))
                TripFiles.validate(SafetyTrip(id=java.util.UUID.randomUUID().toString(),owner=owner,createdAt=System.currentTimeMillis(),steps=steps,sourceCheckedAt=t.fetchedAt,note="Published ${t.day} pattern; confirm holiday exceptions and boarding stops. ${trip.notes}".take(500)))
            }.onSuccess { review=it }.onFailure { m.message("Use a travel date matching ${t.day} and the document’s effective dates. Check estimates.") } },enabled=valid && trip!=null) { Text("Review selected bus trip") }
        }
        Text("Saved itineraries",style=MaterialTheme.typography.titleLarge)
        s.extras.trips.reversed().forEach { trip -> Card { Column(Modifier.padding(12.dp)) { Text("${trip.owner} · ${trip.steps.first().label} → ${trip.steps.last().label}",fontWeight=FontWeight.Bold);Text(checked(trip.steps.first().at));FlowRow { TextButton(onClick={ review=trip }) { Text("Review / share") };TextButton(onClick={ m.deleteTrip(trip.id) }) { Text("Delete") } } } } }
    }
    review?.let { trip -> AlertDialog(onDismissRequest={ review=null },title={ Text("Review your shared itinerary") },text={ Text(TripFiles.text(trip),Modifier.verticalScroll(rememberScrollState())) },confirmButton={ Column { TextButton(onClick={ m.saveTrip(trip);m.share(TripFiles.json.encodeToString(trip),"cttrip","CT Trains safety trip");review=null }) { Text("Save & share file") };TextButton(onClick={ m.saveTrip(trip);shareText(context,TripFiles.text(trip));review=null }) { Text("Share formatted text") } } },dismissButton={ TextButton(onClick={ m.saveTrip(trip);review=null }) { Text("Save only") } }) }
}

@Composable fun ImportedFiles(s: FeatureState,m: FeatureViewModel) {
    s.importedTrip?.let { trip->AlertDialog(onDismissRequest=m::dismissImport,title={ Text("Import safety trip?") },text={ Text(TripFiles.text(trip),Modifier.verticalScroll(rememberScrollState())) },confirmButton={ TextButton(onClick=m::acceptTrip) { Text("Save itinerary") } },dismissButton={ TextButton(onClick=m::dismissImport) { Text("Cancel") } }) }
    s.importedCircle?.let { link->AlertDialog(onDismissRequest=m::dismissImport,title={ Text("Save live-trip invitation?") },text={ Text("${link.trip.owner}\nMatrix owner: ${link.ownerId}\nRoom: ${link.room}\nExpires: ${checked(link.expiresAt)}\n\nThis file contains a private trip key. Share it only with the intended friend. Watching requires Matrix login and a separate start action; importing does not share your own location.") },confirmButton={ TextButton(onClick={ m.acceptCircle() }) { Text("Save invitation") } },dismissButton={ TextButton(onClick=m::dismissImport) { Text("Cancel") } }) }
    s.importedReport?.let { report->AlertDialog(onDismissRequest=m::dismissImport,title={ Text("Save imported observation?") },text={ Text(CommunityReports.text(report)) },confirmButton={ TextButton(onClick={ m.acceptReport() }) { Text("Save as unverified") } },dismissButton={ TextButton(onClick=m::dismissImport) { Text("Cancel") } }) }
}
