package app.lawnchair.theme

import androidx.annotation.ColorInt

/**
 * Rules for the optional solid status-bar background on Home.
 *
 * Android owns the status bar's height and content; a Home app can only paint behind it. The solid
 * background therefore covers exactly the top inset, only while the status bar is shown, and forces
 * light status-bar icons so the clock and icons stay readable on the dark bar.
 */
object StatusBarBackgroundPolicy {

    @ColorInt
    const val SOLID_BACKGROUND_COLOR: Int = 0xFF000000.toInt()

    @JvmStatic
    fun drawsSolidBackground(enabled: Boolean, statusBarShown: Boolean, topInsetPx: Int): Boolean =
        enabled && statusBarShown && topInsetPx > 0

    /** Height of the painted bar; zero when nothing is drawn. */
    @JvmStatic
    fun solidBackgroundHeight(enabled: Boolean, statusBarShown: Boolean, topInsetPx: Int): Int =
        if (drawsSolidBackground(enabled, statusBarShown, topInsetPx)) topInsetPx else 0

    /**
     * Whether Home asks Android for dark status-bar icons. A solid dark bar always wins over a light
     * wallpaper and the Dark status bar setting, because dark icons on it would be invisible.
     */
    @JvmStatic
    fun useDarkStatusBarIcons(
        isWorkspaceDarkText: Boolean,
        darkStatusBar: Boolean,
        solidBackground: Boolean,
        statusBarShown: Boolean,
    ): Boolean {
        if (solidBackground && statusBarShown) return false
        return isWorkspaceDarkText || darkStatusBar
    }
}
