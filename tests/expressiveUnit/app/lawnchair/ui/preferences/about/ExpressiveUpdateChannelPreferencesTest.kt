package app.lawnchair.ui.preferences.about

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ExpressiveUpdateChannelPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun resetPreferences() {
        context.getSharedPreferences("expressive_update_channels", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun noSavedChoice_followsInstalledBuildChannel() {
        assertThat(store("qa").selectedConfig()?.channel).isEqualTo(ExpressiveUpdateChannel.QA)
        assertThat(store("release").selectedConfig()?.channel).isEqualTo(ExpressiveUpdateChannel.RELEASE)
    }

    @Test
    fun selection_survivesNewStoreInstanceAndBuildChannelReplacement() {
        store("qa").select(ExpressiveUpdateChannel.RELEASE)

        assertThat(store("qa").selectedConfig()?.channel).isEqualTo(ExpressiveUpdateChannel.RELEASE)
        assertThat(store("release").selectedConfig()?.channel).isEqualTo(ExpressiveUpdateChannel.RELEASE)
    }

    @Test
    fun explicitChoiceOfCurrentDefault_isPersistedAcrossBuildReplacement() {
        store("qa").select(ExpressiveUpdateChannel.QA)

        assertThat(store("release").selectedConfig()?.channel).isEqualTo(ExpressiveUpdateChannel.QA)
    }

    @Test
    fun oldNotificationAndInstallerSelection_cannotSurviveQaStableQaRoundTrip() {
        val preferences = store("qa")
        val oldConfig = checkNotNull(preferences.selectedConfig())
        val oldRevision = preferences.selectionRevision()
        preferences.select(ExpressiveUpdateChannel.RELEASE)
        store("qa").select(ExpressiveUpdateChannel.QA)
        var actions = 0

        assertThat(preferences.withCurrentSelection(oldConfig, oldRevision) { actions++ }).isFalse()
        assertThat(
            preferences.withCurrentSelection(oldConfig, preferences.selectionRevision()) { actions++ },
        ).isTrue()
        assertThat(actions).isEqualTo(1)
    }

    private fun store(buildType: String) = ExpressiveUpdateChannelPreferences(
        context,
        buildType = buildType,
        qaManifestUrl = "https://example.com/qa-v2/latest.json",
        releaseManifestUrl = "https://example.com/release/latest.json",
        isExpressiveProduct = true,
    )
}
