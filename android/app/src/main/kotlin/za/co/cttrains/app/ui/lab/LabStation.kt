@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package za.co.cttrains.app.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import za.co.cttrains.app.StationBoardModel
import za.co.cttrains.app.StationBoardState
import za.co.cttrains.app.TrainState
import za.co.cttrains.core.*

/** Trains leaving one station: a next-hour counter, the departures behind it, and a train-number search. */
@Composable
fun LabStationScreen(state: TrainState, board: StationBoardState, model: StationBoardModel, initialStation: String?, onPlan: (from: String, to: String) -> Unit) {
    val c = LocalLab.current
    var station by rememberSaveable { mutableStateOf(initialStation?.takeIf { it in state.catalog.stations } ?: board.station.orEmpty()) }
    var typing by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    var wholeDay by rememberSaveable { mutableStateOf(false) }
    val sourceId = state.preferences.sourceId
    LaunchedEffect(station, state.catalog) { if (station.isNotBlank()) model.load(station, state.catalog, sourceId) }

    LazyColumn(Modifier.fillMaxSize().background(c.ground).testTag("lab-station"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(if (station.isBlank()) "Trains at a station" else "Trains at $station", style = LabType.title, color = c.ink, modifier = Modifier.semantics { heading() })
            Text("Published schedules, not live train positions. Today only.", style = LabType.small, color = c.muted)
        }
        item {
            OutlinedTextField(typing, { typing = it.take(40) }, label = { Text(if (station.isBlank()) "Choose a station" else "Change station") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("lab-station-search"))
            val matches = if (typing.isBlank()) emptyList() else FuzzyPlaces.rank(typing, state.catalog.stations) { it }.take(6)
            if (matches.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.forEach { name ->
                    AssistChip(onClick = { station = name; typing = ""; model.clearLookup(); model.load(name, state.catalog, sourceId, force = true) }, label = { Text(name) },
                        modifier = Modifier.heightIn(min = LabTouch).testTag("lab-station-choice:${stationKey(name)}"))
                }
            }
        }
        if (station.isNotBlank()) item { CounterCard(board, station) { model.load(station, state.catalog, sourceId, force = true) } }
        if (station.isNotBlank()) item {
            LabCard(Modifier.testTag("lab-train-search")) {
                LabSectionTitle("Find a train by number")
                Text("Shows where the train goes after $station and every stop with its scheduled time.", style = LabType.small, color = c.muted)
                OutlinedTextField(number, { number = it.filter { ch -> ch.isLetterOrDigit() }.take(7) }, label = { Text("Train number, e.g. 2501") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { model.findTrain(number, station, state.catalog, sourceId, wholeDay) }),
                    modifier = Modifier.fillMaxWidth().testTag("lab-train-number"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!wholeDay, { wholeDay = false }, label = { Text("From now") }, modifier = Modifier.heightIn(min = LabTouch))
                    FilterChip(wholeDay, { wholeDay = true }, label = { Text("Whole day") }, modifier = Modifier.heightIn(min = LabTouch).testTag("lab-train-whole-day"))
                }
                LabPrimaryButton(if (board.searching) "Searching…" else "Find train", { model.findTrain(number, station, state.catalog, sourceId, wholeDay) },
                    Modifier.fillMaxWidth().testTag("lab-train-find"), enabled = number.isNotBlank() && !board.searching)
                board.lookup?.takeIf { it.station == station }?.let { lookup ->
                    lookup.found?.let { DepartureCard(it, station, expanded = true, onPlan = onPlan) }
                        ?: Text(lookup.message.orEmpty(), style = LabType.small, color = c.signal, modifier = Modifier.testTag("lab-train-message"))
                }
            }
        }
        val next = board.nextHour.orEmpty().takeIf { board.station == station }.orEmpty()
        if (next.isNotEmpty()) item { LabSectionTitle("Leaving in the next hour") }
        items(next, key = { "${it.train}-${it.time}" }) { departure -> DepartureCard(departure, station, expanded = false, onPlan = onPlan) }
    }
}

@Composable
private fun CounterCard(board: StationBoardState, station: String, onRefresh: () -> Unit) {
    val c = LocalLab.current
    val count = board.nextHour?.takeIf { board.station == station }?.size
    LabCard(Modifier.testTag("lab-station-counter"), emphasised = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(count?.toString() ?: "–", style = LabType.title, color = c.ink, modifier = Modifier.testTag("lab-station-count"))
            Column(Modifier.weight(1f)) {
                Text(if (count == 1) "train leaving in the next hour" else "trains leaving in the next hour", style = LabType.bodyStrong, color = c.ink)
                Text(when {
                    board.loading -> "Checking the timetable…"
                    board.board != null -> "Scheduled · checked ${LabTiming.age(board.board.fetchedAt, System.currentTimeMillis())}"
                    else -> "Scheduled times"
                }, style = LabType.small, color = c.muted)
            }
        }
        board.issue?.let { Text(it, style = LabType.small, color = c.signal) }
        LabSecondaryButton("Refresh", onRefresh, Modifier.fillMaxWidth().testTag("lab-station-refresh"), enabled = !board.loading)
    }
}

@Composable
private fun DepartureCard(departure: StationDeparture, station: String, expanded: Boolean, onPlan: (String, String) -> Unit) {
    val c = LocalLab.current
    var open by remember(departure.train, departure.time) { mutableStateOf(expanded) }
    val past = runCatching { LocalTime.parse(departure.time).isBefore(LocalTime.now(CapeTownZone).withSecond(0)) }.getOrDefault(false)
    LabCard(Modifier.testTag("lab-departure:${departure.train}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScheduledTime(departure.time, large = false)
            Column(Modifier.weight(1f)) {
                Text("Train ${departure.train} · towards ${departure.towards}", style = LabType.bodyStrong, color = c.ink)
                Text(listOfNotNull(departure.line.takeIf { it.isNotBlank() }, departure.platform?.let { "platform $it" }, "departed".takeIf { past })
                    .joinToString(" · "), style = LabType.small, color = c.muted)
            }
        }
        TextButton(onClick = { open = !open }, Modifier.heightIn(min = LabTouch)) {
            Text(if (open) "Hide stops" else "${departure.stops.size - 1} stops after $station", color = c.ink)
        }
        if (open) {
            departure.stops.drop(1).forEach { stop ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stop.time, style = LabType.small, color = c.ink, modifier = Modifier.width(56.dp))
                    Text(stop.name, style = LabType.small, color = c.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onPlan(station, stop.name) }, Modifier.heightIn(min = LabTouch)) { Text("Plan", color = c.ink) }
                }
            }
            if (!departure.throughToEnd) Text("This search ends at ${departure.towards}; the train may continue further.", style = LabType.small, color = c.muted)
        }
    }
}
