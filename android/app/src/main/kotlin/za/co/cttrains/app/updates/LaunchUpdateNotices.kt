package za.co.cttrains.app.updates

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.cttrains.app.BuildConfig
import za.co.cttrains.core.*

/** Non-blocking ordinary notice, recurring security dialog only for affected builds. */
@Composable fun LaunchUpdateNotices(model: UpdatesModel,policy: ControlPolicy,onUpdates: ()->Unit) {
 val s by model.state.collectAsStateWithLifecycle();val c=LocalContext.current
 val store=remember(c){UpdateStore(c)}
 var foreground by remember { mutableIntStateOf(0) }
 var securityOpen by remember {mutableStateOf(false)}
 var securityShown by remember {mutableStateOf("")}
 val snackbar=remember {SnackbarHostState()}
 androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {foreground++;onPauseOrDispose{}}
 LaunchedEffect(policy.id,foreground) {
  val active=policy.applies(BuildConfig.VERSION_CODE.toLong(),System.currentTimeMillis()/1000)
  if(active && policy.kind=="security" && policy.releaseTag.isNotBlank()) model.checkTarget(policy.releaseTag)
 }
 LaunchedEffect(s.candidate?.manifest?.versionCode,policy.id,foreground) {
  val candidate=s.candidate;val now=System.currentTimeMillis()/1000
  val security=UpdatePrompts.showSecurity(policy,BuildConfig.VERSION_CODE.toLong(),now,candidate?.release?.tag)
  if(security) {
   val key="${policy.id}:$foreground"
   if(securityShown!=key) {securityShown=key;securityOpen=true}
  } else if(candidate!=null && UpdatePrompts.showNormal(candidate.manifest.versionCode,store.seenVersions())) {
   store.sawVersion(candidate.manifest.versionCode)
   if(snackbar.showSnackbar("CT Trains ${candidate.manifest.versionName} is available.","Review",withDismissAction=true,duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) onUpdates()
  } else if(!store.guideShown()) {
   store.guideShown(true)
   if(snackbar.showSnackbar("Updates download first. Android asks before installing; reopen CT Trains afterwards.","How it works",withDismissAction=true,duration=SnackbarDuration.Long)==SnackbarResult.ActionPerformed) onUpdates()
  }
 }
 // A floating snackbar leaves maps and tests usable while the first-install guide is shown.
 androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.BottomCenter) {
  SnackbarHost(snackbar,modifier=androidx.compose.ui.Modifier.padding(start=16.dp,end=16.dp,bottom=96.dp))
 }
 if(securityOpen) AlertDialog(onDismissRequest={securityOpen=false},title={Text("Security update recommended")},text={Text(policy.message+"\n\nThis reminder returns when you reopen the app while your build is affected. Android still asks before installing.")},confirmButton={TextButton(onClick={securityOpen=false;onUpdates()}){Text("Review update")}},dismissButton={TextButton(onClick={securityOpen=false}){Text("Later")}})
}
