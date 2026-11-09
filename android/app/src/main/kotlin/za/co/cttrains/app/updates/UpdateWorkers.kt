package za.co.cttrains.app.updates

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import za.co.cttrains.app.*
import za.co.cttrains.app.R
import za.co.cttrains.core.*
import java.util.concurrent.TimeUnit

fun updateMessage(e: Exception): String {
    val text=e.message.orEmpty()
    return if(listOf("GitHub ","No accessible releases","This release ","This update ","This version ","Use a GitHub ","The release ","Release tag ","Release metadata ","APK ","Staged APK ","Free space ","The download ","Unexpected APK ","Could not stage ").any(text::startsWith)) text.take(300)
    else "Could not complete the update check or download. Your installed app is unchanged; retry when connected."
}
object UpdateWork {
    const val DOWNLOAD="app-update-download"
    const val CHECK="app-update-check"
    fun open(c: Context)=PendingIntent.getActivity(c,1912,Intent(c,MainActivity::class.java).putExtra("app_updates",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun channel(c: Context)=c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("app-updates","App update downloads",NotificationManager.IMPORTANCE_LOW))
    fun configure(c: Context,enabled: Boolean) {
        val manager=WorkManager.getInstance(c)
        if(!enabled) manager.cancelUniqueWork(CHECK)
        else manager.enqueueUniquePeriodicWork(CHECK,ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<UpdateCheckWorker>(24,TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun reset(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(DOWNLOAD);configure(c,false)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launchReset(c)
    }
    private fun kotlinx.coroutines.CoroutineScope.launchReset(c: Context) = launch {
        UpdateRepository(c).clear();UpdateStore(c).clear()
    }
}
class UpdateDownloadWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        try {
            val update=TripFiles.json.decodeFromString<AppUpdate>(inputData.getString("update") ?: return Result.failure())
            UpdateWork.channel(applicationContext)
            val cancel=WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            val builder=NotificationCompat.Builder(applicationContext,"app-updates").setSmallIcon(R.drawable.ic_train).setContentTitle("Downloading CT Trains ${update.manifest.versionName}")
                .setContentText("Your current app keeps working").setContentIntent(UpdateWork.open(applicationContext)).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100,0,true).addAction(0,"Cancel download",cancel)
            suspend fun foreground() { val note=builder.build();setForeground(if(Build.VERSION.SDK_INT>=29) ForegroundInfo(1912,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(1912,note)) }
            foreground()
            var lastNotification=0L
            UpdateRepository(applicationContext).stage(update) { done,total->
                setProgress(workDataOf("done" to done,"total" to total))
                val now=android.os.SystemClock.elapsedRealtime()
                if(now-lastNotification>=1000 || done==total) {
                    builder.setContentText("${done/1024/1024} / ${total/1024/1024} MB").setProgress(100,(done*100/total).toInt(),false)
                    foreground();lastNotification=now
                }
            }
            return Result.success()
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            return Result.failure(workDataOf("error" to updateMessage(e)))
        }
    }
}
class UpdateCheckWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        if(!UpdateStore(applicationContext).settings().automatic) return Result.success()
        return try {
            val update=UpdateRepository(applicationContext).check()
            val store=UpdateStore(applicationContext)
            if(update!=null && update.manifest.versionCode>store.notifiedVersion() && (Build.VERSION.SDK_INT<33 || androidx.core.content.ContextCompat.checkSelfPermission(applicationContext,android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)) {
                UpdateWork.channel(applicationContext)
                applicationContext.getSystemService(NotificationManager::class.java).notify(1913,NotificationCompat.Builder(applicationContext,"app-updates").setSmallIcon(R.drawable.ic_train)
                    .setContentTitle("CT Trains ${update.manifest.versionName} is available").setContentText("Review the release and download when ready").setContentIntent(UpdateWork.open(applicationContext)).setAutoCancel(true).build())
                store.notifiedVersion(update.manifest.versionCode)
            }
            Result.success()
        } catch(e: Exception) { if(e is CancellationException) throw e;Result.retry() }
    }
}
