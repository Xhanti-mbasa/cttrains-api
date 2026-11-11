@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package za.co.cttrains.app.developer

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.*
import za.co.cttrains.app.*
import za.co.cttrains.app.ui.BetaSettings
import za.co.cttrains.app.ui.SmartCardsSettings
import java.time.Instant

val DeveloperScreens=linkedMapOf("map" to "Map & Home","plan" to "Journey planner","saved" to "Saved routes","places" to "Saved places","upcoming" to "Journey reminders","budget" to "Budget","alerts" to "Service alerts","buses" to "Bus timetables","trips" to "Trip history & safety","ride-departures" to "Follow-up departures","community" to "Community & sharing","whatsapp" to "WhatsApp updates","directions" to "Directions")

@Composable fun DeveloperScreen(model: DeveloperModel,feature: FeatureState,features: FeatureViewModel,trains: TrainViewModel,onSettings: ()->Unit) {
 val s by model.state.collectAsStateWithLifecycle();val c=LocalContext.current;val uri=LocalUriHandler.current
 var code by remember { mutableStateOf("") };var report by remember { mutableStateOf("") }
 var min by remember { mutableStateOf("1") };var max by remember { mutableStateOf(BuildConfig.VERSION_CODE.toString()) }
 var tag by remember { mutableStateOf("") };var message by remember { mutableStateOf("") };var security by remember { mutableStateOf(true) };var maintenance by remember { mutableStateOf(false) };var confirm by remember { mutableStateOf(false) }
 var selectedId by remember { mutableStateOf("") }
 val saveReport=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")) { target ->
  if(target!=null) runCatching { c.contentResolver.openOutputStream(target)?.bufferedWriter()?.use { it.write(report) } }
 }
 DisposableEffect(Unit) { val window=(c as? Activity)?.window;val already=window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)!=0
  window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE);onDispose { model.hideCodes();if(!already) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
 }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Text("Developer options",style=MaterialTheme.typography.headlineMedium)
  Text("Server access is verified on opening and every minute while this app is in use. Local screen switches reset when access expires or you lock developer mode. Android permissions still apply.")
  s.error?.let { Text(it,color=MaterialTheme.colorScheme.error) };if(s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  if(!s.verified) {
   OutlinedTextField(code,{code=it.take(80)},label={Text("Single-use server code or owner code")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
   Button(onClick={model.unlock(code);code=""},enabled=code.isNotBlank()&&!s.busy) {Text("Verify access")}
   Text("Ask Xhanti for a code. Each invitation works once across all devices. Tester access lasts seven days; owner sessions last one hour. An internet connection is required.")
  } else {
   Text("${s.session?.role?.replaceFirstChar { it.uppercase() }} · expires ${s.session?.expires?.let { Instant.ofEpochSecond(it) }}",style=MaterialTheme.typography.labelMedium)
   TextButton(onClick=model::lock) {Text("Lock developer options")}
   Text("Screen availability · this device",style=MaterialTheme.typography.titleLarge)
   DeveloperScreens.forEach { (route,label)->Row {
    Text(label,Modifier.weight(1f).padding(top=12.dp));Switch(route !in s.disabled,{model.toggle(route)
     if(route=="directions") za.co.cttrains.app.directions.DirectionsService.stop(c)
     if(route=="community") {features.stopLive();features.stopNearby()}
     if(route=="trips") trains.stopRideDetection()
    })
   } }
   Text("Feature controls",style=MaterialTheme.typography.titleLarge)
   SmartCardsSettings(feature,features)
   BetaSettings(feature,features,onSettings,onSettings,onSettings,onSettings)
   Text("Privacy-sensitive features still use their normal consent and permission flows. Disabling a screen stops its active directions, sharing or boarding session where applicable; it does not delete saved data.",style=MaterialTheme.typography.bodySmall)
   Text("Diagnostics",style=MaterialTheme.typography.titleLarge)
   Button(onClick={report=model.diagnostics()}) {Text("Inspect memory, storage & developer log")}
   if(report.isNotBlank()) { SelectionContainer {Text(report,style=MaterialTheme.typography.bodySmall)};TextButton(onClick={saveReport.launch("cttrains-diagnostics.txt")}){Text("Save reviewed report")}}
   TextButton(onClick={model.clearLogs();report=""}) {Text("Clear developer log")}
   Button(onClick={trains.clearCache()}) {Text("Clear timetable cache")}
   OutlinedButton(onClick={trains.stopRideDetection();features.stopLive();features.stopNearby();za.co.cttrains.app.directions.DirectionsService.stop(c);za.co.cttrains.app.scheduling.WalkLearningService.stop(c)}) {Text("Stop active test sessions")}
   Text("Experimental edition",style=MaterialTheme.typography.titleLarge)
   Text("The redesigned edition installs alongside CT Trains with its own data. It is not published yet. This button opens its releases; Android will ask before installing an APK.")
   OutlinedButton(onClick={uri.openUri("https://github.com/Xhanti-mbasa/cttrains-api/releases?q=experimental")}) {Text("Experimental downloads")}
   if(s.session?.role=="owner") {
    HorizontalDivider();Text("Owner controls",style=MaterialTheme.typography.titleLarge)
    Text("Tester invitations cannot use these actions. Codes are shown once and never stored by the app. Save them privately before leaving this page.")
    Button(onClick={model.owner("codes",buildJsonObject{put("replace",false)})},enabled=!s.busy){Text("Generate 16 single-use codes")}
    OutlinedButton(onClick={model.owner("codes",buildJsonObject{put("replace",true)})},enabled=!s.busy){Text("Replace unused codes with 16 new codes")}
    if(s.codes.isNotEmpty()) {Text(if(s.codes.size==1) "New owner code · save privately" else "16 invitations · valid for 30 days");SelectionContainer{Text(s.codes.joinToString("\n"),style=MaterialTheme.typography.bodySmall)};TextButton(onClick=model::hideCodes){Text("Hide codes")}}
    OutlinedButton(onClick={model.owner("master",buildJsonObject{})},enabled=!s.busy){Text("Rotate owner code · save the new code") }
    TextButton(onClick={model.owner("access",buildJsonObject{})},enabled=!s.busy){Text("Access IDs & server audit")}
    if(s.access.isNotBlank()) SelectionContainer {Text(s.access,style=MaterialTheme.typography.bodySmall)}
    OutlinedTextField(selectedId,{selectedId=it.take(50)},label={Text("Access ID to revoke")},singleLine=true,modifier=Modifier.fillMaxWidth())
    TextButton(onClick={model.owner("revoke",buildJsonObject{put("id",selectedId)});selectedId=""},enabled=selectedId.isNotBlank()&&!s.busy){Text("Revoke selected access")}
    TextButton(onClick={model.owner("revoke",buildJsonObject{put("all",true)})},enabled=!s.busy){Text("Revoke all testers & unused invitations")}
    Text("Build-targeted notice",style=MaterialTheme.typography.titleLarge)
    Text("This does not build or silently install an APK. Publish a signed GitHub release first. A security notice repeats on foregrounding until a compatible update is installed. Notices and maintenance expire after one hour here, at most 24 hours on the server.")
    OutlinedTextField(min,{min=it.take(12)},label={Text("Affected minimum version code")},singleLine=true)
    OutlinedTextField(max,{max=it.take(12)},label={Text("Affected maximum version code")},singleLine=true)
    OutlinedTextField(tag,{tag=it.take(100)},label={Text("Target GitHub release tag (optional)")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(message,{message=it.take(500)},label={Text("User-visible explanation")},modifier=Modifier.fillMaxWidth())
    Row {Checkbox(security,{security=it});Text("Recurring security notice",Modifier.padding(top=12.dp))}
    if(BuildConfig.ALLOW_REMOTE_MAINTENANCE) Row {Checkbox(maintenance,{maintenance=it});Text("Temporary maintenance for affected builds",Modifier.weight(1f).padding(top=12.dp))}
    Button(onClick={confirm=true},enabled=!s.busy&&min.toLongOrNull()!=null&&max.toLongOrNull()!=null&&message.isNotBlank()){Text("Review notice")}
    OutlinedButton(onClick={model.owner("policy",buildJsonObject{put("clear",true)})},enabled=!s.busy){Text("Clear notice & maintenance now")}
   }
  }
  TextButton(onClick=onSettings){Text("Back to Settings")}
 }
 if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Publish to affected users?")},text={Text("Build codes $min–$max\nTarget: ${tag.ifBlank { "latest compatible release" }}\nSecurity: $security\nMaintenance: $maintenance\nExpires in one hour\n\n$message\n\nSettings, backups and updates remain accessible. Older apps without these controls cannot receive this notice.")},confirmButton={TextButton(onClick={confirm=false;model.owner("policy",buildJsonObject{put("kind",if(security)"security" else "normal");put("minCode",min.toLong());put("maxCode",max.toLong());put("releaseTag",tag.trim());put("message",message);put("expires",System.currentTimeMillis()/1000+3600);put("maintenance",maintenance && BuildConfig.ALLOW_REMOTE_MAINTENANCE)})}){Text("Publish notice")}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancel")}})
}
