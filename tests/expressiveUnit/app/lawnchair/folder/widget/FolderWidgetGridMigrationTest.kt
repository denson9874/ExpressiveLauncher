package app.lawnchair.folder.widget

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.GridSizeMigrationDBController
import com.android.launcher3.model.data.FolderInfo
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Grid-size migration reads every Home row first; it must never drop an empty Folder widget. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetGridMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = SQLiteDatabase.create(null).apply { Favorites.addTableToDb(this, 0L, false) }

    @After fun close() = db.close()

    private fun folder(id: Int, cellY: Int, span: Int, options: Int) {
        db.insert(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, span)
                put(Favorites.SPANY, span)
                put(Favorites.OPTIONS, options)
                put(Favorites.TITLE, "Folder $id")
            },
        )
    }

    private fun rowIds() = db.query(Favorites.TABLE_NAME, arrayOf(Favorites._ID), null, null, null, null, null)
        .use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }

    @Test fun emptyFolderWidget_survivesTheMigrationReader() {
        folder(id = 1, cellY = 1, span = 1, options = 0)
        folder(id = 2, cellY = 2, span = 2, options = FolderInfo.FLAG_FOLDER_WIDGET)

        val entries = GridSizeMigrationDBController.readAllEntries(db, Favorites.TABLE_NAME, context)

        assertThat(entries.map { it.id }).containsExactly(2)
        assertThat(rowIds()).containsExactly(2)
    }
}
