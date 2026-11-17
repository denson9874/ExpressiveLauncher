package app.lawnchair.smartspace.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import com.android.launcher3.BuildConfig
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches current conditions for the phone's approximate location from MET Norway's
 * Locationforecast API (https://api.met.no, no key, CC BY 4.0). Results are kept for
 * [CACHE_MILLIS] so Home redraws and launcher restarts of the flow do not hit the network again.
 */
class FallbackWeatherRepository(private val context: Context) {

    private val client by lazy { OkHttpClient() }

    private var cached: Cached? = null

    fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /** Returns the latest weather, or null when there is no permission, location or network. */
    suspend fun current(): FallbackWeather? {
        if (!hasLocationPermission()) return null
        val location = approximateLocation() ?: return cached?.weather
        val lat = roundCoordinate(location.latitude)
        val lon = roundCoordinate(location.longitude)
        val key = "$lat,$lon"
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (it.key == key && now - it.fetchedAt < CACHE_MILLIS) return it.weather }

        val weather = fetch(lat, lon)
        if (weather != null) {
            cached = Cached(key, now, weather)
            return weather
        }
        // Keep showing the last reading for this place while the network is unavailable.
        return cached?.takeIf { it.key == key }?.weather
    }

    private suspend fun fetch(lat: String, lon: String): FallbackWeather? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$lat&lon=$lon")
            // MET Norway's terms require a User-Agent that identifies the application.
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "weather request failed: HTTP ${response.code}")
                    return@withContext null
                }
                parseMetForecast(response.body.string())
            }
        } catch (e: IOException) {
            Log.w(TAG, "weather request failed", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun approximateLocation(): Location? {
        val locationManager = context.getSystemService<LocationManager>() ?: return null
        val providers = try {
            locationManager.getProviders(true)
        } catch (_: SecurityException) {
            return null
        }
        val lastKnown = providers
            .mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            .maxByOrNull { it.time }
        if (lastKnown != null) return lastKnown

        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { it in providers }
            ?: providers.firstOrNull()
            ?: return null
        return withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                try {
                    LocationManagerCompat.getCurrentLocation(
                        locationManager,
                        provider,
                        null as CancellationSignal?,
                        ContextCompat.getMainExecutor(context),
                    ) { location -> if (continuation.isActive) continuation.resume(location) }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private class Cached(val key: String, val fetchedAt: Long, val weather: FallbackWeather)

    companion object {
        private const val TAG = "FallbackWeather"
        const val CACHE_MILLIS = 30 * 60 * 1000L
        private const val LOCATION_TIMEOUT_MILLIS = 20_000L
        private val USER_AGENT =
            "ExpressiveLauncher/${BuildConfig.VERSION_NAME} github.com/denson9874/ExpressiveLauncher"
    }
}
