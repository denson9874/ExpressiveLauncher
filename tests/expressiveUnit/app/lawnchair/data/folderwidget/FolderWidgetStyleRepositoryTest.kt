package app.lawnchair.data.folderwidget

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.data.AppDatabase
import app.lawnchair.folder.widget.FolderWidgetStyle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetStyleRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val repo = FolderWidgetStyleRepository(db)
    private val custom = FolderWidgetStyle(columns = 4, showLabels = false, backgroundOpacity = 0.3f)

    @After fun close() = db.close()

    @Test fun missingRow_isDefaultStyle() = runBlocking { assertThat(repo.observe(7).first()).isEqualTo(FolderWidgetStyle()) }

    @Test fun saveThenObserve_roundTrips() = runBlocking {
        repo.save(7, custom)
        assertThat(repo.observe(7).first()).isEqualTo(custom)
    }

    @Test fun delete_returnsToDefaults() = runBlocking {
        repo.save(7, custom); repo.delete(7)
        assertThat(repo.observe(7).first()).isEqualTo(FolderWidgetStyle())
    }

    @Test fun migration3to4_createsTheSameTableAsRoom() {
        fun normalized(sql: String) = sql.replace("IF NOT EXISTS ", "").replace(Regex("\\s+"), " ").trim()
        val roomSql = db.openHelper.readableDatabase
            .query("SELECT sql FROM sqlite_master WHERE name = 'FolderWidgetStyles'").use { it.moveToFirst(); it.getString(0) }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        AppDatabase.MIGRATION_3_4.migrate(helper.writableDatabase)
        val migratedSql = helper.readableDatabase
            .query("SELECT sql FROM sqlite_master WHERE name = 'FolderWidgetStyles'").use { it.moveToFirst(); it.getString(0) }
        assertThat(normalized(migratedSql)).isEqualTo(normalized(roomSql))
    }
}
