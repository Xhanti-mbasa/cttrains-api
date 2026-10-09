package za.co.cttrains.app.scheduling

import android.content.Context
import androidx.work.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import za.co.cttrains.app.MainActivity
import za.co.cttrains.app.R
import za.co.cttrains.app.data.*
import za.co.cttrains.core.*

object RoutineScheduler {
    fun configure(c: Context,routines: List<CommuteRoutine>) {
        val manager=WorkManager.getInstance(c)
        manager.cancelAllWorkByTag("routine-check")
        if(routines.none { it.enabled }) { manager.cancelUniqueWork("routine-maintenance");return }
        manager.enqueueUniquePeriodicWork("routine-maintenance",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<RoutineMaintenance>(1,TimeUnit.DAYS).build())
        enqueue(c,routines)
    }
    fun enqueue(c: Context,routines: List<CommuteRoutine>) {
        val now=System.currentTimeMillis()
        routines.filter { it.enabled }.forEach { r->CommuteRoutines.dates(r,now).forEach { date->
            val name="routine-${r.id}-$date"
            val work=OneTimeWorkRequestBuilder<RoutineCheck>().setInputData(workDataOf("routine" to r.id,"date" to date.toString(),"revision" to r.hashCode()))
                .setInitialDelay((CommuteRoutines.checkAt(r,date)-now).coerceAtLeast(0),TimeUnit.MILLISECONDS)
                .addTag("routine-check").addTag("routine-${r.id}").build()
            WorkManager.getInstance(c).enqueueUniqueWork(name,ExistingWorkPolicy.KEEP,work)
        } }
    }
    suspend fun cancelOccurrences(c: Context,id: String) {
        WorkManager.getInstance(c).cancelAllWorkByTag("routine-$id").result.get(15,TimeUnit.SECONDS)
        NotificationManagerCompat.from(c).cancel(id.hashCode())
        val store=LocalTransitStore.get(c)
        store.journeys.first().filter { it.routineId==id && it.departureAt>System.currentTimeMillis() }.forEach { JourneyReminders(c).cancel(it.id);store.deleteJourney(it.id) }
    }
}
class RoutineMaintenance(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result { RoutineScheduler.enqueue(applicationContext,FeatureStore(applicationContext).extras().routines);return Result.success() }
}
class RoutineCheck(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        val store=FeatureStore(applicationContext)
        val r=store.extras().routines.firstOrNull { it.id==inputData.getString("routine") && it.enabled } ?: return Result.success()
        if(r.hashCode()!=inputData.getInt("revision",0)) return Result.success()
        val date=runCatching { LocalDate.parse(inputData.getString("date")) }.getOrNull() ?: return Result.failure()
        if(date.dayOfWeek.value !in r.days || CommuteRoutines.departure(r,date)<=System.currentTimeMillis()) return Result.success()
        try {
            if(r.service!=TransportService.PRASA) return checkBus(r,date,store)
            val repository=TrainRepository(applicationContext)
            val q=r.query.copy(date=date.toString())
            val result=repository.search(q,r.sourceId)
            val journey=result.entry.journeys.firstOrNull { it.departure==r.query.time }
            if(journey==null) { notice(r,"No matching departure is published for $date. Open Plan to choose another journey.");return Result.success() }
            val prefs=repository.store.preferences.first()
            var item=ScheduledJourney("routine-${r.id}-$date",prefs.profileId,q,journey,r.sourceId,result.entry.fetchedAt,System.currentTimeMillis(),r.reminderMinutes,r.accessMinutes,r.bufferMinutes,result.entry.fares,
                leaveFrom=r.origin?.label.orEmpty().take(80),reminderBasis="leave",reminderToken=UUID.randomUUID().toString(),routineId=r.id)
            if(item.reminderAt?.let { it<=System.currentTimeMillis() }==true) item=item.copy(reminderMinutes=null)
            // A deleted/edited routine cannot create a plan after its request completes.
            val latest=store.extras().routines.firstOrNull { it.id==r.id }
            if(latest!=r) return Result.success()
            val saved=repository.store.saveJourney(item);JourneyReminders(applicationContext).schedule(saved)
            return Result.success()
        } catch(e: Exception) {
            if(e is CancellationException) throw e
            if(runAttemptCount<3 && System.currentTimeMillis()<CommuteRoutines.departure(r,date)-(r.accessMinutes+r.bufferMinutes)*60_000L) return Result.retry()
            notice(r,"Couldn’t check the timetable for $date. Open Plan before leaving.");return Result.success()
        }
    }
    private suspend fun checkBus(r: CommuteRoutine,date: LocalDate,store: FeatureStore): Result {
        val (t,_)=BusRepository(applicationContext).timetable(r.service,r.busRoute!!,date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase))
        val trip=if(!t.route.validFor(date)) null else t.trips.firstOrNull { row->
            val board=row.calls.indexOfFirst { it.id==r.boardCallId && it.time==r.query.time }
            val end=row.calls.indexOfFirst { it.id==r.alightCallId }
            board>=0 && end>board && (row.validFrom==null || date>=LocalDate.parse(row.validFrom))
        }
        if(trip==null) { notice(r,"No matching ${r.service.title} departure is published for $date. Open Plan to choose another journey.");return Result.success() }
        if(store.extras().routines.firstOrNull { it.id==r.id }!=r) return Result.success()
        val board=trip.calls.first { it.id==r.boardCallId && it.time==r.query.time }
        val end=trip.calls.first { it.id==r.alightCallId }
        val departure=CommuteRoutines.departure(r,date)
        val leave=departure-(r.accessMinutes+r.bufferMinutes)*60_000L
        val saved=TripFiles.validate(SafetyTrip(id="routine-${r.id}-$date",owner=store.extras().localName.ifBlank { "Traveller" },createdAt=System.currentTimeMillis(),steps=listOf(TripStep("leave",null,r.origin?.label ?: "Leave for stop",leave),TripStep("board",r.service,board.name,departure,route=t.route.title.take(100)),TripStep("alight",r.service,end.name,departure+minutesBetween(board.time,end.time)*60_000L,route=t.route.title.take(100))),sourceCheckedAt=t.fetchedAt,note="Recurring ${r.service.title} journey · published weekday pattern; confirm holidays and stop operation."))
        store.update { it.copy(trips=(it.trips.filterNot { row->row.id==saved.id }+saved).takeLast(100)) }
        r.reminderMinutes?.let { lead->
            val at=leave-lead*60_000L
            if(at>System.currentTimeMillis()) WorkManager.getInstance(applicationContext).enqueueUniqueWork("routine-bus-reminder-${r.id}-$date",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<RoutineBusReminder>().setInputData(workDataOf("routine" to r.id,"revision" to r.hashCode(),"trip" to saved.id)).setInitialDelay(at-System.currentTimeMillis(),TimeUnit.MILLISECONDS).addTag("routine-${r.id}").build())
        }
        return Result.success()
    }
    private fun notice(r: CommuteRoutine,text: String) {
        if(!notificationsAllowed(applicationContext)) return
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("routine-status","Recurring journey checks",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(applicationContext,r.id.hashCode(),Intent(applicationContext,MainActivity::class.java).putExtra("journeys",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(applicationContext).notify(r.id.hashCode(),NotificationCompat.Builder(applicationContext,"routine-status").setSmallIcon(R.drawable.ic_train).setContentTitle(r.name).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
    }
}

class RoutineBusReminder(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result {
        val e=FeatureStore(applicationContext).extras()
        val r=e.routines.firstOrNull { it.id==inputData.getString("routine") && it.enabled && it.hashCode()==inputData.getInt("revision",0) } ?: return Result.success()
        val trip=e.trips.firstOrNull { it.id==inputData.getString("trip") } ?: return Result.success()
        val board=trip.steps.first { it.kind=="board" }
        if(board.at<=System.currentTimeMillis() || !notificationsAllowed(applicationContext)) return Result.success()
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("routine-bus","Recurring bus journeys",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=PendingIntent.getActivity(applicationContext,r.id.hashCode(),Intent(applicationContext,MainActivity::class.java).putExtra("journeys",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(applicationContext).notify(r.id.hashCode(),NotificationCompat.Builder(applicationContext,"routine-bus").setSmallIcon(R.drawable.ic_train).setContentTitle("Leave for ${r.name}").setContentText("${r.service.title} · ${r.query.time} · ${r.query.from} → ${r.query.to}").setStyle(NotificationCompat.BigTextStyle().bigText(TripFiles.text(trip))).setContentIntent(intent).setAutoCancel(true).build())
        return Result.success()
    }
}
