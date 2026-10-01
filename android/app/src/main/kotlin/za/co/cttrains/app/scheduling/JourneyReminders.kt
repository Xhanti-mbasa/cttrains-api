package za.co.cttrains.app.scheduling

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import za.co.cttrains.app.MainActivity
import za.co.cttrains.app.R
import za.co.cttrains.app.data.LocalTransitStore
import za.co.cttrains.core.ScheduledJourney

fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

class JourneyReminders(private val context: Context) {
    private val manager = WorkManager.getInstance(context)
    fun schedule(item: ScheduledJourney) {
        manager.cancelUniqueWork("journey-${item.id}")
        NotificationManagerCompat.from(context).cancel(item.id.hashCode())
        val at = item.reminderAt ?: return
        val request = OneTimeWorkRequestBuilder<JourneyReminderWorker>()
            .setInputData(workDataOf("journey_id" to item.id, "profile_id" to item.profileId))
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag("transit-reminders").build()
        manager.enqueueUniqueWork("journey-${item.id}", ExistingWorkPolicy.REPLACE, request)
    }
    fun cancel(id: String) { manager.cancelUniqueWork("journey-$id"); NotificationManagerCompat.from(context).cancel(id.hashCode()) }
    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        manager.cancelAllWorkByTag("transit-reminders").result.get(15, TimeUnit.SECONDS)
        NotificationManagerCompat.from(context).cancelAll()
    }
    suspend fun restoreMissing(items: List<ScheduledJourney>) = withContext(Dispatchers.IO) {
        items.filter { it.reminderAt != null && it.departureAt > System.currentTimeMillis() }.forEach { item ->
            if (manager.getWorkInfosForUniqueWork("journey-${item.id}").get(15, TimeUnit.SECONDS).isEmpty()) schedule(item)
        }
    }
}

class JourneyReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("journey_id") ?: return Result.failure()
        val store = LocalTransitStore.get(applicationContext)
        return try {
            val item = store.journey(id) ?: return Result.success()
            if (item.profileId != inputData.getString("profile_id") || item.profileId != store.preferences.first().profileId ||
                item.reminderAt == null || item.departureAt <= System.currentTimeMillis() || !notificationsAllowed(applicationContext)) return Result.success()
            val service = applicationContext.getSystemService(NotificationManager::class.java)
            service.createNotificationChannel(NotificationChannel("journeys", "Train journey reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Reminders for journeys you explicitly save. Timetable times, not live service status." })
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("journey_id", item.id)
            }
            val pending = PendingIntent.getActivity(applicationContext, item.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(applicationContext, "journeys")
                .setSmallIcon(R.drawable.ic_train).setContentTitle("Train at ${item.journey.departure}")
                .setContentText("${item.query.from} → ${item.query.to} · check service before travelling")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${item.query.from} → ${item.query.to}\nYour planned train departs at ${item.journey.departure}. This is a saved timetable, not a live delay check."))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build()
            currentCoroutineContext().ensureActive()
            // Permission may be revoked after the earlier check.
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
            if (notificationsAllowed(applicationContext)) service.notify(item.id.hashCode(), notification)
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { Result.failure() }
    }
}
