@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.util.Locale
import za.co.cttrains.app.*
import za.co.cttrains.core.*

internal fun stationDistance(station: StationPosition,point: GeoPoint): String {
    val meters=NearbySuggestions.distance(point,WalkingRoutes.endpoint(station).point)
    return if(meters<1000) "${meters.toInt()} m away" else String.format(Locale.ENGLISH,"%.1f km away",meters/1000)
}

@Composable internal fun JourneyStationField(start: Boolean,chosen: String?,place: WalkEndpoint?,train: TrainState,onChoose: ()->Unit,onStationOnly: ()->Unit) {
    val suggested=remember(place,train.stations,train.catalog.stations) { place?.let { StationChoices.ranked(it.point,train.stations,train.catalog.stations).firstOrNull() } }
    OutlinedButton(onClick=onChoose,modifier=Modifier.fillMaxWidth().testTag(if(start) "boarding-station" else "arrival-station")) {
        Text("${if(start) "Board at" else "Get off at"} · ${chosen ?: "Choose station"}")
    }
    if(chosen==null) Text(suggested?.let { "Closest suggestion: ${it.name} · ${stationDistance(it,place!!.point)}. Choose any station you prefer." } ?: "Choose your preferred train station.",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick=onStationOnly) { Text(if(start) "Start at the station instead" else "End at the station instead") }
}

@Composable internal fun JourneyStationPicker(place: WalkEndpoint,train: TrainState,s: FeatureState,start: Boolean,chosen: String?,onDismiss: ()->Unit,onSelect: (String?,Boolean)->Unit) {
    var query by remember { mutableStateOf("") }
    var rememberForSpot by remember(place.id) { mutableStateOf(true) }
    val spot=s.extras.spots.firstOrNull { place.id=="spot:${it.id}" }
    val ranked=remember(place.point,train.stations,train.catalog.stations) { StationChoices.ranked(place.point,train.stations,train.catalog.stations) }
    val matches=remember(ranked,query) { ranked.filter { it.name.contains(query,true) } }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding().testTag("journey-station-picker"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            item {
                Text(if(start) "Choose your boarding station" else "Choose your arrival station",style=MaterialTheme.typography.titleLarge)
                Text("${place.label} · closest to furthest",style=MaterialTheme.typography.bodyMedium)
                Text("Proximity is straight-line distance. You can choose a station that works better for you; walking paths are calculated when planning.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(query,{ query=it.take(100) },label={ Text("Search stations") },singleLine=true,modifier=Modifier.fillMaxWidth())
                if(spot!=null) Row(Modifier.fillMaxWidth().clickable { rememberForSpot=!rememberForSpot }) {
                    Checkbox(rememberForSpot,{ rememberForSpot=it });Text("Remember station for ${spot.name}",modifier=Modifier.padding(top=12.dp))
                }
                TextButton(onClick={ onSelect(null,spot!=null && rememberForSpot) }) { Text("Auto · find nearby usable stations") }
            }
            items(matches,key={ stationKey(it.name) }) { station ->
                ListItem(headlineContent={ Text(station.name) },supportingContent={ Text("${stationDistance(station,place.point)}${if(station==ranked.firstOrNull()) " · closest" else ""}${if(stationKey(station.name)==chosen?.let(::stationKey)) " · selected" else ""}") },
                    modifier=Modifier.testTag("station-choice:${stationKey(station.name)}").clickable { onSelect(station.name,spot!=null && rememberForSpot) })
            }
            if(matches.isEmpty()) item { Text("No mapped station matched. Try another name or refresh the station catalogue.") }
        }
    }
}

@Composable internal fun SaveJourneyPlace(place: WalkEndpoint,busy: Boolean,onDismiss: ()->Unit,onSave: (String,String)->Unit) {
    var name by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("Work") }
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().imePadding(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item { Text("Save this place",style=MaterialTheme.typography.titleLarge);Text(place.label,style=MaterialTheme.typography.bodySmall) }
            item { FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("Home","Work","School","Friend","Custom").forEach { label -> FilterChip(tag==label,{ tag=label },label={ Text(label) }) } } }
            item { OutlinedTextField(name,{ name=it.take(100) },label={ Text("Place name · defaults to $tag") },singleLine=true,modifier=Modifier.fillMaxWidth()) }
            item { Text("Saved locally with your chosen station. You can change the station for any journey.",style=MaterialTheme.typography.bodySmall) }
            item { Button(onClick={ onSave(name.trim().ifBlank { tag },tag) },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Save place") } }
        }
    }
}
