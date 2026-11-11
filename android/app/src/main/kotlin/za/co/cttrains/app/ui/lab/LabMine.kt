@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package za.co.cttrains.app.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import za.co.cttrains.app.BuildConfig
import za.co.cttrains.app.FeatureState
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.app.TrainState
import za.co.cttrains.app.TrainViewModel
import za.co.cttrains.app.ui.*
import za.co.cttrains.core.LabTiming
import za.co.cttrains.core.SavedAddresses
import za.co.cttrains.core.SavedRoute

private data class MineEntry(val route: String, val title: String, val detail: String, val icon: ImageVector)

/** Mine: everything the person owns, one level deep. */
@Composable
fun LabMineScreen(
    state: TrainState, model: TrainViewModel, feature: FeatureState, now: Long,
    onOpen: (String) -> Unit, onPlanRoute: (SavedRoute) -> Unit, onPlanFrom: (String) -> Unit, onDeveloper: () -> Unit,
) {
    val c = LocalLab.current
    var developerTaps by remember { mutableIntStateOf(0) }
    var lastDeveloperTap by remember { mutableLongStateOf(0) }
    val profile = state.preferences.profileId
    val upcoming = state.scheduled.count { it.profileId == profile && it.rideOutcome == null && it.departureAt > now }
    val rides = state.scheduled.count { it.profileId == profile && it.rideOutcome != null }
    val beta = feature.extras.beta
    val entries = listOf(
        MineEntry("upcoming", "Saved trips & reminders", if (upcoming == 0) "None upcoming" else "$upcoming upcoming", Icons.Outlined.NotificationsActive),
        MineEntry("trips", "Rides & safety trips", if (rides == 0) "Caught and missed rides appear here" else "$rides recorded · correct any", Icons.Outlined.History),
        MineEntry("budget", "Fares & budget", "Source fares and your corrections", Icons.Outlined.Payments),
        MineEntry("buses", "Buses · beta", listOfNotNull("MyCiTi".takeIf { beta.myCiti }, "Golden Arrow".takeIf { beta.goldenArrow }).joinToString().ifBlank { "Off" }, Icons.Outlined.DirectionsBus),
        MineEntry("map", "Map & offline areas", "Downloads live under Map options", Icons.Outlined.Map),
        MineEntry("alerts", "Updates & delays", "Published service notices", Icons.Outlined.WarningAmber),
        MineEntry("directions", "Directions", "Walking guidance with a visible notification", Icons.Outlined.Navigation),
        MineEntry("widgets", "Widgets & cards", if (beta.appWidgets) "Widgets on" else "Widgets off", Icons.Outlined.Widgets),
        MineEntry("community", "Community", if (beta.hostedSharing) "On" else "Optional · off", Icons.Outlined.Forum),
        MineEntry("whatsapp", "WhatsApp updates", "Optional reader and sharing", Icons.Outlined.Sms),
        MineEntry("settings", "Privacy, backup & data", "Backups, source, deletion, appearance", Icons.Outlined.Lock),
        MineEntry("app-updates", "App updates", "Lab updates come only from its own channel", Icons.Outlined.SystemUpdate),
    )

    LazyColumn(Modifier.fillMaxSize().background(c.ground).testTag("lab-mine"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Mine", style = LabType.title, color = c.ink, modifier = Modifier.semantics { heading() }) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SavedAddresses.labels.forEach { label ->
                    val spot = feature.extras.spots.firstOrNull { SavedAddresses.role(it) == label }
                    AssistChip(onClick = { onOpen("places") }, label = { Text(if (spot == null) "Set $label" else label) },
                        leadingIcon = { Icon(when (label) { "Home" -> Icons.Outlined.Home; "Work" -> Icons.Outlined.Work; else -> Icons.Outlined.School }, null) },
                        modifier = Modifier.heightIn(min = LabTouch))
                }
                AssistChip(onClick = { onOpen("places") }, label = { Text("All places") }, modifier = Modifier.heightIn(min = LabTouch))
            }
        }
        if (state.preferences.routes.isNotEmpty() || state.preferences.favoriteStations.isNotEmpty()) item { LabSectionTitle("Saved routes & stations") }
        items(state.preferences.routes, key = { "route-${it.from}-${it.to}" }) { route ->
            Row(Modifier.fillMaxWidth().background(c.surface, LabControlShape).heightIn(min = 56.dp).padding(start = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickableRow("Plan ${route.from} to ${route.to}") { onPlanRoute(route) }.padding(vertical = 10.dp)) {
                    Text("${route.from} → ${route.to}", style = LabType.bodyStrong, color = c.ink)
                    Text("Tap for the next trains", style = LabType.small, color = c.muted)
                }
                IconButton(onClick = { model.toggleRoute(route) }) { Icon(Icons.Outlined.BookmarkRemove, "Remove saved route from ${route.from} to ${route.to}", tint = c.muted) }
            }
        }
        items(state.preferences.favoriteStations, key = { "station-$it" }) { name ->
            Row(Modifier.fillMaxWidth().background(c.surface, LabControlShape).heightIn(min = 56.dp).padding(start = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(name, style = LabType.bodyStrong, color = c.ink, modifier = Modifier.weight(1f).clickableRow("Plan from $name") { onPlanFrom(name) }.padding(vertical = 16.dp))
                IconButton(onClick = { model.toggleStation(name) }) { Icon(Icons.Outlined.Star, "Remove $name from favourite stations", tint = c.muted) }
            }
        }
        item { LabSectionTitle("Tools & data") }
        items(entries, key = { it.route }) { entry -> ActionRow(entry.icon, entry.title, entry.detail, Modifier.testTag("lab-mine-${entry.route}")) { onOpen(entry.route) } }
        item {
            Text("CT Trains Lab · experimental · ${BuildConfig.VERSION_NAME}", style = LabType.small, color = c.muted,
                modifier = Modifier.testTag("developer-build-label").heightIn(min = LabTouch).clickable(role = Role.Button) {
                    val t = android.os.SystemClock.elapsedRealtime()
                    developerTaps = if (t - lastDeveloperTap > 2000) 1 else developerTaps + 1
                    lastDeveloperTap = t
                    if (developerTaps >= 7) { developerTaps = 0; onDeveloper() }
                }.padding(vertical = 14.dp))
        }
    }
}

/** Privacy, backup and data: the regular edition's controls, kept intact, on one flat page. */
@Composable
fun LabSettingsScreen(state: TrainState, model: TrainViewModel, feature: FeatureState, features: FeatureViewModel,
                      onOpen: (String) -> Unit, onDeveloperLock: () -> Unit) {
    val c = LocalLab.current
    val uri = LocalUriHandler.current
    var confirmClear by remember { mutableStateOf(false) }
    var confirmProfile by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp).testTag("lab-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LabCard {
            Text("An independent, community app for Cape Town rail commuters. Timetables come from CT Trains. " +
                "This app is not affiliated with CT Trains, PRASA or Metrorail.", style = LabType.small, color = c.ink)
        }
        FeatureStatus(feature)
        LabSectionTitle("Backup & move")
        Text("To move from CT Trains, make an encrypted backup there and restore it here. The two apps never read each other's data.",
            style = LabType.small, color = c.muted)
        ProfileSettings(feature, features)
        LabSectionTitle("Optional features")
        BetaSettings(feature, features, { onOpen("buses") }, { onOpen("trips") }, { onOpen("community") }, { onOpen("whatsapp") })
        HostedSettings(feature, features)
        LabSectionTitle("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("System", "Light", "Dark").forEach { theme ->
                FilterChip(selected = state.preferences.theme == theme, onClick = { model.setTheme(theme) }, label = { Text(theme) },
                    modifier = Modifier.heightIn(min = LabTouch))
            }
        }
        Text("The Lab uses a fixed palette so warning and transport colours keep their meaning.", style = LabType.small, color = c.muted)
        LabSectionTitle("Timetables & privacy")
        LabCard {
            Text("Your last 12 searches are stored on this device for up to 14 days. If a refresh fails, the same route, date and departure time can use its saved result, clearly labelled.", style = LabType.small, color = c.ink)
            Text("Personal records and optional account tokens are encrypted with Android Keystore. Automatic Android backup is off; use encrypted document backups above.", style = LabType.small, color = c.ink)
            Text("Location is sampled once when you ask, kept for up to five minutes, and never uploaded to the timetable source. Calendar export opens your calendar app; this app cannot read your calendar.", style = LabType.small, color = c.ink)
            Text("Opening the app checks GitHub for Lab updates and the owner-operated notice service. They receive your IP address only.", style = LabType.small, color = c.ink)
            Text("Downloaded maps are public cache data and are not encrypted. Manage them from Map options.", style = LabType.small, color = c.ink)
        }
        Text("Map mode", style = LabType.bodyStrong, color = c.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("online" to "Online", "offline" to "Offline", "stations" to "Stations").forEach { (mode, title) ->
                FilterChip(state.preferences.mapMode == mode, { model.setMapMode(mode) }, label = { Text(title) }, modifier = Modifier.heightIn(min = LabTouch))
            }
        }
        SourceSelector(state, model)
        LabSecondaryButton("Forget sampled location", { model.forgetLocation() }, Modifier.fillMaxWidth())
        LabSecondaryButton("Clear cached timetables", { confirmClear = true }, Modifier.fillMaxWidth())
        LabSecondaryButton("Delete all local personal data", { confirmProfile = true }, Modifier.fillMaxWidth())
        LabSectionTitle("Check before you travel")
        Text("Timetables are not live tracking. Updates & delays shows published notices; reports may be incomplete or late. Searches cover today through the end of next month and do not list Sunday or public-holiday services.",
            style = LabType.small, color = c.ink)
        LabSecondaryButton("Updates & delays", { onOpen("alerts") }, Modifier.fillMaxWidth())
        OutlinedButton(onClick = { uri.openUri("https://cttrains.co.za/train-form.php") }, Modifier.fillMaxWidth().heightIn(min = LabTouch), shape = LabControlShape) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Visit CT Trains", color = c.ink)
        }
        TextButton(onClick = { uri.openUri("https://cttrains.co.za/train-form.php") }, Modifier.heightIn(min = LabTouch)) { Text("Report an early train · website", color = c.ink) }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear saved timetables?") },
        text = { Text("You’ll need an internet connection for your next search. Your favourite routes will stay saved.") },
        confirmButton = { TextButton(onClick = { model.clearCache(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    if (confirmProfile) AlertDialog(onDismissRequest = { confirmProfile = false }, title = { Text("Delete your local profile?") },
        text = { Text("Favourites, corrections, journeys, reminders, personal spots/photos, trip files, account sessions and saved timetables will be removed. Public offline maps are managed separately. External backup files and calendar events remain at their chosen locations.") },
        confirmButton = { TextButton(onClick = { onDeveloperLock(); features.reset(); model.clearPersonalData(); confirmProfile = false }) { Text("Delete local data") } },
        dismissButton = { TextButton(onClick = { confirmProfile = false }) { Text("Cancel") } })
}

/** Widgets & cards: the same switches as the regular edition; Lab widgets are separate Android providers. */
@Composable
fun LabWidgetsScreen(feature: FeatureState, features: FeatureViewModel) {
    val c = LocalLab.current
    Column(Modifier.fillMaxSize().background(c.ground).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SmartCardsSettings(feature, features)
        ExperimentalWidgetsSettings(feature, features)
        Text("Widgets show scheduled times from this phone's last check and say when that check is old.", style = LabType.small, color = c.muted)
    }
}
