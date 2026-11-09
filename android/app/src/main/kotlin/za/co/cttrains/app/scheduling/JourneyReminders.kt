package za.co.cttrains.app.scheduling

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import za.co.cttrains.app.MainActivity
import za.co.cttrains.app.R
import za.co.cttrains.app.data.LocalTransitStore
import za.co.cttrains.core.*

fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

class JourneyReminders(private val context: Context) {
    private val manager = WorkManager.getInstance(context)
    private fun workName(item: ScheduledJourney) = "journey-${item.id}" + if (item.reminderStage == 0) "" else "-backup-${item.reminderStage}"
    fun schedule(item: ScheduledJourney) {
        cancel(item.id)
        enqueue(item)
    }
    fun enqueue(item: ScheduledJourney) {
        val at = item.pendingReminderAt ?: return
        if (item.departureAt <= System.currentTimeMillis()) return
        val request = OneTimeWorkRequestBuilder<JourneyReminderWorker>()
            .setInputData(workDataOf("journey_id" to item.id, "profile_id" to item.profileId, "token" to item.reminderToken, "stage" to item.reminderStage))
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag("transit-reminders").addTag("journey-${item.id}").addTag("token-${item.reminderToken}").build()
        manager.enqueueUniqueWork(workName(item), ExistingWorkPolicy.REPLACE, request)
    }
    fun cancel(id: String) {
        manager.cancelUniqueWork("journey-$id") // Includes reminders created by earlier app versions.
        manager.cancelAllWorkByTag("journey-$id")
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }
    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        manager.cancelAllWorkByTag("transit-reminders").result.get(15, TimeUnit.SECONDS)
        NotificationManagerCompat.from(context).cancelAll()
    }
    suspend fun restoreMissing(items: List<ScheduledJourney>) = withContext(Dispatchers.IO) {
        items.filter { it.pendingReminderAt != null && it.departureAt > System.currentTimeMillis() }.forEach { item ->
            val work = manager.getWorkInfosForUniqueWork(workName(item)).get(15, TimeUnit.SECONDS)
            if (work.isEmpty() || (item.reminderToken.isNotBlank() && work.none { "token-${item.reminderToken}" in it.tags })) enqueue(item)
        }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("journey_id") ?: return
        val token = intent.getStringExtra("token").orEmpty()
        val stage = intent.getIntExtra("stage", -1)
        val profile = intent.getStringExtra("profile_id") ?: return
        if (intent.action !in listOf("za.co.cttrains.DISMISS_REMINDER", "za.co.cttrains.STOP_REMINDERS")) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = LocalTransitStore.get(context)
                store.initialize()
                if (store.preferences.first().profileId != profile) return@launch
                val updated = store.updateJourney(id) { item ->
                    if (item.profileId != profile || item.reminderToken != token || item.remindersStopped) null
                    else if (intent.action == "za.co.cttrains.STOP_REMINDERS") item.copy(remindersStopped = true)
                    else JourneyPlanning.afterDismiss(item, stage, token, System.currentTimeMillis())
                } ?: return@launch
                val reminders = JourneyReminders(context)
                NotificationManagerCompat.from(context).cancel(id.hashCode())
                if (updated.remindersStopped) reminders.cancel(id) else reminders.enqueue(updated)
            } catch (_: Exception) { /* The persisted pending stage can be recovered on next launch. */ }
            finally { pending.finish() }
        }
    }
}

class JourneyReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result { return try {
        val id = inputData.getString("journey_id") ?: return Result.failure()
        val store = LocalTransitStore.get(applicationContext)
        val item = store.journey(id) ?: return Result.success()
        if (item.profileId != inputData.getString("profile_id") || item.profileId != store.preferences.first().profileId ||
            item.reminderToken != inputData.getString("token").orEmpty() || item.reminderStage != inputData.getInt("stage", 0) ||
            item.pendingReminderAt == null || item.departureAt <= System.currentTimeMillis() || !notificationsAllowed(applicationContext)) return Result.success()
        val service = applicationContext.getSystemService(NotificationManager::class.java)
        val severity = item.activeSeverity
        val channel = "journeys-$severity"
        val importance = when (severity) { "gentle" -> NotificationManager.IMPORTANCE_LOW; "urgent" -> NotificationManager.IMPORTANCE_HIGH; else -> NotificationManager.IMPORTANCE_DEFAULT }
        service.createNotificationChannel(NotificationChannel(channel, "Journey reminders · $severity", importance).apply {
            description = "User-selected leave reminders. Android notification and Do Not Disturb settings still apply."
        })
        fun action(name: String): PendingIntent {
            val intent = Intent(applicationContext, ReminderActionReceiver::class.java).setAction(name).apply {
                data = android.net.Uri.parse("cttrains://reminder/${item.id}/${item.reminderToken}/${item.reminderStage}/$name")
                putExtra("journey_id", item.id); putExtra("profile_id", item.profileId); putExtra("token", item.reminderToken); putExtra("stage", item.reminderStage)
            }
            return PendingIntent.getBroadcast(applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val open = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("journey_id", item.id)
        }
        val content = PendingIntent.getActivity(applicationContext, item.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(CapeTownZone)
        val leave = clock.format(Instant.ofEpochMilli(item.leaveAt))
        val origin = item.leaveFrom.ifBlank { "your building" }
        val late = System.currentTimeMillis() > item.leaveAt
        val title = if (item.reminderBasis == "departure") "Train at ${item.journey.departure}" else if (late) "Your planned leave time has passed" else "Leave $origin by $leave"
        val text = if (item.reminderBasis == "departure") "${item.query.from} → ${item.query.to} · train ${item.journey.departure}"
            else "$origin → ${item.query.from}: ${item.accessMinutes} min + ${item.bufferMinutes} min buffer. Train to ${item.query.to} at ${item.journey.departure}."
        val backup = item.backupReminders.getOrNull(item.reminderStage)
        val details = text + if (backup != null) " Swipe away or tap Remind again for a ${backup.delayMinutes} minute backup. Tap I'm leaving to stop reminders." else " No more backup reminders."
        val notification = NotificationCompat.Builder(applicationContext, channel).setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(details + " Saved timetable, not a live service check."))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(content).setAutoCancel(false)
            .setDeleteIntent(action("za.co.cttrains.DISMISS_REMINDER"))
            .addAction(0, "I'm leaving", action("za.co.cttrains.STOP_REMINDERS"))
            .apply { if (backup != null) addAction(0, "Remind again", action("za.co.cttrains.DISMISS_REMINDER")); if (severity == "gentle") setSilent(true) }.build()
        currentCoroutineContext().ensureActive()
        val latest = store.journey(id)
        if (latest == null || latest.remindersStopped || latest.reminderToken != item.reminderToken || latest.reminderStage != item.reminderStage) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        if (notificationsAllowed(applicationContext)) service.notify(item.id.hashCode(), notification)
        Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { Result.failure() }
    }
}
