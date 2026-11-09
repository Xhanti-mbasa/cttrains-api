package za.co.cttrains.app.directions

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import za.co.cttrains.core.*

class DirectionsService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var ticker: Job?=null
    private var fix: DirectionFix?=null
    private var walkedAt: Long?=null
    private var observedStep: String?=null
    private val alerted=mutableSetOf<String>()
    private val commands=Mutex()
    private val speech by lazy { DirectionSpeech(this) { note->DirectionsRuntime.state.update { it.copy(error=note) } } }
    private val listener=object: LocationListener {
        override fun onLocationChanged(p: Location) { fix=DirectionFix(GeoPoint(p.latitude,p.longitude),p.accuracy.toDouble(),p.time) }
        override fun onProviderDisabled(provider: String) { fix=null }
        override fun onProviderEnabled(provider: String) { }
        @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?,status: Int,extras: Bundle?) { }
    }
    override fun onBind(intent: Intent?)=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action=="voice") { if(!DirectionSpeech.enabled(this)) speech.stop();return START_NOT_STICKY }
        if(intent?.action=="speech") { val v=DirectionsRuntime.state.value; speech.speak(v.guidance?.let { it.title+". "+it.detail } ?: v.session?.step?.title.orEmpty());return START_NOT_STICKY }
        if(intent?.action=="stop") { stop(this);return START_NOT_STICKY }
        if(intent?.action!="start") {
            scope.launch {
                commands.withLock {
                val s=DirectionsRuntime.state.value.session ?: return@launch
                val next=when(intent?.action) { "next"->DirectionsEngine.advance(s);"back"->DirectionsEngine.back(s);"pause"->s.copy(paused=!s.paused);"unpin"->s.copy(promote=false);else->s }
                if(next.finished) { stop(this@DirectionsService);return@withLock }
                withContext(Dispatchers.IO) { DirectionsStore(this@DirectionsService).saveActive(next) }
                if(DirectionsRuntime.state.value.session?.plan?.id!=s.plan.id) return@withLock
                DirectionsRuntime.state.update { it.copy(session=next,fix=null) };fix=null;walkedAt=null
                locations(!next.paused)
                }
                tick()
            }
            return START_NOT_STICKY
        }
        DirectionsNotifications.channels(this)
        try { startForeground(DirectionsNotifications.ID,DirectionsNotifications.build(this,null,null)) }
        catch(_: Exception) { DirectionsRuntime.state.update { it.copy(error="Location permission or foreground navigation is unavailable.",running=false) };stopSelf();return START_NOT_STICKY }
        ticker?.cancel()
        scope.launch {
            val saved=withContext(Dispatchers.IO) { DirectionsStore(this@DirectionsService).load() }
            if(saved==null) { stop(this@DirectionsService);return@launch }
            DirectionsRuntime.state.update { it.copy(session=saved,preview=null,running=true,error=null) }
            fix=null;walkedAt=null;observedStep=null;alerted.clear();locations(!saved.paused)
            ticker=scope.launch { while(isActive) { tick();delay(10_000) } }
        }
        return START_NOT_STICKY
    }
    private fun locations(enabled: Boolean) {
        val manager=getSystemService(LocationManager::class.java)
        runCatching { manager.removeUpdates(listener) }
        if(!enabled) return
        val fine=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val coarse=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!fine && !coarse) { fix=null;DirectionsRuntime.state.update { it.copy(error="Location access was revoked. Stop or allow it again in app settings.") };stopSelf();return }
        try {
            val providers=buildList {
                if(fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if(manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
            providers.forEach { manager.requestLocationUpdates(it,8_000,10f,listener,Looper.getMainLooper()) }
            if(providers.isEmpty()) DirectionsRuntime.state.update { it.copy(error="Turn on device location for GPS progress. Step controls still work.") }
        } catch(_: SecurityException) { fix=null;stopSelf() }
    }
    private suspend fun tick()=commands.withLock { tickLocked() }
    private suspend fun tickLocked() {
        var s=DirectionsRuntime.state.value.session ?: return
        val now=System.currentTimeMillis()
        if(!DirectionsEngine.alive(s,now)) { stop(this);return }
        val currentFix=fix
        var g=withContext(Dispatchers.Default) { DirectionsEngine.guidance(s,currentFix,now) }
        if(DirectionsRuntime.state.value.session!=s) return
        if(observedStep!=s.step?.id) { observedStep=s.step?.id;walkedAt=null }
        if(!s.paused && s.step?.kind==DirectionKind.WALK && g.nearTarget) {
            if(walkedAt==null) walkedAt=SystemClock.elapsedRealtime()
            else if(SystemClock.elapsedRealtime()-walkedAt!!>=10_000 && DirectionsRuntime.state.value.session==s) {
                s=DirectionsEngine.advance(s);walkedAt=null
                if(s.finished) { stop(this);return }
                withContext(Dispatchers.IO) { DirectionsStore(this@DirectionsService).saveActive(s) }
                g=DirectionsEngine.guidance(s,currentFix,now)
            }
        } else walkedAt=null
        val step=s.step
        if(!s.paused && step?.kind==DirectionKind.RIDE && g.fresh && (g.remainingMeters ?: Int.MAX_VALUE)<=300 && alerted.add(step.id))
            DirectionsNotifications.approaching(this,step)
        // Do not overwrite a Next/Pause command while geometry was computed off the main thread.
        if(DirectionsRuntime.state.value.session?.plan?.id!=s.plan.id) return
        DirectionsRuntime.state.update { it.copy(session=s,guidance=g,fix=currentFix?.takeIf { f->now-f.at in 0..45_000 },running=true) }
        speech.guidance(s,g,DirectionSpeech.enabled(this))
        getSystemService(NotificationManager::class.java).notify(DirectionsNotifications.ID,DirectionsNotifications.build(this,s,g))
    }
    override fun onDestroy() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
        speech.close()
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(DirectionsNotifications.ID)
        DirectionsRuntime.state.update { it.copy(running=false,fix=null,session=it.session?.copy(paused=true)) }
        super.onDestroy()
    }
    companion object {
        fun start(c: Context)=ContextCompat.startForegroundService(c,Intent(c,DirectionsService::class.java).setAction("start"))
        fun command(c: Context,action: String) {
            if(DirectionsRuntime.state.value.running) c.startService(Intent(c,DirectionsService::class.java).setAction(action))
            else if(action=="stop") stop(c)
            else if(action in listOf("next","back")) {
                DirectionsRuntime.state.update { v->v.copy(session=v.session?.let { if(action=="next") DirectionsEngine.advance(it) else DirectionsEngine.back(it) }) }
                DirectionsRuntime.state.value.session?.let { current->CoroutineScope(Dispatchers.IO).launch { DirectionsStore(c.applicationContext).saveActive(current) } }
            }
        }
        fun stop(c: Context) {
            c.stopService(Intent(c,DirectionsService::class.java))
            DirectionsRuntime.state.value=DirectionsState()
            c.getSystemService(NotificationManager::class.java).cancel(DirectionsNotifications.ID)
            c.getSystemService(NotificationManager::class.java).cancel(1911)
            CoroutineScope(Dispatchers.IO).launch { DirectionsStore(c.applicationContext).clearStopped() }
        }
    }
}
