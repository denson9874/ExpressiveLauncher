package com.android.launcher3.graphics

import android.animation.ObjectAnimator
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.util.ScreenOnTracker
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * D13: the Top shadow and the Solid status bar draw at progress x multiplier. The multiplier
 * started at 0 and only the screen-on fade, a drawer swipe or the app-to-home reveal set it to 1,
 * so both were invisible after every launcher start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SysUiScrimStrengthTest {

    @Test
    fun freshScrim_drawsAtItsProgress() {
        val scrim = SysUiScrim(View(launcher()))

        // Home applies NORMAL: WorkspaceStateTransitionAnimation.setScrim moves progress to 1.
        scrim.getSysUIProgress().updateValue(1f)

        assertThat(scrim.getSysUIMultiplier().value).isEqualTo(1f)
        assertThat(scrim.getScrimAlpha()).isEqualTo(255)
        assertThat(scrim.getSolidStatusBarAlpha()).isEqualTo(255)

        scrim.getSysUIProgress().updateValue(0.5f)

        assertThat(scrim.getScrimAlpha()).isEqualTo(128)
        assertThat(scrim.getSolidStatusBarAlpha()).isEqualTo(128)
    }

    @Test
    fun attachedScrim_withNoScreenOnFadePending_drawsAtFullStrength() {
        val root = View(launcher())
        val scrim = SysUiScrim(root)
        scrim.getSysUIProgress().updateValue(1f)
        // The multiplier the scrim used to start with.
        scrim.getSysUIMultiplier().updateValue(0f)
        assertThat(scrim.getScrimAlpha()).isEqualTo(0)

        scrim.onViewAttachedToWindow(root)

        assertThat(scrim.getSysUIMultiplier().value).isEqualTo(1f)
        assertThat(scrim.getScrimAlpha()).isEqualTo(255)
        assertThat(scrim.getSolidStatusBarAlpha()).isEqualTo(255)
    }

    @Test
    fun screenOnFade_stillFadesInFromZero() {
        val root = View(launcher())
        val scrim = SysUiScrim(root)
        // Top shadow on (its default), whatever the test theme says about the wallpaper's text.
        ReflectionHelpers.setField(scrim, "mHideSysUiScrim", false)
        scrim.getSysUIProgress().updateValue(1f)
        scrim.onViewAttachedToWindow(root)

        ReflectionHelpers.getField<ScreenOnTracker.ScreenOnListener>(scrim, "mScreenOnListener")
            .onScreenOnChanged(false)
        // Re-attaching while the fade is pending must not cancel it.
        scrim.onViewDetachedFromWindow(root)
        scrim.onViewAttachedToWindow(root)

        scrim.draw(Canvas(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)))

        val multiplier = scrim.getSysUIMultiplier()
        assertThat(multiplier.value).isEqualTo(0f)
        assertThat(scrim.getScrimAlpha()).isEqualTo(0)
        assertThat(multiplier.isAnimating()).isTrue()

        ReflectionHelpers.getField<ObjectAnimator>(multiplier, "mValueAnimator").end()

        assertThat(multiplier.value).isEqualTo(1f)
        assertThat(scrim.getScrimAlpha()).isEqualTo(255)
    }

    @Test
    fun solidStatusBarToggle_reappliesTheDrawnAlpha() {
        val root = CountingView(launcher())
        val scrim = SysUiScrim(root)
        assertThat(scrim.getSolidStatusBarAlpha()).isEqualTo(0)
        // A direct write skips AnimatedFloat's callback, so the paints still hold the old alpha.
        scrim.getSysUIProgress().value = 0.5f
        root.invalidations = 0

        scrim.setSolidStatusBar(true)

        assertThat(scrim.getSolidStatusBarAlpha()).isEqualTo(128)
        assertThat(root.invalidations).isAtLeast(1)

        scrim.getSysUIProgress().value = 1f
        root.invalidations = 0

        scrim.setSolidStatusBar(false)

        assertThat(scrim.getSolidStatusBarAlpha()).isEqualTo(255)
        assertThat(root.invalidations).isAtLeast(1)
    }

    private fun launcher(): LawnchairLauncher =
        Robolectric.buildActivity(LawnchairLauncher::class.java).get()

    /** Counts redraw requests: turning the setting on or off must redraw Home. */
    private class CountingView(context: Context) : View(context) {
        var invalidations = 0

        override fun invalidate() {
            invalidations++
            super.invalidate()
        }
    }
}
