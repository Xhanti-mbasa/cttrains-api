package za.co.cttrains.app.scheduling

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import za.co.cttrains.app.MainActivity
import za.co.cttrains.app.R
import za.co.cttrains.app.data.TrainRepository
import za.co.cttrains.core.BoardingEvidence
import za.co.cttrains.core.RIDE_GRACE_MS
import za.co.cttrains.core.RailRouter
import za.co.cttrains.core.ScheduledJourney
import za.co.cttrains.core.TrackPoint
import za.co.cttrains.core.canTrackBoarding
import za.co.cttrains.core.stationKey

class RideBoardingService : Service(), SensorEventListener, LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var evidence: BoardingEvidence? = null
    private var journey: ScheduledJourney? = null
    private var revision: Pair<String, String>? = null
    private val acceleration = DoubleArray(3)
    private var gravity: DoubleArray? = null
    private var previousSensorAt = 0L

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (revision?.second == intent.getStringExtra(RideFollowUps.TOKEN)) stopSelf()
            return START_NOT_STICKY
        }
        if (revision != null) return START_NOT_STICKY
        val id = intent?.getStringExtra(RideFollowUps.ID)
        val token = intent?.getStringExtra(RideFollowUps.TOKEN)
        if (id.isNullOrBlank() || token.isNullOrBlank() || !canStart(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        revision = id to token
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ride detection", NotificationManager.IMPORTANCE_LOW))
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP)
            .putExtra(RideFollowUps.TOKEN, token), pendingFlags)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.OPEN_RIDES).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), pendingFlags)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_train)
            .setContentTitle("Checking if you board")
            .setContentText("Using motion and GPS for this planned ride.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop", stop).build()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        activeRide.value = id
        scope.launch {
            try {
                monitor(id, token)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The scheduled follow-up remains eligible.
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun monitor(id: String, token: String) {
        val repository = TrainRepository(applicationContext)
        val item = repository.store.journey(id)?.takeIf {
            it.reminderToken == token && it.canTrackBoarding(System.currentTimeMillis()) &&
                it.profileId == repository.preferences.first().profileId
        } ?: return
        val leg = item.journey.legs.first()
        val stations = repository.store.stations.first()
        val station = stations.firstOrNull { it.mapped && stationKey(it.name) == stationKey(leg.departure.name) } ?: return
        val origin = TrackPoint(requireNotNull(station.latitude), requireNotNull(station.longitude))
        val firstSegment = item.journey.copy(legs = listOf(leg.copy(stops = leg.stops.take(2))))
        val track = repository.trace(firstSegment, stations).legs.single().singleOrNull()?.takeIf { it.followsRail }?.points ?: return
        if (RailRouter.distance(origin, track.first()) > 110) return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        val remaining = item.departureAt + RIDE_GRACE_MS - System.currentTimeMillis()
        if (remaining <= 0) return
        journey = item
        evidence = BoardingEvidence(origin, track)
        // The motion sensor may be non-wakeup. Keep it sampling only during this bounded session.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cttrains:ride-boarding").apply { acquire(remaining) }
        if (!sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)) return
        locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
        withTimeoutOrNull(remaining) {
            repository.store.journeys.first { plans ->
                plans.none { it.id == id && it.reminderToken == token && it.rideOutcome == null }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val at = event.timestamp / 1_000_000L
        val reset = at - previousSensorAt !in 0..500
        previousSensorAt = at
        val raw = event.sensor.type == Sensor.TYPE_ACCELEROMETER
        val baseline = if (raw) gravity ?: DoubleArray(3) { event.values[it].toDouble() }.also { gravity = it } else null
        for (index in 0..2) {
            val value = event.values[index].toDouble()
            if (baseline != null) baseline[index] = if (reset) value else 0.995 * baseline[index] + 0.005 * value
            val linear = value - (baseline?.get(index) ?: 0.0)
            acceleration[index] = if (reset) linear else 0.90 * acceleration[index] + 0.10 * linear
        }
        evidence?.motion(at, sqrt(acceleration.sumOf { it * it }))
    }

    override fun onLocationChanged(location: Location) {
        val current = journey ?: return
        val at = location.elapsedRealtimeNanos / 1_000_000L
        if (SystemClock.elapsedRealtime() - at !in 0..5_000 ||
            System.currentTimeMillis() < current.departureAt - 2 * 60_000L) return
        if (evidence?.fix(at, TrackPoint(location.latitude, location.longitude),
                if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN,
                if (location.hasSpeed()) location.speed.toDouble() else null,
                if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null) == true) {
            evidence = null
            scope.launch {
                try { RideFollowUps.likelyOnboard(this@RideBoardingService, current) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* The ride remains unconfirmed. */ }
                finally { stopSelf() }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onProviderDisabled(provider: String) { stopSelf() }
    override fun onProviderEnabled(provider: String) = Unit
    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onDestroy() {
        scope.cancel()
        sensors.unregisterListener(this)
        runCatching { locations.removeUpdates(this) }
        wakeLock?.let { if (it.isHeld) it.release() }
        activeRide.value = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ride-detection"
        private const val STOP = "stop-ride-detection"
        private const val NOTIFICATION_ID = 1906
        private val activeRide = MutableStateFlow<String?>(null)
        val active = activeRide.asStateFlow()

        fun canStart(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                notificationsAllowed(context) &&
                context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

        fun start(context: Context, item: ScheduledJourney) {
            if (!canStart(context) || !item.canTrackBoarding(System.currentTimeMillis())) return
            ContextCompat.startForegroundService(context, Intent(context, RideBoardingService::class.java)
                .putExtra(RideFollowUps.ID, item.id).putExtra(RideFollowUps.TOKEN, item.reminderToken))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RideBoardingService::class.java))
        }
    }
}
