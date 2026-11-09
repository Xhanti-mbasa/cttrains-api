package za.co.cttrains.app.scheduling

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import za.co.cttrains.app.MainActivity
import za.co.cttrains.app.data.*
import za.co.cttrains.app.location.ForegroundLocation
import za.co.cttrains.core.*

/** Explicit, time-limited session. No boot receiver, silent restart or background-location permission. */
class TripLiveService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job: Job?=null
    private lateinit var store: FeatureStore
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onCreate() { super.onCreate();store=FeatureStore(this) }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="stop") { stopSelf();return START_NOT_STICKY }
        if(job?.isActive==true) return START_NOT_STICKY
        val watch=intent?.getBooleanExtra("watch",false) ?: true
        if(!watch && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) { stopSelf();return START_NOT_STICKY }
        val notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("trip-sharing","Live trip sharing",NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel("friend-checkins","Friend check-ins",NotificationManager.IMPORTANCE_DEFAULT))
        val stop=PendingIntent.getService(this,900,Intent(this,TripLiveService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,901,Intent(this,MainActivity::class.java).putExtra("community",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"trip-sharing").setSmallIcon(za.co.cttrains.app.R.drawable.ic_train)
            .setContentTitle(if(watch) "Watching shared trips" else "Sharing your trip location")
            .setContentText("Ends within two hours. Tap Stop at any time.").setContentIntent(open).setOngoing(true).addAction(0,"Stop",stop).build()
        val type=if(Build.VERSION.SDK_INT>=29) { if(watch) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION } else 0
        ServiceCompat.startForeground(this,980,notification,type)
        activeMode=if(watch) "watching" else "sharing"
        job?.cancel();job=scope.launch {
            val client=MatrixClient();val started=System.currentTimeMillis();val elapsed=android.os.SystemClock.elapsedRealtime()
            try {
                val s=store.session() ?: return@launch
                if(!store.extras().beta.hostedSharing) return@launch
                if(watch) store.extras().friends.filter { it.expiresAt>started }.forEach { runCatching { client.join(s,it.room) } }
                else { val circle=store.liveCircle() ?: return@launch;require(circle.link.ownerId==s.userId);CircleUpdates.validate(circle.link,started);store.saveCircle(circle.copy(sharing=true)) }
                while(isActive && android.os.SystemClock.elapsedRealtime()-elapsed<2*60*60_000L) {
                    val now=System.currentTimeMillis();val extras=store.extras()
                    if(!extras.beta.hostedSharing || store.session()!=s) break
                    if(!watch) {
                        val c=store.liveCircle() ?: break;if(c.link.expiresAt<=now || c.link.ownerId!=s.userId) break
                        val location=ForegroundLocation.sample(this@TripLiveService)?.takeIf { it.capturedAt<=now+30_000 && now-it.capturedAt<=120_000 }
                        val near=location?.let { p -> NearbySuggestions.station(GeoPoint(p.latitude,p.longitude),p.accuracyMeters,store.store.stations.first())?.name }
                        val update=FriendUpdate(c.link.id,now,now,c.link.expiresAt,c.phase,location?.let { GeoPoint(it.latitude,it.longitude) },location?.accuracyMeters,near)
                        try { client.send(s,c.link.room,"Encrypted CT Trains trip update",update=CircleUpdates.seal(c.link,update),transaction="trip-$now") }
                        catch(e: Exception) { if(e is CancellationException) throw e } // No offline location queue.
                    }
                    val watched=(mutablePositions.value+store.watched()).groupBy { it.id }.mapValues { (_,rows)->rows.maxBy { it.update.sequence } }.toMutableMap()
                    var count=0
                    extras.friends.filter { it.expiresAt>now }.take(20).forEach { link ->
                        count++
                        try {
                            val previous=watched[link.id]?.update
                            val messages=client.messages(s,link.room,true)
                            val valid=messages.mapNotNull { m->m.update?.let { sealed -> runCatching { CircleUpdates.open(link,sealed,m.sender,now,previous?.sequence ?: -1) }.getOrNull() } }.maxByOrNull { it.sequence }
                            if(valid!=null) {
                                watched[link.id]=WatchedFriend(link.id,valid)
                                if(previous?.phase!=valid.phase && valid.phase !in listOf("Ready","Stopped") && notificationsAllowed(this@TripLiveService)) {
                                    val note=NotificationCompat.Builder(this@TripLiveService,"friend-checkins").setSmallIcon(za.co.cttrains.app.R.drawable.ic_train)
                                        .setContentTitle("${link.trip.owner}: ${valid.phase}").setContentText("${valid.near?.let { "Near $it · " }.orEmpty()}Shared check-in; open to see when it was sent.")
                                        .setContentIntent(open).setAutoCancel(true).build()
                                    notifications.notify(link.id.hashCode(),note)
                                }
                            }
                        } catch(e: Exception) { if(e is CancellationException) throw e }
                    }
                    val fresh=watched.values.filter { now-it.update.sentAt<=5*60_000 && it.update.expiresAt>now }
                    mutablePositions.value=fresh
                    store.saveWatched(fresh) // Only check-in/replay metadata is persisted; coordinates remain in memory.
                    if(watch && count==0) break
                    delay(60_000)
                }
            } catch(e: Exception) { if(e is CancellationException) throw e }
            finally { try { withContext(NonCancellable) { runCatching { store.liveCircle()?.let { store.saveCircle(it.copy(sharing=false)) } } } } finally { stopSelf() } }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { activeMode=null;mutablePositions.value=emptyList();job?.cancel();scope.cancel();super.onDestroy() }
    companion object {
        private val mutablePositions=MutableStateFlow<List<WatchedFriend>>(emptyList())
        val positions=mutablePositions.asStateFlow()
        @Volatile var activeMode: String? = null
            private set
        fun start(context: Context,watch: Boolean) { ContextCompat.startForegroundService(context,Intent(context,TripLiveService::class.java).putExtra("watch",watch)) }
    }
}
