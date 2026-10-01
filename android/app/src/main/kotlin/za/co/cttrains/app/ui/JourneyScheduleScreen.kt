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
    var removing by remember { mutableStateOf<ScheduledJourney?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val upcoming = state.scheduled.filter { it.departureAt > now }
    val past = state.scheduled.filter { it.departureAt <= now }.takeLast(20).reversed()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Your journeys", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("A little planning. A calmer commute.", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (upcoming.any { it.reminderMinutes != null } && !notificationsAllowed(context)) item {
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
                            Text("Your estimate: ${item.accessMinutes} min to the station + ${item.bufferMinutes} min buffer. No live walking or traffic estimate.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (item.reminderMinutes != null) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff, null, Modifier.size(18.dp))
                        Text(item.reminderAt?.let { "App reminder at ${JourneyDate.format(Instant.ofEpochMilli(it))}" } ?: "No app reminder", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Saved timetable · fetched ${JourneyDate.format(Instant.ofEpochMilli(item.fetchedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { model.openScheduled(item.id, "plan") }) { Text("Details") }
                        OutlinedButton(onClick = { model.openScheduled(item.id, "map") }) { Text("Map") }
                        TextButton(onClick = {
                            try { context.startActivity(android.content.Intent.createChooser(calendarIntent(item), "Add journey to calendar")) }
                            catch (_: ActivityNotFoundException) { model.showMessage("Install a calendar app to add this event.") }
                        }) { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add to calendar") }
                    }
                    TextButton(onClick = { uri.openUri(googleMapsJourneyUrl(item.query)) }) { Text("Compare directions in Google Maps") }
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
    removing?.let { item ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove this journey?") }, text = { Text("Its app reminder will be cancelled. Calendar events you added remain in your calendar.") },
            confirmButton = { TextButton(onClick = { model.removeJourney(item.id); removing = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } })
    }
}

@Composable
fun JourneyScheduleSheet(query: SearchQuery, journey: Journey, model: TrainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reminder by rememberSaveable { mutableStateOf(false) }
    var lead by rememberSaveable { mutableIntStateOf(15) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var customMinutes by rememberSaveable { mutableStateOf("30") }
    var access by rememberSaveable { mutableStateOf("20") }
    var buffer by rememberSaveable { mutableStateOf("5") }
    var error by remember { mutableStateOf<String?>(null) }
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
                Column(Modifier.weight(1f)) { Text("App reminder", fontWeight = FontWeight.Bold); Text("Optional train notification", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = reminder, onCheckedChange = { enabled ->
                    error = null
                    if (!enabled) reminder = false
                    else if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if (notificationsAllowed(context)) reminder = true
                    else error = "Enable app notifications in Android Settings to use reminders."
                })
            }
            if (reminder) {
                Text("Minutes before departure", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 45, 60).forEach { minutes -> FilterChip(selected = !custom && lead == minutes, onClick = { custom = false; lead = minutes }, label = { Text("$minutes") }) }
                    FilterChip(selected = custom, onClick = { custom = true }, label = { Text("Custom") })
                }
                if (custom) MinuteField("Reminder minutes (1–1440)", customMinutes, { customMinutes = it })
                Text("Android may delay notifications under battery controls; these are not exact alarms.", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("When should you leave?", style = MaterialTheme.typography.titleMedium)
            Text("Use your own travel estimate. We won’t guess your walking time or track you in the background.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MinuteField("To station (min)", access, { access = it }, Modifier.weight(1f))
                MinuteField("Buffer (min)", buffer, { buffer = it }, Modifier.weight(1f))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val a = access.toIntOrNull(); val b = buffer.toIntOrNull()
                val r = if (reminder) if (custom) customMinutes.toIntOrNull() else lead else null
                if (a == null || b == null || (reminder && r == null)) error = "Enter valid whole minutes."
                else {
                    saving = true; error = null
                    model.schedule(query, journey, r, a, b) { failure -> saving = false; if (failure == null) onDismiss() else error = failure }
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (saving) "Saving…" else "Save journey") }
        }
    }
}

@Composable
private fun MinuteField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { if (it.length <= 4 && it.all(Char::isDigit)) onChange(it) }, modifier = modifier, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
