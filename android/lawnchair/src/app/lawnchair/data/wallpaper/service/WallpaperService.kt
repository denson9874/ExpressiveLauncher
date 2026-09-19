package app.lawnchair.data.wallpaper.service

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.room.withTransaction
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.wallpaper.Wallpaper
import app.lawnchair.util.bitmapToByteArray
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.SafeCloseable
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@LauncherAppSingleton
class WallpaperService @Inject constructor(
    @ApplicationContext private val context: Context,
) : SafeCloseable {

    private val database = AppDatabase.Companion.INSTANCE.get(context)
    val dao = database.wallpaperDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationMutex = Mutex()

    private val mutableTopWallpapers = MutableStateFlow<List<Wallpaper>>(emptyList())
    val topWallpapers: StateFlow<List<Wallpaper>> = mutableTopWallpapers.asStateFlow()

    init {
        // The launcher asks for this list while opening a popup. Observing Room once here avoids
        // the previous runBlocking database query that froze the UI thread on cold storage.
        scope.launch {
            dao.observeTopWallpapers().collect { mutableTopWallpapers.value = it }
        }
    }

    suspend fun saveWallpaper(wallpaperManager: WallpaperManager) {
        try {
            // WallpaperManager may return null while the system wallpaper service is changing
            // state. Treat that short-lived condition as "nothing to save" instead of crashing.
            val wallpaperDrawable = getCurrentWallpaperIfAuthorized(wallpaperManager) ?: return
            val currentBitmap = wallpaperDrawable.toBitmap()

            val byteArray = bitmapToByteArray(currentBitmap)

            saveWallpaper(byteArray)
        } catch (e: Exception) {
            Log.e("WallpaperChange", "Error detecting wallpaper change: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentWallpaperIfAuthorized(wallpaperManager: WallpaperManager) =
        if (canCaptureCurrentWallpaper()) {
            // The call is guarded by the exact platform grants. Keep the suppression local because
            // lint cannot propagate the permission contract through Environment's app-op check.
            try {
                wallpaperManager.drawable
            } catch (exception: SecurityException) {
                Log.w("WallpaperService", "Wallpaper access changed during capture", exception)
                null
            }
        } else {
            null
        }

    private fun calculateChecksum(imageData: ByteArray): String {
        return MessageDigest.getInstance("MD5")
            .digest(imageData)
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun saveWallpaper(imageData: ByteArray) {
        mutationMutex.withLock {
            val checksum = calculateChecksum(imageData)
            val existingWallpapers = dao.getTopWallpapers()
            if (existingWallpapers.any { it.checksum == checksum }) {
                Log.d("WallpaperService", "Wallpaper already exists with checksum: $checksum")
                return@withLock
            }

            val imagePath = saveImageToAppStorage(imageData)
            var deletedImagePath: String? = null
            database.withTransaction {
                if (existingWallpapers.size >= MAX_WALLPAPER_HISTORY_SIZE) {
                    existingWallpapers.minByOrNull { it.timestamp }?.let { oldest ->
                        dao.deleteWallpaper(oldest.id)
                        deletedImagePath = oldest.imagePath
                    }
                }
                dao.insert(
                    Wallpaper(
                        imagePath = imagePath,
                        rank = 0,
                        timestamp = System.currentTimeMillis(),
                        checksum = checksum,
                    ),
                )
                normalizeRanks()
            }
            deletedImagePath?.let(::deleteWallpaperFile)
        }
    }

    suspend fun updateWallpaperRank(selectedWallpaper: Wallpaper) {
        mutationMutex.withLock {
            database.withTransaction {
                dao.updateWallpaper(
                    selectedWallpaper.id,
                    rank = 0,
                    timestamp = System.currentTimeMillis(),
                )
                normalizeRanks()
            }
        }
    }

    private suspend fun normalizeRanks() {
        dao.getTopWallpapers().forEachIndexed { index, wallpaper ->
            dao.updateRank(wallpaper.id, index)
        }
    }

    fun getTopWallpapers(): List<Wallpaper> = topWallpapers.value

    /**
     * Reading the static wallpaper bitmap is restricted on Android 14+. A Play build must not ask
     * for broad storage access merely to populate a convenience carousel, so callers only attempt
     * a capture when the device has already granted one of the platform-authorized access paths.
     */
    fun canCaptureCurrentWallpaper(): Boolean {
        val hasInternalWallpaperPermission = context.checkSelfPermission(
            READ_WALLPAPER_INTERNAL_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        return hasInternalWallpaperPermission || Environment.isExternalStorageManager()
    }

    private fun deleteWallpaperFile(imagePath: String) {
        val file = File(imagePath)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun saveImageToAppStorage(imageData: ByteArray): String {
        val storageDir = File(context.filesDir, "wallpapers")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        val imageHash = imageData.hashCode().toString()
        val imageFile = File(storageDir, "wallpaper_$imageHash.jpg")

        if (!imageFile.exists()) {
            FileOutputStream(imageFile).use { fos ->
                fos.write(imageData)
            }
        }

        return imageFile.absolutePath
    }

    override fun close() {
        scope.cancel()
        mutableTopWallpapers.value = emptyList()
    }
    companion object {
        private const val MAX_WALLPAPER_HISTORY_SIZE = 4
        private const val READ_WALLPAPER_INTERNAL_PERMISSION =
            "android.permission.READ_WALLPAPER_INTERNAL"

        @JvmField
        val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getWallpaperService)
    }
}
