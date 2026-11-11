package za.co.cttrains.app.ui.lab

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import za.co.cttrains.app.FeatureState
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.app.PlannerOption
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.app.directions.DirectionsModel
import za.co.cttrains.app.directions.DirectionsPlans
import za.co.cttrains.app.ui.JourneyScheduleSheet
import za.co.cttrains.app.ui.ShareJourneyButton
import za.co.cttrains.app.ui.money
import za.co.cttrains.core.*

private data class TripStep(val time: String, val title: String, val detail: String, val kind: Kind, val stops: List<za.co.cttrains.core.Stop> = emptyList())
private enum class Kind { LEAVE, BOARD, CHANGE, ARRIVE, FINISH }

/** One trip, laid out as a timeline with saving and reminders on the same page. */
@Composable
fun LabTripScreen(
    option: PlannerOption, from: WalkEndpoint?, to: WalkEndpoint?, state: TrainState, model: TrainViewModel,
    feature: FeatureState, features: FeatureViewModel, directions: DirectionsModel, onMap: () -> Unit, onDirections: () -> Unit, onSaved: () -> Unit,
) {
    val c = LocalLab.current
    val now = System.currentTimeMillis()
    val access = option.accessMinutes(from)
    val leaveAt = option.leaveAt(from)
    val egress = option.walks.lastOrNull { it.to == to }?.minutes
    var saved by remember(option.id) { mutableStateOf<String?>(null) }
    var error by remember(option.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var moreOptions by remember { mutableStateOf(false) }
    var pendingReminder by remember { mutableStateOf(false) }
    val fareText = option.fares.firstOrNull()?.let { "Single ${money(it.cents)}" }

    fun present() {
        val rail = option.rail ?: return
        model.presentPlannedJourney(option.query!!, rail, option.fetchedAt, option.cached, fareText, option.sourceId, false)
    }
    fun save(reminder: Int?) {
        val rail = option.rail ?: return
        present(); busy = true; error = null
        model.schedule(option.query!!, rail, reminder, access ?: 0, 5, from?.label.orEmpty(), "standard", emptyList()) { problem ->
            busy = false
            if (problem == null) saved = if (reminder == null) "Saved to your trips." else "Saved. We’ll remind you at ${leaveAt?.let(LabTiming::clock) ?: "leave time"}."
            else error = problem
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (pendingReminder) { pendingReminder = false; if (granted) save(0) else error = "Notifications are off, so a reminder can’t ring. You can still save the trip." }
    }

    val steps = remember(option.id, from, to) {
        buildList {
            leaveAt?.let { add(TripStep(LabTiming.clock(it), "Leave ${from?.label ?: "now"}", if (access == 0) "Be on the platform 5 minutes early" else "Walk $access min · 5 min buffer", Kind.LEAVE)) }
            val rail = option.rail
            if (rail != null) rail.legs.forEachIndexed { i, leg ->
                val platform = leg.departure.platform?.let { "platform $it" } ?: "platform unknown"
                val title = if (i == 0) leg.departure.name else "Change at ${leg.departure.name}"
                val detail = buildString {
                    if (i > 0) append("${rail.connectionMinutes(i)} min to change · ")
                    append("Train ${leg.train}${if (leg.line.isNotBlank()) " · ${leg.line}" else ""} · $platform")
                }
                add(TripStep(leg.departure.time, title, detail, if (i == 0) Kind.BOARD else Kind.CHANGE, leg.stops))
            } else option.bus?.let { bus ->
                add(TripStep(bus.departure.time, bus.departure.name, "${option.service.labMode().label} · ${bus.timetable.route.title}", Kind.BOARD))
            }
            val arrive = option.rail?.arrival ?: option.bus?.arrival?.time
            arrive?.let { add(TripStep(LabTiming.arrival(it), option.alight, if (egress != null && egress > 0) "If on time · then walk $egress min" else "If on time", Kind.ARRIVE)) }
            if (egress != null && egress > 0) option.estimate.arrivalAt?.let { add(TripStep(LabTiming.arrival(LabTiming.clock(it)), to?.label ?: "Destination", fareText ?: "", Kind.FINISH)) }
        }
    }

    Column(Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp).testTag("lab-trip"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("To ${to?.label ?: option.alight}", style = LabType.title, color = c.ink, modifier = Modifier.semantics { heading() })
            Text("${option.query?.date?.let(LabTiming::day) ?: ""} · scheduled times · checked ${LabTiming.age(option.fetchedAt, now)}",
                style = LabType.small, color = c.muted)
            if (option.cached) StaleChip("Saved timetable, refresh may differ")
        }
        Surface(onClick = onMap, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("lab-trip-map"), shape = LabCardShape, color = c.chip) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Map, null, tint = c.ink)
                Column(Modifier.weight(1f)) {
                    Text("Show on map", style = LabType.bodyStrong, color = c.ink)
                    Text("Approximate route, not a live position", style = LabType.small, color = c.muted)
                }
            }
        }
        LabCard {
            steps.forEachIndexed { i, step -> TimelineRow(step, last = i == steps.lastIndex) }
            if (steps.isEmpty()) Text("This trip has no timed stops to show.", style = LabType.small, color = c.muted)
            Row {
                Text("Fare", style = LabType.small, color = c.muted, modifier = Modifier.weight(1f))
                Text(option.fares.firstOrNull()?.let { "${money(it.cents)} · ${it.label}" } ?: "Confirm at the ticket office", style = LabType.small, color = c.ink)
            }
            if (option.notice.isNotBlank()) Text(option.notice, style = LabType.small, color = c.muted)
        }

        if (option.rail != null) LabCard(Modifier.testTag("lab-trip-save")) {
            LabSectionTitle("Save & remind")
            when {
                saved != null -> {
                    Text(saved!!, style = LabType.bodyStrong, color = c.ink)
                    LabSecondaryButton("See saved trips", onSaved, Modifier.fillMaxWidth())
                }
                else -> {
                    Text(leaveAt?.let { "Leave by ${LabTiming.clock(it)} to make the ${option.rail?.departure}." }
                        ?: "Turn on walking times in Plan for a personal leave time. Without it, the reminder rings at departure minus 5 minutes.",
                        style = LabType.small, color = c.muted)
                    LabPrimaryButton(if (busy) "Saving…" else "Remind me when to leave", {
                        if (Build.VERSION.SDK_INT >= 33) { pendingReminder = true; notifications.launch(Manifest.permission.POST_NOTIFICATIONS) } else save(0)
                    }, Modifier.fillMaxWidth().testTag("lab-trip-remind"), signal = true, enabled = !busy)
                    LabSecondaryButton("Save without a reminder", { save(null) }, Modifier.fillMaxWidth().testTag("lab-trip-save-only"), enabled = !busy)
                    TextButton(onClick = { present(); moreOptions = true }, Modifier.heightIn(min = LabTouch)) {
                        Text("Repeat on weekdays, backups, calendar, boarding detection…", color = c.ink)
                    }
                }
            }
            error?.let { Text(it, style = LabType.small, color = c.signal) }
        } else LabCard {
            LabSectionTitle("Bus trips")
            Text("Published ${option.service.labMode().label} timetable. Confirm the stop and current operation before you travel; saving bus trips is coming to Lab next.",
                style = LabType.small, color = c.muted)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                directions.preview({ DirectionsPlans.option(option, model.state.value, feature) }) {
                    if (option.rail != null) { features.showTrainMap(); features.usePlannedWalks(option.walks); present() }
                    else { model.selectJourney(null); features.selectBus(option.bus!!); features.usePlannedWalks(option.walks) }
                    onDirections()
                }
            }, Modifier.weight(1f).heightIn(min = LabTouch), shape = LabControlShape) {
                Icon(Icons.Outlined.Navigation, null, tint = c.ink); Spacer(Modifier.width(6.dp)); Text("Directions", color = c.ink)
            }
            ShareJourneyButton(features) {
                JourneySharing.itinerary(DirectionsPlans.option(option, model.state.value, feature), option.service, feature.extras.localName, System.currentTimeMillis())
            }
        }
        Text("Times are published schedules. Trains can leave early or late and platforms can change; check Updates & delays.",
            style = LabType.small, color = c.muted)
    }
    if (moreOptions) option.rail?.let { rail ->
        JourneyScheduleSheet(option.query!!, rail, model, initialAccess = access ?: 0, initialLeaveFrom = from?.label.orEmpty(),
            accessEndpoint = from, initialWalk = option.walks.firstOrNull { it.from == from }) { moreOptions = false }
    }
}

@Composable
private fun TimelineRow(step: TripStep, last: Boolean) {
    val c = LocalLab.current
    var open by remember { mutableStateOf(false) }
    val dotColor = when (step.kind) { Kind.LEAVE -> c.signal; Kind.FINISH -> c.ink; else -> c.train }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(step.time, style = LabType.timeSmall, color = if (step.kind == Kind.LEAVE) c.signal else c.ink, softWrap = false, modifier = Modifier.width(72.dp))
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Dot(dotColor, if (step.kind == Kind.BOARD || step.kind == Kind.CHANGE || step.kind == Kind.ARRIVE) 14.dp else 12.dp, hollow = step.kind != Kind.LEAVE && step.kind != Kind.FINISH)
            if (!last) Box(Modifier.width(if (step.kind == Kind.LEAVE || step.kind == Kind.ARRIVE) 2.dp else 5.dp).weight(1f)
                .background(if (step.kind == Kind.LEAVE || step.kind == Kind.ARRIVE) c.muted else c.train))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = if (last) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.title, style = LabType.bodyStrong, color = c.ink, fontWeight = FontWeight.Bold)
            if (step.detail.isNotBlank()) Text(step.detail, style = LabType.small, color = c.muted)
            val middle = step.stops.drop(1).dropLast(1)
            if (middle.isNotEmpty()) {
                TextButton(onClick = { open = !open }, Modifier.heightIn(min = LabTouch), contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (open) "Hide ${middle.size} stops" else "${middle.size} stops on the way", color = c.ink)
                }
                if (open) middle.forEach { s -> Text("${s.time}  ${s.name}", style = LabType.small, color = c.muted) }
            }
        }
    }
}

@Composable
private fun Dot(color: Color, size: Dp, hollow: Boolean) {
    val c = LocalLab.current
    if (hollow) Box(Modifier.padding(top = 4.dp).size(size).background(color, CircleShape).padding(3.dp).background(c.surface, CircleShape))
    else Box(Modifier.padding(top = 4.dp).size(size).background(color, if (color == c.ink) RoundedCornerShape(3.dp) else CircleShape))
}
