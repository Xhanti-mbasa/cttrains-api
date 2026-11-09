@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
package za.co.cttrains.app.whatsapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import za.co.cttrains.app.ui.checked
import za.co.cttrains.app.ui.shareText
import za.co.cttrains.core.*

@Composable fun WhatsAppScreen(onReports: ()->Unit,onTrips: ()->Unit) {
    val model: WhatsAppModel=androidx.lifecycle.viewmodel.compose.viewModel()
    val s by model.state.collectAsStateWithLifecycle()
    val c=s.connection
    val context=LocalContext.current
    val uri=LocalUriHandler.current
    val owner=LocalLifecycleOwner.current
    var server by rememberSaveable { mutableStateOf("") }
    var session by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var feedId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(WhatsAppFeedKind.CHANNEL) }
    var service by rememberSaveable { mutableStateOf(TransportService.PRASA) }
    var review by remember { mutableStateOf<WhatsAppPost?>(null) }
    var backgroundConsent by remember { mutableStateOf(false) }
    var guide by rememberSaveable { mutableStateOf(false) }
    var manualSession by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(c?.identity,c?.enabled,c?.feeds,owner) {
        if(c?.run { enabled && feeds.isNotEmpty() }==true) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) { model.refresh();delay(60_000) }
        }
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("WhatsApp updates · alpha",style=MaterialTheme.typography.titleLarge)
            Text("Read selected transit channels or groups through your OpenWA gateway. The inbox is separate from official schedules and community chat.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ guide=true }) { Text("How to set this up") }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onReports) { Text("Share a delay report") }
                OutlinedButton(onClick=onTrips) { Text("Share a safety trip") }
            }
        }
        if(c==null) item {
            Text("Connect an OpenWA gateway",style=MaterialTheme.typography.titleMedium)
            Text("OpenWA runs on a separate computer/server with a linked WhatsApp account. This Android app does not include that server or read the installed WhatsApp app. Channel reading requires the whatsapp-web.js engine.",style=MaterialTheme.typography.bodySmall)
            Text("Use a dedicated account and a restricted Reader API key. OpenWA is unofficial; its maintainers warn that WhatsApp may restrict automated accounts.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(server,{ server=it.take(500);session="";model.clearSessions() },label={ Text("Gateway website · HTTPS") },supportingText={ Text("For example https://your-host. We add /api for a website address; a custom API path is kept.") },singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(key,{ key=it.take(500);session="";model.clearSessions() },label={ Text("Reader API key") },supportingText={ Text("OpenWA calls this the viewer role. Keep admin keys in the gateway dashboard.") },visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={ model.discover(server,key) },enabled=server.isNotBlank() && key.isNotBlank() && !s.busy) { Text("Find linked accounts") }
            s.sessions.forEach { linked ->
                FilterChip(session==linked.id,{ session=linked.id },label={ Text("${linked.name.ifBlank { "Linked account" }} · ${linked.status}") })
            }
            TextButton(onClick={ manualSession=!manualSession }) { Text(if(manualSession) "Hide account ID" else "Enter account ID manually") }
            if(manualSession) OutlinedTextField(session,{ session=it.take(100) },label={ Text("OpenWA session ID · UUID") },supportingText={ Text("Copy the account's UUID from OpenWA Sessions, not its display name.") },singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(onClick={ model.connect(server,session,key) },enabled=server.isNotBlank() && session.isNotBlank() && key.isNotBlank() && !s.busy) { Text("Test & connect reader") }
        } else {
            item {
                Text(if(c.enabled) "Reader enabled" else "Reader paused",style=MaterialTheme.typography.titleMedium)
                Text("Your encrypted credentials stay on this installation and are excluded from profile backups. Requests go to your configured gateway.",style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick=model::pause) { Text(if(c.enabled) "Pause reader" else "Resume reader") }
                    TextButton(onClick=model::disconnect) { Text("Disconnect & erase inbox") }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("Background checks");Text("About every 15 minutes with a connection; Android may defer them.",style=MaterialTheme.typography.bodySmall) }
                    Switch(c.background,{ if(it) backgroundConsent=true else model.background(false) },enabled=c.enabled)
                }
            }
            item {
                Text("Selected feeds · ${c.feeds.size}/5",style=MaterialTheme.typography.titleMedium)
                TextButton(onClick=model::channels,enabled=c.enabled && !s.busy) { Text("Browse subscribed channels") }
            }
            items(s.channels.filter { ch -> c.feeds.none { it.id==ch.id } },key={ "channel:${it.id}" }) { channel ->
                TextButton(onClick={ model.add(channel.copy(service=service)) },enabled=c.enabled && !s.busy && c.feeds.size<5) { Text("Add ${channel.title}") }
            }
            items(c.feeds,key={ "feed:${it.id}" }) { feed -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("${feed.title} · ${feed.service.title}",style=MaterialTheme.typography.titleMedium)
                Text("${feed.kind} · ${feed.id}",style=MaterialTheme.typography.labelSmall)
                TextButton(onClick={ model.remove(feed.id) },enabled=!s.busy) { Text("Remove feed") }
            } } }
            item {
                Text("Add channel or group ID",style=MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    WhatsAppFeedKind.values().forEach { value -> FilterChip(kind==value,{ kind=value },label={ Text(value.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
                }
                FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { TransportService.values().forEach { v -> FilterChip(service==v,{ service=v },label={ Text(v.title) }) } }
                OutlinedTextField(title,{ title=it.take(100) },label={ Text("Feed label") },modifier=Modifier.fillMaxWidth())
                OutlinedTextField(feedId,{ feedId=it.take(80) },label={ Text(if(kind==WhatsAppFeedKind.CHANNEL) "ID ending @newsletter" else "ID ending @g.us") },modifier=Modifier.fillMaxWidth())
                val feed=runCatching { WhatsAppUpdates.validateFeed(WhatsAppFeed(feedId.trim(),title.trim(),kind,service)) }.getOrNull()
                Button(onClick={ model.add(feed!!);feedId="";title="" },enabled=c.enabled && feed!=null && c.feeds.size<5 && !s.busy) { Text("Save selected feed") }
                Text("Follow the actual PRASA channel in the linked account, then choose it from the list. No PRASA channel identity is assumed. Service labels are personal tags.",style=MaterialTheme.typography.bodySmall)
            }
            item {
                Text("Local inbox",style=MaterialTheme.typography.titleMedium)
                Text("Last check ${checked(s.inbox.checkedAt)}",style=MaterialTheme.typography.labelSmall)
                TextButton(onClick=model::refresh,enabled=c.enabled && c.feeds.isNotEmpty() && !s.busy) { Text("Refresh WhatsApp updates") }
                s.inbox.issue?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                if(s.inbox.posts.isEmpty()) Text("No posts saved yet. Connect a supported gateway and select a feed.",style=MaterialTheme.typography.bodySmall)
            }
            items(s.inbox.posts.filter { post -> c.feeds.any { it.id==post.feed.id } },key={ "post:${it.feed.id}:${it.id}" }) { post ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${post.feed.service.title} · ${post.feed.title}",style=MaterialTheme.typography.titleSmall)
                    Text(post.postedAt?.let(::checked) ?: "Posted time not supplied",style=MaterialTheme.typography.labelSmall)
                    Text(post.body,style=MaterialTheme.typography.bodyMedium)
                    Text("Unverified WhatsApp observation",style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={ review=post }) { Text("Review & share") }
                } }
            }
        }
        item { if(s.busy) LinearProgressIndicator(Modifier.fillMaxWidth());s.issue?.let { Text(it,color=MaterialTheme.colorScheme.error) } }
    }
    LaunchedEffect(c?.identity) { if(c!=null) key="" }
    if(guide) WhatsAppSetupGuide { guide=false }
    review?.let { post -> AlertDialog(onDismissRequest={ review=null },title={ Text("Review WhatsApp observation") },text={ Text(WhatsAppUpdates.shareText(post),Modifier.verticalScroll(rememberScrollState())) },confirmButton={ TextButton(onClick={ shareText(context,WhatsAppUpdates.shareText(post));review=null }) { Text("Open share sheet") } },dismissButton={ TextButton(onClick={ review=null }) { Text("Cancel") } }) }
    if(backgroundConsent) AlertDialog(onDismissRequest={ backgroundConsent=false },title={ Text("Allow background feed checks?") },text={ Text("The optional reader will contact your OpenWA gateway about every 15 minutes when Android permits it. Only selected feeds are cached locally; there is no automatic message sending or timetable rewriting.") },confirmButton={ TextButton(onClick={ model.background(true);backgroundConsent=false }) { Text("Enable") } },dismissButton={ TextButton(onClick={ backgroundConsent=false }) { Text("Cancel") } })
}
