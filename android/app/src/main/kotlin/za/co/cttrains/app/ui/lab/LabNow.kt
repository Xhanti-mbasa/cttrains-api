@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package za.co.cttrains.app.ui.lab

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.cttrains.app.FeatureState
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.app.SmartCardsModel
import za.co.cttrains.app.SmartCardsState
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.core.LabTiming
import za.co.cttrains.core.SavedAddresses
import za.co.cttrains.core.SavedRoute
import za.co.cttrains.core.ScheduledJourney
import za.co.cttrains.core.SmartJourney

/** Now: what to do next, with no input. Every time shown is a published schedule. */
@Composable
fun LabNowScreen(
    state: TrainState, model: TrainViewModel, feature: FeatureState, features: FeatureViewModel,
    smartState: SmartCardsState, smart: SmartCardsModel, now: Long,
    onMap: () -> Unit, onPlan: () -> Unit, onAlerts: () -> Unit, onPlaces: () -> Unit, onSettings: () -> Unit,
) {
    val c = LocalLab.current
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) { denied = false; model.locateOnce() } else denied = true
    }
    val next = LabTiming.nextPlan(state.scheduled, state.preferences.profileId, now)
    val home = feature.extras.spots.firstOrNull { SavedAddresses.role(it) == "Home" }
    val homeCard = home?.let { h -> smartState.cards.firstOrNull { it.destinationLabel == h.name } }
    val firstUse = feature.ready && feature.extras.spots.isEmpty() && state.preferences.routes.isEmpty() && state.scheduled.isEmpty()

    LazyColumn(Modifier.fillMaxSize().background(c.ground).testTag("lab-now"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Now", style = LabType.title, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                FilledTonalIconButton(onClick = onMap, Modifier.size(LabTouch),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = c.surface, contentColor = c.ink)) {
                    Icon(Icons.Outlined.Map, "Open map")
                }
            }
        }
        item { AlertsStrip(state, now, onAlerts) }
        when {
            next != null -> item(key = "next-${next.id}") { NextPlanCard(next, now, onTrip = { model.openScheduled(next.id) },
                onLater = { model.useRoute(SavedRoute(next.query.from, next.query.to)); onPlan() }) }
            firstUse -> item(key = "first-use") {
                LabStateCard("First use", "Where do you usually go?",
                    "Set Home and Work once and Now shows your next train and when to leave. Places stay on this phone. " +
                        "CT Trains Lab is an independent app, not affiliated with Metrorail, PRASA or CT Trains.",
                    "Set Home" to onPlaces, "Just search" to onPlan)
            }
        }
        if (firstUse) item {
            TextButton(onClick = onSettings, Modifier.heightIn(min = LabTouch)) { Text("Restore a backup from CT Trains", color = c.ink) }
        }
        items(smartState.cards.filter { it.id != homeCard?.id }.take(3), key = { "smart-${it.id}" }) { card ->
            SuggestionCard(card, now) { smart.select(card) }
        }
        item {
            ActionRow(Icons.Outlined.Home, "Get home", when {
                home == null -> "Set your Home place first"
                homeCard != null -> "${homeCard.journey.departure} sched. from ${homeCard.query.from}"
                else -> "Plan a trip to ${home.name}"
            }, Modifier.testTag("lab-get-home")) {
                when {
                    home == null -> onPlaces()
                    homeCard != null -> smart.select(homeCard)
                    else -> onPlan()
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Stacked, not side by side: at large font sizes a shared row squeezes the title to one letter per line.
                LabSectionTitle("Near you")
                Text("Uses one location sample, forgotten after 5 minutes.", style = LabType.small, color = c.muted)
                when {
                    !feature.extras.beta.smartCards -> LabStateCard("Off", "Nearby stations are turned off",
                        "Turn on Smart Cards to see the closest stations and suggested trains. Location is only sampled when you ask.",
                        "Turn on" to { features.beta { it.copy(smartCards = true) }; Unit }, "Search stations" to onPlan)
                    smartState.nearby.isNotEmpty() -> Column(Modifier.fillMaxWidth().background(c.surface, LabCardShape)) {
                        smartState.nearby.forEachIndexed { index, (station, meters) ->
                            if (index > 0) HorizontalDivider(color = c.line)
                            ListItem(
                                headlineContent = { Text(station.name, style = LabType.bodyStrong, color = c.ink) },
                                supportingContent = { Text(if (meters < 1000) "$meters m" else "%.1f km".format(meters / 1000.0), style = LabType.small, color = c.muted) },
                                leadingContent = { ModeBadge(LabMode.TRAIN) },
                                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted) },
                                colors = ListItemDefaults.colors(containerColor = c.surface),
                                modifier = Modifier.heightIn(min = 56.dp).clickableRow("Plan from ${station.name}") {
                                    model.edit { it.copy(from = station.name) }; onPlan()
                                },
                            )
                        }
                    }
                    denied -> LabStateCard("Location not allowed", "Pick your station instead",
                        "Near you needs one location sample while the app is open. Everything else works without it.",
                        "Search stations" to onPlan, "Allow in Android settings" to {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))); Unit
                        })
                    state.locating -> Text("Finding nearby stations…", style = LabType.body, color = c.muted)
                    else -> {
                        smartState.message?.let { Text(it, style = LabType.small, color = c.muted) }
                        LabSecondaryButton("Use my location once", {
                            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, Modifier.fillMaxWidth().testTag("lab-locate"))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsStrip(state: TrainState, now: Long, onAlerts: () -> Unit) {
    val c = LocalLab.current
    val snapshot = state.alerts
    val count = snapshot?.alerts?.size ?: 0
    val text = when {
        snapshot == null -> "Updates & delays · check published notices"
        count == 0 -> "No published notices · checked ${LabTiming.age(snapshot.fetchedAt, now)}"
        else -> "$count published ${if (count == 1) "notice" else "notices"} · checked ${LabTiming.age(snapshot.fetchedAt, now)}"
    }
    Surface(onClick = onAlerts, modifier = Modifier.fillMaxWidth().heightIn(min = LabTouch).testTag("lab-alerts"), shape = LabControlShape,
        color = if (count > 0) c.staleBg else c.surface) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.WarningAmber, null, tint = if (count > 0) c.staleInk else c.muted)
            Text(text, style = LabType.small, color = if (count > 0) c.staleInk else c.ink, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = if (count > 0) c.staleInk else c.muted)
        }
    }
}

@Composable
private fun NextPlanCard(plan: ScheduledJourney, now: Long, onTrip: () -> Unit, onLater: () -> Unit) {
    val c = LocalLab.current
    val r = LabTiming.reachability(plan.departureAt, plan.accessMinutes, plan.bufferMinutes, now)
    LabCard(Modifier.testTag("lab-next-plan")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LabSectionTitle("Next · ${LabTiming.day(plan.query.date)}", Modifier.weight(1f))
            LeaveByChip(plan.leaveAt)
        }
        ScheduledTime(plan.journey.departure)
        Text("${plan.query.from} → ${plan.query.to}", style = LabType.route, color = c.ink)
        Text("${LabTiming.changes(plan.journey.transfers).replaceFirstChar { it.uppercase() }} · arrive ${LabTiming.arrival(plan.journey.arrival)} if on time",
            style = LabType.small, color = c.muted)
        FeasibilityLine(r)
        Text("${plan.accessMinutes} min to the station (your estimate) + ${plan.bufferMinutes} min buffer", style = LabType.small, color = c.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabPrimaryButton("Trip & reminder", onTrip, Modifier.weight(1f))
            LabSecondaryButton("Later trains", onLater, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SuggestionCard(card: SmartJourney, now: Long, onOpen: () -> Unit) {
    val c = LocalLab.current
    val r = LabTiming.reachability(card.departureAt, card.accessMinutes, 5, now)
    LabCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabSectionTitle("Suggested · to ${card.destinationLabel}", Modifier.weight(1f))
            if (card.cached || LabTiming.stale(card.fetchedAt, now)) StaleChip("Checked ${LabTiming.age(card.fetchedAt, now)}")
        }
        ScheduledTime(card.journey.departure, large = false)
        Text("${card.query.from} → ${card.query.to}", style = LabType.bodyStrong, color = c.ink)
        Text("${LabTiming.changes(card.journey.transfers).replaceFirstChar { it.uppercase() }} · arrive ${LabTiming.arrival(card.journey.arrival)} if on time",
            style = LabType.small, color = c.muted)
        FeasibilityLine(r)
        LabSecondaryButton("Plan this ride", onOpen, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalLab.current
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), shape = LabControlShape, color = c.surface) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = c.ink)
            Column(Modifier.weight(1f)) {
                Text(title, style = LabType.bodyStrong, color = c.ink, fontWeight = FontWeight.Bold)
                Text(subtitle, style = LabType.small, color = c.muted)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.muted)
        }
    }
}
