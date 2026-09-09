package app.lawnchair.search.algorithms.engine.provider

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.search.algorithms.data.FileInfo
import app.lawnchair.search.algorithms.data.FolderInfo
import app.lawnchair.search.algorithms.data.IFileInfo
import app.lawnchair.search.algorithms.engine.SearchProvider
import app.lawnchair.search.algorithms.engine.SearchResult
import app.lawnchair.util.FileAccessManager
import app.lawnchair.util.FileAccessState
import app.lawnchair.util.VisualMediaCollectionAccess
import app.lawnchair.util.audioFileTypes
import app.lawnchair.util.exists
import app.lawnchair.util.imageFileTypes
import app.lawnchair.util.isDirectory
import app.lawnchair.util.isHidden
import app.lawnchair.util.isRegularFile
import app.lawnchair.util.mimeType2Extension
import app.lawnchair.util.videoFileTypes
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath

private data class CrossSourceFileIdentity(
    val kind: String,
    val normalizedName: String,
    val size: Long,
    val modifiedSecond: Long,
)

private fun IFileInfo.crossSourceIdentity(): CrossSourceFileIdentity {
    val kind = when (this) {
        is FileInfo -> mimeType.orEmpty()
        is FolderInfo -> DocumentsContract.Document.MIME_TYPE_DIR
    }
    return CrossSourceFileIdentity(
        kind = kind,
        normalizedName = name.lowercase(Locale.ROOT),
        size = size,
        modifiedSecond = dateModified / 1_000,
    )
}

/** Fairly interleaves providers and removes only duplicates that cross URI/path boundaries. */
internal fun mergeFileResultSources(
    sources: List<List<IFileInfo>>,
    maxResults: Int,
): List<IFileInfo> {
    if (maxResults <= 0) return emptyList()

    val iterators = sources.filter { it.isNotEmpty() }.map { it.iterator() }
    val interleaved = buildList {
        while (iterators.any { it.hasNext() }) {
            iterators.forEach { iterator ->
                if (iterator.hasNext()) add(iterator.next())
            }
        }
    }

    val seenPaths = mutableSetOf<String>()
    val contentIdentities = mutableSetOf<CrossSourceFileIdentity>()
    val filesystemIdentities = mutableSetOf<CrossSourceFileIdentity>()
    return buildList {
        interleaved.forEach { item ->
            if (!seenPaths.add(item.path)) return@forEach

            val identity = item.crossSourceIdentity()
            val isContentResult = item.contentUri != null
            val oppositeSourceIdentities = if (isContentResult) {
                filesystemIdentities
            } else {
                contentIdentities
            }
            if (identity in oppositeSourceIdentities) return@forEach

            add(item)
            if (isContentResult) contentIdentities += identity else filesystemIdentities += identity
        }
    }.take(maxResults)
}

object FileSearchProvider : SearchProvider {
    override val id = "Files"

    override fun search(
        context: Context,
        query: String,
    ): Flow<List<SearchResult>> = flow {
        val prefs = PreferenceManager.getInstance(context)

        val searchAllFiles = prefs.searchResultAllFiles.get()
        val searchAudio = prefs.searchResultAudio.get()
        val searchVisualMedia = prefs.searchResultVisualMedia.get()

        val fileSearchEnabled = prefs.searchResultFilesToggle.get()
        val anyProviderEnabled = searchAllFiles || searchAudio || searchVisualMedia
        if (query.isBlank() || !fileSearchEnabled || !anyProviderEnabled) {
            // do nothing if query is empty, file search is disabled, or none of the providers are enabled
            emit(emptyList())
            return@flow
        }

        val prefs2 = PreferenceManager2.getInstance(context)
        val maxResults = prefs2.maxFileResultCount.firstCached()

        // check for permissions:
        val fileAccessManager = FileAccessManager.getInstance(context)
        fileAccessManager.refresh()
        val allFilesAccessState = fileAccessManager.allFilesAccessState.value
        val selectedTreeUri = fileAccessManager.selectedTreeUri.value
        val audioGranted = fileAccessManager.audioAccessState.value == FileAccessState.Full
        val visualMediaAccess = fileAccessManager.visualMediaCollectionAccess.value

        val results = coroutineScope {
            val allFilesDeferred = when {
                !searchAllFiles -> null

                allFilesAccessState == FileAccessState.Full -> {
                    async { searchAllFiles(context, query, maxResults).first() }
                }

                allFilesAccessState == FileAccessState.Partial && selectedTreeUri != null -> {
                    async { searchSelectedTree(context, selectedTreeUri, query, maxResults).first() }
                }

                else -> null
            }

            // A full all-files query already includes media. A selected tree is only partial
            // access, so combine it with every separately enabled MediaStore collection.
            val suppressMediaStoreSearches =
                allFilesAccessState == FileAccessState.Full && allFilesDeferred != null
            val visualMediaDeferred = if (
                !suppressMediaStoreSearches &&
                visualMediaAccess.hasAnyAccess &&
                searchVisualMedia
            ) {
                async { searchVisualMedia(context, query, maxResults, visualMediaAccess).first() }
            } else {
                null
            }

            val audioDeferred = if (!suppressMediaStoreSearches && audioGranted && searchAudio) {
                async { searchAudio(context, query, maxResults).first() }
            } else {
                null
            }

            mergeFileResultSources(
                sources = listOfNotNull(
                    allFilesDeferred?.await(),
                    visualMediaDeferred?.await(),
                    audioDeferred?.await(),
                ),
                maxResults = maxResults,
            )
        }

        emit(results.map { SearchResult.File(it) })
    }

    private val IMAGE_MIME_TYPES = imageFileTypes.values.toTypedArray()
    private val VIDEO_MIME_TYPES = videoFileTypes.values.toTypedArray()
    private val AUDIO_MIME_TYPES = audioFileTypes.values.toTypedArray()

    /**
     * Searches for photos and videos based on visual media access.
     */
    private fun searchVisualMedia(
        context: Context,
        query: String,
        maxResults: Int,
        access: VisualMediaCollectionAccess,
    ): Flow<List<IFileInfo>> = flow {
        val results = mutableListOf<IFileInfo>()

        if (access.canReadImages) {
            results.addAll(
                queryMediaStoreByType(
                    context = context,
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    keyword = query,
                    mimeTypes = IMAGE_MIME_TYPES,
                    maxResult = maxResults,
                ).toList(),
            )
        }

        if (access.canReadVideos) {
            results.addAll(
                queryMediaStoreByType(
                    context = context,
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    keyword = query,
                    mimeTypes = VIDEO_MIME_TYPES,
                    maxResult = maxResults,
                ).toList(),
            )
        }

        val uniqueResults = results.distinctBy { it.path }.take(maxResults)

        emit(uniqueResults)
    }

    /**
     * Searches for audio files based on audio access.
     */
    private fun searchAudio(
        context: Context,
        query: String,
        maxResults: Int,
    ): Flow<List<IFileInfo>> = flow {
        val audioInfoList = queryMediaStoreByType(
            context = context,
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            keyword = query,
            mimeTypes = AUDIO_MIME_TYPES,
            maxResult = maxResults,
        ).toList()

        emit(audioInfoList.take(maxResults))
    }

    /**
     * Searches for all file types that MediaStore can index,
     * relying on "All Files Access" (MANAGE_EXTERNAL_STORAGE or legacy READ_EXTERNAL_STORAGE).
     * This will also find folders.
     */
    private fun searchAllFiles(
        context: Context,
        query: String,
        maxResults: Int,
    ): Flow<List<IFileInfo>> = flow {
        val fileInfoList = queryGeneralFilesInMediaStore(
            context = context,
            keyword = query,
            maxResult = maxResults,
        ).toList()

        emit(fileInfoList)
    }

    /** Searches only the directory tree explicitly granted through the Storage Access Framework. */
    private fun searchSelectedTree(
        context: Context,
        treeUri: Uri,
        query: String,
        maxResults: Int,
    ): Flow<List<IFileInfo>> = flow {
        emit(querySelectedTree(context, treeUri, query, maxResults).toList())
    }

    private val commonProjection = arrayOf(
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.TITLE,
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DOCUMENT_ID,
    )

    private val documentProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    /**
     * Queries the MediaStore for specific media types (Images, Video, Audio) based on a keyword.
     * This function is optimized for finding files and does not return folders.
     *
     * @param context The application context.
     * @param uri The MediaStore URI to query (e.g., `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`).
     * @param keyword The search term to match against file display names or titles.
     * @param maxResult The maximum number of results to return.
     * @param mimeTypes An optional array of MIME types to filter the results.
     *                  If null or empty, no MIME type filtering is applied.
     * @return A [Sequence] of [FileInfo] objects matching the query criteria.
     *         The sequence is processed lazily.
     */
    private suspend fun queryMediaStoreByType(
        context: Context,
        uri: Uri,
        keyword: String,
        maxResult: Int,
        mimeTypes: Array<String>? = null,
    ): Sequence<FileInfo> = withContext(Dispatchers.IO) {
        val selectionClauses = mutableListOf<String>()
        val selectionArgsList = mutableListOf<String>()

        selectionClauses.add("(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.TITLE} LIKE ?)")
        selectionArgsList.add("%$keyword%")
        selectionArgsList.add("%$keyword%")

        if (!mimeTypes.isNullOrEmpty()) {
            selectionClauses.add("${MediaStore.MediaColumns.MIME_TYPE} IN (${mimeTypes.joinToString { "?" }})")
            selectionArgsList.addAll(mimeTypes)
        }

        val selection = selectionClauses.joinToString(separator = " AND ")
        val selectionArgs = selectionArgsList.toTypedArray()

        contentResolverQuery(
            context = context,
            uri = uri,
            projection = commonProjection,
            selection = selection,
            selectionArgs = selectionArgs,
            maxResult = maxResult,
        ) { cursor ->
            createFileInfoFromCursor(cursor)
        }
    }

    /**
     * Queries MediaStore.Files.getContentUri("external") which can return any indexed file or folder.
     */
    private suspend fun queryGeneralFilesInMediaStore(
        context: Context,
        keyword: String,
        maxResult: Int,
        path: String = "",
        mimeTypes: Array<String>? = null,
    ): Sequence<IFileInfo> = withContext(Dispatchers.IO) {
        val selectionClauses = mutableListOf<String>()
        val selectionArgsList = mutableListOf<String>()

        // Path filtering (if provided)
        if (path.isNotBlank()) {
            selectionClauses.add("${MediaStore.MediaColumns.DATA} LIKE ?")
            selectionArgsList.add("$path%")
        }

        selectionClauses.add("(${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)")
        selectionArgsList.add("%$keyword%")
        selectionArgsList.add("%$keyword%")

        if (!mimeTypes.isNullOrEmpty()) {
            selectionClauses.add("${MediaStore.MediaColumns.MIME_TYPE} IN (${mimeTypes.joinToString { "?" }})")
            selectionArgsList.addAll(mimeTypes)
        }

        val selection = selectionClauses.joinToString(separator = " AND ")
        val selectionArgs = selectionArgsList.toTypedArray()

        contentResolverQuery(
            context,
            MediaStore.Files.getContentUri("external"),
            commonProjection,
            selection,
            selectionArgs,
            maxResult = maxResult,
        ) { cursor ->
            // Determine if it's a file or folder.
            // A simple check could be if MIME_TYPE is null for folders, or check file system.
            // However, it's more robust to check the file system attributes if possible,
            // or rely on MediaStore.Files.FileColumns.MEDIA_TYPE (equals MEDIA_TYPE_NONE for folders)
            // For now, let's use a filesystem check on the path.
            val filePath = cursor.getString(cursor.getColumnIndexOrThrow(commonProjection[0])).toPath()
            if (filePath.isDirectory() && !filePath.isRegularFile()) {
                createFolderInfoFromCursor(cursor)
            } else {
                createFileInfoFromCursor(cursor)
            }
        }
    }

    /**
     * Breadth-first traversal of a persisted SAF tree. The scan is performed on IO, stops as soon
     * as enough matches are found, and has a hard document cap so a provider cannot make a single
     * launcher search walk an unbounded tree.
     */
    internal suspend fun querySelectedTree(
        context: Context,
        treeUri: Uri,
        keyword: String,
        maxResult: Int,
    ): Sequence<IFileInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<IFileInfo>()
        val pendingDirectories = ArrayDeque<String>()
        val visitedDirectoryIds = mutableSetOf<String>()

        try {
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            pendingDirectories.add(rootDocumentId)
            visitedDirectoryIds.add(rootDocumentId)
            var scannedDocuments = 0

            while (
                pendingDirectories.isNotEmpty() &&
                results.size < maxResult &&
                scannedDocuments < MAX_SELECTED_TREE_DOCUMENTS_SCANNED
            ) {
                currentCoroutineContext().ensureActive()
                val parentDocumentId = pendingDirectories.removeFirst()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    parentDocumentId,
                )

                queryDocumentChildren(context, childrenUri)?.use { cursor ->
                    val documentIdColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    )
                    val displayNameColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    )
                    val mimeTypeColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    )
                    val sizeColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_SIZE,
                    )
                    val modifiedColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    )

                    while (
                        cursor.moveToNext() &&
                        results.size < maxResult &&
                        scannedDocuments < MAX_SELECTED_TREE_DOCUMENTS_SCANNED
                    ) {
                        currentCoroutineContext().ensureActive()
                        scannedDocuments++

                        val documentId = cursor.getString(documentIdColumn) ?: continue
                        val name = cursor.getString(displayNameColumn) ?: continue
                        val mimeType = cursor.getString(mimeTypeColumn)
                        val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

                        if (name.startsWith('.')) continue
                        if (isDirectory && visitedDirectoryIds.add(documentId)) {
                            pendingDirectories.add(documentId)
                        }
                        if (!name.contains(keyword, ignoreCase = true)) continue

                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            documentId,
                        )
                        val uriString = documentUri.toString()
                        val size = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn)
                        val dateModified = if (cursor.isNull(modifiedColumn)) {
                            0L
                        } else {
                            cursor.getLong(modifiedColumn)
                        }

                        results += if (isDirectory) {
                            FolderInfo(
                                path = uriString,
                                name = name,
                                size = size,
                                dateModified = dateModified,
                                contentUri = uriString,
                            )
                        } else {
                            FileInfo(
                                fileId = documentId,
                                path = uriString,
                                name = name,
                                size = size,
                                dateModified = dateModified,
                                mimeType = mimeType,
                                contentUri = uriString,
                            )
                        }
                    }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SecurityException) {
            Log.w(TAG, "Selected document tree permission was revoked: $treeUri", exception)
            results.clear()
            FileAccessManager.getInstance(context).invalidateSelectedTreeAccess(treeUri)
        } catch (exception: Exception) {
            Log.e(TAG, "Error searching selected document tree $treeUri", exception)
        }

        results.asSequence()
    }

    /** Requeries boundedly when a cloud DocumentsProvider reports that children are still loading. */
    private suspend fun queryDocumentChildren(
        context: Context,
        childrenUri: Uri,
    ): Cursor? {
        repeat(MAX_DOCUMENT_PROVIDER_LOADING_ATTEMPTS) { attempt ->
            val cursor = queryDocumentChildrenOnce(context, childrenUri)
            val isLoading = cursor?.extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
            if (!isLoading || attempt == MAX_DOCUMENT_PROVIDER_LOADING_ATTEMPTS - 1) {
                return cursor
            }

            cursor.close()
            awaitDocumentProviderChange(context, childrenUri)
        }
        return null
    }

    /** Runs one DocumentsProvider query that is cancelled with its calling coroutine. */
    private suspend fun queryDocumentChildrenOnce(
        context: Context,
        childrenUri: Uri,
    ): Cursor? = suspendCancellableCoroutine { continuation ->
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation { cancellationSignal.cancel() }

        try {
            val cursor = context.contentResolver.query(
                childrenUri,
                documentProjection,
                null,
                null,
                null,
                cancellationSignal,
            )
            if (continuation.isActive) {
                continuation.resume(cursor) { _, cancelledCursor, _ -> cancelledCursor?.close() }
            } else {
                cursor?.close()
            }
        } catch (exception: Exception) {
            if (continuation.isActive) {
                continuation.resumeWithException(exception)
            }
        }
    }

    private suspend fun awaitDocumentProviderChange(
        context: Context,
        childrenUri: Uri,
    ) {
        withTimeoutOrNull(DOCUMENT_PROVIDER_LOADING_WAIT_MS) {
            suspendCancellableCoroutine { continuation ->
                val resolver = context.contentResolver
                val unregistered = AtomicBoolean(false)
                lateinit var observer: ContentObserver
                val unregister = {
                    if (unregistered.compareAndSet(false, true)) {
                        runCatching { resolver.unregisterContentObserver(observer) }
                    }
                    Unit
                }

                observer = object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        unregister()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                continuation.invokeOnCancellation { unregister() }

                try {
                    resolver.registerContentObserver(childrenUri, true, observer)
                } catch (exception: Exception) {
                    unregister()
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            }
        }
    }

    /**
     * Core ContentResolver query logic.
     */
    private suspend inline fun <T : IFileInfo> contentResolverQuery(
        context: Context,
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        maxResult: Int,
        crossinline factory: (Cursor) -> T?,
    ): Sequence<T> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    var count = 0
                    buildList {
                        while (cursor.moveToNext() && count < maxResult) {
                            factory(cursor)?.let { item ->
                                add(item)
                                count++
                            }
                        }
                    }.asSequence()
                } ?: emptySequence()
        } catch (e: Exception) {
            // Log error (e.g., SecurityException if permissions are somehow still an issue)
            Log.e("FileSearchProvider", "Error querying MediaStore at $uri: ${e.message}")
            emptySequence()
        }
    }

    private fun createFileInfoFromCursor(cursor: Cursor): FileInfo? {
        val pathString = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)) ?: return null
        val path = pathString.toPath()

        if (!path.exists || !path.isRegularFile() || path.isHidden) return null

        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            ?: path.name
        if (name.isBlank()) return null

        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
        val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)) * 1000
        val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
        val fileId = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))

        val finalName = if (mimeType != null && !name.contains(".") && name.isNotEmpty()) {
            mimeType.mimeType2Extension()?.let { "$name.$it" } ?: name
        } else {
            name
        }

        return FileInfo(fileId, pathString, finalName, size, dateModified, mimeType)
    }

    private fun createFolderInfoFromCursor(cursor: Cursor): FolderInfo? {
        val pathString = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)) ?: return null
        val path = pathString.toPath()

        if (!path.exists || !path.isDirectory() || path.isHidden) return null

        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            ?: path.name
        if (name.isBlank()) return null

        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)) // Often 0 for folders
        val dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)) * 1000

        return FolderInfo(pathString, name, size, dateModified)
    }

    private const val TAG = "FileSearchProvider"
    private const val MAX_SELECTED_TREE_DOCUMENTS_SCANNED = 25_000
    private const val MAX_DOCUMENT_PROVIDER_LOADING_ATTEMPTS = 3
    private const val DOCUMENT_PROVIDER_LOADING_WAIT_MS = 400L
}
