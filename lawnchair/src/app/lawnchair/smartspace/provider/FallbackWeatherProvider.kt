package app.lawnchair.smartspace.provider

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import app.lawnchair.BlankActivity
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.smartspace.weather.FallbackWeather
import app.lawnchair.smartspace.weather.FallbackWeatherIcon
import app.lawnchair.smartspace.weather.FallbackWeatherRepository
import app.lawnchair.smartspace.weather.temperatureUnitFor
import com.android.launcher3.R
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Backup weather for the At a Glance date card. The Google app's At a Glance widget stays the
 * first source ([SmartspaceWidgetReader]); this one only asks the network while that widget shows
 * no temperature, and [preferLiveWeather] hides it again as soon as Google's weather is back.
 *
 * Off by default: it needs the approximate location permission, requested when it is turned on.
 */
class FallbackWeatherProvider(
    context: Context,
    googleHasWeather: Flow<Boolean>,
) : SmartspaceDataSource(
    context,
    R.string.smartspace_fallback_weather,
    { smartspaceFallbackWeather },
    providerDescription = R.string.smartspace_fallback_weather_description,
) {
    private val repository = FallbackWeatherRepository(context)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override val internalTargets: Flow<List<SmartspaceTarget>> = googleHasWeather
        // The widget reports "no weather" for a moment at start; wait before using the network.
        .debounce(GOOGLE_GRACE_MILLIS)
        .distinctUntilChanged()
        .flatMapLatest { hasLiveWeather ->
            if (hasLiveWeather) flowOf(emptyList()) else pollWeather()
        }

    private fun pollWeather(): Flow<List<SmartspaceTarget>> = flow {
        while (true) {
            val weather = repository.current()
            emit(listOfNotNull(weather?.let(::createTarget)))
            delay(if (weather != null) FallbackWeatherRepository.CACHE_MILLIS else RETRY_MILLIS)
        }
    }

    private fun createTarget(weather: FallbackWeather): SmartspaceTarget {
        val forecast = Intent(Intent.ACTION_VIEW, Uri.parse(FORECAST_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return SmartspaceTarget(
            id = TARGET_ID,
            headerAction = SmartspaceAction(
                id = "fallbackWeatherAction",
                icon = Icon.createWithResource(context, iconResource(weather.icon)),
                title = "",
                subtitle = weather.title(temperatureUnitFor(Locale.getDefault())),
                intent = forecast,
            ),
            score = SmartspaceScores.SCORE_WEATHER,
            featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
        )
    }

    override suspend fun requiresSetup() = !repository.hasLocationPermission()

    override suspend fun startSetup(activity: Activity) {
        val intent = ActivityResultContracts.RequestMultiplePermissions()
            .createIntent(activity, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        BlankActivity.startBlankActivityForResult(activity, intent)
    }

    companion object {
        const val TARGET_ID = "fallbackWeather"
        private const val FORECAST_URL = "https://www.google.com/search?q=weather"
        private const val GOOGLE_GRACE_MILLIS = 10_000L
        private const val RETRY_MILLIS = 5 * 60 * 1000L

        private fun iconResource(icon: FallbackWeatherIcon): Int = when (icon) {
            FallbackWeatherIcon.CLEAR_DAY -> R.drawable.ic_fallback_weather_clear_day
            FallbackWeatherIcon.CLEAR_NIGHT -> R.drawable.ic_fallback_weather_clear_night
            FallbackWeatherIcon.PARTLY_CLOUDY_DAY -> R.drawable.ic_fallback_weather_partly_cloudy_day
            FallbackWeatherIcon.PARTLY_CLOUDY_NIGHT -> R.drawable.ic_fallback_weather_partly_cloudy_night
            FallbackWeatherIcon.CLOUDY -> R.drawable.ic_fallback_weather_cloudy
            FallbackWeatherIcon.FOG -> R.drawable.ic_fallback_weather_fog
            FallbackWeatherIcon.RAIN -> R.drawable.ic_fallback_weather_rain
            FallbackWeatherIcon.SNOW -> R.drawable.ic_fallback_weather_snow
            FallbackWeatherIcon.THUNDER -> R.drawable.ic_fallback_weather_thunder
        }
    }
}
