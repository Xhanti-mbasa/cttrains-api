package za.co.cttrains.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.core.CapeTownZone
import za.co.cttrains.core.RideOutcome
import za.co.cttrains.core.awaitingRideAnswer

private val RideDate = DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale.ENGLISH).withZone(CapeTownZone)

@Composable
internal fun PendingRidePrompt(state: TrainState, model: TrainViewModel) {
    var resumed by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(resumed) {
        if (resumed) while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val item = state.scheduled.lastOrNull { it.profileId == state.preferences.profileId && it.awaitingRideAnswer(now) }
    if (resumed && item != null) {
        LaunchedEffect(item.id, item.reminderToken) { model.showRidePrompt(item) }
        AlertDialog(
            onDismissRequest = { model.answerRide(item.id, item.reminderToken, null) },
            title = { Text("Did you miss your ride?") },
            text = { Text("${item.query.from} → ${item.query.to}\n${RideDate.format(Instant.ofEpochMilli(item.departureAt))}") },
            confirmButton = {
                TextButton(onClick = { model.answerRide(item.id, item.reminderToken, RideOutcome.MISSED) }) { Text("Yes, show next trains") }
            },
            dismissButton = {
                TextButton(onClick = { model.answerRide(item.id, item.reminderToken, RideOutcome.CONFIRMED) }) { Text("No, I caught it") }
            },
        )
    }
}

@Composable
internal fun RideHistory(state: TrainState, model: TrainViewModel) {
    Text("Train rides", style = MaterialTheme.typography.headlineMedium)
    val recorded = state.scheduled.filter { it.profileId == state.preferences.profileId && it.rideOutcome != null }
    if (recorded.isEmpty()) Text("Confirmed, missed and likely onboard rides appear here.")
    recorded.asReversed().forEach { item ->
        val label = when (item.rideOutcome) {
            RideOutcome.LIKELY_ONBOARD -> "Likely onboard"
            RideOutcome.CONFIRMED -> "Ride confirmed"
            RideOutcome.MISSED -> "Missed ride"
            null -> ""
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${item.query.from} → ${item.query.to}", style = MaterialTheme.typography.titleMedium)
                Text("${RideDate.format(Instant.ofEpochMilli(item.departureAt))} · $label")
                if (item.rideOutcome == RideOutcome.LIKELY_ONBOARD) Row {
                    TextButton(onClick = { model.answerRide(item.id, item.reminderToken, RideOutcome.CONFIRMED) }) { Text("I caught it") }
                    TextButton(onClick = { model.answerRide(item.id, item.reminderToken, RideOutcome.MISSED) }) { Text("I missed it") }
                }
            }
        }
    }
}
