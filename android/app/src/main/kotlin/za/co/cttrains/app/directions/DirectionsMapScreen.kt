package za.co.cttrains.app.directions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.cttrains.app.*
import za.co.cttrains.app.maps.*
import za.co.cttrains.app.ui.OfflineStationMap
import za.co.cttrains.core.*

/** Draw the encrypted itinerary, rather than depending on a surviving planner screen. */
@Composable fun DirectionsMapScreen(train: TrainState,model: TrainViewModel,features: FeatureState,onClose: ()->Unit) {
    val directions by DirectionsRuntime.state.collectAsStateWithLifecycle()
    val downloads by model.offlineMaps.state.collectAsStateWithLifecycle()
    val plan=directions.preview ?: directions.session?.plan
    val layers=remember(plan) {
        val steps=plan?.steps.orEmpty()
        val pins=steps.mapIndexedNotNull { i,s->s.target?.let { MapPin("direction:${s.id}","${i+1}. ${s.title}",it,null,s.detail) } }
        val walks=steps.filter { it.kind==DirectionKind.WALK && it.points.size>=2 && it.distanceMeters!=null && it.durationSeconds!=null }.map { s->
            WalkingLeg(WalkEndpoint("${s.id}:from",s.detail.substringBefore(" → "),s.points.first()),WalkEndpoint("${s.id}:to",s.title.removePrefix("Walk to "),s.points.last()),s.distanceMeters!!,s.durationSeconds!!,s.points,plan!!.createdAt,instructions=s.instructions)
        }
        val rides=steps.filter { it.kind==DirectionKind.RIDE && it.points.size>=2 }.map { BusShape(it.id,it.title,it.points,"Saved directions · approximate transit link") }
        FeatureLayers(pins=pins,walks=walks,shapes=rides,fitName="directions:${plan?.id}",fitPoints=steps.flatMap { it.points }+pins.map { it.point })
    }
    val viewport=remember(plan?.id) { MapViewport() }
    var failed by remember(train.preferences.mapMode,features.extras.beta.mapStyle) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<MapPin?>(null) }
    val region=downloads.regions.firstOrNull { it.id==train.preferences.offlineMapId && it.complete } ?: downloads.regions.firstOrNull { it.complete }
    val streets=train.mapsReady && train.preferences.mapMode!="stations" && !failed && (train.preferences.mapMode=="online" || region!=null)
    val mapState=train.copy(stations=emptyList(),selectedJourney=null,routeTrace=null,from="",to="")
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("directions-map")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(plan?.title ?: "Journey map",style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f),maxLines=2)
            TextButton(onClick=onClose) { Text("Close map") }
        }
        Text(directions.guidance?.title ?: "Saved journey · tap a numbered stop for details",style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(horizontal=12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if(streets) key(plan?.id,train.preferences.mapMode,retry) {
                StreetMap(mapState,if(train.preferences.mapMode=="offline") model.offlineMaps.definition(region?.id) else null,viewport,emptyList(),{}, {},{ failed=true },Modifier.fillMaxSize(),features.extras.beta.mapStyle,layers,{ selected=it })
            } else OfflineStationMap(emptyList(),null,null,viewport,emptyList(),train.currentPosition,"","",{}, {},layers,{ selected=it },{})
            if(failed) TextButton(onClick={ failed=false;retry++ },modifier=Modifier.align(Alignment.TopEnd)) { Text("Retry map") }
        }
        Text("Transit links are approximate. Walks follow cached routed paths. Offline imagery needs a downloaded map.",style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(12.dp))
    }
    selected?.let { pin->AlertDialog(onDismissRequest={ selected=null },title={ Text(pin.name) },text={ Text(pin.detail) },confirmButton={ TextButton(onClick={ selected=null }) { Text("Done") } }) }
}
