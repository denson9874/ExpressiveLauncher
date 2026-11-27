package app.lawnchair.ui.popup

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.theme.UiColorMode
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.ui.preferences.components.ThemeChoice
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.android.launcher3.shortcuts.DeepShortcutView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android 17 QPR2 Pixel Launcher home menu: one wallpaper-tinted card holding the wallpaper
 * carousel and the options, instead of separate tiles. Upstream Lawnchair flavors keep their style.
 */
internal val useQpr2HomeMenu: Boolean
    get() = BuildConfig.IS_EXPRESSIVE_PRODUCT

private const val HOME_MENU_SCREEN_FRACTION = 0.78f
private const val HOME_MENU_MAX_WIDTH_DP = 360
private const val HOME_MENU_MAX_RECENT_WALLPAPERS = 3

/** Pixel's menu spans about 78% of a phone's width; large screens are capped so rows stay compact. */
internal fun homeMenuWidthPx(screenWidthPx: Int, density: Float): Int = min((screenWidthPx * HOME_MENU_SCREEN_FRACTION).roundToInt(), (HOME_MENU_MAX_WIDTH_DP * density).roundToInt())

internal data class HomeMenuCarouselPlan(val currentPreviews: Int, val savedPreviews: Int)

/** QPR2 shows the current home wallpaper, then recent ones; there is no separate lock preview. */
internal fun homeMenuCarouselPlan(savedCount: Int): HomeMenuCarouselPlan = HomeMenuCarouselPlan(currentPreviews = 1, savedPreviews = savedCount.coerceIn(0, HOME_MENU_MAX_RECENT_WALLPAPERS))

internal data class HomeMenuColors(
    @param:ColorInt val surface: Int,
    @param:ColorInt val onSurface: Int,
)

internal fun isQpr2HomeMenuDark(
    themeChoice: String,
    systemDark: Boolean,
): Boolean = when (themeChoice) {
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
    else -> systemDark
}

internal fun resolveQpr2HomeMenuColors(context: Context): HomeMenuColors {
    val themeChoice = PreferenceManager.getInstance(context).launcherTheme.get()
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val colorMode = if (isQpr2HomeMenuDark(themeChoice, systemDark)) {
        UiColorMode.Dark
    } else {
        UiColorMode.Light
    }
    return HomeMenuColors(
        surface = ColorTokens.SurfaceContainerHigh.resolveColor(context, colorMode),
        onSurface = ColorTokens.TextColorPrimary.resolveColor(context, colorMode),
    )
}

/** Draws the popup as a single tinted card: rows sit directly on it with no gaps or shadows. */
internal fun applyQpr2HomeMenuStyle(popup: ViewGroup) {
    val context = popup.context
    val colors = resolveQpr2HomeMenuColors(context)
    popup.background = GradientDrawable().apply {
        cornerRadius = context.resources.getDimension(R.dimen.expressive_home_menu_corner_radius)
        setColor(colors.surface)
    }
    popup.clipToOutline = true
    val verticalPadding = context.resources.getDimensionPixelSize(R.dimen.expressive_home_menu_vertical_padding)
    popup.setPadding(0, verticalPadding, 0, verticalPadding)
    for (index in 0 until popup.childCount) {
        val child = popup.getChildAt(index)
        child.background = null
        child.elevation = 0f
        (child.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
        if (child is DeepShortcutView) {
            child.bubbleText.setTextColor(colors.onSurface)
            child.iconView.background?.mutate()?.setTint(colors.onSurface)
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }
}
