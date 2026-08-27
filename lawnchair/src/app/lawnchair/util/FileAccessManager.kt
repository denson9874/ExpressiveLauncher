package app.lawnchair.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import app.lawnchair.ui.util.canRequestBroadVisualMediaAccess
import app.lawnchair.ui.util.canRequestManageAllFilesAccess
import com.android.launcher3.util.MainThreadInitializedObject
import com.android.launcher3.util.SafeCloseable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface FileAccessState {
    /** Unrestricted shared-storage access, or complete access to a media collection. */
    data object Full : FileAccessState

    /** User-selected media or a user-selected Storage Access Framework folder. */
    data object Partial : FileAccessState

    data object Denied : FileAccessState
}

internal data class VisualMediaCollectionAccess(
    val canReadImages: Boolean,
    val canReadVideos: Boolean,
) {
    val hasAnyAccess: Boolean get() = canReadImages || canReadVideos
}

/**
 * Resolves the effective access available to the general file-search provider.
 *
 * Android's all-files app-op is only usable when the matching manifest permission is present.
 * Play-distributed builds intentionally omit it and use a persisted, user-selected folder instead.
 */
internal fun resolveAllFilesAccessState(
    sdkInt: Int,
    canRequestManageAllFiles: Boolean,
    isExternalStorageManager: Boolean,
    hasSelectedTreeAccess: Boolean,
    hasLegacyReadPermission: Boolean,
): FileAccessState {
    if (sdkInt >= Build.VERSION_CODES.R) {
        return when {
            canRequestManageAllFiles && isExternalStorageManager -> FileAccessState.Full
            hasSelectedTreeAccess -> FileAccessState.Partial
            else -> FileAccessState.Denied
        }
    }

    return if (hasLegacyReadPermission) FileAccessState.Full else FileAccessState.Denied
}

/**
 * Resolves access to the visual-media collections without consulting Android or build globals.
 *
 * Play-distributed channels deliberately omit all three visual-media permissions. For eligible
 * sideload channels, access to both collections is full, while either one collection or Android's
 * user-selected-media grant is partial.
 */
internal fun resolveVisualMediaAccessState(
    canRequestBroadVisualMedia: Boolean,
    hasAllFilesAccess: Boolean,
    hasReadImagesPermission: Boolean,
    hasReadVideoPermission: Boolean,
    hasSelectedVisualMediaPermission: Boolean,
): FileAccessState {
    if (hasAllFilesAccess) return FileAccessState.Full
    if (!canRequestBroadVisualMedia) return FileAccessState.Denied

    return when {
        hasReadImagesPermission && hasReadVideoPermission -> FileAccessState.Full

        hasReadImagesPermission ||
            hasReadVideoPermission ||
            hasSelectedVisualMediaPermission -> FileAccessState.Partial

        else -> FileAccessState.Denied
    }
}

/** Resolves the exact MediaStore collections that may be queried without a permission failure. */
internal fun resolveVisualMediaCollectionAccess(
    canRequestBroadVisualMedia: Boolean,
    hasAllFilesAccess: Boolean,
    hasReadImagesPermission: Boolean,
    hasReadVideoPermission: Boolean,
    hasSelectedVisualMediaPermission: Boolean,
): VisualMediaCollectionAccess {
    if (hasAllFilesAccess) return VisualMediaCollectionAccess(true, true)
    if (!canRequestBroadVisualMedia) return VisualMediaCollectionAccess(false, false)

    // Android's selected-media grant can contain both photos and videos, so both MediaStore
    // collections are safe to query; Android filters each query to the user's selected items.
    if (hasSelectedVisualMediaPermission) return VisualMediaCollectionAccess(true, true)

    return VisualMediaCollectionAccess(
        canReadImages = hasReadImagesPermission,
        canReadVideos = hasReadVideoPermission,
    )
}

/** Prevents an obsolete in-flight search from clearing a folder selected more recently. */
internal fun shouldInvalidateSelectedTreeAccess(
    currentUri: Uri?,
    failedUri: Uri,
): Boolean = currentUri == failedUri

/**
 * Tracks Android media permissions, all-files special access, and a persisted SAF folder grant.
 *
 * The Expressive/Play channels never request `MANAGE_EXTERNAL_STORAGE`: a launcher does not gain
 * Play-policy eligibility merely by offering file search. Those channels expose a folder picker,
 * retain the returned URI grant, and report [FileAccessState.Partial] for general file access.
 */
class FileAccessManager private constructor(private val context: Context) : SafeCloseable {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val selectedTreeMutationLock = Any()

    val selectedTreeUri: StateFlow<Uri?>
        field = MutableStateFlow(getPersistedSelectedTreeUri())

    val selectedTreeLabel: StateFlow<String?>
        field = MutableStateFlow(selectedTreeUri.value?.let(::treeLabel))

    /**
     * [FileAccessState.Full] means the special all-files app-op is active.
     * [FileAccessState.Partial] means a user-selected SAF folder is available.
     */
    val allFilesAccessState: StateFlow<FileAccessState>
        field = MutableStateFlow(getCurrentAllFilesAccessState())

    val visualMediaAccessState: StateFlow<FileAccessState>
        field = MutableStateFlow(getCurrentVisualMediaState())

    internal val visualMediaCollectionAccess: StateFlow<VisualMediaCollectionAccess>
        field = MutableStateFlow(getCurrentVisualMediaCollectionAccess())

    val audioAccessState: StateFlow<FileAccessState>
        field = MutableStateFlow(getCurrentAudioState())

    val wallpaperAccessState: StateFlow<FileAccessState>
        field = MutableStateFlow(getCurrentWallpaperAccessState())

    val hasAnyPermission: StateFlow<Boolean>
        field = MutableStateFlow(hasAnyPermissions())

    /** Re-checks grants after returning from a runtime permission dialog or system picker. */
    fun refresh() {
        synchronized(selectedTreeMutationLock) {
            refreshLocked()
        }
    }

    private fun refreshLocked() {
        val treeUri = getPersistedSelectedTreeUri()
        selectedTreeUri.value = treeUri
        selectedTreeLabel.value = treeUri?.let(::treeLabel)

        // General access must be updated first because media states can inherit full access.
        allFilesAccessState.value = getCurrentAllFilesAccessState()
        visualMediaAccessState.value = getCurrentVisualMediaState()
        visualMediaCollectionAccess.value = getCurrentVisualMediaCollectionAccess()
        audioAccessState.value = getCurrentAudioState()
        wallpaperAccessState.value = getCurrentWallpaperAccessState()
        hasAnyPermission.value = hasAnyPermissions()
    }

    /**
     * Persists a read grant returned by `ACTION_OPEN_DOCUMENT_TREE`.
     *
     * A failed or non-tree URI is rejected without replacing the last working folder.
     */
    fun grantSelectedTreeAccess(uri: Uri): Boolean = synchronized(selectedTreeMutationLock) {
        if (!DocumentsContract.isTreeUri(uri)) return@synchronized false

        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (exception: SecurityException) {
            Log.w(TAG, "The document provider did not grant persistent read access", exception)
            return@synchronized false
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "The selected URI cannot be persisted", exception)
            return@synchronized false
        }

        val previousUri = selectedTreeUri.value
        preferences.edit().putString(KEY_SELECTED_TREE_URI, uri.toString()).apply()

        if (previousUri != null && previousUri != uri) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    previousUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { exception ->
                Log.d(TAG, "The previous folder grant was already unavailable", exception)
            }
        }

        refreshLocked()
        selectedTreeUri.value == uri
    }

    fun clearSelectedTreeAccess() {
        synchronized(selectedTreeMutationLock) {
            clearSelectedTreeAccessLocked(selectedTreeUri.value)
        }
    }

    /** Clears a revoked tree only if it is still the tree used by the failing search. */
    fun invalidateSelectedTreeAccess(failedUri: Uri): Boolean = synchronized(selectedTreeMutationLock) {
        if (!shouldInvalidateSelectedTreeAccess(selectedTreeUri.value, failedUri)) {
            return@synchronized false
        }

        clearSelectedTreeAccessLocked(failedUri)
        true
    }

    private fun clearSelectedTreeAccessLocked(uri: Uri?) {
        uri?.let {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        preferences.edit().remove(KEY_SELECTED_TREE_URI).apply()
        refreshLocked()
    }

    private fun hasAnyPermissions(): Boolean {
        return visualMediaAccessState.value != FileAccessState.Denied ||
            audioAccessState.value != FileAccessState.Denied ||
            allFilesAccessState.value != FileAccessState.Denied
    }

    private fun getCurrentWallpaperAccessState(): FileAccessState {
        // WallpaperManager's direct drawable API is broader than a selected folder or MediaStore
        // collection. Do not claim that a SAF/media grant authorizes it.
        return if (getCurrentAllFilesAccessState() == FileAccessState.Full) {
            FileAccessState.Full
        } else {
            FileAccessState.Denied
        }
    }

    private fun getCurrentVisualMediaState(): FileAccessState {
        return resolveVisualMediaAccessState(
            canRequestBroadVisualMedia = canRequestBroadVisualMediaAccess(),
            hasAllFilesAccess = getCurrentAllFilesAccessState() == FileAccessState.Full,
            hasReadImagesPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkPermission(Manifest.permission.READ_MEDIA_IMAGES),
            hasReadVideoPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkPermission(Manifest.permission.READ_MEDIA_VIDEO),
            hasSelectedVisualMediaPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                checkPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        )
    }

    private fun getCurrentVisualMediaCollectionAccess(): VisualMediaCollectionAccess {
        return resolveVisualMediaCollectionAccess(
            canRequestBroadVisualMedia = canRequestBroadVisualMediaAccess(),
            hasAllFilesAccess = getCurrentAllFilesAccessState() == FileAccessState.Full,
            hasReadImagesPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkPermission(Manifest.permission.READ_MEDIA_IMAGES),
            hasReadVideoPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkPermission(Manifest.permission.READ_MEDIA_VIDEO),
            hasSelectedVisualMediaPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                checkPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        )
    }

    private fun getCurrentAudioState(): FileAccessState {
        if (getCurrentAllFilesAccessState() == FileAccessState.Full) {
            return FileAccessState.Full
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkPermission(Manifest.permission.READ_MEDIA_AUDIO)
        ) {
            return FileAccessState.Full
        }

        return FileAccessState.Denied
    }

    private fun getCurrentAllFilesAccessState(): FileAccessState {
        return resolveAllFilesAccessState(
            sdkInt = Build.VERSION.SDK_INT,
            canRequestManageAllFiles = canRequestManageAllFilesAccess(),
            isExternalStorageManager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager(),
            hasSelectedTreeAccess = selectedTreeUri.value != null,
            hasLegacyReadPermission = checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE),
        )
    }

    private fun getPersistedSelectedTreeUri(): Uri? {
        val storedValue = preferences.getString(KEY_SELECTED_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(storedValue) }.getOrNull()
            ?: return clearInvalidTreePreference()
        val hasReadGrant = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
        return if (hasReadGrant) uri else clearInvalidTreePreference()
    }

    private fun clearInvalidTreePreference(): Uri? {
        preferences.edit().remove(KEY_SELECTED_TREE_URI).apply()
        return null
    }

    private fun treeLabel(uri: Uri): String? {
        return runCatching {
            DocumentsContract.getTreeDocumentId(uri)
                .substringAfterLast(':')
                .trimEnd('/')
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun close() {}

    companion object {
        private const val TAG = "FileAccessManager"
        private const val PREFERENCES_NAME = "file_access"
        private const val KEY_SELECTED_TREE_URI = "selected_tree_uri"

        @JvmField
        val INSTANCE = MainThreadInitializedObject(::FileAccessManager)

        @JvmStatic
        fun getInstance(context: Context) = INSTANCE.get(context)!!
    }
}
