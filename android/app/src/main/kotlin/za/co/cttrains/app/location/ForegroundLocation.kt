package za.co.cttrains.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class CurrentPosition(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val capturedAt: Long)

/** An explicit, cancellable, bounded sample. No subscriptions, geofences or stored GPS history. */
object ForegroundLocation {
    suspend fun sample(context: Context): CurrentPosition? = suspendCancellableCoroutine { continuation ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            continuation.resume(null); return@suspendCancellableCoroutine
        }
        val token = CancellationTokenSource()
        continuation.invokeOnCancellation { token.cancel() }
        val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(30_000).setDurationMillis(20_000).build()
        try {
            LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(request, token.token)
                .addOnSuccessListener { location -> if (continuation.isActive) continuation.resume(location?.let { CurrentPosition(it.latitude, it.longitude, it.accuracy, it.time) }) }
                .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        } catch (error: SecurityException) { if (continuation.isActive) continuation.resumeWithException(error) }
    }
}
