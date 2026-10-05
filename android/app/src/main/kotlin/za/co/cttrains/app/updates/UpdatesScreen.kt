@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.updates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import za.co.cttrains.app.BuildConfig

@Composable fun UpdatesScreen() {
    val m: UpdatesModel=androidx.lifecycle.viewmodel.compose.viewModel()
    val s by m.state.collectAsStateWithLifecycle()
    val c=LocalContext.current;val uri=LocalUriHandler.current
    var setup by remember { mutableStateOf(false) };var help by remember { mutableStateOf(false) };var notes by remember { mutableStateOf(false) }
    var repo by remember(s.settings.repository) { mutableStateOf(s.settings.repository) }
    var token by remember { mutableStateOf("") }
    var beta by remember(s.settings.beta) { mutableStateOf(s.settings.beta) };var auto by remember(s.settings.automatic) { mutableStateOf(s.settings.automatic) }
    var downloadAfterPermission by remember { mutableStateOf(false) }
    val notificationPermission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        if(downloadAfterPermission) m.download()
        downloadAfterPermission=false
    }
    fun askNotifications(forDownload: Boolean) {
        if(android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(c,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            downloadAfterPermission=forDownload
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else if(forDownload) m.download()
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { m.refresh();onPauseOrDispose { } }
    LazyColumn(Modifier.fillMaxSize().testTag("app-updates-screen"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("App updates",style=MaterialTheme.typography.headlineSmall) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Current · ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.titleLarge)
            Text("Your installed app stays available while the next version downloads. Android replaces it only after verification and your installation confirmation.")
            Text("Source · ${s.settings.repository}",style=MaterialTheme.typography.labelSmall)
            Button(onClick=m::check,enabled=!s.checking && !s.downloading,modifier=Modifier.testTag("check-app-updates")) { Text(if(s.checking) "Checking GitHub…" else "Check for updates") }
            s.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) };s.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            if(s.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        } } }
        if(s.downloading) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Downloading next version",style=MaterialTheme.typography.titleMedium)
            if(s.total>0) { LinearProgressIndicator(progress={ s.bytes.toFloat()/s.total },modifier=Modifier.fillMaxWidth());Text("${s.bytes/1024/1024} / ${s.total/1024/1024} MB") } else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick=m::cancel) { Text("Cancel download") }
        } } }
        s.staged?.let { ready -> item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Next · ${ready.manifest.versionName}",style=MaterialTheme.typography.titleLarge)
            Text("Downloaded · checksum, package, version and APK signature checked",style=MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={ m.install(c) },enabled=!s.downloading) { Text("Install verified update") }
                TextButton(onClick=m::discard,enabled=!s.downloading) { Text("Discard download") }
            }
        } } } }
        s.candidate?.let { update -> item { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Available · ${update.manifest.versionName}",style=MaterialTheme.typography.titleMedium)
            Text("${update.manifest.sizeBytes/1024/1024} MB · ${if(update.release.prerelease) "Beta" else "Stable"}",style=MaterialTheme.typography.labelSmall)
            if(s.staged?.manifest?.versionCode!=update.manifest.versionCode) Button(onClick={ askNotifications(true) },enabled=!s.downloading) { Text("Download update") }
            TextButton(onClick={ notes=!notes }) { Text(if(notes) "Hide release notes" else "Release notes") }
            if(notes) Text(update.release.notes.ifBlank { "No release notes provided." },style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ uri.openUri("https://github.com/${update.repo}/releases/tag/${update.release.tag}") }) { Text("View on GitHub") }
        } } } }
        item { TextButton(onClick={ setup=!setup }) { Text(if(setup) "Hide update settings" else "Update source & channel") } }
        if(setup) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(repo,{ repo=it.take(150) },label={ Text("GitHub owner/repository") },singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(token,{ token=it.take(300) },label={ Text(if(s.settings.token.isEmpty()) "Read-only token · private repos only" else "Token saved · enter to replace") },singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
            Text("Public releases need no token. Private releases require Contents: read access to this repository. The token stays encrypted on this device and is excluded from backups.",style=MaterialTheme.typography.bodySmall)
            Row { Checkbox(beta,{ beta=it });Text("Include beta releases",Modifier.weight(1f).padding(top=12.dp)) }
            Row { Checkbox(auto,{ auto=it });Text("Check daily when connected",Modifier.weight(1f).padding(top=12.dp)) }
            Button(onClick={ m.save(repo,token.takeIf { it.isNotBlank() },beta,auto);token="";if(auto) askNotifications(false) },enabled=!s.downloading) { Text("Save update settings") }
            if(s.settings.token.isNotEmpty()) TextButton(onClick={ m.save(repo,"",beta,auto);token="" }) { Text("Remove saved token") }
        } } }
        item { TextButton(onClick={ help=!help }) { Text(if(help) "Hide setup guide" else "How do updates work?") } }
        if(help) item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("1. Use this repo, or a public repository that publishes CT Trains releases. Each release must contain its signed APK and update.json.")
            Text("2. For a private repo, create a fine-grained GitHub token for only that repo, with Contents: read-only. Set an expiry and enter it above. Never use your password or a write/admin token.")
            Text("3. Tap Check, review the release, then Download. A failed or cancelled download leaves the current app installed.")
            Text("4. Tap Install verified update. Android may ask you to allow installs from CT Trains and confirm installation. Your local data stays in place during a compatible upgrade.")
            Text("This is app-level staging, not Android system A/B slots. Automatic APK downgrades or rollback after a successful install are not available to an ordinary app. Keep a profile backup; recovery needs a compatible release with a higher version code.",style=MaterialTheme.typography.bodySmall)
            Text("Publisher: keep the same private signing key for every release. CI includes update.json with the APK checksum and certificate. Releases signed with another key are rejected. Do not commit a signing key or a GitHub token.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ uri.openUri("https://github.com/settings/personal-access-tokens/new") }) { Text("Create a read-only GitHub token") }
        } }
    }
}
