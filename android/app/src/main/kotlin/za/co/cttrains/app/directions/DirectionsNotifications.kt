package za.co.cttrains.app.directions

import android.app.*
import android.content.*
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import za.co.cttrains.app.*
import za.co.cttrains.core.*

object DirectionsNotifications {
    const val CHANNEL="journey-directions"
    const val ID=1910
    fun channels(c: Context) { c.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel(CHANNEL,"Active journey directions",NotificationManager.IMPORTANCE_LOW))
        createNotificationChannel(NotificationChannel("journey-milestones","Approaching your stop",NotificationManager.IMPORTANCE_HIGH))
    } }
    private fun action(c: Context,name: String,code: Int)=PendingIntent.getService(c,code,Intent(c,DirectionsService::class.java).setAction(name),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun open(c: Context)=PendingIntent.getActivity(c,1910,Intent(c,MainActivity::class.java).putExtra("directions",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun build(c: Context,s: DirectionsSession?,g: DirectionGuidance?): Notification {
        val title=g?.title ?: "Preparing journey directions"
        val detail=g?.let { listOfNotNull(it.remainingMeters?.let { m->"$m m" },it.minutes?.let { n->"~$n min" },it.detail).joinToString(" · ") } ?: "Your active journey"
        val promote=s?.promote!=false && s?.paused!=true
        val extras=Bundle().apply { putBoolean("android.requestPromotedOngoing",promote) }
        if(Build.VERSION.SDK_INT>=36) {
            val b=Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_train).setContentTitle(title).setContentText(detail)
                .setContentIntent(open(c)).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_NAVIGATION)
                .setVisibility(Notification.VISIBILITY_PRIVATE).addExtras(extras).setShowWhen(false)
                .setShortCriticalText(if(s?.paused==true) "Paused" else g?.minutes?.let { "${it}m" } ?: "Route")
            val style=Notification.ProgressStyle().addProgressSegment(Notification.ProgressStyle.Segment(100).setColor(android.graphics.Color.rgb(0,120,110)))
                .setProgress(g?.progress ?: 0).setStyledByProgress(true)
            b.setStyle(style)
            b.addAction(Notification.Action.Builder(null,if(s?.paused==true) "Resume" else "Pause",action(c,"pause",1911)).build())
            b.addAction(Notification.Action.Builder(null,"Next step",action(c,"next",1912)).build())
            b.addAction(Notification.Action.Builder(null,"Stop",action(c,"stop",1913)).build())
            b.setDeleteIntent(action(c,"unpin",1914))
            return b.build()
        }
        return NotificationCompat.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_train).setContentTitle(title).setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(open(c)).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_NAVIGATION).addExtras(extras)
            .addAction(0,if(s?.paused==true) "Resume" else "Pause",action(c,"pause",1911)).addAction(0,"Next step",action(c,"next",1912))
            .addAction(0,"Stop",action(c,"stop",1913)).setDeleteIntent(action(c,"unpin",1914)).build()
    }
    fun promotionAllowed(c: Context): Boolean?=if(Build.VERSION.SDK_INT<36) null else runCatching {
        NotificationManager::class.java.getMethod("canPostPromotedNotifications").invoke(c.getSystemService(NotificationManager::class.java)) as Boolean
    }.getOrNull()
    fun approaching(c: Context,step: DirectionStep) {
        if(Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(c,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) return
        c.getSystemService(NotificationManager::class.java).notify(1911,NotificationCompat.Builder(c,"journey-milestones").setSmallIcon(R.drawable.ic_train)
            .setContentTitle("Approaching your stop").setContentText(step.title+" · check outside before getting off")
            .setContentIntent(open(c)).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
}
