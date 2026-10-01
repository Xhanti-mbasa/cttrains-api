@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.core.*

private val AlertDate = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH).withZone(CapeTownZone)

@Composable
fun AlertsScreen(state: TrainState, model: TrainViewModel) {
    val uri = LocalUriHandler.current
    var group by rememberSaveable { mutableStateOf("All") }
    var search by rememberSaveable { mutableStateOf("") }
    var relevant by rememberSaveable { mutableStateOf(false) }
    val snapshot = state.alerts
    LaunchedEffect(Unit) { if (snapshot == null || System.currentTimeMillis() - snapshot.fetchedAt > 60_000) model.refreshAlerts() }
    val groups = listOf("All") + snapshot?.alerts.orEmpty().map { it.group }.distinct()
    val selected = group.takeIf { it in groups } ?: "All"
    val alerts = snapshot?.alerts.orEmpty().filter { alert ->
        (selected == "All" || selected == alert.group) && (search.isBlank() || "${alert.message} ${alert.group}".contains(search.trim(), true)) &&
            (!relevant || state.selectedJourney == null || ServiceAlerts.relevant(alert, state.selectedJourney))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Updates & delays", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = model::refreshAlerts, enabled = !state.alertsLoading) { Icon(Icons.Outlined.Refresh, "Refresh service alerts") }
            }
            Text("Published by CT Trains from Metrorail reports. Message times use South African time.", style = MaterialTheme.typography.bodySmall)
        }
        if (state.alertsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Refreshing alerts…", style = MaterialTheme.typography.labelSmall) }
        if (snapshot != null) item {
            Surface(color = if (state.alertsOffline || snapshot.sourceDate != LocalDate.now(CapeTownZone).toString()) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Report date: ${snapshot.sourceDate}", fontWeight = FontWeight.Bold)
                    Text("${if (state.alertsOffline) "Saved snapshot · refresh unavailable" else "Last fetched"} ${AlertDate.format(Instant.ofEpochMilli(snapshot.fetchedAt))}", style = MaterialTheme.typography.bodySmall)
                    if (snapshot.sourceDate != LocalDate.now(CapeTownZone).toString()) Text("These reports are from another date. They do not describe today's service.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.alertsError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        item {
            OutlinedTextField(search, { if (it.length <= 100) search = it }, label = { Text("Find a train, station or message") }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Outlined.Search, null) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { groups.forEach { name -> FilterChip(selected == name, { group = name }, label = { Text(name) }) } }
            if (state.selectedJourney != null) FilterChip(relevant, { relevant = !relevant }, label = { Text("For this journey") }, leadingIcon = { Icon(Icons.Outlined.Route, null, Modifier.size(18.dp)) })
        }
        if (alerts.isEmpty() && !state.alertsLoading) item {
            Text(if (snapshot == null) "No alert snapshot available. Refresh or open the source page." else if (snapshot.alerts.isEmpty()) "The source published no messages for this snapshot." else "No messages match these filters.")
        }
        items(alerts, key = { it.id }) { alert ->
            Card(colors = CardDefaults.cardColors(containerColor = if (alert.kind in listOf("Cancellation", "Disruption")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (alert.kind in listOf("Cancellation", "Disruption")) Icons.Outlined.WarningAmber else Icons.Outlined.Info, null, Modifier.size(20.dp))
                        Text(alert.group, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(alert.time, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(alert.kind, style = MaterialTheme.typography.labelMedium)
                    Text(alert.message)
                }
            }
        }
        item {
            Text("A lack of notices does not confirm that trains are running normally. Reports can be incomplete or late; confirm your service and platform at the station.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri(ServiceAlerts.URL) }) { Text("Open CT Trains source page") }
        }
    }
}
