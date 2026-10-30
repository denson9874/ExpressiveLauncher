package app.lawnchair.util

import android.app.Application
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Frosted glass needs API 37.2, the pref, window blur support and blur not switched off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class BackdropGlassTest {

    @Test
    fun enabledOnlyWhenEveryConditionHolds() {
        assertThat(BackdropGlass.isEnabled(true, true, true, true)).isTrue()
    }

    @Test
    fun anyMissingConditionTurnsGlassOff() {
        assertThat(BackdropGlass.isEnabled(false, true, true, true)).isFalse() // below 37.2
        assertThat(BackdropGlass.isEnabled(true, false, true, true)).isFalse() // toggle off
        assertThat(BackdropGlass.isEnabled(true, true, false, true)).isFalse() // no window blur
        assertThat(BackdropGlass.isEnabled(true, true, true, false)).isFalse() // Battery Saver
    }
}
