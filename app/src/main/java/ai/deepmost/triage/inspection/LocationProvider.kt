package ai.deepmost.triage.inspection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Best-effort geo-stamp using the platform LocationManager (no Play Services dependency). The app
 * operates fully without location; this returns null when permission is absent or no fix exists.
 */
class LocationProvider(private val context: Context) {

    fun currentStamp(): LocationStamp? {
        if (!hasPermission()) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var best: android.location.Location? = null
            for (p in providers) {
                val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            }
            best?.let { LocationStamp(it.latitude, it.longitude, it.time) }
        } catch (t: Throwable) {
            Timber.w(t, "Location unavailable")
            null
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
