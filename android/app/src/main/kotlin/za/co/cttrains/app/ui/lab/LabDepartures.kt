package za.co.cttrains.app.ui.lab

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.app.ui.JourneyScheduleSheet
import za.co.cttrains.core.Journey
import za.co.cttrains.core.JourneyComparison
import za.co.cttrains.core.LabTiming

/**
 * Missed-ride recovery. Opened after "I missed it": the shared model has already recorded the
 * answer and searched the next scheduled departures from the same station.
 */
@Composable
fun LabDeparturesScreen(state: TrainState, model: TrainViewModel, now: Long, onMap: (Journey) -> Unit, onAlerts: () -> Unit) {
    val c = LocalLab.current
    val context = LocalContext.current
    var planning by remember { mutableStateOf<Journey?>(null) }
    val entry = state.result?.entry
    val journeys = entry?.let { LabTiming.upcoming(it.journeys, it.query.date, now) }.orEmpty()

    LazyColumn(Modifier.fillMaxSize().background(c.ground).testTag("lab-departures"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (entry == null) "Next departures" else "Still get to ${entry.query.to}", style = LabType.title, color = c.ink,
                modifier = Modifier.semantics { heading() })
        }
        entry?.let { e ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("From ${e.query.from} · ${LabTiming.day(e.query.date)}", style = LabType.small, color = c.muted, modifier = Modifier.weight(1f))
                    if (state.result?.offline == true || LabTiming.stale(e.fetchedAt, now)) StaleChip("Checked ${LabTiming.age(e.fetchedAt, now)}")
                }
            }
        }
        when {
            state.loading -> item { Text("Checking the published timetable…", style = LabType.body, color = c.muted) }
            state.error != null -> item {
                LabStateCard("Couldn’t check", "No departures could be loaded", state.error ?: "",
                    "Try again" to { model.refreshRideDepartures(); Unit }, "Updates & delays" to onAlerts)
            }
            entry != null && journeys.isEmpty() -> item {
                LabStateCard("No more trains", "Nothing else is listed today from ${entry.query.from}",
                    "The published timetable has no later departure on this date. Check Updates & delays, or plan another route.",
                    "Updates & delays" to onAlerts)
            }
        }
        itemsIndexed(journeys, key = { _, j -> j.legs.joinToString { "${it.train}:${it.departure.time}" } }) { index, journey ->
            val departureAt = JourneyComparison.requestedAt(entry!!.query.date, journey.departure)
            val minutesAway = Math.floorDiv(departureAt - now, 60_000L)
            LabCard(Modifier.testTag("lab-departure-$index"), emphasised = index == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ScheduledTime(journey.departure, Modifier.weight(1f), large = false)
                    ModeBadge(LabMode.TRAIN, LabTiming.changes(journey.transfers))
                }
                Text("Arrive ${LabTiming.arrival(journey.arrival)} if on time · ${LabTiming.duration(journey.durationMinutes)}", style = LabType.small, color = c.muted)
                Text("Scheduled in $minutesAway min · you are at or near ${entry.query.from}", style = LabType.small, color = c.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabPrimaryButton("Switch to this train", { planning = journey }, Modifier.weight(1f), signal = index == 0)
                    LabSecondaryButton("Map", { onMap(journey) })
                }
            }
        }
        item {
            LabSecondaryButton("Tell someone I’m running late", {
                val text = journeys.firstOrNull()?.let { "Running late. Next scheduled train from ${entry?.query?.from} at ${it.departure}, arriving about ${it.arrival} if on time." }
                    ?: "Running late. I missed my train and I’m checking the next one."
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
            }, Modifier.fillMaxWidth())
        }
        item { LabSecondaryButton("Check Updates & delays", onAlerts, Modifier.fillMaxWidth()) }
        item {
            Text("These are published schedule times. The train may already have left or be delayed; nothing here tracks a live position.",
                style = LabType.small, color = c.muted)
        }
    }
    val query = entry?.query
    planning?.let { journey ->
        if (query != null) JourneyScheduleSheet(query, journey, model) { planning = null }
    }
}
