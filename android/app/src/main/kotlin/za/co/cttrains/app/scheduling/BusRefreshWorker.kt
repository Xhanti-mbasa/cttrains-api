package za.co.cttrains.app.scheduling

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import za.co.cttrains.app.data.BusRepository
import za.co.cttrains.app.data.FeatureStore
import za.co.cttrains.core.*

class BusRefreshWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val service=runCatching { TransportService.valueOf(inputData.getString("operator").orEmpty()) }.getOrNull() ?: return Result.failure()
        val store=FeatureStore(applicationContext);val preferences=store.extras()
        if(!preferences.beta.enabled(service) || service==TransportService.PRASA) return Result.success()
        return try {
            val repo=BusRepository(applicationContext);val snapshot=repo.refresh(service)
            for(favorite in preferences.busFavorites.filter { it.service==service }.take(5)) {
                if(!store.extras().beta.enabled(service)) break
                val route=snapshot.routes.firstOrNull { it.id==favorite.routeId } ?: continue
                repo.timetable(service,route,favorite.day)
            }
            store.store.cleanLargeValues()
            Result.success()
        } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; if(runAttemptCount<3) Result.retry() else Result.failure() }
    }
    companion object {
        fun configure(context: Context,beta: BetaPreferences) {
            val manager=WorkManager.getInstance(context)
            listOf(TransportService.GOLDEN_ARROW,TransportService.MYCITI).forEach { service ->
                val name="bus-refresh-${service.name}"
                if(!beta.enabled(service)) manager.cancelUniqueWork(name)
                else {
                    val request=PeriodicWorkRequestBuilder<BusRefreshWorker>(beta.busRefreshMinutes.coerceIn(15,240).toLong(),TimeUnit.MINUTES)
                        .setInputData(workDataOf("operator" to service.name)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES).build()
                    manager.enqueueUniquePeriodicWork(name,ExistingPeriodicWorkPolicy.UPDATE,request)
                }
            }
        }
    }
}
