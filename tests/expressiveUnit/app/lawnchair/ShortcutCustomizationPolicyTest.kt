package app.lawnchair

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.ui.popup.LawnchairShortcut
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.ShortcutUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ShortcutCustomizationPolicyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun supportsShortcuts_trueForDeepShortcut() {
        val item = WorkspaceItemInfo().apply {
            itemType = ITEM_TYPE_DEEP_SHORTCUT
            container = CONTAINER_DESKTOP
        }
        assertThat(ShortcutUtil.supportsShortcuts(item)).isTrue()
    }

    @Test
    fun supportsShortcuts_trueForLegacyShortcut() {
        val item = WorkspaceItemInfo().apply {
            itemType = ITEM_TYPE_SHORTCUT
            container = CONTAINER_DESKTOP
        }
        assertThat(ShortcutUtil.supportsShortcuts(item)).isTrue()
    }

    @Test
    fun customizeFactory_returnsCustomizeShortcutForDeepShortcut() {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        val item = WorkspaceItemInfo().apply {
            itemType = ITEM_TYPE_DEEP_SHORTCUT
            container = CONTAINER_DESKTOP
            intent = Intent().apply { component = ComponentName(context, "dummy") }
        }
        val view = View(launcher)
        val shortcut = LawnchairShortcut.CUSTOMIZE.getShortcut(launcher, item, view)
        assertThat(shortcut).isInstanceOf(LawnchairShortcut.CustomizeShortcut::class.java)
    }

    @Test
    fun customizeFactory_returnsCustomizeShortcutForLegacyShortcut() {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        val item = WorkspaceItemInfo().apply {
            itemType = ITEM_TYPE_SHORTCUT
            container = CONTAINER_DESKTOP
            intent = Intent().apply { component = ComponentName(context, "dummy") }
        }
        val view = View(launcher)
        val shortcut = LawnchairShortcut.CUSTOMIZE.getShortcut(launcher, item, view)
        assertThat(shortcut).isInstanceOf(LawnchairShortcut.CustomizeShortcut::class.java)
    }
}
