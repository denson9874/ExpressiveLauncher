package app.lawnchair.smartspace.weather

import app.lawnchair.util.Temperature
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Current conditions from the backup weather service (MET Norway's Locationforecast), shown on the
 * At a Glance date card when the Google app's own At a Glance feed has no weather.
 */
data class FallbackWeather(
    val temperature: Temperature,
    val icon: FallbackWeatherIcon,
) {
    fun title(unit: Temperature.Unit): String = "${temperature.inUnit(unit)}${unit.suffix}"
}

enum class FallbackWeatherIcon {
    CLEAR_DAY,
    CLEAR_NIGHT,
    PARTLY_CLOUDY_DAY,
    PARTLY_CLOUDY_NIGHT,
    CLOUDY,
    FOG,
    RAIN,
    SNOW,
    THUNDER,
}

private val metJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class MetForecast(val properties: MetProperties? = null)

@Serializable
internal data class MetProperties(val timeseries: List<MetTimeStep> = emptyList())

@Serializable
internal data class MetTimeStep(val data: MetData? = null)

@Serializable
internal data class MetData(
    val instant: MetInstant? = null,
    @SerialName("next_1_hours") val next1Hours: MetPeriod? = null,
    @SerialName("next_6_hours") val next6Hours: MetPeriod? = null,
    @SerialName("next_12_hours") val next12Hours: MetPeriod? = null,
)

@Serializable
internal data class MetInstant(val details: MetInstantDetails? = null)

@Serializable
internal data class MetInstantDetails(
    @SerialName("air_temperature") val airTemperature: Double? = null,
)

@Serializable
internal data class MetPeriod(val summary: MetSummary? = null)

@Serializable
internal data class MetSummary(@SerialName("symbol_code") val symbolCode: String? = null)

/** Reads the first time step of a Locationforecast "compact" response, or null when it has none. */
internal fun parseMetForecast(json: String): FallbackWeather? {
    val forecast = try {
        metJson.decodeFromString<MetForecast>(json)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    return forecast.toFallbackWeather()
}

internal fun MetForecast.toFallbackWeather(): FallbackWeather? {
    val now = properties?.timeseries?.firstOrNull()?.data ?: return null
    val celsius = now.instant?.details?.airTemperature ?: return null
    if (celsius.isNaN()) return null
    val symbol = now.next1Hours?.summary?.symbolCode
        ?: now.next6Hours?.summary?.symbolCode
        ?: now.next12Hours?.summary?.symbolCode
    return FallbackWeather(
        temperature = Temperature(celsius.roundToInt(), Temperature.Unit.Celsius),
        icon = iconForMetSymbol(symbol),
    )
}

/**
 * MET symbol codes look like "clearsky_day", "lightrainshowers_night" or "heavysnowandthunder".
 * Unknown or missing codes fall back to the cloudy icon.
 */
internal fun iconForMetSymbol(symbolCode: String?): FallbackWeatherIcon {
    val code = symbolCode?.lowercase(Locale.ROOT).orEmpty()
    val night = code.endsWith("_night")
    return when {
        "thunder" in code -> FallbackWeatherIcon.THUNDER

        "snow" in code -> FallbackWeatherIcon.SNOW

        "sleet" in code || "rain" in code -> FallbackWeatherIcon.RAIN

        "fog" in code -> FallbackWeatherIcon.FOG

        code.startsWith("clearsky") ->
            if (night) FallbackWeatherIcon.CLEAR_NIGHT else FallbackWeatherIcon.CLEAR_DAY

        code.startsWith("fair") || code.startsWith("partlycloudy") ->
            if (night) FallbackWeatherIcon.PARTLY_CLOUDY_NIGHT else FallbackWeatherIcon.PARTLY_CLOUDY_DAY

        else -> FallbackWeatherIcon.CLOUDY
    }
}

/**
 * Coordinates sent to the weather service, rounded to two decimals (about a kilometre). That is
 * all a forecast needs, keeps the request cacheable, and avoids sending a precise position.
 */
internal fun roundCoordinate(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private val FAHRENHEIT_COUNTRIES = setOf("US", "BS", "BZ", "KY", "LR", "PW", "FM", "MH")

internal fun temperatureUnitFor(locale: Locale): Temperature.Unit = if (locale.country.uppercase(Locale.ROOT) in FAHRENHEIT_COUNTRIES) {
    Temperature.Unit.Fahrenheit
} else {
    Temperature.Unit.Celsius
}
