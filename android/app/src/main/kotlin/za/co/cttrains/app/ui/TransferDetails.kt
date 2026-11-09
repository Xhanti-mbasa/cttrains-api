@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package za.co.cttrains.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.cttrains.core.TransferPoint

@Composable
fun TransferInstructions(point: TransferPoint, onAlerts: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${point.number} · Change at ${point.station}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Leave train ${point.arrivingTrain} at ${point.arrival}.")
        Text("Board train ${point.nextTrain} · ${point.nextLine}\nDeparts ${point.departure} · ${point.minutes} min connection", fontWeight = FontWeight.Bold)
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Arrive: ${point.arrivalPlatform?.let { "platform $it" } ?: "platform not published"}")
                Text("Board: ${point.departurePlatform?.let { "platform $it" } ?: "platform not published"}", fontWeight = FontWeight.Bold)
                Text(when (point.differentPlatform) { true -> "The published platforms differ. Change platforms before boarding."; false -> "The published platform number stays the same. You still need to change trains."; null -> "A platform change cannot be confirmed from the available information. Check station boards or ask staff." }, style = MaterialTheme.typography.bodySmall)
                point.noticeTime?.let { Text("Boarding platform from a CT Trains report at $it. Confirm at the station.", style = MaterialTheme.typography.labelSmall) }
            }
        }
        TextButton(onClick = onAlerts) { Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Check updates & platform notices") }
    }
}

@Composable
fun TransferDetailsSheet(points: List<TransferPoint>, onDismiss: () -> Unit, onAlerts: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(24.dp).navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            points.forEach { point -> TransferInstructions(point, onAlerts); HorizontalDivider() }
            Text("Times and platforms are source snapshots. The map does not show a verified walking route between platforms.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
    }
}
