@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.directions

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.cttrains.core.*

@Composable fun DirectionsButton(makePlan: ()->DirectionsPlan,onReady: ()->Unit) {
    val m: DirectionsModel=androidx.lifecycle.viewmodel.compose.viewModel()
    TextButton(onClick={ m.preview(makePlan,onReady) },modifier=Modifier.testTag("start-directions-preview")) { Text("Directions") }
}
@Composable fun DirectionsCompactBanner(onOpen: ()->Unit) {
    val s by DirectionsRuntime.state.collectAsStateWithLifecycle()
    if(s.session!=null && s.preview==null) Surface(onClick=onOpen,color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(s.guidance?.title ?: if(s.running) "Directions active" else "Resume your directions",style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("Open",style=MaterialTheme.typography.labelLarge)
        }
    }
}
@Composable fun DirectionsScreen(onPlan: ()->Unit,onMap: ()->Unit) {
    val m: DirectionsModel=androidx.lifecycle.viewmodel.compose.viewModel()
    val state by m.state.collectAsStateWithLifecycle()
    val c=LocalContext.current
    var permissions by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(DirectionSpeech.enabled(c)) }
    val previewSpeech=remember { DirectionSpeech(c) { permissions=it } }
    DisposableEffect(Unit) { onDispose { previewSpeech.close() } }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { values ->
        if(values[Manifest.permission.ACCESS_FINE_LOCATION]==true || values[Manifest.permission.ACCESS_COARSE_LOCATION]==true) {
            permissions=if(Build.VERSION.SDK_INT>=33 && values[Manifest.permission.POST_NOTIFICATIONS]!=true) "Notifications are disabled. Allow them for Live Updates and the Now Bar." else null
            m.start()
        } else permissions="Location access is needed for active directions. You can still read the steps below."
    }
    val plan=state.preview ?: state.session?.plan
    val session=state.session?.takeIf { state.preview==null }
    fun begin() { permission.launch((listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)+if(Build.VERSION.SDK_INT>=33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray()) }
    LazyColumn(Modifier.fillMaxSize().testTag("directions-screen"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("Journey directions",style=MaterialTheme.typography.headlineSmall)
            if(plan==null) { Text("Choose a train or bus journey in Plan, then tap Directions.");Button(onClick=onPlan) { Text("Plan a journey") } }
            state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            permissions?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            TextButton(onClick={ setup=!setup }) { Text(if(setup) "Hide notification setup" else "Live notifications & Now Bar setup") }
        }
        if(setup) item { LiveDirectionsSetup() }
        if(plan!=null) {
            item {
                Text(plan.title,style=MaterialTheme.typography.titleLarge)
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Spoken directions",Modifier.weight(1f));Switch(voice,{ voice=it;DirectionSpeech.enabled(c,it);if(!it) previewSpeech.stop();if(state.running) m.command("voice") })
                }
                Text("Step changes, mapped walking instructions and approaching stops. Uses your phone’s speech voice and media volume.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ if(state.running && session!=null) m.command("speech") else previewSpeech.speak(plan.steps.firstOrNull()?.let { it.title+". "+it.detail } ?: plan.title) },modifier=Modifier.testTag("repeat-direction")) { Text("Read current instruction") }
                Text(plan.notice,style=MaterialTheme.typography.bodySmall)
                Text(if(plan.sourceCheckedAt>0) "Timetable checked ${java.time.Instant.ofEpochMilli(plan.sourceCheckedAt).atZone(CapeTownZone).toLocalDateTime()}" else "Timetable source date unavailable · check before travelling",style=MaterialTheme.typography.labelSmall)
                if(session==null || !state.running) {
                    Button(onClick=::begin,modifier=Modifier.fillMaxWidth().testTag("begin-directions")) { Text(if(session==null) "Start directions" else "Resume directions") }
                    Text("Uses location only during this session. Runs with a visible notification for up to four hours. GPS stays on your phone; no location history is stored.",style=MaterialTheme.typography.bodySmall)
                    if(state.preview!=null && state.running) Text("Starting this journey replaces your current directions.",style=MaterialTheme.typography.labelSmall)
                    if(state.preview!=null) TextButton(onClick=m::discardPreview) { Text("Cancel preview") }
                }
            }
            if(session!=null) item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Step ${session.index+1} of ${plan.steps.size}",style=MaterialTheme.typography.labelLarge)
                    Text(state.guidance?.title ?: session.step?.title.orEmpty(),style=MaterialTheme.typography.titleLarge)
                    Text(state.guidance?.detail ?: session.step?.detail.orEmpty())
                    state.guidance?.remainingMeters?.let { Text("$it m${state.guidance?.minutes?.let { n->" · ~$n min" }.orEmpty()}",style=MaterialTheme.typography.labelLarge) }
                    LinearProgressIndicator(progress={ (state.guidance?.progress ?: 0)/100f },modifier=Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={ m.command("back") },enabled=session.index>0) { Text("Previous step") }
                        Button(onClick={ m.command("next") },modifier=Modifier.testTag("directions-next")) { Text(when(session.step?.kind) { DirectionKind.BOARD->"I'm on board";DirectionKind.RIDE->"I've arrived";else->"Walk completed" }) }
                        if(state.running) TextButton(onClick={ m.command("pause") }) { Text(if(session.paused) "Resume GPS" else "Pause") }
                        TextButton(onClick=m::stop,modifier=Modifier.testTag("directions-stop")) { Text("Stop directions") }
                    }
                } }
            }
            item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick=onMap) { Text("View journey map") }
                TextButton(onClick={
                    val point=session?.step?.target ?: plan.steps.firstOrNull()?.target
                    if(point!=null) runCatching { c.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${point.latitude},${point.longitude}&travelmode=walking"))) }
                }) { Text("Open current stop in Google") }
            } }
            itemsIndexed(plan.steps,key={ _,s->s.id }) { i,step ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text("${i+1}. ${step.title}",style=MaterialTheme.typography.titleMedium)
                    Text(step.detail,style=MaterialTheme.typography.bodySmall)
                    step.scheduledAt?.let { Text("Scheduled ${java.time.Instant.ofEpochMilli(it).atZone(CapeTownZone).toLocalTime().toString().take(5)}",style=MaterialTheme.typography.labelSmall) }
                    step.distanceMeters?.let { Text("${it.toInt()} m · ${step.durationSeconds?.let { s->kotlin.math.ceil(s/60).toInt() }} min estimated",style=MaterialTheme.typography.labelSmall) }
                } }
            }
            if(session==null) item { TextButton(onClick=m::stop) { Text("Clear directions") } }
        }
    }
}
@Composable fun LiveDirectionsSetup() {
    val c=LocalContext.current
    var promoted by remember { mutableStateOf(DirectionsNotifications.promotionAllowed(c)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { promoted=DirectionsNotifications.promotionAllowed(c);onPauseOrDispose { } }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Live notifications",style=MaterialTheme.typography.titleMedium)
        Text(if(Build.VERSION.SDK_INT<36) "This Android version uses an ongoing directions notification. Android 16 adds Live Updates." else when(promoted) { true->"Android allows promoted notifications.";false->"Promoted notifications are disabled in device settings.";null->"This firmware decides which notifications can appear as Live Updates." })
        Text("On One UI 8, check Settings → Lock screen and AOD → Now Bar, and CT Trains notification settings. Allow lock-screen content if you want the instruction there. Samsung controls eligibility; the app cannot force Now Bar placement.",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={ runCatching { c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName)) } }) { Text("Notification settings") }
            if(Build.VERSION.SDK_INT>=36) TextButton(onClick={
                val live=Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").setData(android.net.Uri.parse("package:${c.packageName}"))
                if(live.resolveActivity(c.packageManager)!=null) c.startActivity(live)
                else c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName))
            }) { Text("Live Update settings") }
        }
        Text("Pause stops GPS sampling. Stop ends the session. Boarding and getting off require your confirmation; passing a station or a scheduled time will not advance those steps.",style=MaterialTheme.typography.bodySmall)
    } }
}
