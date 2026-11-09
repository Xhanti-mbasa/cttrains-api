@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package za.co.cttrains.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel

@Composable
fun MapDownloadsSheet(state: TrainState, model: TrainViewModel, featureArea: za.co.cttrains.core.MapArea? = null, onDismiss: () -> Unit) {
    val downloads by model.offlineMaps.state.collectAsState()
    var deleting by remember { mutableStateOf<Long?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Offline street maps", style = MaterialTheme.typography.headlineSmall)
            Text("Download on Wi-Fi if possible. Maps use data and storage. Downloads pause when the app backgrounds; resume here. No account or key needed.")
            Text("Rail overview: zoom 8–12. Selected journey: street detail at zoom 10–14. The download includes map styles, fonts and tiles.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { model.downloadMap() }, enabled = !downloads.loading && !downloads.creating, modifier = Modifier.fillMaxWidth()) { Text("Download rail overview") }
            OutlinedButton(onClick = { model.downloadMap(state.selectedJourney) }, enabled = state.selectedJourney != null && !state.traceLoading && !downloads.loading && !downloads.creating, modifier = Modifier.fillMaxWidth()) { Text("Download selected journey") }
            featureArea?.let { area -> OutlinedButton(onClick={ model.downloadArea(area) },enabled=!downloads.loading && !downloads.creating,modifier=Modifier.fillMaxWidth()) { Text("Download ${area.name}") } }
            if (downloads.loading || downloads.creating) LinearProgressIndicator(Modifier.fillMaxWidth())
            downloads.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            downloads.regions.forEach { area ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(area.name, style = MaterialTheme.typography.titleMedium)
                        Text("${"%.1f".format(java.util.Locale.ENGLISH, area.bytes / 1048576.0)} MB · ${if (area.complete) "Ready offline" else if (area.active) "Downloading" else "Paused / incomplete"}")
                        if (!area.complete) {
                            if (area.required > 0) LinearProgressIndicator(progress = { (area.completed.toFloat() / area.required).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("${area.completed} / ${area.required.coerceAtLeast(area.completed)} resources", style = MaterialTheme.typography.labelSmall)
                        }
                        area.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (area.complete) TextButton(onClick = { model.useOfflineMap(area.id); onDismiss() }, enabled = !area.deleting) { Text("Use offline") }
                            else TextButton(onClick = { if (area.active) model.offlineMaps.pause(area.id) else model.resumeMap(area.id) }, enabled = !area.deleting) { Text(if (area.active) "Pause" else "Resume") }
                            TextButton(onClick = { deleting = area.id }, enabled = !area.deleting) { Text(if (area.deleting) "Deleting…" else "Delete") }
                        }
                    }
                }
            }
            Text("Up to 3 areas, 5,000 estimated tiles per area and a 200 MB download budget. Size may slightly exceed the budget while requests finish. Only completed areas are ready offline. No street-map coverage is promised outside the saved area/zoom range.", style = MaterialTheme.typography.bodySmall)
            Text("Public map caches and saved area bounds are not encrypted. They may reveal the areas you viewed. They are separate from encrypted journey records.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model.offlineMaps::clearCache) { Text("Clear temporary map cache") }
        }
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete saved map?") }, text = { Text("This area will need downloading again to use its street map offline. Station positions remain available.") }, confirmButton = { TextButton(onClick = { model.offlineMaps.delete(id); deleting = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }) }
}
