package app.lawnchair

import android.app.Application
import android.content.ComponentName
import app.lawnchair.ui.popup.LawnchairShortcut
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_ALL_APPS_PREDICTION
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_HOTSEAT
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_PRIVATESPACE
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TG-003: one-tap "Hide" is offered for drawer apps only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class HideAppShortcutPolicyTest {

    private val component = ComponentName("com.example.app", "com.example.app.Main")

    private fun drawerApp(container: Int) = AppInfo().apply {
        itemType = ITEM_TYPE_APPLICATION
        this.container = container
        componentName = component
    }

    @Test
    fun offeredForAppsInTheDrawerAndItsPredictionRow() {
        assertThat(LawnchairShortcut.canHideFromDrawer(drawerApp(CONTAINER_ALL_APPS), false)).isTrue()
        assertThat(LawnchairShortcut.canHideFromDrawer(drawerApp(CONTAINER_ALL_APPS_PREDICTION), false)).isTrue()
    }

    @Test
    fun notOfferedOnHomeHotseatOrPrivateSpace() {
        for (container in listOf(CONTAINER_DESKTOP, CONTAINER_HOTSEAT, CONTAINER_PRIVATESPACE)) {
            assertThat(LawnchairShortcut.canHideFromDrawer(drawerApp(container), false)).isFalse()
        }
    }

    @Test
    fun notOfferedWhileHomeScreenIsLocked() {
        assertThat(LawnchairShortcut.canHideFromDrawer(drawerApp(CONTAINER_ALL_APPS), true)).isFalse()
    }

    @Test
    fun notOfferedForShortcutsOrItemsWithoutAComponent() {
        val shortcut = WorkspaceItemInfo().apply {
            itemType = ITEM_TYPE_DEEP_SHORTCUT
            container = CONTAINER_ALL_APPS
        }
        val noComponent = AppInfo().apply {
            itemType = ITEM_TYPE_APPLICATION
            container = CONTAINER_ALL_APPS
        }
        assertThat(LawnchairShortcut.canHideFromDrawer(shortcut, false)).isFalse()
        assertThat(LawnchairShortcut.canHideFromDrawer(noComponent, false)).isFalse()
    }
}
