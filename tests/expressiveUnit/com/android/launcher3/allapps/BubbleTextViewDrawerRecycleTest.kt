package com.android.launcher3.allapps

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Point
import android.os.Process
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.BubbleTextView
import com.android.launcher3.DeviceProfile
import com.android.launcher3.deviceprofile.AllAppsProfile
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.popup.PopupDataProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BubbleTextViewDrawerRecycleTest {

    @Test
    fun reset_onAllAppsDisplay_synchronizesIconSizeWithAllAppsProfile() {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        val dp = DeviceProfile()
        val allAppsProfile = AllAppsProfile(
            borderSpacePx = Point(16, 16),
            cellHeightPx = 120,
            iconSizePx = 64,
            iconTextSizePx = 14f,
            iconDrawablePaddingPx = 8,
            maxAllAppsTextLineCount = 1,
            cellWidthPx = 100,
        )
        ReflectionHelpers.setField(dp, "mAllAppsProfile", allAppsProfile)
        ReflectionHelpers.setField(launcher, "mDeviceProfile", dp)

        val bubbleTextView = BubbleTextView(launcher)
        bubbleTextView.setDisplay(BubbleTextView.DISPLAY_ALL_APPS)

        // Simulate stale or overridden icon size on recycled view
        bubbleTextView.setIconSizeOverridePx(40)
        assertThat(bubbleTextView.iconSize).isEqualTo(40)

        // TG-009: Resetting recycled view re-synchronizes iconSize to the active AllAppsProfile
        bubbleTextView.reset()
        assertThat(bubbleTextView.iconSize).isEqualTo(64)

        // When AllAppsProfile dynamic size changes, next reset updates to new size
        val updatedProfile = allAppsProfile.copy(iconSizePx = 72)
        ReflectionHelpers.setField(dp, "mAllAppsProfile", updatedProfile)

        bubbleTextView.reset()
        assertThat(bubbleTextView.iconSize).isEqualTo(72)
    }

    @Test
    fun applyFromApplicationInfo_onAllAppsDisplay_synchronizesIconSize() {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        val dp = DeviceProfile()
        val allAppsProfile = AllAppsProfile(
            borderSpacePx = Point(16, 16),
            cellHeightPx = 120,
            iconSizePx = 60,
            iconTextSizePx = 14f,
            iconDrawablePaddingPx = 8,
            maxAllAppsTextLineCount = 1,
            cellWidthPx = 100,
        )
        ReflectionHelpers.setField(dp, "mAllAppsProfile", allAppsProfile)
        ReflectionHelpers.setField(launcher, "mDeviceProfile", dp)
        ReflectionHelpers.setField(launcher, "mPopupDataProvider", PopupDataProvider(launcher))

        val bubbleTextView = BubbleTextView(launcher)
        bubbleTextView.setDisplay(BubbleTextView.DISPLAY_ALL_APPS)
        bubbleTextView.setIconSizeOverridePx(40)

        val appInfo = AppInfo().apply {
            componentName = ComponentName(launcher, "com.example.test.MainActivity")
            intent = Intent().setComponent(componentName)
            user = Process.myUserHandle()
            title = "Test App"
            bitmap = com.android.launcher3.icons.BitmapInfo.of(
                android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888),
                0
            )
        }

        bubbleTextView.applyFromApplicationInfo(appInfo)
        assertThat(bubbleTextView.iconSize).isEqualTo(60)
    }
}
