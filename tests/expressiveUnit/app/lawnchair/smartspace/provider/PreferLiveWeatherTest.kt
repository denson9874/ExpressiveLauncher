package app.lawnchair.smartspace.provider

import app.lawnchair.smartspace.model.SmartspaceTarget
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreferLiveWeatherTest {

    private val live = weather(SmartspaceWidgetReader.WEATHER_TARGET_ID)
    private val dateOnly = weather(SmartspaceWidgetReader.DUMMY_TARGET_ID)
    private val backup = weather(FallbackWeatherProvider.TARGET_ID)
    private val battery = SmartspaceTarget(
        id = "battery",
        featureType = SmartspaceTarget.FeatureType.FEATURE_CALENDAR,
    )

    @Test
    fun googleWeatherWinsOverTheBackup() {
        assertThat(preferLiveWeather(listOf(live, backup, battery)).map { it.id })
            .containsExactly(SmartspaceWidgetReader.WEATHER_TARGET_ID, "battery").inOrder()
    }

    @Test
    fun backupReplacesTheDateOnlyCardWhenGoogleHasNoWeather() {
        assertThat(preferLiveWeather(listOf(dateOnly, backup, battery)).map { it.id })
            .containsExactly(FallbackWeatherProvider.TARGET_ID, "battery").inOrder()
    }

    @Test
    fun withoutBackupWeatherNothingChanges() {
        val targets = listOf(dateOnly, battery)

        assertThat(preferLiveWeather(targets)).isSameInstanceAs(targets)
    }

    private fun weather(id: String) = SmartspaceTarget(
        id = id,
        featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
    )
}
