package app.lawnchair

import app.lawnchair.gestures.config.GestureHandlerOption
import app.lawnchair.gestures.handlers.SleepMode
import com.android.launcher3.BuildConfig

/**
 * Expressive Core (`-PexpressiveCore=true`) ships without the accessibility service and notification
 * listener, because Play Protect's enhanced fraud protection blocks browser-installed apps that
 * declare them (XDA-021). These rules keep Core from offering anything that needs either service.
 */
object ExpressiveCore {

    val isCore: Boolean get() = BuildConfig.EXPRESSIVE_CORE

    /** Notification dots need the notification listener. */
    fun supportsNotificationDots(forCore: Boolean = isCore): Boolean = !forCore

    /** Accessibility global actions (lock screen, recents, a11y fallbacks) need the accessibility service. */
    fun supportsAccessibilityActions(forCore: Boolean = isCore): Boolean = !forCore

    /** Sleep modes a user can pick: Core drops the accessibility mode. */
    fun availableSleepModes(forCore: Boolean = isCore): List<SleepMode> =
        SleepMode.values().filter { supportsAccessibilityActions(forCore) || it != SleepMode.ACCESSIBILITY }

    /** Gesture actions a user can pick: Core drops Recents, which only works through accessibility. */
    fun filterGestureOptions(options: List<GestureHandlerOption>, forCore: Boolean = isCore): List<GestureHandlerOption> =
        if (supportsAccessibilityActions(forCore)) options else options.filterNot { it == GestureHandlerOption.Recents }
}
