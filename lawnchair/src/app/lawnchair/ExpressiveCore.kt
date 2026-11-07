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

    private const val REPOSITORY_URL = "https://github.com/denson9874/ExpressiveLauncher"

    val isCore: Boolean get() = BuildConfig.EXPRESSIVE_CORE

    /**
     * Where a Core user downloads Expressive Full. Core ships on QA only, and each Core QA release is
     * published after the Full release of the same version, so that page always exists. Full installs
     * over Core in place (same package and signer) and keeps the user's setup.
     */
    fun fullDownloadUrl(
        versionName: String = BuildConfig.VERSION_DISPLAY_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
        buildType: String = BuildConfig.BUILD_TYPE,
    ): String = if (buildType == "qa") {
        "$REPOSITORY_URL/releases/tag/qa-v$versionName-$versionCode"
    } else {
        "$REPOSITORY_URL/releases"
    }

    /** Notification dots need the notification listener. */
    fun supportsNotificationDots(forCore: Boolean = isCore): Boolean = !forCore

    /**
     * At a Glance "Now playing" reads media sessions through the notification listener. In Core its
     * setup could never finish, leaving a permanent "Tap to set up" card, so Core leaves it out.
     */
    fun supportsNowPlaying(forCore: Boolean = isCore): Boolean = !forCore

    /** Accessibility global actions (lock screen, recents, a11y fallbacks) need the accessibility service. */
    fun supportsAccessibilityActions(forCore: Boolean = isCore): Boolean = !forCore

    /** Sleep modes a user can pick: Core drops the accessibility mode. */
    fun availableSleepModes(forCore: Boolean = isCore): List<SleepMode> = SleepMode.values().filter { supportsAccessibilityActions(forCore) || it != SleepMode.ACCESSIBILITY }

    /** Gesture actions a user can pick: Core drops Recents, which only works through accessibility. */
    fun filterGestureOptions(options: List<GestureHandlerOption>, forCore: Boolean = isCore): List<GestureHandlerOption> = if (supportsAccessibilityActions(forCore)) options else options.filterNot { it == GestureHandlerOption.Recents }
}
