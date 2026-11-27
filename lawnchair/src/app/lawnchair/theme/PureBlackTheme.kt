package app.lawnchair.theme

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.ColorInt
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color as ComposeColor
import app.lawnchair.theme.color.tokens.SystemSurfaceRole

/**
 * Pure Black AMOLED dark surface hierarchy shared across Compose themes and View color tokens.
 *
 * Reads only public [Settings.Secure] keys so it remains 100% safe on unrooted Android 12-17
 * (`minSdk 31`) devices without privileged permissions.
 */
internal object PureBlackTheme {
    const val SETTING_EOS_BLACK_THEME = "eos_black_theme"
    const val SETTING_BERRY_BLACK_THEME = "berry_black_theme"

    @ColorInt const val SURFACE = 0xFF000000.toInt()

    @ColorInt const val SURFACE_CONTAINER_LOW = 0xFF0D0D0D.toInt()

    @ColorInt const val SURFACE_CONTAINER = 0xFF141414.toInt()

    @ColorInt const val SURFACE_CONTAINER_HIGH = 0xFF1C1C1C.toInt()

    @ColorInt const val SURFACE_CONTAINER_HIGHEST = 0xFF242424.toInt()

    @ColorInt const val SURFACE_BRIGHT = 0xFF2C2C2C.toInt()

    @ColorInt const val OUTLINE = 0xFF8E9099.toInt()

    @ColorInt const val OUTLINE_VARIANT = 0xFF3A3C42.toInt()

    fun isEnabled(context: Context): Boolean {
        val resolver = context.contentResolver ?: return false
        return runCatching {
            Settings.Secure.getInt(resolver, SETTING_EOS_BLACK_THEME, 0) == 1 ||
                Settings.Secure.getInt(resolver, SETTING_BERRY_BLACK_THEME, 0) == 1
        }.getOrDefault(false)
    }

    fun applyToComposeColorScheme(
        colorScheme: ColorScheme,
        darkTheme: Boolean,
        pureBlackEnabled: Boolean,
    ): ColorScheme {
        if (!darkTheme || !pureBlackEnabled) return colorScheme
        return colorScheme.copy(
            background = ComposeColor(SURFACE),
            surface = ComposeColor(SURFACE),
            surfaceDim = ComposeColor(SURFACE),
            surfaceContainerLowest = ComposeColor(SURFACE),
            surfaceContainerLow = ComposeColor(SURFACE_CONTAINER_LOW),
            surfaceContainer = ComposeColor(SURFACE_CONTAINER),
            surfaceContainerHigh = ComposeColor(SURFACE_CONTAINER_HIGH),
            surfaceContainerHighest = ComposeColor(SURFACE_CONTAINER_HIGHEST),
            surfaceBright = ComposeColor(SURFACE_BRIGHT),
            outline = ComposeColor(OUTLINE),
            outlineVariant = ComposeColor(OUTLINE_VARIANT),
        )
    }

    @ColorInt
    fun resolveDarkSurfaceColor(role: SystemSurfaceRole): Int? = when (role) {
        SystemSurfaceRole.SURFACE_DARK,
        SystemSurfaceRole.SURFACE_DIM_DARK,
        -> SURFACE

        SystemSurfaceRole.SURFACE_CONTAINER_LOW_DARK -> SURFACE_CONTAINER_LOW

        SystemSurfaceRole.SURFACE_CONTAINER_DARK -> SURFACE_CONTAINER

        SystemSurfaceRole.SURFACE_CONTAINER_HIGH_DARK -> SURFACE_CONTAINER_HIGH

        SystemSurfaceRole.SURFACE_CONTAINER_HIGHEST_DARK -> SURFACE_CONTAINER_HIGHEST

        SystemSurfaceRole.SURFACE_BRIGHT_DARK -> SURFACE_BRIGHT

        else -> null
    }

    fun registerObserver(
        context: Context,
        onChange: () -> Unit,
    ): ContentObserver? {
        val resolver = context.contentResolver ?: return null
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChange()
            }
        }
        return runCatching {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_EOS_BLACK_THEME),
                false,
                observer,
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(SETTING_BERRY_BLACK_THEME),
                false,
                observer,
            )
            observer
        }.getOrNull()
    }

    fun unregisterObserver(context: Context, observer: ContentObserver?) {
        if (observer == null) return
        runCatching {
            context.contentResolver?.unregisterContentObserver(observer)
        }
    }
}
