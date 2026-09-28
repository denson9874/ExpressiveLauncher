package app.lawnchair.qsb

import android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
import android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GoogleDockSearchWidgetPolicyTest {
    private val premium = candidate("com.google.android.apps.gsa.staticplugins.searchwidget.PremiumSearchWidgetProvider", WIDGET_CATEGORY_HOME_SCREEN)
    private val legacy = candidate("com.google.android.googlequicksearchbox.SearchWidgetProvider")
    private val pixel = candidate("com.google.android.apps.gsa.staticplugins.searchwidget.GoogleSearchWidgetProvider")

    @Test
    fun prefersTheWidgetPixelLauncherHostsInItsDock() {
        assertThat(pickGoogleDockSearchWidget(listOf(premium, legacy, pixel))).isEqualTo(pixel)
    }

    @Test
    fun fallsBackToAnotherSearchBoxWidget() {
        assertThat(pickGoogleDockSearchWidget(listOf(premium, legacy))).isEqualTo(legacy)
    }

    @Test
    fun neverBindsAHomeScreenOnlyWidgetThatRendersEmptyInTheDock() {
        assertThat(pickGoogleDockSearchWidget(listOf(premium))).isNull()
    }

    @Test
    fun skipsWidgetsWhoseConfigurationIsMandatory() {
        val configurable = pixel.copy(needsConfiguration = true)
        assertThat(pickGoogleDockSearchWidget(listOf(configurable, legacy))).isEqualTo(legacy)
    }

    @Test
    fun optionalCustomizationDoesNotBlockBinding() {
        // Flags observed from the Google app 17.46: GoogleSearchWidgetProvider=5, SearchWidgetProvider=7.
        assertThat(needsMandatoryConfiguration(hasConfigureActivity = true, widgetFeatures = 5)).isFalse()
        assertThat(needsMandatoryConfiguration(hasConfigureActivity = true, widgetFeatures = 7)).isFalse()
        assertThat(needsMandatoryConfiguration(hasConfigureActivity = true, widgetFeatures = 1)).isTrue()
        assertThat(needsMandatoryConfiguration(hasConfigureActivity = false, widgetFeatures = 2)).isFalse()
    }

    private fun candidate(className: String, category: Int = WIDGET_CATEGORY_HOME_SCREEN or WIDGET_CATEGORY_SEARCHBOX) =
        SearchWidgetCandidate(className = className, category = category, needsConfiguration = false)
}
