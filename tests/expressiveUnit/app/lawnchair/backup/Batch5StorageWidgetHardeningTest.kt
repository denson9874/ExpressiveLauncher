package app.lawnchair.backup

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.net.Uri
import android.util.DisplayMetrics
import android.util.SparseIntArray
import android.view.View
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.DeviceProfileOverrides
import app.lawnchair.LawnchairAppWidgetHostView
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.LawnchairProto.GridState
import app.lawnchair.icons.CustomIconImages
import com.android.launcher3.DeviceProfile
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherFiles
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.ShadowLauncherAppStateCompanion
import com.android.launcher3.model.DatabaseHelper
import com.android.launcher3.model.DbEntry
import com.android.launcher3.model.GridSizeMigrationLogic
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.util.BaseContext
import com.android.launcher3.views.BaseDragLayer
import com.android.launcher3.widget.BaseLauncherAppWidgetHostView
import com.android.launcher3.widget.LocalColorExtractor
import com.android.launcher3.widget.RoundedCornerEnforcement
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.xml.sax.SAXException

// SDK 34: avoids Android 17 framework jar shadowing com.android.launcher3.Flags in Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    shadows = [
        ShadowLauncherAppStateCompanion::class,
        Batch5StorageWidgetHardeningTest.ShadowLocalColorExtractor::class,
    ],
)
class Batch5StorageWidgetHardeningTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        RoundedCornerEnforcement.sRoundedCornerEnabled = true
        RoundedCornerEnforcement.sCustomCornerRadiusDp = -1f
        ShadowLauncherAppStateCompanion.idp = testIdp()
    }

    @After
    fun tearDown() {
        RoundedCornerEnforcement.sRoundedCornerEnabled = true
        RoundedCornerEnforcement.sCustomCornerRadiusDp = -1f
    }

    @Test
    fun truncatedZipRestore_failsWithoutDeletingLiveDatabasesOrCustomIcons() = runBlocking {
        withTimeout(10_000L) {
            val databases = context.getDatabasePath(LawnchairBackup.LAUNCHER_DB_FILE_NAME).parentFile!!
            File(databases, LawnchairBackup.LAUNCHER_DB_FILE_NAME).seed("live-launcher-db")
            File(databases, "launcher_6_5_5.db").seed("live-grid-db")
            File(databases, "preferences").seed("live-room-prefs")
            File(CustomIconImages.dir(context), "override_1.png").seed("live-custom-icon")
            prefsXml().seed("<map><string name=\"k\">live</string></map>")
            val before = snapshotLiveState()

            val fullArchive = writeBackupArchive(
                contents = LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS,
                entries = mapOf(
                    LawnchairBackup.LAUNCHER_DB_FILE_NAME to ByteArray(2048) { (it % 251).toByte() },
                    "${CustomIconImages.DIR_NAME}/override_2.png" to ByteArray(512) { 0x42 },
                ),
            )
            val truncated = File(context.cacheDir, "truncated.lawnchairbackup")
            val fullBytes = fullArchive.readBytes()
            truncated.writeBytes(fullBytes.copyOf((fullBytes.size * 55) / 100))

            val backup = LawnchairBackup(context, Uri.fromFile(truncated))
            backup.info = BackupInfo.newBuilder()
                .setContents(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
                .setGridState(GridState.newBuilder().setGridSize("5,6").setHotseatCount(5).build())
                .build()

            var thrown: Throwable? = null
            try {
                backup.restore(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
            } catch (t: Throwable) {
                thrown = t
            }

            assertThat(thrown).isInstanceOf(IOException::class.java)
            assertThat(snapshotLiveState()).isEqualTo(before)
        }
    }

    @Test
    fun corruptNonSqliteLauncherDbInZip_failsWithoutDeletingLiveState() = runBlocking {
        withTimeout(10_000L) {
            val databases = context.getDatabasePath(LawnchairBackup.LAUNCHER_DB_FILE_NAME).parentFile!!
            File(databases, "launcher_6_5_5.db").seed("live-grid-db-intact")
            File(CustomIconImages.dir(context), "keep.png").seed("keep-custom-icon")
            val before = snapshotLiveState()

            val badArchive = writeBackupArchive(
                contents = LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS,
                entries = mapOf(
                    LawnchairBackup.LAUNCHER_DB_FILE_NAME to "NOT_A_VALID_SQLITE_FILE".toByteArray(),
                ),
            )
            val backup = LawnchairBackup(context, Uri.fromFile(badArchive))
            backup.readInfoAndPreview()

            var thrown: Throwable? = null
            try {
                backup.restore(LawnchairBackup.INCLUDE_LAYOUT_AND_SETTINGS)
            } catch (t: Throwable) {
                thrown = t
            }

            assertThat(thrown).isInstanceOf(IOException::class.java)
            assertThat(snapshotLiveState()).isEqualTo(before)
        }
    }

    @Test
    fun renameRestoredDbFile_removesStaleSidecarsAndSwapsAtomically() {
        val dbDir = context.getDatabasePath("launcher_6_5_5.db").parentFile!!
        dbDir.mkdirs()
        val restored = context.getDatabasePath(LawnchairBackup.RESTORED_DB_FILE_NAME)
        val target = context.getDatabasePath("launcher_6_5_5.db")
        val targetWal = File(dbDir, "launcher_6_5_5.db-wal")
        val targetShm = File(dbDir, "launcher_6_5_5.db-shm")
        val targetJournal = File(dbDir, "launcher_6_5_5.db-journal")
        val restoredWal = File(dbDir, "${LawnchairBackup.RESTORED_DB_FILE_NAME}-wal")

        target.writeText("old-db")
        targetWal.writeText("stale-wal")
        targetShm.writeText("stale-shm")
        targetJournal.writeText("stale-journal")
        restored.writeText("new-restored-db")
        restoredWal.writeText("stale-restored-wal")

        LawnchairBackup.renameRestoredDbFile(context, "launcher_6_5_5.db")

        assertThat(target.readText()).isEqualTo("new-restored-db")
        assertThat(restored.exists()).isFalse()
        assertThat(targetWal.exists()).isFalse()
        assertThat(targetShm.exists()).isFalse()
        assertThat(targetJournal.exists()).isFalse()
        assertThat(restoredWal.exists()).isFalse()
    }

    @Test
    fun prepareRestoredWidgets_resetsForeignAppWidgetIdAndSetsRestoreFlags() {
        val dbFile = File(context.cacheDir, "widget_restore_test.db")
        dbFile.delete()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            Favorites.addTableToDb(db, 0L, false)
            db.insert(
                Favorites.TABLE_NAME,
                null,
                ContentValues().apply {
                    put(Favorites._ID, 1)
                    put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPWIDGET)
                    put(Favorites.APPWIDGET_ID, 4242)
                    put(Favorites.APPWIDGET_PROVIDER, "com.example.app/.WidgetProvider")
                    put(Favorites.RESTORED, 0)
                    put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                    put(Favorites.SCREEN, 0)
                    put(Favorites.CELLX, 0)
                    put(Favorites.CELLY, 1)
                    put(Favorites.SPANX, 2)
                    put(Favorites.SPANY, 2)
                },
            )
            db.insert(
                Favorites.TABLE_NAME,
                null,
                ContentValues().apply {
                    put(Favorites._ID, 2)
                    put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                    put(Favorites.APPWIDGET_ID, -1)
                    put(Favorites.RESTORED, 0)
                    put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                    put(Favorites.SCREEN, 0)
                    put(Favorites.CELLX, 2)
                    put(Favorites.CELLY, 1)
                    put(Favorites.SPANX, 1)
                    put(Favorites.SPANY, 1)
                },
            )
        }

        LawnchairBackup.prepareRestoredWidgets(dbFile)

        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT ${Favorites.APPWIDGET_ID}, ${Favorites.RESTORED} FROM ${Favorites.TABLE_NAME} WHERE ${Favorites._ID} = 1",
                null,
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(LauncherAppWidgetInfo.NO_ID)
                assertThat(cursor.getInt(1)).isEqualTo(
                    LauncherAppWidgetInfo.FLAG_ID_NOT_VALID or
                        LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY or
                        LauncherAppWidgetInfo.FLAG_UI_NOT_READY,
                )
            }
            db.rawQuery(
                "SELECT ${Favorites.RESTORED} FROM ${Favorites.TABLE_NAME} WHERE ${Favorites._ID} = 2",
                null,
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
        dbFile.delete()
    }

    @Test
    fun novaParser_rejectsXxeDtdPayload_andParsesCleanConfig() {
        val converter = NovaBackupConverter(context, Uri.EMPTY)
        val xxeXml = File(context.cacheDir, "nova_xxe.xml").apply {
            writeText(
                """
                <?xml version='1.0' encoding='utf-8'?>
                <!DOCTYPE map [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <map>
                    <string name="desktop_grid">&xxe;</string>
                    <int name="dock_grid_cols" value="5" />
                </map>
                """.trimIndent(),
            )
        }

        assertThrows(SAXException::class.java) {
            converter.parseNovaConfig(xxeXml)
        }

        val cleanXml = File(context.cacheDir, "nova_clean.xml").apply {
            writeText(
                """
                <?xml version='1.0' encoding='utf-8'?>
                <map>
                    <string name="desktop_grid">6x5</string>
                    <string name="theme_icon_pack">0:1:com.example.iconpack</string>
                    <int name="dock_grid_cols" value="4" />
                </map>
                """.trimIndent(),
            )
        }
        val parsed = converter.parseNovaConfig(cleanXml)
        assertThat(parsed.rows).isEqualTo(6)
        assertThat(parsed.columns).isEqualTo(5)
        assertThat(parsed.dockCols).isEqualTo(4)
        assertThat(parsed.iconPackPackage).isEqualTo("com.example.iconpack")
    }

    @Test
    fun solveHotseatPlacement_handlesShrinkOverflowWithoutBoundsCrash() {
        val logic = GridSizeMigrationLogic()
        val placed = listOf(
            dbEntry(id = 10, screenId = 0),
            dbEntry(id = 11, screenId = 2),
            dbEntry(id = 12, screenId = 4), // Out of bounds when hotseatSize shrinks to 4
            dbEntry(id = 13, screenId = 5), // Out of bounds when hotseatSize shrinks to 4
            dbEntry(id = 14, screenId = -1), // Defensive negative screenId
        )

        val solution = logic.solveHotseatPlacement(
            hotseatSize = 4,
            placedHotseatItems = placed,
            itemsToPlace = emptyList(),
        )

        // Slots 1 and 3 were free, so the first two overflow entries get relocated cleanly.
        assertThat(solution.map { it.id }).containsExactly(12, 13).inOrder()
        assertThat(solution.map { it.screenId }).containsExactly(1, 3).inOrder()
        assertThat(solution.all { it.screenId in 0 until 4 }).isTrue()

        // Zero hotseat size must also not crash.
        assertThat(logic.solveHotseatPlacement(0, placed, emptyList())).isEmpty()
    }

    @Test
    fun roundedCornerEnforcement_scalesCustomDpByDensityAndGuardsDetachedParents() {
        RoundedCornerEnforcement.sRoundedCornerEnabled = true
        RoundedCornerEnforcement.sCustomCornerRadiusDp = 18f

        val ctx2x = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                densityDpi = DisplayMetrics.DENSITY_XHIGH // 2.0x
            },
        )
        val ctx3x = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                densityDpi = DisplayMetrics.DENSITY_XXHIGH // 3.0x
            },
        )

        assertThat(RoundedCornerEnforcement.computeEnforcedRadius(ctx2x)).isWithin(0.01f).of(36f)
        assertThat(RoundedCornerEnforcement.computeEnforcedRadius(ctx3x)).isWithin(0.01f).of(54f)

        RoundedCornerEnforcement.sRoundedCornerEnabled = false
        assertThat(RoundedCornerEnforcement.computeEnforcedRadius(ctx3x)).isEqualTo(0f)

        // Detached background view (parent == null) must not throw NPE or ClassCastException.
        val host = FrameLayout(context)
        val detachedChild = View(context)
        val outRect = Rect(1, 2, 3, 4)
        RoundedCornerEnforcement.computeRoundedRectangle(host, detachedChild, outRect)
        assertThat(outRect.isEmpty).isTrue()

        // Non-View ViewParent (like ViewRootImpl) must not throw ClassCastException.
        val orphanView = View(context)
        val nonViewParent = Proxy.newProxyInstance(
            ViewParent::class.java.classLoader,
            arrayOf(ViewParent::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        } as ViewParent
        ReflectionHelpers.setField(orphanView, "mParent", nonViewParent)
        val outRect2 = Rect(1, 2, 3, 4)
        RoundedCornerEnforcement.computeRoundedRectangle(host, orphanView, outRect2)
        assertThat(outRect2.isEmpty).isTrue()
    }

    @Test
    fun baseLauncherAppWidgetHostView_switchToErrorViewHandlesNullInfoAndLiveCornerRefresh() {
        val widgetContext = WidgetHostTestContext(context)
        var lastRemoteViews: RemoteViews? = null
        val hostView = object : BaseLauncherAppWidgetHostView(widgetContext) {
            override fun shouldAllowDirectClick(): Boolean = false

            override fun onColorsChanged(extractedColors: SparseIntArray?) = Unit

            override fun updateAppWidget(remoteViews: RemoteViews?) {
                lastRemoteViews = remoteViews
                super.updateAppWidget(remoteViews)
            }
        }

        assertThat(hostView.appWidgetInfo).isNull()
        // Must not throw NullPointerException when getAppWidgetInfo() is null.
        hostView.switchToErrorView()
        assertThat(lastRemoteViews).isNotNull()
        assertThat(lastRemoteViews!!.layoutId).isEqualTo(0)
        assertThat(lastRemoteViews!!.`package`).isEqualTo(widgetContext.packageName)

        // LawnchairAppWidgetHostView must safely accept null AppWidgetProviderInfo.
        assertThat(LawnchairAppWidgetHostView.inflateCustomView(widgetContext, null, false)).isNull()

        // Live corner radius refresh must recompute from updated dp preference.
        RoundedCornerEnforcement.sRoundedCornerEnabled = true
        RoundedCornerEnforcement.sCustomCornerRadiusDp = 24f
        hostView.refreshEnforcedCornerRadius()
        val expectedPx = 24f * widgetContext.resources.displayMetrics.density
        assertThat(hostView.enforcedCornerRadius).isWithin(0.01f).of(expectedPx)
    }

    @Test
    fun databaseHelper_v30Migration_shiftsItemsOffRowZeroWithoutDeleting() {
        // Case A: screen 0 has room to shift down by 1 row.
        val sparseDbFile = File(context.cacheDir, "v30_sparse.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(sparseDbFile, null).use { db ->
            Favorites.addTableToDb(db, 0L, false)
            insertDesktopItem(db, id = 1, screen = 0, cellX = 0, cellY = 0)
            insertDesktopItem(db, id = 2, screen = 0, cellX = 1, cellY = 1)

            DatabaseHelper.shiftItemsOffFirstRowOnScreenZero(db, 5)

            val rows = queryDesktopPositions(db)
            assertThat(rows).hasSize(2)
            assertThat(rows[1]).isEqualTo(Triple(0, 0, 1))
            assertThat(rows[2]).isEqualTo(Triple(0, 1, 2))
        }
        sparseDbFile.delete()

        // Case B: screen 0 is full down to bottom row -> relocate row-0 item to screen 1.
        val fullDbFile = File(context.cacheDir, "v30_full.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(fullDbFile, null).use { db ->
            Favorites.addTableToDb(db, 0L, false)
            for (y in 0 until 5) {
                insertDesktopItem(db, id = y + 1, screen = 0, cellX = 0, cellY = y)
            }

            DatabaseHelper.shiftItemsOffFirstRowOnScreenZero(db, 5)

            val rows = queryDesktopPositions(db)
            assertThat(rows).hasSize(5)
            assertThat(rows[1]).isEqualTo(Triple(1, 0, 0))
            for (y in 1 until 5) {
                assertThat(rows[y + 1]).isEqualTo(Triple(0, 0, y))
            }
        }
        fullDbFile.delete()
    }

    @Test
    fun deviceProfileOverrides_applyUi_updatesFolderGridAcrossAllFourIndices() {
        val idp = testIdp().apply {
            numFolderRows = intArrayOf(3, 3, 3, 3)
            numFolderColumns = intArrayOf(3, 3, 3, 3)
            iconSize = floatArrayOf(60f, 60f, 60f, 60f)
            allAppsIconSize = floatArrayOf(60f, 60f, 60f, 60f)
            allAppsIconTextSize = floatArrayOf(12f, 12f, 12f, 12f)
            numShownHotseatIcons = 5
            numDatabaseHotseatIcons = 5
        }
        val options = DeviceProfileOverrides.Options(
            numAllAppsColumns = 5,
            numFolderRows = 5,
            numFolderColumns = 4,
            iconSizeFactor = 1f,
            allAppsIconSizeFactor = 1f,
            allAppsIconTextSizeFactor = 1f,
            enableTaskbarOnPhone = false,
        )

        options.applyUi(idp)

        assertThat(idp.numFolderRows.toList()).containsExactly(5, 5, 5, 5)
        assertThat(idp.numFolderColumns.toList()).containsExactly(4, 4, 4, 4)
    }

    private fun insertDesktopItem(db: SQLiteDatabase, id: Int, screen: Int, cellX: Int, cellY: Int) {
        db.insert(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, screen)
                put(Favorites.CELLX, cellX)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
            },
        )
    }

    private fun queryDesktopPositions(db: SQLiteDatabase): Map<Int, Triple<Int, Int, Int>> {
        val out = LinkedHashMap<Int, Triple<Int, Int, Int>>()
        db.rawQuery(
            "SELECT ${Favorites._ID}, ${Favorites.SCREEN}, ${Favorites.CELLX}, ${Favorites.CELLY} FROM ${Favorites.TABLE_NAME}",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out[cursor.getInt(0)] = Triple(cursor.getInt(1), cursor.getInt(2), cursor.getInt(3))
            }
        }
        return out
    }

    private fun dbEntry(id: Int, screenId: Int): DbEntry = DbEntry().apply {
        this.id = id
        this.screenId = screenId
        this.cellX = screenId
        this.cellY = 0
    }

    private fun prefsXml() = File(context.cacheDir.parent, "shared_prefs/${LauncherFiles.SHARED_PREFERENCES_KEY}.xml")

    private fun File.seed(text: String) {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun snapshotLiveState(): Map<String, List<Byte>> = listOf(
        context.getDatabasePath(LawnchairBackup.LAUNCHER_DB_FILE_NAME).parentFile!!,
        CustomIconImages.dir(context),
        prefsXml().parentFile!!,
    ).filter { it.exists() }
        .flatMap { dir -> dir.walkTopDown().filter { it.isFile }.toList() }
        .associate { it.path to it.readBytes().toList() }

    private fun writeBackupArchive(contents: Int, entries: Map<String, ByteArray>): File {
        val info = BackupInfo.newBuilder()
            .setContents(contents)
            .setGridState(GridState.newBuilder().setGridSize("5,6").setHotseatCount(5).build())
            .build()
        val archive = File(context.cacheDir, "batch5_${System.nanoTime()}.lawnchairbackup")
        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(LawnchairBackup.INFO_FILE_NAME))
            info.writeTo(out)
            out.closeEntry()
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return archive
    }

    private class WidgetHostTestContext(base: Context) : BaseContext(base, 0) {
        override fun getDeviceProfile(): DeviceProfile = throw UnsupportedOperationException()
        override fun getDragLayer(): BaseDragLayer<*> = throw UnsupportedOperationException()
    }

    @Implements(LocalColorExtractor::class)
    class ShadowLocalColorExtractor {
        companion object {
            @JvmStatic
            @Implementation
            fun newInstance(context: Context): LocalColorExtractor = LocalColorExtractor()
        }
    }

    private fun testIdp(): InvariantDeviceProfile {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return (unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, InvariantDeviceProfile::class.java) as InvariantDeviceProfile).apply {
            numColumns = 5
            numRows = 6
            numDatabaseHotseatIcons = 5
            dbFile = "launcher_6_5_5.db"
        }
    }
}
