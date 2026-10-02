package app.lawnchair.folder.widget

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetsTest {
    private fun folder(options: Int = 0) = FolderInfo().apply { this.options = options }

    @Test fun flag_marksAFolderWidget() {
        assertThat(FolderWidgets.isFolderWidget(folder(FolderInfo.FLAG_FOLDER_WIDGET))).isTrue()
        assertThat(FolderWidgets.isFolderWidget(folder())).isFalse()
        assertThat(FolderWidgets.isFolderWidget(null)).isFalse()
        assertThat(FolderWidgets.isFolderWidget(WorkspaceItemInfo())).isFalse()
    }

    @Test fun wantsWidget_onlyOnDesktopWithFlagOrBigSpan() {
        val desk = Favorites.CONTAINER_DESKTOP
        assertThat(FolderWidgets.wantsWidget(desk, 0, 2, 2)).isTrue()
        assertThat(FolderWidgets.wantsWidget(desk, FolderInfo.FLAG_FOLDER_WIDGET, 1, 1)).isTrue()
        assertThat(FolderWidgets.wantsWidget(desk, 0, 1, 1)).isFalse()
        assertThat(FolderWidgets.wantsWidget(Favorites.CONTAINER_HOTSEAT, 0, 2, 2)).isFalse()
    }

    @Test fun loadedOptions_flagOnlyOnHome() {
        val flag = FolderInfo.FLAG_FOLDER_WIDGET
        val named = FolderInfo.FLAG_MANUAL_FOLDER_NAME
        val desk = Favorites.CONTAINER_DESKTOP
        assertThat(FolderWidgets.loadedOptions(desk, named, 2, 2)).isEqualTo(named or flag)
        assertThat(FolderWidgets.loadedOptions(desk, flag, 1, 1)).isEqualTo(flag)
        assertThat(FolderWidgets.loadedOptions(desk, named, 1, 1)).isEqualTo(named)
        assertThat(FolderWidgets.loadedOptions(Favorites.CONTAINER_HOTSEAT, named or flag, 1, 1)).isEqualTo(named)
    }

    @Test fun span_needsAtLeastTwoCells() {
        assertThat(FolderWidgets.isValidSpan(1, 1)).isFalse()
        assertThat(FolderWidgets.isValidSpan(2, 1)).isTrue()
        assertThat(FolderWidgets.isValidSpan(1, 2)).isTrue()
        assertThat(FolderWidgets.isValidSpan(0, 3)).isFalse()
    }

    @Test fun folderWidget_neverDissolves() {
        assertThat(FolderWidgets.canDissolve(folder(FolderInfo.FLAG_FOLDER_WIDGET))).isFalse()
        assertThat(FolderWidgets.canDissolve(folder())).isTrue()
    }

    @Test fun acceptsDrop_appsAndShortcutsOnly() {
        fun item(type: Int) = WorkspaceItemInfo().apply { itemType = type }
        assertThat(FolderWidgets.acceptsDrop(item(Favorites.ITEM_TYPE_APPLICATION))).isTrue()
        assertThat(FolderWidgets.acceptsDrop(item(Favorites.ITEM_TYPE_DEEP_SHORTCUT))).isTrue()
        assertThat(FolderWidgets.acceptsDrop(item(Favorites.ITEM_TYPE_SHORTCUT))).isTrue()
        assertThat(FolderWidgets.acceptsDrop(FolderInfo())).isFalse()
        assertThat(FolderWidgets.acceptsDrop(LauncherAppWidgetInfo())).isFalse()
    }

    @Test fun emptyFolderCleanup_skipsFolderWidgets() {
        val sql = FolderWidgets.emptyFoldersSelection()
        assertThat(sql).contains("${Favorites.ITEM_TYPE} = ${Favorites.ITEM_TYPE_FOLDER}")
        assertThat(sql).contains("NOT IN (SELECT ${Favorites.CONTAINER} FROM ${Favorites.TABLE_NAME})")
        assertThat(sql).contains("(${Favorites.OPTIONS} & ${FolderInfo.FLAG_FOLDER_WIDGET}) = 0")
    }

    @Test fun emptyFolderCleanup_selectsOnlyEmptyNormalFolders() {
        val db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE ${Favorites.TABLE_NAME} (${Favorites._ID} INTEGER PRIMARY KEY, " +
                "${Favorites.ITEM_TYPE} INTEGER, ${Favorites.CONTAINER} INTEGER, ${Favorites.OPTIONS} INTEGER)",
        )
        fun row(id: Int, type: Int, container: Int, options: Int = 0) = db.insert(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.ITEM_TYPE, type)
                put(Favorites.CONTAINER, container)
                put(Favorites.OPTIONS, options)
            },
        )
        row(1, Favorites.ITEM_TYPE_FOLDER, Favorites.CONTAINER_DESKTOP)
        row(2, Favorites.ITEM_TYPE_FOLDER, Favorites.CONTAINER_DESKTOP, FolderInfo.FLAG_FOLDER_WIDGET)
        row(3, Favorites.ITEM_TYPE_FOLDER, Favorites.CONTAINER_DESKTOP)
        row(4, Favorites.ITEM_TYPE_APPLICATION, 3)
        val selected = db.query(
            Favorites.TABLE_NAME,
            arrayOf(Favorites._ID),
            FolderWidgets.emptyFoldersSelection(),
            null,
            null,
            null,
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
        db.close()
        assertThat(selected).containsExactly(1)
    }
}
