package za.co.cttrains.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class CurrentPosition(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val capturedAt: Long)

/** Android's system provider, one bounded foreground fix; no Google Play services. */
object ForegroundLocation {
    suspend fun sample(context: Context): CurrentPosition? = withTimeoutOrNull(20_000) { suspendCancellableCoroutine { continuation ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            continuation.resume(null); return@suspendCancellableCoroutine
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) { continuation.resume(null); return@suspendCancellableCoroutine }
        val cancel = CancellationSignal()
        continuation.invokeOnCancellation { cancel.cancel() }
        try {
            LocationManagerCompat.getCurrentLocation(manager, provider, cancel, ContextCompat.getMainExecutor(context)) { location ->
                if (continuation.isActive) continuation.resume(location?.let { CurrentPosition(it.latitude, it.longitude, it.accuracy, it.time) })
            }
        } catch (error: SecurityException) { if (continuation.isActive) continuation.resumeWithException(error) }
    } }
}
