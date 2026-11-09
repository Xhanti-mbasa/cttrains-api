package za.co.cttrains.app.scheduling

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import za.co.cttrains.app.MainActivity
import za.co.cttrains.app.R
import za.co.cttrains.app.data.LocalTransitStore
import za.co.cttrains.core.RIDE_GRACE_MS
import za.co.cttrains.core.RideFollowUp
import za.co.cttrains.core.RideOutcome
import za.co.cttrains.core.ScheduledJourney
import za.co.cttrains.core.awaitingRideAnswer
import za.co.cttrains.core.recordRideAnswer

object RideFollowUps {
    const val REPLAN = "za.co.cttrains.REPLAN_RIDE"
    const val ID = "ride_id"
    const val TOKEN = "ride_token"
    private const val CAUGHT = "za.co.cttrains.CAUGHT_RIDE"
    private const val DISMISS = "za.co.cttrains.DISMISS_RIDE_FOLLOW_UP"
    private const val CHANNEL = "ride-follow-ups"
    private var unlockRegistered = false

    fun schedule(context: Context, item: ScheduledJourney) {
        if (item.rideFollowUp != RideFollowUp.PENDING || item.rideOutcome != null) return
        val delay = (item.departureAt + RIDE_GRACE_MS - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<RideFollowUpWorker>()
            .setInputData(workDataOf(ID to item.id, TOKEN to item.reminderToken))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag("transit-reminders")
            .addTag("ride-${item.id}")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ride-${item.id}-${item.reminderToken}", ExistingWorkPolicy.KEEP, request,
        )
    }

    @Synchronized
    fun watchUnlock(context: Context) {
        if (unlockRegistered) return
        ContextCompat.registerReceiver(context.applicationContext, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_USER_PRESENT) return
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val store = LocalTransitStore.get(context)
                        store.initialize()
                        val now = System.currentTimeMillis()
                        store.journeys.first().filter {
                            it.rideFollowUp == RideFollowUp.PENDING && it.awaitingRideAnswer(now)
                        }.forEach { post(context, it.id, it.reminderToken) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // The pending ride is still available when the app opens.
                    } finally {
                        pending.finish()
                    }
                }
            }
        }, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        unlockRegistered = true
    }

    suspend fun showInApp(context: Context, item: ScheduledJourney) {
        update(context, item.id, item.reminderToken) { latest ->
            if (latest.rideFollowUp == RideFollowUp.DISMISSED ||
                !latest.awaitingRideAnswer(System.currentTimeMillis())) return@update null
            cancelNotification(context, item.id)
            if (latest.rideFollowUp == RideFollowUp.NOTIFIED) null
            else latest.copy(rideFollowUp = RideFollowUp.NOTIFIED)
        }
    }

    suspend fun answer(context: Context, id: String, token: String, outcome: RideOutcome?): ScheduledJourney? =
        update(context, id, token) { item ->
            item.recordRideAnswer(outcome, System.currentTimeMillis())?.also { cancelNotification(context, id) }
        }

    suspend fun likelyOnboard(context: Context, item: ScheduledJourney): Boolean =
        update(context, item.id, item.reminderToken) { latest ->
            if (latest.rideOutcome != null || System.currentTimeMillis() >= latest.departureAt + RIDE_GRACE_MS) null
            else {
                cancelNotification(context, item.id)
                latest.copy(rideOutcome = RideOutcome.LIKELY_ONBOARD, rideFollowUp = RideFollowUp.DISMISSED)
            }
        } != null

    private suspend fun update(
        context: Context, id: String, token: String, transform: (ScheduledJourney) -> ScheduledJourney?,
    ): ScheduledJourney? {
        if (token.isBlank()) return null
        val store = LocalTransitStore.get(context)
        store.initialize()
        val profile = store.preferences.first().profileId
        return store.updateJourney(id) { item ->
            if (item.profileId == profile && item.reminderToken == token) transform(item) else null
        }
    }

    private fun cancelNotification(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }

    @SuppressLint("MissingPermission")
    suspend fun post(context: Context, id: String, token: String) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked || !notificationsAllowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ride follow-ups", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return
        update(context, id, token) { item ->
            if (keyguard.isKeyguardLocked || item.rideFollowUp != RideFollowUp.PENDING ||
                !item.awaitingRideAnswer(System.currentTimeMillis())) return@update null

            fun action(name: String, activity: Boolean = false): PendingIntent {
                val intent = Intent(context, if (activity) MainActivity::class.java else RideActionReceiver::class.java)
                    .setAction(name)
                    .setData(Uri.Builder().scheme("cttrains").authority("ride").appendPath(id).appendPath(token).appendPath(name).build())
                    .putExtra(ID, id).putExtra(TOKEN, token)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                return if (activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    PendingIntent.getActivity(context, 0, intent, flags)
                } else PendingIntent.getBroadcast(context, 0, intent, flags)
            }

            val text = "${item.journey.departure} · ${item.query.from} → ${item.query.to}"
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_train).setContentTitle("Did you miss your ride?")
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
                .setContentIntent(action(MainActivity.OPEN_RIDES, activity = true))
                .setDeleteIntent(action(DISMISS))
                .addAction(0, "Yes, show next trains", action(REPLAN, activity = true))
                .addAction(0, "No, I caught it", action(CAUGHT))
                .build()
            try {
                // Share the leave reminder's ID so the follow-up updates it in place.
                manager.notify(id.hashCode(), notification)
                item.copy(rideFollowUp = RideFollowUp.NOTIFIED)
            } catch (_: SecurityException) {
                null
            }
        }
    }

    class RideActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(ID) ?: return
            val token = intent.getStringExtra(TOKEN) ?: return
            val outcome = when (intent.action) {
                CAUGHT -> RideOutcome.CONFIRMED
                DISMISS -> null
                else -> return
            }
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    answer(context, id, token, outcome)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The ride remains available in the app.
                } finally {
                    pending.finish()
                }
            }
        }
    }
}

class RideFollowUpWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(RideFollowUps.ID) ?: return Result.failure()
        val token = inputData.getString(RideFollowUps.TOKEN) ?: return Result.failure()
        return try {
            RideFollowUps.watchUnlock(applicationContext)
            RideFollowUps.post(applicationContext, id, token)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
