@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import za.co.cttrains.app.*
import za.co.cttrains.core.*

@Composable fun SavedAddressesScreen(train: TrainState,model: TrainViewModel,s: FeatureState,m: FeatureViewModel,onPlan: ()->Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var chosenLabel by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<WalkEndpoint?>(null) }
    var stationChoice by remember { mutableStateOf<MarkedSpot?>(null) }
    var removing by remember { mutableStateOf<MarkedSpot?>(null) }
    fun edit(label: String,id: String?) { editing=label;chosenLabel=label;editingId=id }
    fun use(spot: MarkedSpot,start: Boolean) {
        m.place(WalkEndpoint("spot:${spot.id}",spot.name,spot.point),start,spot.preferredStation)
        spot.preferredStation?.let { station->model.edit { if(start) it.copy(from=station) else it.copy(to=station) } }
        onPlan()
    }
    LazyColumn(Modifier.fillMaxSize().testTag("saved-addresses"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Your places",style=MaterialTheme.typography.headlineMedium);Text("Set an address once, then use it in either direction. Your station is your choice.") }
        items(SavedAddresses.labels) { label->
            val spot=s.extras.spots.firstOrNull { SavedAddresses.role(it)==label }
            AddressCard(label,spot,{ edit(label,spot?.id) },{ spot?.let { use(it,true) } },{ spot?.let { use(it,false) } },{ stationChoice=spot },{ removing=spot })
        }
        items(s.extras.spots.filter { SavedAddresses.role(it)==null },key={ it.id }) { spot->AddressCard(spot.name,spot,{ edit(spot.name,spot.id) },{ use(spot,true) },{ use(spot,false) },{ stationChoice=spot },{ removing=spot }) }
        item { Text("Add another place by saving an address in Plan, or holding a point on the map.",style=MaterialTheme.typography.bodySmall) }
    }
    editing?.let { label->JourneyPlacePicker(train,model,s,m,true,{ editing=null }) { place->target=place;editing=null } }
    target?.let { place->
        val label=chosenLabel
        AlertDialog(onDismissRequest={ target=null },title={ Text("Confirm $label address") },text={ Text(place.label+"\n\n${place.point.latitude}, ${place.point.longitude}\nYou can choose a station after saving.") },confirmButton={ TextButton(onClick={ m.saveAddress(label,place,editingId);target=null }) { Text("Save address") } },dismissButton={ TextButton(onClick={ target=null }) { Text("Cancel") } })
    }
    stationChoice?.let { spot->JourneyStationPicker(WalkEndpoint("spot:${spot.id}",spot.name,spot.point),train,s,true,spot.preferredStation,{ stationChoice=null }) { station,_->
        m.saveSpotStation(spot.id,station);stationChoice=null
    } }
    removing?.let { spot->AlertDialog(onDismissRequest={ removing=null },title={ Text("Remove ${spot.name}?") },text={ Text("You can set it again later.") },confirmButton={ TextButton(onClick={ m.removeSpot(spot.id);removing=null }) { Text("Remove") } },dismissButton={ TextButton(onClick={ removing=null }) { Text("Keep") } }) }
}
@Composable private fun AddressCard(label: String,spot: MarkedSpot?,onEdit: ()->Unit,onFrom: ()->Unit,onTo: ()->Unit,onStation: ()->Unit,onRemove: ()->Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(label,style=MaterialTheme.typography.titleLarge)
        Text(spot?.note?.ifBlank { "Saved map location" } ?: "Address not set",style=MaterialTheme.typography.bodyMedium)
        spot?.preferredStation?.let { Text("Preferred station · $it",style=MaterialTheme.typography.labelLarge) }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton(onClick=onEdit,modifier=Modifier.testTag("edit-address:$label")) { Text(if(spot==null) "Set address" else "Edit address") }
            if(spot!=null) {
                TextButton(onClick=onFrom) { Text("From here") };TextButton(onClick=onTo) { Text("To here") }
                TextButton(onClick=onStation) { Text("Choose station") };TextButton(onClick=onRemove) { Text("Remove") }
            }
        }
    } }
}
