package za.co.cttrains.app.ui.lab

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import za.co.cttrains.app.BuildConfig
import za.co.cttrains.app.PlannerOption
import za.co.cttrains.core.*

private data class UberRide(val tag: String, val label: String, val pickup: UberPlace, val dropoff: UberPlace)

/**
 * Optional beta: hands a first or last mile to the Uber app via Uber's official universal link.
 * Nothing is requested from Uber here; the rider confirms the ride, ride type and price in Uber.
 * The link (which carries locations) is never logged.
 */
@Composable
internal fun UberHandoffCard(option: PlannerOption, from: WalkEndpoint?, to: WalkEndpoint?, stations: List<StationPosition>, now: Long) {
    val c = LocalLab.current
    val uri = LocalUriHandler.current
    var confirm by remember { mutableStateOf<UberRide?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val board = remember(option.id, stations) {
        option.bus?.board?.let { UberPlace("${it.name} stop", it.point) } ?: if (option.rail != null) UberHandoff.station(option.board, stations) else null
    }
    val alight = remember(option.id, stations) {
        option.bus?.alight?.let { UberPlace("${it.name} stop", it.point) } ?: if (option.rail != null) UberHandoff.station(option.alight, stations) else null
    }
    fun sameStation(e: WalkEndpoint?, name: String) = e?.id?.startsWith("station:") == true && stationKey(e.label) == stationKey(name)
    val rides = buildList {
        // First mile: only for an upcoming departure, and not when the trip already starts at that station.
        if (board != null && option.estimate.departureAt > now && !sameStation(from, option.board)) {
            val pickup = from?.let { UberPlace(it.label, it.point) } ?: UberPlace("Current location", null)
            add(UberRide("uber-first-mile", "Uber to ${board.name}", pickup, board))
        }
        // Last mile: from the stop or station you get off at to the place you chose.
        if (alight != null && to != null && !sameStation(to, option.alight)) add(UberRide("uber-last-mile", "Uber from ${alight.name} to ${to.label}", alight, UberPlace(to.label, to.point)))
    }
    if (rides.isEmpty()) return
    LabCard(Modifier.testTag("lab-trip-uber")) {
        LabSectionTitle("Uber · beta")
        Text("Opens the Uber app with the places filled in. You choose the ride and see Uber's price and pickup time there; nothing is booked from CT Trains.",
            style = LabType.small, color = c.muted)
        if (rides.any { it.tag == "uber-first-mile" }) Text("To make the ${LabTiming.clock(option.estimate.departureAt)} departure, aim to be dropped off by ${LabTiming.clock(option.estimate.departureAt - 5 * 60_000L)}.",
            style = LabType.small, color = c.ink)
        rides.forEach { ride -> LabSecondaryButton(ride.label, { problem = null; confirm = ride }, Modifier.fillMaxWidth().testTag(ride.tag)) }
        problem?.let { Text(it, style = LabType.small, color = c.signal) }
    }
    confirm?.let { ride ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Open Uber?") },
            text = {
                Text("Uber will receive this pickup and drop-off:\n\nPickup · ${if (ride.pickup.point == null) "your current location (set in Uber)" else ride.pickup.name}\nDrop-off · ${ride.dropoff.name}\n\nCheck both places, the ride type and the price in Uber before you confirm. Without the Uber app, Android opens Uber's website or Play Store page.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    problem = try { uri.openUri(UberHandoff.link(ride.pickup, ride.dropoff, BuildConfig.UBER_CLIENT_ID)); null }
                        catch (e: Exception) { "Uber could not be opened on this phone. Install Uber or open it from a browser." }
                }, Modifier.testTag("uber-open")) { Text("Open Uber") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
