@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.app.data.WalkingRepository
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.app.scheduling.*
import za.co.cttrains.core.*

private val JourneyDate = DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale.ENGLISH).withZone(CapeTownZone)
private val Clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(CapeTownZone)

@Composable
fun UpcomingScreen(state: TrainState, model: TrainViewModel) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val features: FeatureViewModel=androidx.lifecycle.viewmodel.compose.viewModel()
    val feature by features.state.collectAsStateWithLifecycle()
    var repeatEdit by remember { mutableStateOf<CommuteRoutine?>(null) }
    var removing by remember { mutableStateOf<ScheduledJourney?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val upcoming = state.scheduled.filter { it.departureAt > now }
    val past = state.scheduled.filter { it.departureAt <= now }.takeLast(20).reversed()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Your journeys", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("A little planning. A calmer commute.", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Text("Recurring journeys",style=MaterialTheme.typography.titleLarge);Text("Choose Repeat on a train or bus journey to set its weekdays.",style=MaterialTheme.typography.bodySmall) }
        items(feature.extras.routines,key={ "routine:${it.id}" }) { routine->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row { Text(routine.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(routine.enabled,{ features.toggleRoutine(routine.id) }) }
                Text("${routine.origin?.label ?: routine.query.from} → ${routine.destination?.label ?: routine.query.to} · ${routine.query.time}")
                Text(routine.days.sorted().joinToString { RoutineDayLabels[it-1] },style=MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick={ repeatEdit=routine }) { Text("Edit recurrence") }
                    TextButton(onClick={
                        val date=CommuteRoutines.dates(routine.copy(enabled=true),now).firstOrNull()?.toString() ?: java.time.LocalDate.now(CapeTownZone).toString()
                        features.plannerMode(routine.service)
                        features.place(routine.origin,true,if(routine.service==TransportService.PRASA) routine.query.from else null);features.place(routine.destination,false,if(routine.service==TransportService.PRASA) routine.query.to else null)
                        model.edit { it.copy(from=routine.query.from,to=routine.query.to,date=date,time=routine.query.time,requestedScreen="plan") }
                    }) { Text("Plan next journey") }
                    TextButton(onClick={ features.removeRoutine(routine.id) }) { Text("Remove recurrence") }
                }
            } }
        }
        items(feature.extras.trips.filter { it.id.startsWith("routine-") && it.steps.last().at>now },key={ "bus-repeat:${it.id}" }) { trip->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { Text("Recurring bus itinerary",style=MaterialTheme.typography.titleMedium);Text(TripFiles.text(trip));ShareJourneyButton(features) { trip } } }
        }
        if (upcoming.isEmpty()) item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.EventNote, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Make space for the next train", style = MaterialTheme.typography.titleLarge)
                    Text("Find a train in Plan, then save the journey. Reminders are optional and stay on this device.")
                }
            }
        }
        if (upcoming.any { it.pendingReminderAt != null } && !notificationsAllowed(context)) item {
            Text("App notifications are currently disabled. Your saved journeys are still available.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
        }
        items(upcoming, key = { it.id }) { item ->
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(JourneyDate.format(Instant.ofEpochMilli(item.departureAt)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { removing = item }) { Icon(Icons.Outlined.DeleteOutline, "Remove scheduled journey") }
                    }
                    Text("${item.query.from} → ${item.query.to}", style = MaterialTheme.typography.headlineSmall)
                    Text("${item.journey.departure}–${item.journey.arrival} · ${item.journey.durationMinutes} min · ${sourceLabel(item.sourceId)}", style = MaterialTheme.typography.bodySmall)
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Leave by ${JourneyDate.format(Instant.ofEpochMilli(item.leaveAt))}", fontWeight = FontWeight.Bold)
                            if (item.leaveFrom.isNotBlank()) Text("${item.leaveFrom} → ${item.query.from}", style = MaterialTheme.typography.bodyMedium)
                            Text("Your estimate: ${item.accessMinutes} min to the station + ${item.bufferMinutes} min buffer. No live walking or traffic estimate.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (item.reminderMinutes != null) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff, null, Modifier.size(18.dp))
                        Text(if (item.remindersStopped) "App reminders stopped" else item.pendingReminderAt?.let { "${if (item.reminderStage > 0) "Backup ${item.reminderStage}" else if (item.reminderBasis == "leave") "Leave reminder" else "Train reminder"} at ${JourneyDate.format(Instant.ofEpochMilli(it))} · ${item.activeSeverity}" } ?: "No app reminder", style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.backupReminders.isNotEmpty()) Text("Dismissal backups: " + item.backupReminders.joinToString { "${it.delayMinutes} min · ${it.severity}" }, style = MaterialTheme.typography.bodySmall)
                    if (item.pendingReminderAt != null) TextButton(onClick = { model.stopJourneyReminders(item.id) }) { Text("I'm leaving / stop app reminders") }
                    Text("Saved timetable · fetched ${JourneyDate.format(Instant.ofEpochMilli(item.fetchedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { model.openScheduled(item.id, "plan") }) { Text("Details") }
                        OutlinedButton(onClick = { model.openScheduled(item.id, "map") }) { Text("Map") }
                        TextButton(onClick={ repeatEdit=CommuteRoutine(java.util.UUID.randomUUID().toString(),"${item.query.from} → ${item.query.to}",item.query.copy(time=item.journey.departure),setOf(1,2,3,4,5),item.sourceId,item.accessMinutes,item.bufferMinutes,item.reminderMinutes,createdAt=now) }) { Text("Repeat journey") }
                        ShareJourneyButton(features) { TripFiles.fromJourney(item.query,item.journey,feature.extras.localName,item.accessMinutes,item.bufferMinutes,item.leaveFrom,"Destination",0,now,item.fetchedAt) }
                        TextButton(onClick = {
                            try { context.startActivity(android.content.Intent.createChooser(calendarIntent(item), "Add journey to calendar")) }
                            catch (_: ActivityNotFoundException) { model.showMessage("Install a calendar app to add this event.") }
                        }) { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add to calendar") }
                    }
                    TextButton(onClick = { uri.openUri(openStreetMapStationUrl(item.query.from)) }) { Text("Open departure station in OpenStreetMap") }
                }
            }
        }
        if (past.isNotEmpty()) {
            item { Text("Past plans", style = MaterialTheme.typography.titleLarge); Text("Saved plans are not proof that you travelled. Kept for 90 days.", style = MaterialTheme.typography.bodySmall) }
            items(past, key = { it.id }) { item ->
                ListItem(headlineContent = { Text("${item.query.from} → ${item.query.to}") }, supportingContent = { Text(JourneyDate.format(Instant.ofEpochMilli(item.departureAt))) },
                    trailingContent = { IconButton(onClick = { removing = item }) { Icon(Icons.Outlined.DeleteOutline, "Remove past plan") } })
            }
        }
        item { Text("Notifications use Android background scheduling and may be delayed by battery controls. Calendar reminders are managed separately in your calendar app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    repeatEdit?.let { RoutineEditor(it,features) { repeatEdit=null } }
    removing?.let { item ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove this journey?") }, text = { Text("Its app reminder will be cancelled. Calendar events you added remain in your calendar.") },
            confirmButton = { TextButton(onClick = { model.removeJourney(item.id); removing = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } })
    }
}

@Composable
fun JourneyScheduleSheet(query: SearchQuery, journey: Journey, model: TrainViewModel, initialAccess: Int=20, initialLeaveFrom: String="", accessEndpoint: WalkEndpoint?=null, initialWalk: WalkingLeg?=null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val features: FeatureViewModel=androidx.lifecycle.viewmodel.compose.viewModel()
    val feature by features.state.collectAsStateWithLifecycle()
    val trains by model.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val walker=remember(context) { WalkingRepository(context) }
    val boarding=trains.stations.firstOrNull { it.mapped && stationKey(it.name)==stationKey(query.from) }?.let(WalkingRoutes::endpoint)
    val origin=accessEndpoint ?: feature.access ?: boarding
    val known=initialWalk?.takeIf { it.from==origin && it.to==boarding } ?: feature.walks.firstOrNull { it.from==origin && it.to==boarding }
    var estimateConsent by remember { mutableStateOf(false) }
    var estimating by remember { mutableStateOf(false) }
    var estimateNote by remember { mutableStateOf<String?>(null) }
    var reminder by rememberSaveable { mutableStateOf(false) }
    var lead by rememberSaveable { mutableIntStateOf(0) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var customMinutes by rememberSaveable { mutableStateOf("30") }
    var access by rememberSaveable { mutableStateOf((known?.minutes ?: if(origin==boarding && boarding!=null) 0 else initialAccess).toString()) }
    var buffer by rememberSaveable { mutableStateOf("5") }
    var leaveFrom by rememberSaveable { mutableStateOf(initialLeaveFrom) }
    var severity by rememberSaveable { mutableStateOf("standard") }
    var backupMinutes by rememberSaveable { mutableStateOf(listOf<String>()) }
    var backupSeverity by rememberSaveable { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var repeat by rememberSaveable { mutableStateOf(false) }
    var repeatDays by remember { mutableStateOf(setOf(1,2,3,4,5)) }
    var saving by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        reminder = allowed && notificationsAllowed(context)
        if (!reminder) error = "Notifications are disabled. You can still save the journey."
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Make it a plan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${query.from} → ${query.to}\n${query.date} · ${journey.departure}–${journey.arrival}")
            Text("Save locally. Add to your calendar afterwards if you choose.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Leave reminder", fontWeight = FontWeight.Bold); Text("Based on your building-to-station estimate", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = reminder, onCheckedChange = { enabled ->
                    error = null
                    if (!enabled) reminder = false
                    else if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if (notificationsAllowed(context)) reminder = true
                    else error = "Enable app notifications in Android Settings to use reminders."
                })
            }
            if (reminder) {
                Text("Minutes before you need to leave", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 5, 10, 15, 30).forEach { minutes -> FilterChip(selected = !custom && lead == minutes, onClick = { custom = false; lead = minutes }, label = { Text(if (minutes == 0) "At leave time" else "$minutes min") }) }
                    FilterChip(selected = custom, onClick = { custom = true }, label = { Text("Custom") })
                }
                if (custom) MinuteField("Before leave time (0–1440 min)", customMinutes, { customMinutes = it })
                Text("First notification severity", style = MaterialTheme.typography.labelLarge)
                ReminderSeverity(severity) { severity = it }
                Text("Dismiss or swipe away to schedule the next backup. Tap I'm leaving to stop the chain. Backups stop after the train's departure; they do not guarantee you can still catch it.", style = MaterialTheme.typography.bodySmall)
                backupMinutes.forEachIndexed { index, value ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Backup ${index + 1}", style = MaterialTheme.typography.titleSmall)
                            MinuteField("Minutes after dismissal (1–60)", value, { v -> backupMinutes = backupMinutes.mapIndexed { i, old -> if (i == index) v else old } })
                            ReminderSeverity(backupSeverity[index]) { v -> backupSeverity = backupSeverity.mapIndexed { i, old -> if (i == index) v else old } }
                            TextButton(onClick = { backupMinutes = backupMinutes.filterIndexed { i, _ -> i != index }; backupSeverity = backupSeverity.filterIndexed { i, _ -> i != index } }) { Text("Remove backup") }
                        }
                    }
                }
                if (backupMinutes.size < 3) OutlinedButton(onClick = { backupMinutes = backupMinutes + "5"; backupSeverity = backupSeverity + "standard" }) { Text("Add backup reminder") }
                Text("Urgent notifications use Android's high-importance channel. Sound, heads-up display and Do Not Disturb follow your device settings.", style = MaterialTheme.typography.bodySmall)
                Text("Android may delay notifications under battery controls; these are not exact alarms.", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Repeat this journey",Modifier.weight(1f));Switch(repeat,{ repeat=it }) }
            if(repeat) { WeekdayChoices(repeatDays) { repeatDays=it };Text("Each travel date gets its own timetable check. Manage days and reminders in Journeys.",style=MaterialTheme.typography.bodySmall) }
            Text("When should you leave?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(leaveFrom, { if (it.length <= 80) leaveFrom = it }, label = { Text("Building / office / campus (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick={
                error=null
                when {
                    origin==null || boarding==null -> error="Choose a mapped start place in Plan first. A name alone does not give us its coordinates."
                    origin==boarding -> { access="0";estimateNote="Your start is the boarding station." }
                    known!=null -> { access=known.minutes.toString();leaveFrom=origin.label;estimateNote="Mapped walk · ${known.distanceMeters.toInt()} m · checked ${checked(known.fetchedAt)}" }
                    else -> estimateConsent=true
                }
            },enabled=!estimating) { Text(if(estimating) "Estimating walk…" else "Auto estimate leave time") }
            Text("Use your mapped start place to calculate the walk to ${query.from}. We subtract the walk and your boarding buffer from the train's departure. You can adjust either estimate.", style = MaterialTheme.typography.bodySmall)
            estimateNote?.let { Text(it,style=MaterialTheme.typography.labelSmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MinuteField("To station (min)", access, { access = it }, Modifier.weight(1f))
                MinuteField("Buffer (min)", buffer, { buffer = it }, Modifier.weight(1f))
            }
            val accessMinutes=access.toIntOrNull();val bufferMinutes=buffer.toIntOrNull()
            if(accessMinutes in 0..480 && bufferMinutes in 0..120) Text("Leave by ${Clock.format(Instant.ofEpochMilli(WalkingRoutes.times(query,journey,accessMinutes!!,0,bufferMinutes!!).leaveAt))}",style=MaterialTheme.typography.titleMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val a = access.toIntOrNull(); val b = buffer.toIntOrNull()
                val r = if (reminder) if (custom) customMinutes.toIntOrNull() else lead else null
                val backups = if (reminder) backupMinutes.mapIndexed { i, v -> BackupReminder(v.toIntOrNull() ?: 0, backupSeverity[i]) } else emptyList()
                if (repeat && repeatDays.isEmpty()) error="Choose at least one weekday."
                else if (a == null || b == null || (reminder && r == null)) error = "Enter valid whole minutes."
                else {
                    saving = true; error = null
                    model.schedule(query, journey, r, a, b, leaveFrom, severity, backups) { failure -> saving = false; if (failure == null) {
                        if(repeat) features.saveRoutine(CommuteRoutine(java.util.UUID.randomUUID().toString(),"${origin?.label ?: query.from} → ${feature.destination?.label ?: query.to}".take(100),query.copy(time=journey.departure),repeatDays,trains.result?.entry?.sourceId ?: trains.preferences.sourceId,a,b,r,origin,feature.destination,createdAt=System.currentTimeMillis()))
                        onDismiss()
                    } else error = failure }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (saving) "Saving…" else "Save journey") }
        }
    }
    if(estimateConsent) AlertDialog(onDismissRequest={ estimateConsent=false },title={ Text("Estimate the walk to your station?") },text={ Text("Your selected start and station coordinates go to FOSSGIS / OSRM. A saved route is used when available. Walking and boarding times are estimates.") },confirmButton={ TextButton(onClick={
        estimateConsent=false;estimating=true
        scope.launch {
            try { val (leg,cached)=walker.route(origin!!,boarding!!);access=leg.minutes.toString();leaveFrom=origin.label;estimateNote="${if(cached) "Saved" else "Mapped"} walk · ${leg.distanceMeters.toInt()} m · checked ${checked(leg.fetchedAt)}" }
            catch(e: Exception) { if(e is CancellationException) throw e;error="Could not calculate this walk. Retry or enter your own minutes; the app has not guessed a walking time." }
            finally { estimating=false }
        }
    }) { Text("Estimate walk") } },dismissButton={ TextButton(onClick={ estimateConsent=false }) { Text("Cancel") } })
}

@Composable
private fun MinuteField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { if (it.length <= 4 && it.all(Char::isDigit)) onChange(it) }, modifier = modifier, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun ReminderSeverity(value: String, onChange: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { JourneyPlanning.severities.forEach { level -> FilterChip(value == level, { onChange(level) }, label = { Text(level.replaceFirstChar(Char::uppercase)) }) } }
}
