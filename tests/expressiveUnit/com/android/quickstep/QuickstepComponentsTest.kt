package com.android.quickstep

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recents and gestures need Android 16. Below it, SystemUI binding TouchInteractionService (when
 * QuickSwitch made the launcher the Recents app) crash-looped Home, GitHub issue #60, so the
 * service and RecentsActivity ship disabled there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuickstepComponentsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(sdk = [31])
    fun android12_componentsAreOff() {
        assertThat(context.resources.getBoolean(R.bool.quickstep_components_enabled)).isFalse()
    }

    @Test
    @Config(sdk = [35])
    fun android15_componentsAreOff() {
        assertThat(context.resources.getBoolean(R.bool.quickstep_components_enabled)).isFalse()
    }

    @Test
    @Config(sdk = [36])
    fun android16_componentsAreOn() {
        assertThat(context.resources.getBoolean(R.bool.quickstep_components_enabled)).isTrue()
    }

    @Test
    @Config(sdk = [37])
    fun currentAndroid_componentsAreOn() {
        assertThat(context.resources.getBoolean(R.bool.quickstep_components_enabled)).isTrue()
        assertThat(serviceEnabled()).isTrue()
        assertThat(activityEnabled()).isTrue()
    }

    @Test
    @Config(sdk = [31])
    fun android12_packageManagerSeesThemDisabled() {
        assertThat(serviceEnabled()).isFalse()
        assertThat(activityEnabled()).isFalse()
    }

    // Class names as strings: loading these classes on an old SDK is exactly what fails.
    private fun serviceEnabled(): Boolean = context.packageManager.getServiceInfo(
        ComponentName(context.packageName, "com.android.quickstep.TouchInteractionService"),
        PackageManager.MATCH_DISABLED_COMPONENTS,
    ).enabled

    private fun activityEnabled(): Boolean = context.packageManager.getActivityInfo(
        ComponentName(context.packageName, "com.android.quickstep.RecentsActivity"),
        PackageManager.MATCH_DISABLED_COMPONENTS,
    ).enabled
}
