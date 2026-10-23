package app.lawnchair.backup

import android.app.Application
import android.app.WallpaperManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.LawnchairProto.GridState
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.folderwidget.FolderWidgetStyleRepository
import app.lawnchair.folder.widget.FolderWidgetStyle
import app.lawnchair.util.MainThreadInitializedObject
import app.lawnchair.wallpaper.WallpaperManagerCompat
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherFiles
import com.android.launcher3.model.DeviceGridState
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWallpaperManager

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [LawnchairBackupTest.ShadowLauncherAppStateCompanion::class],
)
class LawnchairBackupTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val backupContext = BackupContext(context)
    private val custom = FolderWidgetStyle(columns = 4, showLabels = false, backgroundOpacity = 0.3f)

    @Test
    fun wallpaperOnlyRestore_leavesLauncherDbsAndPreferencesByteIdentical() = runBlocking {
        val databases = context.getDatabasePath(LawnchairBackup.LAUNCHER_DB_FILE_NAME).parentFile!!
        File(databases, LawnchairBackup.LAUNCHER_DB_FILE_NAME).seed("live launcher db")
        File(databases, "launcher_6_5_5.db").seed("live grid db")
        File(databases, PREFS_DB).seed("live room preferences db")
        prefsXml().seed(livePrefsXml)
        File(context.filesDir, "datastore/$PREFS_DATASTORE").seed("live datastore")
        val before = snapshot()

        // A full backup, restored with "Layout and settings" switched off.
        val archive = writeArchive(
            contents = LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS or LawnchairBackup.INCLUDE_WALLPAPER,
            entries = mapOf(
                LawnchairBackup.WALLPAPER_FILE_NAME to pngBytes(),
                LawnchairBackup.LAUNCHER_DB_FILE_NAME to "backup launcher db".toByteArray(),
                PREFS_XML to "backup prefs xml".toByteArray(),
                PREFS_DB to "backup room preferences db".toByteArray(),
                PREFS_DATASTORE to "backup datastore".toByteArray(),
            ),
        )
        val backup = LawnchairBackup(context, Uri.fromFile(archive))
        backup.readInfoAndPreview()
        backup.restore(LawnchairBackup.INCLUDE_WALLPAPER)

        assertThat(snapshot()).isEqualTo(before)
        val wallpaperManager = Shadow.extract<ShadowWallpaperManager>(WallpaperManager.getInstance(context))
        assertThat(wallpaperManager.getBitmap(WallpaperManager.FLAG_SYSTEM)).isNotNull()
    }

    @Test
    fun create_archivesAStyleSavedJustBeforeTheBackup() = runBlocking {
        val prefsDb = AppDatabase.INSTANCE.get(backupContext)
        try {
            FolderWidgetStyleRepository(prefsDb).save(FOLDER_ID, custom)
            // Room keeps the new row in preferences-wal, and the archive copies only the main file.
            val wal = File("${backupContext.getDatabasePath(PREFS_DB).path}-wal")
            assertThat(wal.length()).isGreaterThan(0L)

            val archive = File(context.cacheDir, "create.lawnchairbackup")
            LawnchairBackup.create(
                backupContext,
                LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS,
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888),
                Uri.fromFile(archive),
            )

            val archivedPrefsDb = extract(archive, PREFS_DB)
            SQLiteDatabase.openDatabase(archivedPrefsDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery(
                    "SELECT columns, showLabels FROM FolderWidgetStyles WHERE folderId = ?",
                    arrayOf(FOLDER_ID.toString()),
                ).use { row ->
                    assertThat(row.moveToFirst()).isTrue()
                    assertThat(row.getInt(0)).isEqualTo(4)
                    assertThat(row.getInt(1)).isEqualTo(0)
                }
            }
        } finally {
            prefsDb.close()
        }
    }

    private fun prefsXml() = File(context.cacheDir.parent, "shared_prefs/$PREFS_XML")

    private fun File.seed(text: String) {
        parentFile?.mkdirs()
        writeText(text)
    }

    /** Every file a layout restore replaces, by path, with its exact bytes. */
    private fun snapshot(): Map<String, List<Byte>> = listOf(
        context.getDatabasePath(LawnchairBackup.LAUNCHER_DB_FILE_NAME).parentFile!!,
        prefsXml().parentFile!!,
        File(context.filesDir, "datastore"),
    ).flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
        .associate { it.path to it.readBytes().toList() }

    private fun writeArchive(contents: Int, entries: Map<String, ByteArray>): File {
        val info = BackupInfo.newBuilder()
            .setContents(contents)
            .setGridState(GridState.newBuilder().setGridSize("4,5").setHotseatCount(4).build())
            .build()
        val archive = File(context.cacheDir, "restore.lawnchairbackup")
        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
            info.writeTo(out)
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
            }
        }
        return archive
    }

    private fun extract(archive: File, name: String): File {
        val extracted = File(context.cacheDir, "archived-$name")
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) extracted.outputStream().use { zip.copyTo(it) }
            }
        }
        assertThat(extracted.exists()).isTrue()
        return extracted
    }

    private fun pngBytes(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /**
     * Gives create() the real Room DB and a wallpaper helper that reports no colors. A real
     * WallpaperManagerCompat starts WallpaperService from the full launcher graph.
     */
    private class BackupContext(base: Context) :
        MainThreadInitializedObject.SandboxContext(base, AppDatabase.INSTANCE, WallpaperManagerCompat.INSTANCE) {

        override fun <T> createObject(obj: MainThreadInitializedObject<T>): T {
            if (obj !== WallpaperManagerCompat.INSTANCE) return super.createObject(obj)
            @Suppress("UNCHECKED_CAST")
            return allocate(Class.forName("app.lawnchair.wallpaper.WallpaperManagerCompatVS")) as T
        }
    }

    // Kotlin callers reach LauncherAppState.getIDP through its companion. The real IDP runs the
    // grid init, which needs the full launcher graph; the backup only reads the grid and DB name.
    @Implements(LauncherAppState.Companion::class)
    class ShadowLauncherAppStateCompanion {
        @Implementation
        fun getIDP(context: Context): InvariantDeviceProfile = gridProfile
    }

    companion object {
        private const val FOLDER_ID = 7
        private const val PREFS_DB = "preferences"
        private const val PREFS_DATASTORE = "preferences.preferences_pb"
        private const val PREFS_XML = "${LauncherFiles.SHARED_PREFERENCES_KEY}.xml"

        private val livePrefsXml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="${DeviceGridState.KEY_WORKSPACE_SIZE}">5,6</string>
                <int name="${DeviceGridState.KEY_HOTSEAT_COUNT}" value="5" />
            </map>
        """.trimIndent()

        private val gridProfile by lazy {
            (allocate(InvariantDeviceProfile::class.java) as InvariantDeviceProfile).apply {
                numColumns = 5
                numRows = 6
                numDatabaseHotseatIcons = 5
                dbFile = "launcher_6_5_5.db"
            }
        }

        /** Creates [type] without running its constructor, as InvariantDeviceProfileLocaleTest does. */
        private fun allocate(type: Class<*>): Any {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                .get(null)
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)!!
        }
    }
}
