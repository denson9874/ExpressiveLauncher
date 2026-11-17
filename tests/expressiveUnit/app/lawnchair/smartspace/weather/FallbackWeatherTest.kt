package app.lawnchair.smartspace.weather

import app.lawnchair.util.Temperature
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class FallbackWeatherTest {

    @Test
    fun parse_readsTemperatureAndSymbolOfTheFirstTimeStep() {
        val weather = parseMetForecast(
            """
            {"type":"Feature","properties":{"meta":{"updated_at":"2026-11-17T10:00:00Z"},
            "timeseries":[
              {"time":"2026-11-17T11:00:00Z","data":{
                "instant":{"details":{"air_temperature":-3.6,"wind_speed":2.1}},
                "next_1_hours":{"summary":{"symbol_code":"partlycloudy_night"},"details":{"precipitation_amount":0.0}},
                "next_6_hours":{"summary":{"symbol_code":"rain"}}}},
              {"time":"2026-11-17T12:00:00Z","data":{
                "instant":{"details":{"air_temperature":20.0}},
                "next_1_hours":{"summary":{"symbol_code":"clearsky_day"}}}}
            ]}}
            """.trimIndent(),
        )

        assertThat(weather).isNotNull()
        assertThat(weather!!.temperature.value).isEqualTo(-4)
        assertThat(weather.temperature.unit).isEqualTo(Temperature.Unit.Celsius)
        assertThat(weather.icon).isEqualTo(FallbackWeatherIcon.PARTLY_CLOUDY_NIGHT)
    }

    @Test
    fun parse_fallsBackToLongerPeriodsWhenTheHourlySymbolIsMissing() {
        val weather = parseMetForecast(
            """{"properties":{"timeseries":[{"data":{
                "instant":{"details":{"air_temperature":7.4}},
                "next_6_hours":{"summary":{"symbol_code":"heavysnowshowers_day"}}}}]}}""",
        )

        assertThat(weather!!.icon).isEqualTo(FallbackWeatherIcon.SNOW)
        assertThat(weather.temperature.value).isEqualTo(7)
    }

    @Test
    fun parse_returnsNullWithoutATemperatureOrOnBrokenInput() {
        assertThat(parseMetForecast("""{"properties":{"timeseries":[]}}""")).isNull()
        assertThat(
            parseMetForecast("""{"properties":{"timeseries":[{"data":{"instant":{"details":{}}}}]}}"""),
        ).isNull()
        assertThat(parseMetForecast("<html>503</html>")).isNull()
        assertThat(parseMetForecast("")).isNull()
    }

    @Test
    fun icon_mapsMetSymbolCodes() {
        assertThat(iconForMetSymbol("clearsky_day")).isEqualTo(FallbackWeatherIcon.CLEAR_DAY)
        assertThat(iconForMetSymbol("clearsky_night")).isEqualTo(FallbackWeatherIcon.CLEAR_NIGHT)
        assertThat(iconForMetSymbol("clearsky_polartwilight")).isEqualTo(FallbackWeatherIcon.CLEAR_DAY)
        assertThat(iconForMetSymbol("fair_night")).isEqualTo(FallbackWeatherIcon.PARTLY_CLOUDY_NIGHT)
        assertThat(iconForMetSymbol("partlycloudy_day")).isEqualTo(FallbackWeatherIcon.PARTLY_CLOUDY_DAY)
        assertThat(iconForMetSymbol("cloudy")).isEqualTo(FallbackWeatherIcon.CLOUDY)
        assertThat(iconForMetSymbol("fog")).isEqualTo(FallbackWeatherIcon.FOG)
        assertThat(iconForMetSymbol("lightrainshowers_day")).isEqualTo(FallbackWeatherIcon.RAIN)
        assertThat(iconForMetSymbol("sleet")).isEqualTo(FallbackWeatherIcon.RAIN)
        assertThat(iconForMetSymbol("lightsnow")).isEqualTo(FallbackWeatherIcon.SNOW)
        assertThat(iconForMetSymbol("heavyrainandthunder")).isEqualTo(FallbackWeatherIcon.THUNDER)
        assertThat(iconForMetSymbol("snowshowersandthunder_night")).isEqualTo(FallbackWeatherIcon.THUNDER)
        assertThat(iconForMetSymbol(null)).isEqualTo(FallbackWeatherIcon.CLOUDY)
        assertThat(iconForMetSymbol("somethingnew")).isEqualTo(FallbackWeatherIcon.CLOUDY)
    }

    @Test
    fun coordinates_areRoundedToTwoDecimalsWithADotInEveryLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertThat(roundCoordinate(40.712776)).isEqualTo("40.71")
            assertThat(roundCoordinate(-74.005974)).isEqualTo("-74.01")
            assertThat(roundCoordinate(0.0)).isEqualTo("0.00")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun title_usesFahrenheitOnlyWhereItIsTheLocalUnit() {
        val weather = FallbackWeather(
            Temperature(20, Temperature.Unit.Celsius),
            FallbackWeatherIcon.CLEAR_DAY,
        )

        assertThat(weather.title(temperatureUnitFor(Locale.US))).isEqualTo("68°F")
        assertThat(weather.title(temperatureUnitFor(Locale.GERMANY))).isEqualTo("20°C")
        assertThat(weather.title(temperatureUnitFor(Locale.UK))).isEqualTo("20°C")
    }
}
