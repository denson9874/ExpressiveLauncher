package app.lawnchair.predictions

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DockSuggestionsPolicyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun enabled_showsEveryPrediction() {
        val predictions = listOf("Camera", "Maps", "Photos")

        assertThat(DockSuggestionsPolicy.visibleSuggestions(true, predictions))
            .containsExactlyElementsIn(predictions).inOrder()
    }

    @Test
    fun disabled_keepsEmptyDockSpotsEmpty() {
        assertThat(DockSuggestionsPolicy.visibleSuggestions(false, listOf("Camera", "Maps")))
            .isEmpty()
    }

    @Test
    fun hidingSuggestions_neverRestoresTheMigrationBackup() {
        // The predictor still sends apps; only the dock hides them.
        assertThat(DockSuggestionsPolicy.shouldRestoreMigrationBackup(listOf("Camera"))).isFalse()
    }

    @Test
    fun emptyPredictorResult_keepsUpstreamRestoreBehaviour() {
        assertThat(DockSuggestionsPolicy.shouldRestoreMigrationBackup(emptyList<String>())).isTrue()
    }

    @Test
    fun preference_defaultsOnLikePixelAndPersists() {
        val prefs = PreferenceManager2.getInstance(context)

        assertThat(context.resources.getBoolean(R.bool.config_default_show_suggested_apps_in_dock)).isTrue()
        assertThat(prefs.showSuggestedAppsInDock.defaultValue).isTrue()

        prefs.showSuggestedAppsInDock.setBlocking(false)
        assertThat(prefs.showSuggestedAppsInDock.firstBlocking()).isFalse()
        prefs.showSuggestedAppsInDock.setBlocking(true)
    }

    @Test
    fun settingCopy_explainsEmptySpots() {
        assertThat(context.getString(R.string.show_suggested_apps_in_dock)).isEqualTo("Suggestions in dock")
        assertThat(context.getString(R.string.show_suggested_apps_in_dock_description))
            .contains("empty spots empty")
    }
}
