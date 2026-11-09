package za.co.cttrains.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import za.co.cttrains.app.*
import za.co.cttrains.app.data.RouteSteps

@Composable fun HealthStepsSettings(s: FeatureState,m: FeatureViewModel) {
    val context=LocalContext.current;val health=remember { RouteSteps(context) };val scope=rememberCoroutineScope()
    var granted by remember { mutableStateOf(false) };var status by remember { mutableStateOf(HealthConnectClient.getSdkStatus(context)) }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val listener=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_RESUME) scope.launch { status=HealthConnectClient.getSdkStatus(context);granted=health.granted() } }
        owner.lifecycle.addObserver(listener);onDispose { owner.lifecycle.removeObserver(listener) }
    }
    LaunchedEffect(Unit) { granted=health.granted() }
    val permission=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        granted=result.containsAll(RouteSteps.permissions);m.beta { it.copy(healthSteps=granted) }
        m.message(if(granted) "Steps enabled for completed route walks. Samsung Health must share Steps with Health Connect." else "Steps access was not granted. Walking routes still work.")
    }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Route steps · Health Connect",style=MaterialTheme.typography.titleMedium)
        Text("Read only step totals during walks you explicitly learn. Data stays local. Watch sync can be delayed; counts do not replace measured walking duration.",style=MaterialTheme.typography.bodySmall)
        Text(when { status!=HealthConnectClient.SDK_AVAILABLE -> "Health Connect unavailable or needs an update";!granted -> "Steps permission not granted";s.extras.beta.healthSteps -> "Enabled · read only for completed walks";else -> "Permission granted · feature paused" },style=MaterialTheme.typography.labelSmall)
        if(status==HealthConnectClient.SDK_AVAILABLE) {
            OutlinedButton(onClick={ if(s.extras.beta.healthSteps) m.beta { it.copy(healthSteps=false) } else permission.launch(RouteSteps.permissions) }) { Text(if(s.extras.beta.healthSteps) "Pause route steps" else "Enable & request Steps permission") }
            TextButton(onClick={ m.refreshRouteSteps() },enabled=granted && s.extras.beta.healthSteps) { Text("Update steps for recent completed walks") }
            TextButton(onClick={ runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } }) { Text("Health Connect permissions & data sources") }
        } else TextButton(onClick={ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))) } }) { Text("Install / update Health Connect") }
        Text("In Samsung Health: Settings → Health Connect → App permissions → Samsung Health → allow writing Steps. Sync your watch, open Samsung Health, then update completed walks here.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={ context.startActivity(Intent(context,HealthRationaleActivity::class.java)) }) { Text("Read the local data policy") }
        s.extras.walkSamples.takeLast(5).reversed().forEach { sample -> Text("${sample.distanceMeters.toInt()} m · ${(sample.seconds+59)/60} min · ${sample.steps?.let { "$it steps" } ?: "steps not available / not read"}",style=MaterialTheme.typography.bodySmall) }
    }
}
