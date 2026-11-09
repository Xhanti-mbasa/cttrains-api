@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import za.co.cttrains.app.FeatureViewModel
import za.co.cttrains.core.*

val RoutineDayLabels=listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")
@Composable fun RoutineEditor(routine: CommuteRoutine,m: FeatureViewModel,onDismiss: ()->Unit) {
    var name by remember(routine.id) { mutableStateOf(routine.name) }
    var days by remember(routine.id) { mutableStateOf(routine.days) }
    var reminder by remember(routine.id) { mutableStateOf(routine.reminderMinutes) }
    var time by remember(routine.id) { mutableStateOf(routine.query.time) }
    var pickingTime by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding().imePadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Recurring journey",style=MaterialTheme.typography.headlineSmall)
            Text("${routine.origin?.label ?: routine.query.from} → ${routine.destination?.label ?: routine.query.to}")
            OutlinedTextField(name,{ name=it.take(100) },label={ Text("Journey name") },modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={ pickingTime=true }) { Text("Departure time · $time") }
            WeekdayChoices(days) { days=it }
            Text("Reminder before leave time",style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf<Int?>(null,0,5,10,15,30).forEach { lead->FilterChip(reminder==lead,{ reminder=lead },label={ Text(when(lead) { null->"Off";0->"At leave time";else->"$lead min" }) }) } }
            Text("Timetables are checked for each selected date. A saved weekday departure is not proof the service runs on a public holiday. If that departure is missing, you’ll be asked to replan. Android may delay background reminders.",style=MaterialTheme.typography.bodySmall)
            Button(onClick={ m.saveRoutine(routine.copy(name=name.trim(),days=days,query=routine.query.copy(time=time),reminderMinutes=reminder));onDismiss() },enabled=days.isNotEmpty() && name.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("save-routine")) { Text("Save recurring journey") }
        }
    }
    if(pickingTime) DepartureTimePicker(time,{ pickingTime=false },trainOnly=routine.service==TransportService.PRASA) { time=it;pickingTime=false }
}
@Composable internal fun WeekdayChoices(days: Set<Int>,onChange: (Set<Int>)->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { RoutineDayLabels.forEachIndexed { i,label->FilterChip(i+1 in days,{ onChange(if(i+1 in days) days-(i+1) else days+(i+1)) },label={ Text(label) },modifier=Modifier.testTag("repeat-day:${i+1}")) } }
}
