package za.co.cttrains.app.scheduling

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import za.co.cttrains.app.data.*
import za.co.cttrains.core.*

class ReportUploadWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val store=FeatureStore(applicationContext)
        if(!store.extras().beta.hostedSharing) return Result.success()
        val s=store.session() ?: return Result.success()
        val client=MatrixClient()
        try {
            for(item in store.extras().queued) {
                val e=store.extras()
                if(!e.beta.hostedSharing || store.session()!=s) return Result.success()
                if(item.owner!=s.userId || !CommunityReports.fresh(item.report,System.currentTimeMillis()) || e.rooms.none { it.service==item.report.service && it.room==item.room }) {
                    store.update { it.copy(queued=it.queued.filterNot { q->q.report.id==item.report.id }) };continue
                }
                client.send(s,item.room,CommunityReports.text(item.report),item.report,transaction="report-${item.report.id}")
                store.update { it.copy(queued=it.queued.filterNot { q->q.report.id==item.report.id },reports=(it.reports+ReceivedReport(item.report,s.userId,"local-${item.report.id}",System.currentTimeMillis(),false)).takeLast(300)) }
            }
            return Result.success()
        } catch(e: Exception) { if(e is CancellationException) throw e;return if(runAttemptCount<6) Result.retry() else Result.failure() }
    }
    companion object {
        fun enqueue(context: Context) {
            val work=OneTimeWorkRequestBuilder<ReportUploadWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("community-report-upload",ExistingWorkPolicy.APPEND_OR_REPLACE,work)
        }
    }
}
