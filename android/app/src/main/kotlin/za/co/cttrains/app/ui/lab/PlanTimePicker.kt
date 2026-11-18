package za.co.cttrains.app.ui.lab

import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalTime

@Composable
internal fun PlanTimePicker(
    time: LocalTime,
    mode: String,
    onDismiss: () -> Unit,
    onAuto: () -> Unit,
    onAllDay: () -> Unit,
    onSelected: (String) -> Unit,
) {
    var selection by rememberSaveable { mutableStateOf(if (mode == "auto") "depart" else mode) }
    var hour by rememberSaveable { mutableIntStateOf(time.hour) }
    var minute by rememberSaveable { mutableIntStateOf(time.minute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave at") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TimeChoice("Next available", "auto", selection) { selection = "auto" }
                TimeChoice("Leave after", "depart", selection) { selection = "depart" }
                TimeChoice("Any time · All day", "all", selection) { selection = "all" }
                if (selection == "depart") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        TimeWheel("Hour", 24, hour) { hour = it }
                        TimeWheel("Minute", 60, minute) { minute = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (selection) {
                    "all" -> onAllDay()
                    "auto" -> onAuto()
                    else -> onSelected(LocalTime.of(hour, minute).toString())
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TimeChoice(text: String, key: String, selection: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = LabTouch).selectable(selected = selection == key, onClick = onClick, role = Role.RadioButton)
            .testTag("lab-time-choice-$key"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selection == key, onClick = null)
        Text(text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TimeWheel(label: String, count: Int, selected: Int, onChange: (Int) -> Unit) {
    val theme = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) android.R.style.Theme_Material
    else android.R.style.Theme_Material_Light
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label)
        AndroidView(
            factory = { context ->
                NumberPicker(ContextThemeWrapper(context, theme)).apply {
                    maxValue = count - 1
                    setFormatter { it.toString().padStart(2, '0') }
                    value = selected
                    wrapSelectorWheel = true
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    contentDescription = label
                    setOnValueChangedListener { _, _, next -> onChange(next) }
                }
            },
            modifier = Modifier.testTag("lab-time-wheel-${label.lowercase()}"),
        )
    }
}
