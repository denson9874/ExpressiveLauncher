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
        assertThat(ExpressiveCore.supportsAccessibilityActions(forCore = false)).isTrue()
        assertThat(ExpressiveCore.availableSleepModes(forCore = false)).containsExactlyElementsIn(SleepMode.values())
        assertThat(ExpressiveCore.filterGestureOptions(gestureHandlerOptions, forCore = false))
            .containsExactlyElementsIn(gestureHandlerOptions).inOrder()
    }

    @Test
    fun coreBuild_dropsNotificationDotsAndAccessibilityActions() {
        assertThat(ExpressiveCore.supportsNotificationDots(forCore = true)).isFalse()
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
    fun thisTestBuild_isFull() {
        // Unit tests compile the default (non-Core) variant.
        assertThat(ExpressiveCore.isCore).isFalse()
    }
}
