package app.lawnchair.util

import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.children
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.Utilities
import com.android.systemui.shared.system.BlurUtils
import java.lang.reflect.Method

/**
 * Frosted glass on Android 17 QPR2 (API 37.2): [View.setBackdropRenderEffect] blurs the content
 * this window drew behind a view. The wallpaper is a separate surface and never part of that
 * backdrop. The blur is also composited over the original pixels, so over the transparent home
 * window thin text behind the glass stays crisp unless [apply] gets an opaque `base` color.
 *
 * The framework-16 prebuilt hides the 37.2 APIs from Java (and Window's from Kotlin too), so all
 * calls go through here.
 */
object BackdropGlass {

    private const val TAG = "BackdropGlass"
    private const val RADIUS_DP = 24f
    private const val SATURATION = 1.6f
    internal const val PIECE_ALPHA_LIGHT = 199 // 78% WCAG 2.2 AA frosted scrim floor in light mode
    internal const val PIECE_ALPHA_DARK = 210 // 82% WCAG 2.2 AA frosted scrim floor in dark mode

    @JvmStatic
    internal fun pieceAlphaForBase(@ColorInt baseColor: Int): Int = if (ColorUtils.calculateLuminance(baseColor) < 0.5) PIECE_ALPHA_DARK else PIECE_ALPHA_LIGHT

    // Reflection: the framework-16 prebuilt's Window, which the compiler sees, has no setDimColor.
    // Cache the reflected Method once per process instead of looking it up on every window open.
    private val setDimColorMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        try {
            Window::class.java.getMethod("setDimColor", Long::class.javaPrimitiveType)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Window.setDimColor unavailable; keeping the black dim", e)
            null
        }
    }

    @JvmStatic
    fun isEnabled(context: Context): Boolean = isEnabled(
        sdkSupported = Utilities.ATLEAST_CINNAMON_BUN_2,
        prefEnabled = PreferenceManager.getInstance(context).frostedGlass.get(),
        blurSupported = BlurUtils.supportsBlursOnWindows(),
        blurAllowed = context.getSystemService(WindowManager::class.java)
            ?.isCrossWindowBlurEnabled == true,
    )

    /** Battery Saver and "Allow window-level blurs" switch glass off along with other blurs. */
    @JvmStatic
    fun isEnabled(
        sdkSupported: Boolean,
        prefEnabled: Boolean,
        blurSupported: Boolean,
        blurAllowed: Boolean,
    ): Boolean = sdkSupported && prefEnabled && blurSupported && blurAllowed

    /** Frosts what is behind [view], clipped to its outline, over an optional opaque [base]. */
    @JvmStatic
    @JvmOverloads
    fun apply(view: View, @ColorInt base: Int = Color.TRANSPARENT) {
        if (!Utilities.ATLEAST_CINNAMON_BUN_2) return
        val radius = RADIUS_DP * view.resources.displayMetrics.density
        var effect = RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) }),
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
        )
        if (Color.alpha(base) != 0) {
            // DST_OVER keeps the blurred pixels on top of the base color.
            effect = RenderEffect.createColorFilterEffect(
                BlendModeColorFilter(base, BlendMode.DST_OVER),
                effect,
            )
        }
        view.clipToOutline = true
        view.setBackdropRenderEffect(effect)
    }

    /**
     * Frosts the first opaque [GradientDrawable] piece down each branch of [group], such as popup
     * items or menu options. Each piece turns translucent over a blur of what is behind it.
     */
    @JvmStatic
    fun applyToPieces(group: ViewGroup) {
        for (child in group.children) {
            if (child.visibility != View.VISIBLE) continue
            val background = child.background as? GradientDrawable
            val color = background?.color?.defaultColor
            if (background != null && color != null && Color.alpha(color) == 255) {
                background.mutate()
                background.setColor(ColorUtils.setAlphaComponent(color, pieceAlphaForBase(color)))
                apply(child, color)
            } else if (child is ViewGroup) {
                applyToPieces(child)
            }
        }
    }

    /**
     * Blurs the app behind a floating [window] and tints its dim with [dimColor]. Before
     * `Window.setDimColor` (API 37.2) the dim could only be black.
     */
    @JvmStatic
    fun applyBehindWindow(window: Window, @ColorInt dimColor: Int) {
        if (!isEnabled(window.context)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.also {
            it.blurBehindRadius = (RADIUS_DP * window.context.resources.displayMetrics.density).toInt()
        }
        val method = setDimColorMethod ?: return
        try {
            method.invoke(window, Color.pack(dimColor))
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Window.setDimColor invocation failed; keeping the black dim", e)
        }
    }

    /** Removes the glass and the outline clip [apply] turned on. */
    @JvmStatic
    fun clear(view: View) {
        if (!Utilities.ATLEAST_CINNAMON_BUN_2) return
        view.setBackdropRenderEffect(null)
        view.clipToOutline = false
    }
}
