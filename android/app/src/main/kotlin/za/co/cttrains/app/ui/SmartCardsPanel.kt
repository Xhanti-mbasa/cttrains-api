@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package za.co.cttrains.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import za.co.cttrains.app.*
import za.co.cttrains.app.widgets.TravelWidgets
import za.co.cttrains.core.*

private val SmartClock = DateTimeFormatter.ofPattern("HH:mm").withZone(CapeTownZone)

@Composable
fun SmartCardsPanel(
    train: TrainState,
    model: TrainViewModel,
    feature: FeatureState,
    features: FeatureViewModel,
    smart: SmartCardsModel,
    now: Long,
    onStation: ((WalkEndpoint) -> Unit)? = null,
    onJourney: () -> Unit = {},
) {
    if (!feature.extras.beta.smartCards) return
    val state by smart.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) model.locateOnce()
        else model.showMessage("Choose a station in Plan, or enable location to use Smart Cards.")
    }
    fun locate() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) model.locateOnce()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    Column(Modifier.fillMaxWidth().testTag("smart-cards"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Smart Cards", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (train.currentPosition == null) locate()
                else smart.update(train, feature.extras, System.currentTimeMillis(), force = true)
            }, enabled = !state.loading && !train.locating) { Text("Refresh") }
        }
        Text("Current time ${SmartClock.format(Instant.ofEpochMilli(now))} · Cape Town", style = MaterialTheme.typography.labelSmall)
        if (state.nearby.isNotEmpty()) {
            Text("Closest stations", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.nearby.forEach { (station, meters) ->
                    val distance = if (meters < 1000) "$meters m" else String.format(Locale.ENGLISH, "%.1f km", meters / 1000.0)
                    AssistChip(onClick = {
                        val endpoint = WalkingRoutes.endpoint(station)
                        if (onStation != null) onStation(endpoint)
                        else {
                            features.place(null, true)
                            model.keepOrigin()
                            model.edit { it.copy(from = station.name, requestedScreen = "plan") }
                        }
                    }, label = { Text("${station.name} · $distance") }, modifier = Modifier.testTag("nearby-smart:${stationKey(station.name)}"))
                }
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (train.currentPosition == null) {
            FilledTonalButton(onClick = ::locate, enabled = !train.locating, modifier = Modifier.fillMaxWidth()) {
                Text(if (train.locating) "Finding your location…" else "Use location for Smart Cards")
            }
        }
        SmartCards.rank(state.cards, now).forEachIndexed { index, card ->
            ElevatedCard(onClick = {
                onJourney()
                smart.select(card)
            }, modifier = Modifier.fillMaxWidth().testTag("smart-card:$index")) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Current location → ${card.destinationLabel}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${card.journey.departure} from ${card.query.from}", style = MaterialTheme.typography.bodyLarge)
                    val etaMinutes = kotlin.math.ceil((card.arrivalAt - now) / 60_000.0).toInt()
                    Text("Scheduled ETA ${card.journey.arrival} at ${card.query.to} · $etaMinutes min from now", style = MaterialTheme.typography.bodyMedium)
                    Text("${card.journey.durationMinutes} min on train · about ${card.accessMinutes} min access walk · ${card.journey.transfers} changes", style = MaterialTheme.typography.bodySmall)
                    Text("${if (card.cached) "Saved timetable" else "Checked timetable"} · ${checked(card.fetchedAt)}", style = MaterialTheme.typography.labelSmall)
                    Text("Plan this ride", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text("Suggestions use Home, saved routes and confirmed rides. Walk times estimate proximity. ETA is at the destination station. Plan can check mapped walks.", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { model.edit { it.copy(requestedScreen = "places") } }) { Text("Set Home or change your places") }
    }
}

@Composable
fun SmartCardsSettings(feature: FeatureState, features: FeatureViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Home screen", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Smart Cards"); Text("Nearby stations and three journey suggestions.", style = MaterialTheme.typography.bodySmall) }
                Switch(feature.extras.beta.smartCards, { value -> features.beta { it.copy(smartCards = value) } }, Modifier.testTag("smart-cards-toggle"))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Show map on Home"); Text("Keep station cards and planning available with the map hidden.", style = MaterialTheme.typography.bodySmall) }
                Switch(feature.extras.beta.homeMap, { value -> features.beta { it.copy(homeMap = value) } }, Modifier.testTag("home-map-toggle"))
            }
        }
    }
}

@Composable
fun ExperimentalWidgetsSettings(feature: FeatureState, features: FeatureViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Experimental app widgets", fontWeight = FontWeight.Bold); Text("Android home-screen Smart Cards and your next saved trip.", style = MaterialTheme.typography.bodySmall) }
            Switch(feature.extras.beta.appWidgets, { value -> features.beta { it.copy(appWidgets = value) } }, Modifier.testTag("app-widgets-toggle"))
        }
        if (feature.extras.beta.appWidgets) {
            Text("Smart Cards use your last app location and saved suggestions. Open the app to refresh them. Android controls background update timing.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true to "Add Smart Cards widget", false to "Add next trip widget").forEach { (smart, label) ->
                    OutlinedButton(onClick = {
                        if (!TravelWidgets.pin(context, smart)) features.message("Add CT Trains from your launcher's Widgets menu.")
                    }) { Text(label) }
                }
            }
        }
    }
}
