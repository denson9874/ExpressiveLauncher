package app.lawnchair.backup

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairProto.BackupInfo
import app.lawnchair.data.AppDatabase
import app.lawnchair.icons.CustomIconImages
import app.lawnchair.util.hasFlag
import app.lawnchair.util.scaleDownTo
import app.lawnchair.util.scaleDownToDisplaySize
import app.lawnchair.wallpaper.WallpaperColorsCompat
import app.lawnchair.wallpaper.WallpaperManagerCompat
import com.android.launcher3.BuildConfig
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherFiles
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.R
import com.android.launcher3.model.DeviceGridState
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.provider.RestoreDbTask
import com.google.protobuf.Timestamp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException

class LawnchairBackup(
    private val context: Context,
    private val uri: Uri,
) {
    lateinit var info: BackupInfo
    var screenshot: Bitmap? = null
    var wallpaper: Bitmap? = null

    suspend fun readInfoAndPreview() {
        var tmpScreenshot: Bitmap? = null
        var tmpWallpaper: Bitmap? = null
        readZip(
            mapOf(
                INFO_FILE_NAME to { info = BackupInfo.newBuilder().mergeFrom(it).build() },
                SCREENSHOT_FILE_NAME to { tmpScreenshot = BitmapFactory.decodeStream(it) },
                WALLPAPER_FILE_NAME to { tmpWallpaper = BitmapFactory.decodeStream(it) },
            ),
        )
        if (!::info.isInitialized) {
            throw IOException("Backup archive is missing $INFO_FILE_NAME")
        }
        val size = max(info.previewWidth, info.previewHeight).coerceAtMost(4000)
        screenshot = tmpScreenshot?.scaleDownTo(size)
        wallpaper = tmpWallpaper?.scaleDownToDisplaySize(context)
    }

    suspend fun restore(selectedContents: Int) {
        withContext(Dispatchers.IO) {
            val contents = selectedContents and info.contents
            if (!contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                // Everything below replaces the home layout: it deletes databases/ (launcher*.db and
                // the Room DB with custom icons, drawer folders and folder-widget styles) and
                // overwrites the grid size. A restore without the layout, such as wallpaper only,
                // must leave both alone.
                val handlers = mutableMapOf<String, suspend (InputStream) -> Unit>()
                if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                    handlers[WALLPAPER_FILE_NAME] = { input ->
                        val bitmap = BitmapFactory.decodeStream(input)
                            ?: throw IOException("Backup contains an unreadable wallpaper image")
                        WallpaperManager.getInstance(context).setBitmap(bitmap)
                    }
                }
                readZip(handlers)
                return@withContext
            }

            val stagingDir = File(context.cacheDir, "restore_staging_${UUID.randomUUID()}")
            if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
                throw IOException("Unable to create restore staging directory")
            }

            try {
                // Phase 1: Copy and validate the entire ZIP archive in a staging directory BEFORE
                // deleting or modifying any live databases, preferences, or custom icons.
                val stagedZip = File(stagingDir, "backup.zip")
                copyUriToFile(uri, stagedZip)

                // ZipFile validates the End-Of-Central-Directory record and central directory,
                // catching truncated ZIPs even when cut cleanly between entries.
                ZipFile(stagedZip).use { zf ->
                    if (zf.size() == 0) {
                        throw IOException("Backup archive is empty")
                    }
                }

                val stagedLauncherDb = File(stagingDir, LAUNCHER_DB_FILE_NAME)
                val stagedPrefsXml = File(stagingDir, PREFS_FILE_NAME)
                val stagedPrefsDb = File(stagingDir, PREFS_DB_FILE_NAME)
                val stagedDataStore = File(stagingDir, PREFS_DATASTORE_FILE_NAME)
                val stagedWallpaper = File(stagingDir, WALLPAPER_FILE_NAME)
                val stagedCustomIconsDir = File(stagingDir, CustomIconImages.DIR_NAME)

                val knownTargets = mutableMapOf(
                    LAUNCHER_DB_FILE_NAME to stagedLauncherDb,
                    PREFS_FILE_NAME to stagedPrefsXml,
                    PREFS_DB_FILE_NAME to stagedPrefsDb,
                    PREFS_DATASTORE_FILE_NAME to stagedDataStore,
                )
                if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                    knownTargets[WALLPAPER_FILE_NAME] = stagedWallpaper
                }

                extractZipToStaging(stagedZip, knownTargets, stagedCustomIconsDir)

                // Phase 2: Validate integrity of extracted SQLite DBs, preferences XML, and wallpaper
                // before mutating any live state.
                if (!stagedLauncherDb.exists()) {
                    throw IOException("Backup archive is missing $LAUNCHER_DB_FILE_NAME")
                }
                validateSqliteFile(stagedLauncherDb, requireFavoritesTable = true)

                if (stagedPrefsDb.exists()) {
                    validateSqliteFile(stagedPrefsDb, requireFavoritesTable = false)
                }

                val restoredPrefsMap = if (stagedPrefsXml.exists()) {
                    parseSharedPrefsXml(stagedPrefsXml)
                } else {
                    null
                }

                val decodedWallpaper = if (contents.hasFlag(INCLUDE_WALLPAPER) && stagedWallpaper.exists()) {
                    BitmapFactory.decodeFile(stagedWallpaper.absolutePath)
                        ?: throw IOException("Backup contains a corrupt wallpaper image")
                } else {
                    null
                }

                // Clear foreign appWidgetIds and mark restored widget rows for rebind/placeholder
                // recovery while the DB is still in staging.
                prepareRestoredWidgets(stagedLauncherDb)

                // Phase 3: Close open DB handles cleanly, remove stale WAL/SHM/journal sidecars,
                // and commit staged files into live locations.
                closeLiveAppDatabaseIfOpen()

                val databasesDir = context.getDatabasePath(LAUNCHER_DB_FILE_NAME).parentFile
                databasesDir?.deleteRecursively()
                databasesDir?.mkdirs()

                val restoredDbTarget = context.getDatabasePath(RESTORED_DB_FILE_NAME)
                deleteSqliteSidecars(restoredDbTarget)
                stagedLauncherDb.copyTo(restoredDbTarget, overwrite = true)

                if (stagedPrefsDb.exists()) {
                    val prefsDbTarget = prefsDbFile(context)
                    prefsDbTarget.parentFile?.mkdirs()
                    deleteSqliteSidecars(prefsDbTarget)
                    stagedPrefsDb.copyTo(prefsDbTarget, overwrite = true)
                }

                if (stagedDataStore.exists()) {
                    val dataStoreTarget = prefsDataStoreFile(context)
                    dataStoreTarget.parentFile?.mkdirs()
                    val tmpDataStore = File(dataStoreTarget.parentFile, "${dataStoreTarget.name}.tmp")
                    stagedDataStore.copyTo(tmpDataStore, overwrite = true)
                    if (!tmpDataStore.renameTo(dataStoreTarget)) {
                        tmpDataStore.copyTo(dataStoreTarget, overwrite = true)
                        tmpDataStore.delete()
                    }
                }

                // The restored icon overrides name their own images; older backups have none.
                val liveCustomIconsDir = CustomIconImages.dir(context)
                liveCustomIconsDir.deleteRecursively()
                if (stagedCustomIconsDir.isDirectory) {
                    liveCustomIconsDir.mkdirs()
                    stagedCustomIconsDir.listFiles()?.filter { it.isFile }?.forEach { iconFile ->
                        iconFile.copyTo(File(liveCustomIconsDir, iconFile.name), overwrite = true)
                    }
                }

                // Commit SharedPreferences through the Editor API so in-memory SharedPreferences
                // and on-disk XML stay synchronized instead of racing raw file writes.
                if (restoredPrefsMap != null) {
                    applySharedPrefsMap(context, restoredPrefsMap)
                }
                DeviceGridState(info.gridState).writeToPrefs(context, true)

                val dbController = ModelDbController(context)
                try {
                    val restoredOk = RestoreDbTask.performRestore(context, dbController)
                    if (!restoredOk) {
                        throw IOException("RestoreDbTask failed to sanitize restored launcher database")
                    }
                    dbController.clearFlagEmptyDbCreated()
                } finally {
                    dbController.close()
                }

                clearEmptyDbFlags(context)

                if (decodedWallpaper != null) {
                    WallpaperManager.getInstance(context).setBitmap(decodedWallpaper)
                }
            } finally {
                stagingDir.deleteRecursively()
            }
        }
    }

    private fun closeLiveAppDatabaseIfOpen() {
        if (context.applicationContext !is LawnchairApp) return
        try {
            val prefsDb = AppDatabase.INSTANCE.get(context)
            if (prefsDb.isOpen) {
                runCatching { checkpointPrefsDb(prefsDb) }
                prefsDb.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to checkpoint/close live AppDatabase before restore", t)
        }
    }

    private fun copyUriToFile(sourceUri: Uri, destFile: File) {
        val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            ?: throw IOException("Unable to open backup URI")
        pfd.use {
            FileInputStream(it.fileDescriptor).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun extractZipToStaging(
        zipFile: File,
        knownTargets: Map<String, File>,
        stagedCustomIconsDir: File,
    ) {
        val iconsCanonicalPrefix = stagedCustomIconsDir.canonicalPath + File.separator
        FileInputStream(zipFile).buffered().use { rawIn ->
            ZipInputStream(rawIn).use { zipIs ->
                while (true) {
                    val entry = zipIs.nextEntry ?: break
                    try {
                        if (!entry.isDirectory) {
                            val target = knownTargets[entry.name]
                            if (target != null) {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out -> zipIs.copyTo(out) }
                            } else {
                                extractCustomIconToStaging(
                                    entry.name,
                                    zipIs,
                                    stagedCustomIconsDir,
                                    iconsCanonicalPrefix,
                                )
                            }
                        }
                    } finally {
                        zipIs.closeEntry()
                    }
                }
            }
        }
    }

    private fun extractCustomIconToStaging(
        entryName: String,
        input: InputStream,
        stagedCustomIconsDir: File,
        iconsCanonicalPrefix: String,
    ) {
        val name = entryName.removePrefix("${CustomIconImages.DIR_NAME}/")
        if (name == entryName || name.isEmpty() || name.contains('/') || name.contains('\\') || name.startsWith(".")) {
            return
        }
        stagedCustomIconsDir.mkdirs()
        val outFile = File(stagedCustomIconsDir, name)
        if (!outFile.canonicalPath.startsWith(iconsCanonicalPrefix)) {
            throw IOException("Zip entry outside custom icons staging dir: $entryName")
        }
        FileOutputStream(outFile).use { out -> input.copyTo(out) }
    }

    private suspend fun readZip(
        handlers: Map<String, suspend (InputStream) -> Unit>,
        restoreCustomIcons: Boolean = false,
    ) {
        withContext(Dispatchers.IO) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Unable to open backup URI")
            pfd.use {
                FileInputStream(it.fileDescriptor).use { inStream ->
                    ZipInputStream(inStream).use { zipIs ->
                        var entry: ZipEntry?
                        while (true) {
                            entry = zipIs.nextEntry
                            if (entry == null) break
                            try {
                                handlers[entry.name]?.invoke(zipIs)
                                if (restoreCustomIcons) restoreCustomIcon(entry.name, zipIs)
                            } finally {
                                zipIs.closeEntry()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Copies a `custom_icons/<file>` entry back; any other name, or a path that leaves the folder, is ignored. */
    private fun restoreCustomIcon(entryName: String, input: InputStream) {
        val name = entryName.removePrefix("${CustomIconImages.DIR_NAME}/")
        if (name == entryName || name.isEmpty() || name.contains('/') || name.startsWith(".")) return
        val dir = CustomIconImages.dir(context).apply { mkdirs() }
        File(dir, name).outputStream().use { input.copyTo(it) }
    }

    companion object {
        private const val TAG = "LawnchairBackup"
        private const val BACKUP_VERSION = 1
        private const val PREFS_FILE_NAME = "${LauncherFiles.SHARED_PREFERENCES_KEY}.xml"
        private const val PREFS_DB_FILE_NAME = "preferences"
        private const val PREFS_DATASTORE_FILE_NAME = "preferences.preferences_pb"
        private const val EMPTY_DATABASE_CREATED_PREFIX = "EMPTY_DATABASE_CREATED"
        private val SQLITE_HEADER_BYTES = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val SQLITE_SIDECAR_SUFFIXES = arrayOf("-wal", "-shm", "-journal")

        const val INFO_FILE_NAME = "info.pb"
        const val WALLPAPER_FILE_NAME = "wallpaper.png"
        const val SCREENSHOT_FILE_NAME = "screenshot.png"
        const val LAUNCHER_DB_FILE_NAME = "launcher.db"
        const val RESTORED_DB_FILE_NAME = "restored.db"

        const val INCLUDE_LAYOUT_AND_SETTINGS = 1 shl 0
        const val INCLUDE_WALLPAPER = 1 shl 1

        const val MIME_TYPE = "application/zip"
        val EXTRA_MIME_TYPES = arrayOf(MIME_TYPE, "application/x-zip", "application/octet-stream")

        val contentOptions = listOf(
            INCLUDE_LAYOUT_AND_SETTINGS to R.string.backup_content_layout_and_settings,
            INCLUDE_WALLPAPER to R.string.backup_content_wallpaper,
        )

        fun generateBackupFileName(): String {
            val productName = if (BuildConfig.STANDARD_HOME_ONLY) "ExpressiveLauncherL3" else "Lawnchair"
            val fileName = "${productName}_Backup ${SimpleDateFormat.getDateTimeInstance().format(Date())}"
            return "$fileName.lawnchairbackup"
        }

        fun getFiles(context: Context, forRestore: Boolean): Map<String, File> {
            return mapOf(
                LAUNCHER_DB_FILE_NAME to launcherDbFile(context, forRestore),
                PREFS_FILE_NAME to prefsFile(context),
                PREFS_DB_FILE_NAME to prefsDbFile(context),
                PREFS_DATASTORE_FILE_NAME to prefsDataStoreFile(context),
            )
        }

        @JvmStatic
        fun deleteSqliteSidecars(dbFile: File) {
            val parent = dbFile.parentFile ?: return
            for (suffix in SQLITE_SIDECAR_SUFFIXES) {
                val sidecar = File(parent, "${dbFile.name}$suffix")
                if (sidecar.exists()) {
                    sidecar.delete()
                }
            }
        }

        @JvmStatic
        fun renameRestoredDbFile(context: Context, dbName: String) {
            val restoredDbFile = context.getDatabasePath(RESTORED_DB_FILE_NAME)
            if (!restoredDbFile.exists()) return
            val dbFile = context.getDatabasePath(dbName)
            dbFile.parentFile?.mkdirs()
            deleteSqliteSidecars(restoredDbFile)
            deleteSqliteSidecars(dbFile)
            if (dbFile.exists()) {
                dbFile.delete()
            }
            if (!restoredDbFile.renameTo(dbFile)) {
                restoredDbFile.copyTo(dbFile, overwrite = true)
                restoredDbFile.delete()
            }
        }

        @JvmStatic
        internal fun validateSqliteFile(dbFile: File, requireFavoritesTable: Boolean) {
            if (!dbFile.isFile || dbFile.length() < SQLITE_HEADER_BYTES.size) {
                throw IOException("Invalid SQLite file (too small): ${dbFile.name}")
            }
            val header = ByteArray(SQLITE_HEADER_BYTES.size)
            val bytesRead = dbFile.inputStream().use { it.read(header) }
            if (bytesRead != SQLITE_HEADER_BYTES.size || !header.contentEquals(SQLITE_HEADER_BYTES)) {
                throw IOException("Invalid SQLite header in ${dbFile.name}")
            }
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { db ->
                db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    if (!cursor.moveToFirst() || !cursor.getString(0).equals("ok", ignoreCase = true)) {
                        throw IOException("SQLite integrity check failed for ${dbFile.name}")
                    }
                }
                if (requireFavoritesTable) {
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                        arrayOf(Favorites.TABLE_NAME),
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) {
                            throw IOException("Restored database is missing ${Favorites.TABLE_NAME} table")
                        }
                    }
                }
            }
            deleteSqliteSidecars(dbFile)
        }

        @VisibleForTesting
        internal fun prepareRestoredWidgets(dbFile: File) {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { db ->
                val columns = mutableSetOf<String>()
                db.rawQuery("PRAGMA table_info(${Favorites.TABLE_NAME})", null).use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIdx >= 0) columns.add(cursor.getString(nameIdx))
                    }
                }
                if (Favorites.ITEM_TYPE !in columns ||
                    Favorites.APPWIDGET_ID !in columns ||
                    Favorites.RESTORED !in columns
                ) {
                    return@use
                }
                db.beginTransaction()
                try {
                    val values = ContentValues().apply {
                        put(Favorites.APPWIDGET_ID, LauncherAppWidgetInfo.NO_ID)
                        put(
                            Favorites.RESTORED,
                            LauncherAppWidgetInfo.FLAG_ID_NOT_VALID or
                                LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY or
                                LauncherAppWidgetInfo.FLAG_UI_NOT_READY,
                        )
                    }
                    db.update(
                        Favorites.TABLE_NAME,
                        values,
                        "${Favorites.ITEM_TYPE} = ?",
                        arrayOf(Favorites.ITEM_TYPE_APPWIDGET.toString()),
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            deleteSqliteSidecars(dbFile)
        }

        private fun clearEmptyDbFlags(context: Context) {
            val launcherPrefs = LauncherPrefs.get(context)
            val prefDbFile = launcherPrefs.get(LauncherPrefs.DB_FILE)
            if (prefDbFile.isNotEmpty()) {
                launcherPrefs.removeSync(ModelDbController.getEmptyDbCreatedKey(prefDbFile))
            }
            val idpDbFile = runCatching { LauncherAppState.getIDP(context).dbFile }.getOrNull()
            if (!idpDbFile.isNullOrEmpty()) {
                launcherPrefs.removeSync(ModelDbController.getEmptyDbCreatedKey(idpDbFile))
            }
            launcherPrefs.removeSync(ModelDbController.getEmptyDbCreatedKey(LauncherFiles.LAUNCHER_DB))
        }

        private fun parseSharedPrefsXml(xmlFile: File): Map<String, Any> {
            val rawXml = xmlFile.readText(Charsets.UTF_8)
            if (rawXml.contains("<!DOCTYPE", ignoreCase = true) ||
                rawXml.contains("<!ENTITY", ignoreCase = true)
            ) {
                throw SAXException("DOCTYPE/ENTITY declarations are disallowed in backup preferences")
            }
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeatureSafely(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
                setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
                setFeatureSafely("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw SAXException("External entities are disallowed") }
            }
            val doc = xmlFile.inputStream().use { builder.parse(it) }
            if (doc.doctype != null) {
                throw SAXException("DOCTYPE declarations are disallowed in backup preferences")
            }
            val root = doc.documentElement
                ?: throw IOException("Backup preferences XML has no root element")
            val result = LinkedHashMap<String, Any>()
            val children = root.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType != Node.ELEMENT_NODE) continue
                val el = node as Element
                val name = el.getAttribute("name")
                if (name.isEmpty() || name.startsWith(EMPTY_DATABASE_CREATED_PREFIX)) continue
                when (el.tagName) {
                    "string" -> result[name] = el.textContent ?: ""
                    "int" -> el.getAttribute("value").toIntOrNull()?.let { result[name] = it }
                    "long" -> el.getAttribute("value").toLongOrNull()?.let { result[name] = it }
                    "float" -> el.getAttribute("value").toFloatOrNull()?.let { result[name] = it }
                    "boolean" -> result[name] = el.getAttribute("value").equals("true", ignoreCase = true)
                    "set" -> {
                        val setValues = LinkedHashSet<String>()
                        val items = el.childNodes
                        for (j in 0 until items.length) {
                            val itemNode = items.item(j)
                            if (itemNode.nodeType == Node.ELEMENT_NODE &&
                                (itemNode as Element).tagName == "string"
                            ) {
                                setValues.add(itemNode.textContent ?: "")
                            }
                        }
                        result[name] = setValues
                    }
                }
            }
            return result
        }

        private fun DocumentBuilderFactory.setFeatureSafely(feature: String, value: Boolean) {
            try {
                setFeature(feature, value)
            } catch (_: ParserConfigurationException) {
                // Android's Harmony DocumentBuilderFactoryImpl may not support Xerces feature URIs;
                // doctype pre-checks and rejecting EntityResolver still enforce XXE protection.
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun applySharedPrefsMap(context: Context, prefsMap: Map<String, Any>) {
            val editor = LauncherPrefs.getPrefs(context).edit().clear()
            for ((key, value) in prefsMap) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value as Set<String>)
                }
            }
            editor.commit()
        }

        @SuppressLint("MissingPermission")
        suspend fun create(context: Context, contents: Int, screenshotBitmap: Bitmap, fileUri: Uri) {
            val prefsDb = AppDatabase.INSTANCE.get(context)
            withContext(Dispatchers.IO) { checkpointPrefsDb(prefsDb) }

            val idp = LauncherAppState.getIDP(context)
            val createdAt = Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
            val colorHints = WallpaperManagerCompat.INSTANCE.get(context).wallpaperColors?.colorHints ?: 0
            val wallpaperSupportsDarkText = (colorHints and WallpaperColorsCompat.HINT_SUPPORTS_DARK_TEXT) != 0
            val info = BackupInfo.newBuilder()
                .setLawnchairVersion(BuildConfig.VERSION_CODE)
                .setBackupVersion(BACKUP_VERSION)
                .setCreatedAt(createdAt)
                .setContents(contents)
                .setGridState(DeviceGridState(idp).toProtoMessage())
                .setPreviewWidth(screenshotBitmap.width)
                .setPreviewHeight(screenshotBitmap.height)
                .setPreviewDarkText(wallpaperSupportsDarkText)
                .build()

            val pfd = context.contentResolver.openFileDescriptor(fileUri, "w")
                ?: throw IOException("Unable to open backup destination URI")
            withContext(Dispatchers.IO) {
                pfd.use {
                    ZipOutputStream(FileOutputStream(pfd.fileDescriptor).buffered()).use { out ->
                        out.putNextEntry(ZipEntry(INFO_FILE_NAME))
                        info.writeTo(out)
                        out.closeEntry()

                        if (contents.hasFlag(INCLUDE_WALLPAPER)) {
                            val wallpaperManager = WallpaperManager.getInstance(context)
                            val wallpaperBitmap = wallpaperManager.drawable?.toBitmap()
                            if (wallpaperBitmap != null) {
                                out.putNextEntry(ZipEntry(WALLPAPER_FILE_NAME))
                                wallpaperBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                out.closeEntry()
                            }
                        }
                        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                            out.putNextEntry(ZipEntry(SCREENSHOT_FILE_NAME))
                            screenshotBitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                            out.closeEntry()
                        }

                        getFiles(context, forRestore = false).entries.forEach {
                            if (!it.value.exists()) return@forEach
                            out.putNextEntry(ZipEntry(it.key))
                            it.value.inputStream().use { input -> input.copyTo(out) }
                            out.closeEntry()
                        }
                        if (contents.hasFlag(INCLUDE_LAYOUT_AND_SETTINGS)) {
                            CustomIconImages.dir(context).listFiles()?.filter { it.isFile }?.forEach {
                                out.putNextEntry(ZipEntry("${CustomIconImages.DIR_NAME}/${it.name}"))
                                it.inputStream().use { input -> input.copyTo(out) }
                                out.closeEntry()
                            }
                        }
                    }
                }
            }
        }

        /**
         * Room runs the preferences DB in WAL mode, so recent icon overrides, drawer folders and
         * folder-widget styles stay in preferences-wal until a checkpoint, and the backup copies
         * only the main file. The result row is (busy, WAL frames, checkpointed frames): busy alone
         * only means a reader kept the WAL from being reset, but frames left behind would be lost.
         */
        private fun checkpointPrefsDb(db: AppDatabase) {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { result ->
                result.moveToFirst()
                val busy = result.getInt(0) != 0
                val walFrames = result.getInt(1)
                val checkpointed = result.getInt(2)
                // A busy checkpoint that could not even read the WAL reports -1 for both counts.
                if (busy && (walFrames < 0 || walFrames != checkpointed)) {
                    throw IOException("Recent preferences are still in the WAL and would be missing from the backup")
                }
            }
        }

        private fun launcherDbFile(context: Context, forRestore: Boolean): File {
            val dbName = if (forRestore) RESTORED_DB_FILE_NAME else LauncherAppState.getIDP(context).dbFile
            return context.getDatabasePath(dbName)
        }

        private fun prefsFile(context: Context): File {
            val dir = context.cacheDir.parent
            return File(dir, "shared_prefs/$PREFS_FILE_NAME")
        }

        private fun prefsDbFile(context: Context): File {
            return context.getDatabasePath(PREFS_DB_FILE_NAME)
        }

        private fun prefsDataStoreFile(context: Context): File {
            return File(context.filesDir, "datastore/${PREFS_DATASTORE_FILE_NAME}")
        }
    }
}
