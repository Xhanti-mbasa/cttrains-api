@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import za.co.cttrains.app.*
import za.co.cttrains.core.*

@Composable fun NearbyUsers(train: TrainState,model: TrainViewModel,s: FeatureState,m: FeatureViewModel,service: TransportService,onSettings: ()->Unit) {
    var confirm by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner=LocalLifecycleOwner.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants->if(grants.values.any { it }) { sharing=true;model.locateOnce() } else m.message("Nearby users needs location while this screen is open.") }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_STOP) { sharing=false;m.stopNearby() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer);m.stopNearby() }
    }
    LaunchedEffect(Unit) { while(true) { delay(15_000);now=System.currentTimeMillis() } }
    LaunchedEffect(service,sharing,s.extras.beta.nearbyUsers,s.extras.beta.hostedSharing,s.session) {
        if(!sharing || !s.extras.beta.nearbyUsers || !s.extras.beta.hostedSharing || s.session==null) { sharing=false;m.stopNearby();return@LaunchedEffect }
        val until=System.currentTimeMillis()+15*60_000L
        while(sharing && System.currentTimeMillis()<until) { model.locateOnce();delay(60_000);now=System.currentTimeMillis() }
        sharing=false;m.stopNearby()
    }
    LaunchedEffect(train.currentPosition?.capturedAt,sharing,service) {
        val p=train.currentPosition
        if(sharing && p!=null && p.accuracyMeters<=1000 && System.currentTimeMillis()-p.capturedAt in 0..60_000) m.nearby(service,GeoPoint(p.latitude,p.longitude))
    }
    Text("Nearby users · beta",style=MaterialTheme.typography.titleLarge)
    Text("Uses Android location permission and consenting Matrix accounts. It does not scan Bluetooth devices, so no Nearby devices permission is required. Enable the feature, connect a room, then choose Share nearby presence to request location access.",style=MaterialTheme.typography.bodySmall)
    if(!s.extras.beta.nearbyUsers) {
        Text("Enable Nearby users in Settings → Beta features.")
        TextButton(onClick=onSettings) { Text("Open settings") }
        return
    }
    if(!s.extras.beta.hostedSharing || s.session==null || s.extras.rooms.none { it.service==service }) {
        Text("Connect a Matrix account and join this service’s room in Chat first. Nearby users are consenting accounts in that room.")
        TextButton(onClick=onSettings) { Text("Account settings") }
        return
    }
    Text("Approximate positions within 5 km, rounded to about 1 km. Accounts are not verified people; this does not discover every app user nearby.",style=MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={ confirm=true },enabled=!sharing) { Text("Share nearby presence · 15 min") }
        OutlinedButton(onClick={ sharing=false;m.stopNearby() },enabled=sharing || s.nearbySharing) { Text("Stop") }
        TextButton(onClick={ model.locateOnce();m.sync(service);now=System.currentTimeMillis() }) { Text("Refresh nearby") }
    }
    train.currentPosition?.let { p ->
        val users=NearbyPeople.visible(s.nearby.mapNotNull { msg->msg.presence?.let { msg.sender to it } },s.session.userId,GeoPoint(p.latitude,p.longitude),now)
        if(users.isEmpty()) Text("No fresh nearby presence from other accounts in this room.")
        users.forEach { user->Card { Column(Modifier.padding(12.dp)) { Text(user.sender);Text("About ${(user.distanceMeters/1000).toInt()} km · shared ${checked(user.presence.sentAt)}",style=MaterialTheme.typography.bodySmall) } } }
    } ?: Text("Refresh your location to compare nearby presence.")
    if(confirm) AlertDialog(onDismissRequest={ confirm=false },title={ Text("Share approximate presence?") },text={ Text("Your selected service room and its hosting provider can read and retain your Matrix ID and position rounded to about 1 km. This app hides entries after 5 minutes; server history can remain. Sharing stops when you leave this screen, background the app, or after 15 minutes. Exact GPS coordinates are never sent by this feature.") },confirmButton={ TextButton(onClick={ confirm=false;permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)) }) { Text("Allow and start") } },dismissButton={ TextButton(onClick={ confirm=false }) { Text("Cancel") } })
}
