package com.android.quickstep.util

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GitHub issue #60: on Android 12.0 the launcher crashed on every start as the QuickSwitch Recents
 * provider, because SystemBarUtils (Android 12L+) does not exist there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StatusBarHeightCompatTest {

    @Test
    @Config(sdk = [31])
    fun android12_returnsTheStatusBarDimen_withoutSystemBarUtils() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val height = StatusBarHeightCompat.get(activity)

        assertThat(height).isGreaterThan(0)
        assertThat(height).isEqualTo(statusBarDimen(activity))
    }

    @Test
    @Config(sdk = [31])
    fun android12_contextWithoutADisplay_stillReturnsTheDimen() {
        val context: Context = ApplicationProvider.getApplicationContext()

        assertThat(StatusBarHeightCompat.get(context)).isEqualTo(statusBarDimen(context))
    }

    @Test
    @Config(sdk = [37])
    fun currentAndroid_fallbackRuleStillReturnsAHeight() {
        // API 32+ goes through SystemBarUtils unchanged; Robolectric's activities have no display for
        // it to read, so only the fallback rule is checked here.
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        assertThat(StatusBarHeightCompat.beforeSystemBarUtils(activity)).isGreaterThan(0)
    }

    private fun statusBarDimen(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return context.resources.getDimensionPixelSize(id)
    }
}
