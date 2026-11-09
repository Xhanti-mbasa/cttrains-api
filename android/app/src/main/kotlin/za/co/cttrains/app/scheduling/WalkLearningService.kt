package za.co.cttrains.app.scheduling

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import za.co.cttrains.app.R
import za.co.cttrains.app.MainActivity
import za.co.cttrains.app.data.FeatureStore
import za.co.cttrains.app.location.ForegroundLocation
import za.co.cttrains.core.*

/** User-started walking segments only; aggregate durations, never a GPS trail. */
class WalkLearningService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var task: Job?=null
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="stop") { stopSelf();return START_NOT_STICKY }
        if(task?.isActive==true) return START_NOT_STICKY
        val store=FeatureStore(this)
        val leg=runCatching { store.json.decodeFromString<WalkingLeg>(intent?.getStringExtra("leg") ?: "") }.getOrNull()
        if(leg==null || leg.distanceMeters !in 50.0..20000.0) { stopSelf();return START_NOT_STICKY }
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("walking-learning","Walking time learning",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,0,Intent(this,WalkLearningService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"walking-learning").setSmallIcon(R.drawable.ic_train).setContentTitle("Learning this walk")
            .setContentText("${leg.from.label} → ${leg.to.label} · stops within 2 hours").setContentIntent(open).setOngoing(true).addAction(0,"Stop",stop).build()
        try { startForeground(1905,notification);active.value=true } catch(_: Exception) { stopSelf();return START_NOT_STICKY }
        task=scope.launch {
            try {
                if(!store.extras().beta.walkLearning) return@launch
                val first=ForegroundLocation.sample(this@WalkLearningService) ?: return@launch
                if(first.accuracyMeters>60 || System.currentTimeMillis()-first.capturedAt !in 0..30_000 || NearbySuggestions.distance(GeoPoint(first.latitude,first.longitude),leg.from.point)>100) return@launch
                val beganAt=System.currentTimeMillis();val began=SystemClock.elapsedRealtime();var goalAt: Long?=null
                while(SystemClock.elapsedRealtime()-began<2*60*60_000L) {
                    delay(20_000)
                    if(!store.extras().beta.walkLearning) break
                    val p=ForegroundLocation.sample(this@WalkLearningService) ?: continue
                    if(p.accuracyMeters>60 || System.currentTimeMillis()-p.capturedAt !in 0..30_000) continue
                    val reached=NearbySuggestions.distance(GeoPoint(p.latitude,p.longitude),leg.to.point)<=60
                    val elapsed=SystemClock.elapsedRealtime()
                    if(!reached) { goalAt=null;continue }
                    if(goalAt==null) { goalAt=elapsed;continue }
                    if(elapsed-goalAt>=15_000) {
                        val seconds=((goalAt-began)/1000).toInt()
                        runCatching { WalkingRoutes.sample(leg,seconds,System.currentTimeMillis()).copy(startedAt=beganAt) }.getOrNull()?.let { sample ->
                            store.update { e->val old=e.walkSamples.filter { it.key!=sample.key };val same=e.walkSamples.filter { it.key==sample.key }.takeLast(19);e.copy(walkSamples=(old+same+sample).takeLast(200)) }
                        }
                        break
                    }
                }
            } finally { stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { active.value=false;scope.cancel();super.onDestroy() }
    companion object {
        val active=MutableStateFlow(false)
        fun start(context: Context,leg: WalkingLeg) {
            val simple=leg.copy(points=listOf(leg.from.point,leg.to.point))
            runCatching { ContextCompat.startForegroundService(context,Intent(context,WalkLearningService::class.java).putExtra("leg",TripFiles.json.encodeToString(simple))) }
        }
        fun stop(context: Context) { context.stopService(Intent(context,WalkLearningService::class.java)) }
    }
}
