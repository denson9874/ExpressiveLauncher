package app.lawnchair

import app.lawnchair.gestures.config.GestureHandlerOption
import app.lawnchair.gestures.config.gestureHandlerOptions
import app.lawnchair.gestures.handlers.SleepMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** XDA-021: Expressive Core must not offer anything that needs accessibility or notification access. */
class ExpressiveCoreTest {

    @Test
    fun fullBuild_keepsEveryCapability() {
        assertThat(ExpressiveCore.supportsNotificationDots(forCore = false)).isTrue()
        assertThat(ExpressiveCore.supportsNowPlaying(forCore = false)).isTrue()
        assertThat(ExpressiveCore.supportsAccessibilityActions(forCore = false)).isTrue()
        assertThat(ExpressiveCore.availableSleepModes(forCore = false)).containsExactlyElementsIn(SleepMode.values())
        assertThat(ExpressiveCore.filterGestureOptions(gestureHandlerOptions, forCore = false))
            .containsExactlyElementsIn(gestureHandlerOptions).inOrder()
    }

    @Test
    fun coreBuild_dropsNotificationDotsAndAccessibilityActions() {
        assertThat(ExpressiveCore.supportsNotificationDots(forCore = true)).isFalse()
        assertThat(ExpressiveCore.supportsNowPlaying(forCore = true)).isFalse()
        assertThat(ExpressiveCore.supportsAccessibilityActions(forCore = true)).isFalse()
    }

    @Test
    fun coreBuild_offersSleepWithoutAccessibility() {
        val modes = ExpressiveCore.availableSleepModes(forCore = true)
        assertThat(modes).doesNotContain(SleepMode.ACCESSIBILITY)
        assertThat(modes).containsAtLeast(SleepMode.AUTO, SleepMode.DEVICE_ADMIN, SleepMode.ROOT)
    }

    @Test
    fun coreBuild_hidesTheRecentsGestureOnly() {
        val options = ExpressiveCore.filterGestureOptions(gestureHandlerOptions, forCore = true)
        assertThat(options).doesNotContain(GestureHandlerOption.Recents)
        assertThat(options).containsAtLeast(
            GestureHandlerOption.Sleep,
            GestureHandlerOption.OpenNotifications,
            GestureHandlerOption.OpenQuickSettings,
        )
        assertThat(options).hasSize(gestureHandlerOptions.size - 1)
    }

    @Test
    fun fullDownloadUrl_qaCore_opensTheFullReleaseOfTheSameVersion() {
        // Matches the published tags, e.g. qa-v5.1.1-73 (Full) beside qa-core-v5.1.1-73 (Core).
        assertThat(ExpressiveCore.fullDownloadUrl(versionName = "5.1.2", versionCode = 74, buildType = "qa"))
            .isEqualTo("https://github.com/denson9874/ExpressiveLauncher/releases/tag/qa-v5.1.2-74")
    }

    @Test
    fun fullDownloadUrl_otherBuilds_openTheReleasesList() {
        assertThat(ExpressiveCore.fullDownloadUrl(versionName = "5.1.2", versionCode = 74, buildType = "release"))
            .isEqualTo("https://github.com/denson9874/ExpressiveLauncher/releases")
        assertThat(ExpressiveCore.fullDownloadUrl(versionName = "5.1.2", versionCode = 74, buildType = "debug"))
            .isEqualTo("https://github.com/denson9874/ExpressiveLauncher/releases")
    }

    @Test
    fun thisTestBuild_isFull() {
        // Unit tests compile the default (non-Core) variant.
        assertThat(ExpressiveCore.isCore).isFalse()
    }
}
