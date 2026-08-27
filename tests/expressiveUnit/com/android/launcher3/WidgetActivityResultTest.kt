package com.android.launcher3

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WidgetActivityResultTest {

    @Test
    fun nullBindResult_usesPreviouslyAllocatedWidgetId() {
        assertThat(Launcher.getAppWidgetIdFromResult(null, 42)).isEqualTo(42)
    }

    @Test
    fun resultWithoutWidgetExtra_usesPreviouslyAllocatedWidgetId() {
        assertThat(Launcher.getAppWidgetIdFromResult(Intent(), 42)).isEqualTo(42)
    }

    @Test
    fun resultWithWidgetExtra_usesReturnedWidgetId() {
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 99)

        assertThat(Launcher.getAppWidgetIdFromResult(result, 42)).isEqualTo(99)
    }
}
