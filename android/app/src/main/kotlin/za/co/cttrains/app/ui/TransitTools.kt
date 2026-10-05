@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
fun TransitTools(onAlerts: () -> Unit, onBudget: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CT Trains tools", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onAlerts, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.NotificationsActive, null); Spacer(Modifier.width(8.dp)); Text("Updates & delays") }
        FilledTonalButton(onClick = onBudget, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Calculate, null); Spacer(Modifier.width(8.dp)); Text("Fare & budget calculator") }
        OutlinedButton(onClick = { uri.openUri("https://cttrains.co.za/earlytrain1.php") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Report, null); Spacer(Modifier.width(8.dp)); Text("Early train reporter · website") }
        Text("Updates open inside the app. The early train reporter opens CT Trains in your browser; details are submitted only when you choose to send them there.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TransitToolsSheet(onDismiss: () -> Unit, onAlerts: () -> Unit, onBudget: () -> Unit,onBuses: ()->Unit={},onTrips: ()->Unit={},onCommunity: ()->Unit={},onWhatsApp: ()->Unit={},onDirections: ()->Unit={},onUpdates: ()->Unit={}) {
    ModalBottomSheet(onDismissRequest = onDismiss) { Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding()) {
        TransitTools(onAlerts, onBudget)
        FilledTonalButton(onClick=onDirections,modifier=Modifier.fillMaxWidth()) { Text("Journey directions · live notifications") }
        OutlinedButton(onClick=onUpdates,modifier=Modifier.fillMaxWidth()) { Text("App updates · GitHub") }
        OutlinedButton(onClick=onBuses,modifier=Modifier.fillMaxWidth()) { Text("Golden Arrow & MyCiTi · beta") }
        OutlinedButton(onClick=onTrips,modifier=Modifier.fillMaxWidth()) { Text("Safety trips · files & sharing") }
        OutlinedButton(onClick=onCommunity,modifier=Modifier.fillMaxWidth()) { Text("Community reports, chat & friends") }
        OutlinedButton(onClick=onWhatsApp,modifier=Modifier.fillMaxWidth()) { Text("WhatsApp updates · alpha") }
    } }
}
