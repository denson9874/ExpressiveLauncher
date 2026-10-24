package app.lawnchair.gestures.handlers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import app.lawnchair.LawnchairLauncher
import app.lawnchair.util.ComponentKeySerializer
import app.lawnchair.util.IntentSerializer
import app.lawnchair.util.UserHandlerSerializer
import com.android.launcher3.R
import com.android.launcher3.util.ComponentKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class OpenAppGestureHandler(
    context: Context,
    private val target: OpenAppTarget,
) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: LawnchairLauncher) {
        when (target) {
            is OpenAppTarget.App -> startApp(launcher, target.key)
            is OpenAppTarget.Shortcut -> Unit
        }
    }

    /**
     * A gesture keeps its target after that app is uninstalled or disabled, or while its profile
     * is paused or locked, and LauncherApps then refuses the launch. Say so instead of crashing
     * Home (R15). The stored gesture is kept on purpose: the app can come back after an update,
     * an unpause or a restore, and the user can pick another action themselves.
     */
    private fun startApp(launcher: LawnchairLauncher, key: ComponentKey) {
        val launcherApps = launcher.getSystemService<LauncherApps>() ?: return
        try {
            launcherApps.startMainActivity(key.componentName, key.user, null, null)
        } catch (e: SecurityException) {
            onTargetUnavailable(launcher, launcherApps, key, e)
        } catch (e: ActivityNotFoundException) {
            onTargetUnavailable(launcher, launcherApps, key, e)
        } catch (e: IllegalStateException) {
            onTargetUnavailable(launcher, launcherApps, key, e)
        }
    }

    private fun onTargetUnavailable(
        launcher: LawnchairLauncher,
        launcherApps: LauncherApps,
        key: ComponentKey,
        error: RuntimeException,
    ) {
        Log.w(TAG, "Open-app target ${key.componentName.flattenToShortString()} can't be started", error)
        Toast.makeText(
            launcher,
            launcher.getString(R.string.gesture_handler_open_app_unavailable, targetLabel(launcher, launcherApps, key)),
            Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * The target app's own label for its user, or its package name when it can't be found there
     * (uninstalled, or its profile can't be queried). Never the name stored in the config: the
     * exported START_ACTION trampoline (RunHandlerActivity) takes that config from any app, so
     * its name is text the caller chose, and Home must not show it as its own message.
     */
    private fun targetLabel(launcher: LawnchairLauncher, launcherApps: LauncherApps, key: ComponentKey): String {
        val packageName = key.componentName.packageName
        val label = try {
            // A disabled app is still installed and keeps its name.
            launcherApps.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS, key.user)
                .loadLabel(launcher.packageManager)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            // The profile is gone or Home may not query it.
            null
        } catch (_: IllegalStateException) {
            // The profile is locked or not running.
            null
        }
        return label?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: packageName
    }

    private companion object {
        const val TAG = "OpenAppGestureHandler"
    }
}

@Serializable
sealed class OpenAppTarget {
    @Serializable
    @SerialName("app")
    data class App(
        @Serializable(ComponentKeySerializer::class) val key: ComponentKey,
    ) : OpenAppTarget()

    @Serializable
    @SerialName("shortcut")
    data class Shortcut(
        @Serializable(IntentSerializer::class) val intent: Intent,
        @Serializable(UserHandlerSerializer::class) val user: UserHandle,
        val packageName: String,
        val id: String,
    ) : OpenAppTarget()
}
