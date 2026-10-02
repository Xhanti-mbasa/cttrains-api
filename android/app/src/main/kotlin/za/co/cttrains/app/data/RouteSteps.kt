package za.co.cttrains.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

/** Reads only aggregate steps for an explicitly completed route walk, while app is visible. */
class RouteSteps(private val context: Context) {
    companion object { val permissions=setOf(HealthPermission.getReadPermission(StepsRecord::class)) }
    fun available()=HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE
    suspend fun granted()=available() && HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(permissions)
    suspend fun read(start: Long,end: Long): Long? {
        require(start>0 && end>start && end-start<=2*60*60_000L)
        if(!granted()) return null
        // The aggregate API deduplicates activity providers; don't add watch and phone totals.
        val response=HealthConnectClient.getOrCreate(context).aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),TimeRangeFilter.between(Instant.ofEpochMilli(start),Instant.ofEpochMilli(end))))
        return response[StepsRecord.COUNT_TOTAL]?.takeIf { it in 0..100_000 }
    }
}
