package za.co.cttrains.app.ui.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.cttrains.core.Reachability
import za.co.cttrains.core.LabTiming
import za.co.cttrains.core.WalkFeasibility

/** Minimum touch height for every Lab action. */
val LabTouch = 48.dp
val LabCardShape = RoundedCornerShape(20.dp)
val LabControlShape = RoundedCornerShape(14.dp)

@Composable
fun LabCard(modifier: Modifier = Modifier, emphasised: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalLab.current
    Column(
        modifier.fillMaxWidth()
            .then(if (emphasised) Modifier.border(2.dp, c.ink, LabCardShape) else Modifier)
            .background(c.surface, LabCardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

@Composable
fun LabSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = LabType.label, color = LocalLab.current.muted, modifier = modifier.semantics { heading() })
}

/** A scheduled departure time. The "sched." mark is part of the spoken label too. */
@Composable
fun ScheduledTime(time: String, modifier: Modifier = Modifier, large: Boolean = true) {
    val c = LocalLab.current
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "$time, scheduled" },
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(time, style = if (large) LabType.time else LabType.timeMedium, color = c.ink, softWrap = false)
        SchedMark(Modifier.padding(bottom = 6.dp))
    }
}

@Composable
fun SchedMark(modifier: Modifier = Modifier) {
    val c = LocalLab.current
    Surface(modifier, shape = RoundedCornerShape(50), color = Color.Transparent, border = BorderStroke(1.5.dp, c.muted)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(12.dp), tint = c.muted)
            Text("sched.", style = LabType.small, color = c.muted, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun LeaveByChip(leaveAt: Long, modifier: Modifier = Modifier) {
    val c = LocalLab.current
    Surface(modifier, shape = RoundedCornerShape(50), color = c.signal) {
        Text("Leave by ${LabTiming.clock(leaveAt)}", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = LabType.small,
            color = c.onSignal, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StaleChip(text: String, modifier: Modifier = Modifier) {
    val c = LocalLab.current
    Surface(modifier, shape = RoundedCornerShape(50), color = c.staleBg) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = LabType.small, color = c.staleInk, fontWeight = FontWeight.SemiBold)
    }
}

enum class LabMode(val label: String) { TRAIN("Train"), MYCITI("MyCiTi"), GOLDEN_ARROW("Golden Arrow"), WALK("Walk") }

@Composable
fun ModeBadge(mode: LabMode, detail: String? = null, modifier: Modifier = Modifier) {
    val c = LocalLab.current
    val (bg, fg, icon: ImageVector) = when (mode) {
        LabMode.TRAIN -> Triple(c.train, c.onMode, Icons.Outlined.Train)
        LabMode.MYCITI -> Triple(c.myCiti, c.onMode, Icons.Outlined.DirectionsBus)
        LabMode.GOLDEN_ARROW -> Triple(c.goldenArrow, c.onMode, Icons.Outlined.DirectionsBus)
        LabMode.WALK -> Triple(c.chip, c.ink, Icons.AutoMirrored.Outlined.DirectionsWalk)
    }
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = bg) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(14.dp), tint = fg)
            Text(if (detail == null) mode.label else "${mode.label} · $detail", style = LabType.small, color = fg, fontWeight = FontWeight.Bold)
        }
    }
}

/** Walk feasibility: the word carries the meaning; colour only reinforces it. */
@Composable
fun FeasibilityLine(r: Reachability, modifier: Modifier = Modifier) {
    val c = LocalLab.current
    val colour = when (r.feasibility) { WalkFeasibility.TIGHT -> c.signal; WalkFeasibility.REACHABLE -> c.ink; else -> c.muted }
    Text(LabTiming.reachabilityLabel(r), modifier, style = LabType.small, color = colour, fontWeight = FontWeight.SemiBold)
}

@Composable
fun LabPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, signal: Boolean = false, enabled: Boolean = true) {
    val c = LocalLab.current
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = LabControlShape,
        colors = ButtonDefaults.buttonColors(containerColor = if (signal) c.signal else c.ink, contentColor = if (signal) c.onSignal else c.surface)) {
        Text(text, style = LabType.bodyStrong)
    }
}

@Composable
fun LabSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = LocalLab.current
    OutlinedButton(onClick, modifier.heightIn(min = LabTouch), enabled = enabled, shape = LabControlShape,
        border = BorderStroke(1.5.dp, c.ink), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink)) {
        Text(text, style = LabType.bodyStrong)
    }
}

/** Every empty, failed or blocked state names what happened and offers the next useful action. */
@Composable
fun LabStateCard(kicker: String, title: String, body: String, primary: Pair<String, () -> Unit>?, secondary: Pair<String, () -> Unit>? = null, modifier: Modifier = Modifier) {
    val c = LocalLab.current
    LabCard(modifier) {
        LabSectionTitle(kicker)
        Text(title, style = LabType.route, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(body, style = LabType.small, color = c.muted)
        primary?.let { (label, action) -> LabPrimaryButton(label, action, Modifier.fillMaxWidth()) }
        secondary?.let { (label, action) -> LabSecondaryButton(label, action, Modifier.fillMaxWidth()) }
    }
}

/** A whole row as one accessible button with a spoken action label. */
fun Modifier.clickableRow(label: String, onClick: () -> Unit): Modifier = this.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
