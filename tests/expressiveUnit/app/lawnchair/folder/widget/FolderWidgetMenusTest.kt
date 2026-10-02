package app.lawnchair.folder.widget

import android.app.Application
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.model.data.FolderInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetMenusTest {
    @Test fun normalFolder_offersMakeWidget() {
        val f = FolderInfo().apply { container = Favorites.CONTAINER_DESKTOP }
        assertThat(FolderWidgetMenus.itemsFor(f)).containsExactly(R.string.folder_widget_make_widget)
    }

    @Test fun folderWidget_offersCustomizeMakeFolderRemove() {
        val f = FolderInfo().apply { container = Favorites.CONTAINER_DESKTOP; options = FolderInfo.FLAG_FOLDER_WIDGET }
        assertThat(FolderWidgetMenus.itemsFor(f)).containsExactly(
            R.string.folder_widget_customize,
            R.string.folder_widget_make_folder,
            R.string.folder_widget_remove,
        ).inOrder()
    }

    @Test fun dockFolder_offersNothing() {
        assertThat(FolderWidgetMenus.itemsFor(FolderInfo().apply { container = Favorites.CONTAINER_HOTSEAT })).isEmpty()
    }
}
