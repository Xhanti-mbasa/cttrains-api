@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package za.co.cttrains.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.time.*
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import za.co.cttrains.app.*
import za.co.cttrains.core.*

@Composable fun HostedSettings(s: FeatureState,m: FeatureViewModel) {
    if(!s.extras.beta.hostedSharing) return
    var server by rememberSaveable { mutableStateOf(s.session?.server ?: "https://matrix.org") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(false) }
    val uri=LocalUriHandler.current
    Card { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Hosted community account",style=MaterialTheme.typography.titleLarge)
        Text("Matrix is an open protocol with public hosting. Use your own account on a trusted homeserver; no server hosted by you is required. Google profile sign-in does not create a Matrix account.")
        if(s.session==null) {
            OutlinedTextField(server,{ server=it.take(300) },label={ Text("HTTPS Matrix homeserver") },modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(user,{ user=it.take(300) },label={ Text("Matrix username / @name:server") },modifier=Modifier.fillMaxWidth(),singleLine=true)
            FilterChip(token,{ token=!token;password="" },label={ Text(if(token) "Use access token" else "Use password") })
            OutlinedTextField(password,{ password=it.take(8192) },label={ Text(if(token) "Token from your trusted Matrix client" else "Matrix password") },visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
            Button(onClick={ m.login(server,user,password,token);password="" },enabled=password.isNotBlank() && !s.busy) { Text("Connect Matrix") }
            TextButton(onClick={ uri.openUri("https://element.io/get-started") }) { Text("Create an account with a Matrix client") }
            Text("SSO-only servers can connect with a token obtained in their trusted client. Passwords are used for sign-in only. Public room messages and report tags can be read by room members and hosting providers.",style=MaterialTheme.typography.bodySmall)
        } else {
            Text("Connected: ${s.session.userId}\n${s.session.server}")
            TextButton(onClick={ m.logout() }) { Text("Disconnect and revoke this session") }
        }
    } }
}

@Composable fun CommunityScreen(train: TrainState,s: FeatureState,m: FeatureViewModel,model: TrainViewModel,onSettings: ()->Unit) {
    var service by rememberSaveable { mutableStateOf(TransportService.PRASA) }
    var tab by rememberSaveable { mutableStateOf("Reports") }
    var stop by rememberSaveable { mutableStateOf(train.from) }
    var route by rememberSaveable { mutableStateOf(train.selectedJourney?.legs?.firstOrNull()?.train.orEmpty()) }
    var direction by rememberSaveable { mutableStateOf(train.to) }
    var category by rememberSaveable { mutableStateOf(ReportCategory.DELAYED) }
    var detail by rememberSaveable { mutableStateOf("") }
    var observed by rememberSaveable { mutableStateOf(LocalDateTime.now(CapeTownZone).withSecond(0).withNano(0).toString()) }
    var room by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var online by rememberSaveable { mutableStateOf(false) }
    var friend by rememberSaveable { mutableStateOf("") }
    var tripId by remember(s.extras.trips) { mutableStateOf(s.extras.trips.lastOrNull()?.id) }
    var confirmLive by remember { mutableStateOf(false) }
    var watchNext by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val context=LocalContext.current
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(watchNext) m.live(true,true)
        else if(grants[Manifest.permission.ACCESS_FINE_LOCATION]==true || grants[Manifest.permission.ACCESS_COARSE_LOCATION]==true || androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED) m.live(true,false)
        else m.message("Location permission is needed to share position. Trip files still work without it.")
    }
    LaunchedEffect(Unit) { while(true) { delay(30_000);now=System.currentTimeMillis() } }
    LaunchedEffect(service,s.session?.userId,s.extras.beta.hostedSharing,tab,s.extras.rooms) {
        if(tab in listOf("Chat","Nearby") && s.session!=null && s.extras.beta.hostedSharing && s.extras.rooms.any { it.service==service }) while(true) { m.sync(service);delay(60_000) }
    }
    val report=runCatching { val at=LocalDateTime.parse(observed).atZone(CapeTownZone).toInstant().toEpochMilli();require(at<=now+30_000 && now-at<=CommunityReports.MAX_AGE);CommunityReports.validate(CommunityReport(id=java.util.UUID.randomUUID().toString(),service=service,stop=stop.trim(),route=route.trim(),direction=direction.trim(),observedAt=at,category=category,message=detail.trim())) }.getOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Community & friends · beta",style=MaterialTheme.typography.headlineSmall)
        FeatureStatus(s)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("Reports","Chat","Friends","Nearby").forEach { v->FilterChip(tab==v,{ tab=v },label={ Text(v) }) } }
        if(tab!="Friends") FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { TransportService.values().forEach { v->FilterChip(service==v,{ service=v },label={ Text(v.title) }) } }
        if(tab=="Reports") {
            Text("Observations are separate from official schedules. Reports expire after one hour. Counts mean distinct Matrix accounts, not verified people.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(stop,{ stop=it.take(180) },label={ Text("Stop / station where observed") },modifier=Modifier.fillMaxWidth())
            OutlinedTextField(route,{ route=it.take(100) },label={ Text("Route / train number") },modifier=Modifier.fillMaxWidth())
            OutlinedTextField(direction,{ direction=it.take(180) },label={ Text("Direction / destination") },modifier=Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { ReportCategory.values().forEach { v->FilterChip(category==v,{ category=v },label={ Text(v.title) }) } }
            OutlinedTextField(observed,{ observed=it.take(16) },label={ Text("Observed SAST · YYYY-MM-DDTHH:MM") },modifier=Modifier.fillMaxWidth())
            TextButton(onClick={ observed=LocalDateTime.now(CapeTownZone).withSecond(0).withNano(0).toString() }) { Text("Observed now") }
            OutlinedTextField(detail,{ detail=it.take(600) },label={ Text("Optional custom message") },modifier=Modifier.fillMaxWidth())
            if(s.extras.beta.hostedSharing && s.session!=null && s.extras.rooms.any { it.service==service }) Row { Checkbox(online,{ online=it });Text("Post to this service’s connected room") }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={ report?.let { m.report(it,online) } },enabled=report!=null) { Text(if(online) "Queue report" else "Save locally") }
                OutlinedButton(onClick={ report?.let { shareText(context,CommunityReports.text(it)) } },enabled=report!=null) { Text("Share to WhatsApp / other app") }
                TextButton(onClick={ report?.let { m.share(TripFiles.json.encodeToString(it),"ctreport","CT Trains community observation") } },enabled=report!=null) { Text("Share report file") }
            }
            if(report==null) Text("Enter a stop and an observation time within the last hour.",style=MaterialTheme.typography.bodySmall)
            if(s.extras.queued.isNotEmpty()) Text("${s.extras.queued.size} pending reports · sent when connected while still fresh",style=MaterialTheme.typography.bodySmall)
            Text("Recent observations",style=MaterialTheme.typography.titleLarge)
            val rows=s.extras.reports.filter { it.report.service==service && CommunityReports.fresh(it.report,now) }.sortedByDescending { it.report.observedAt }
            rows.distinctBy { CommunityReports.key(it.report)+it.report.category }.forEach { r->Card { Column(Modifier.padding(12.dp)) {
                Text("${r.report.category.title} · ${r.report.stop}",fontWeight=FontWeight.Bold)
                Text("${r.report.route} → ${r.report.direction}\n${checked(r.report.observedAt)}\n${r.report.message}")
                val accounts=CommunityReports.accounts(rows,r.report,now)
                Text(if(r.hosted) "$accounts distinct accounts · unverified" else "Local / imported observation · not included in account counts",style=MaterialTheme.typography.labelSmall)
                TextButton(onClick={ shareText(context,CommunityReports.text(r.report)) }) { Text("Share formatted report") }
            } } }
            if(rows.isEmpty()) Text("No fresh reports saved for this service.")
        } else if(tab=="Chat") {
            if(!s.ready) Text("Loading your saved community settings…")
            else if(!s.extras.beta.hostedSharing) {
                Text("Hosted community is off. Reports can still be shared through WhatsApp.")
                Button(onClick={ m.beta { it.copy(hostedSharing=true) } }) { Text("Enable hosted community") }
            } else if(s.session==null) {
                Text("Hosted community is enabled. Connect your Matrix account to send and receive room messages.")
                HostedSettings(s,m)
                TextButton(onClick=onSettings) { Text("Open account settings") }
            }
            else {
                Text("${service.title} room",style=MaterialTheme.typography.titleLarge)
                val configured=s.extras.rooms.firstOrNull { it.service==service }
                configured?.let { Text(it.room);TextButton(onClick={ shareText(context,"CT Trains ${service.title} community room: ${it.room}") }) { Text("Share room ID") } }
                OutlinedTextField(room,{ room=it.take(500) },label={ Text("Join room · #alias:server or !id:server") },modifier=Modifier.fillMaxWidth())
                FlowRow { OutlinedButton(onClick={ m.room(service,room,false) },enabled=room.isNotBlank() && !s.busy) { Text("Join") };TextButton(onClick={ m.room(service,"",true) },enabled=!s.busy) { Text("Create public service room") };TextButton(onClick={ m.sync(service) },enabled=configured!=null && !s.busy) { Text("Refresh") } }
                Text("Public room messages are readable by members and hosting providers. Do not post home addresses or live positions here.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(draft,{ draft=it.take(1500) },label={ Text("Community message") },modifier=Modifier.fillMaxWidth())
                if(Regex("\\bdelay(?:ed|s)?\\b",RegexOption.IGNORE_CASE).containsMatchIn(draft)) TextButton(onClick={ detail=draft;category=ReportCategory.DELAYED;tab="Reports" }) { Text("Turn this into a structured delay report") }
                Button(onClick={ m.chat(service,draft) { draft="" } },enabled=draft.isNotBlank() && configured!=null && !s.busy) { Text("Send to room") }
                s.chat.takeIf { s.chatService==service }.orEmpty().takeLast(100).forEach { message->Card { Column(Modifier.padding(12.dp)) { Text(message.sender,style=MaterialTheme.typography.labelMedium);Text(message.text);Text(checked(message.at),style=MaterialTheme.typography.labelSmall) } } }
            }
        } else if(tab=="Nearby") {
            NearbyUsers(train,model,s,m,service,onSettings)
        } else {
            Text("Live trips are optional, require internet and a Matrix account, and stop within two hours. Position checks are roughly once a minute. ‘At station’ and ‘On board’ are your manual check-ins; location alone cannot prove boarding.")
            Text("Private invitation files contain the trip key. Keep them with the invited friend. This experimental feature has not received an independent security audit.",style=MaterialTheme.typography.bodySmall)
            if(!s.extras.beta.hostedSharing || s.session==null) Text("Connect a hosted Matrix account in Settings first.")
            else {
                Text("Create a private trip",style=MaterialTheme.typography.titleLarge)
                s.extras.trips.takeLast(20).forEach { t->FilterChip(tripId==t.id,{ tripId=t.id },label={ Text("${t.steps.first().label} → ${t.steps.last().label}") }) }
                OutlinedTextField(friend,{ friend=it.take(500) },label={ Text("Friend’s Matrix ID · @name:server") },modifier=Modifier.fillMaxWidth())
                Button(onClick={ s.extras.trips.firstOrNull { it.id==tripId }?.let { m.createCircle(it,friend) } },enabled=tripId!=null && friend.startsWith("@") && !s.busy) { Text("Create invitation · sharing stays off") }
                s.live?.let { live ->
                    Text("${live.link.trip.owner} · expires ${checked(live.link.expiresAt)}")
                    TextButton(onClick={ m.share(TripFiles.json.encodeToString(live.link),"ctcircle","Private CT Trains invitation · keep this file private") },enabled=live.link.expiresAt>now) { Text("Share invitation file") }
                    if(live.link.expiresAt>now) {
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { CircleUpdates.phases.forEach { p->FilterChip(live.phase==p,{ m.phase(p) },label={ Text(p) }) } }
                        Button(onClick={ confirmLive=true },enabled=!live.sharing) { Text("Start sharing my location") }
                    }
                }
                if(s.extras.friends.isNotEmpty()) {
                    OutlinedButton(onClick={ watchNext=true;val p=if(Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray();if(p.isEmpty()) m.live(true,true) else permissions.launch(p) }) { Text("Watch imported invitations · 2 hours") }
                    s.extras.friends.forEach { link->val update=s.watched.firstOrNull { it.id==link.id }?.update;Card { Column(Modifier.padding(12.dp)) {
                        Text(link.trip.owner,fontWeight=FontWeight.Bold)
                        if(link.expiresAt<=now) Text("Invitation expired") else if(update==null || now-update.sentAt>5*60_000) Text("No recent update · current position unknown") else Text("${update.phase}${update.near?.let { " · near $it" }.orEmpty()}\nLast shared ${checked(update.sentAt)}")
                        TextButton(onClick={ m.removeFriend(link.id) }) { Text("Remove invitation") }
                    } } }
                }
                TextButton(onClick={ m.stopLive() }) { Text("Stop sharing and watching") }
            }
        }
    }
    if(confirmLive) AlertDialog(onDismissRequest={ confirmLive=false },title={ Text("Share this trip’s live position?") },text={ Text("Your position and manual check-ins will be sent to the private trip room until its invitation expires, for at most two hours. Android will show a persistent notification with Stop. Offline positions are not queued.") },confirmButton={ TextButton(onClick={ confirmLive=false;watchNext=false;val p=mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION);if(Build.VERSION.SDK_INT>=33) p+=Manifest.permission.POST_NOTIFICATIONS;permissions.launch(p.toTypedArray()) }) { Text("Allow and start") } },dismissButton={ TextButton(onClick={ confirmLive=false }) { Text("Cancel") } })
}
