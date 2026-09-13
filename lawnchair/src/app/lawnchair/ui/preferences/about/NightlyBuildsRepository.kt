package app.lawnchair.ui.preferences.about

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import app.lawnchair.util.getApkVersionComparison
import com.android.launcher3.BuildConfig
import com.android.launcher3.Utilities
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class NightlyBuildsRepository(
    val applicationContext: Context,
    val api: GitHubService,
    expressiveUpdateConfig: ExpressiveUpdateConfig? = null,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = ExpressiveUpdateRequestGeneration()
    private var expressiveUpdateConfig = expressiveUpdateConfig
    private var runningJob: Job? = null
    private var validatedDownload: ValidatedDownload? = null
    private var completedDownload: File? = null
    private val installerFiles = mutableSetOf<File>()

    private data class ValidatedDownload(
        val file: File,
        val update: UpdateState.Available,
        val generation: Long,
        val config: ExpressiveUpdateConfig,
        val selectionRevision: Long,
    )

    val updateState: StateFlow<UpdateState>
        field = MutableStateFlow<UpdateState>(UpdateState.UpToDate)

    private var currentBuildNumber: Int = 0
    private var latestBuildNumber: Int = 0
    private var currentCommitHash: String = BuildConfig.COMMIT_HASH

    fun selectUpdateChannel(config: ExpressiveUpdateConfig) = synchronized(requests) {
        expressiveUpdateConfig = config
        checkForUpdate()
    }

    fun close() = synchronized(requests) {
        requests.advance()
        discardCompletedDownload()
        coroutineScope.cancel()
    }

    fun checkForUpdate() = synchronized(requests) {
        val generation = requests.advance()
        val config = expressiveUpdateConfig
        discardCompletedDownload()
        runningJob?.cancel()
        updateState.update { UpdateState.Checking }
        runningJob = coroutineScope.launch(Dispatchers.Default) {
            try {
                if (config != null) {
                    checkExpressiveUpdate(config, generation)
                } else {
                    checkNightlyUpdate(generation)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for update", e)
                publishState(generation, UpdateState.Failed)
            }
        }
    }

    private fun publishState(generation: Long, state: UpdateState) {
        requests.withCurrent(generation) { updateState.update { state } }
    }

    private fun discardCompletedDownload() = synchronized(requests) {
        completedDownload?.let(::deleteUnlessHandedToInstaller)
        completedDownload = null
        validatedDownload = null
    }

    private fun deleteUnlessHandedToInstaller(file: File): Unit = synchronized(requests) {
        // Android may read the granted URI after this repository closes or the channel changes.
        if (file !in installerFiles) file.delete()
    }

    private suspend fun checkExpressiveUpdate(config: ExpressiveUpdateConfig, generation: Long) {
        val decision = fetchExpressiveUpdateDecision(
            api = api,
            config = config,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            currentPackageName = BuildConfig.APPLICATION_ID,
            installedChannel = installedExpressiveUpdateConfig()?.channel ?: config.channel,
        )
        currentCoroutineContext().ensureActive()
        when (decision) {
            is ExpressiveUpdateDecision.Available -> {
                val update = decision.manifest
                publishState(
                    generation,
                    UpdateState.Available(
                        name = "Expressive Launcher ${update.versionName} (${if (config.channel == ExpressiveUpdateChannel.QA) "QA" else "Stable"})",
                        url = update.apkUrl,
                        changelogState = null,
                        expectedSha256 = update.sha256,
                        expectedSizeBytes = update.sizeBytes,
                        expectedVersionCode = update.versionCode,
                        expectedPackageName = update.packageName,
                        releaseNotes = update.releaseNotes,
                    ),
                )
            }

            is ExpressiveUpdateDecision.WaitingForChannel -> publishState(
                generation,
                UpdateState.WaitingForChannel(decision.manifest.versionName, config.channel.wireName),
            )

            ExpressiveUpdateDecision.UpToDate -> publishState(generation, UpdateState.UpToDate)

            is ExpressiveUpdateDecision.Rejected -> {
                throw IOException("Rejected Expressive update manifest: ${decision.reason}")
            }
        }
    }

    private suspend fun checkNightlyUpdate(generation: Long) {
        val releases = api.getReleases()
        val nightly = releases.firstOrNull { it.tagName == "nightly" }
        val asset = nightly?.assets?.firstOrNull()

        val majorVersion = applicationContext.getApkVersionComparison().first[0]
        val expectedBranch = "$majorVersion-dev"

        if (nightly != null && nightly.targetCommitish != expectedBranch) {
            Log.d(TAG, "Skipping update from branch ${nightly.targetCommitish}, expected $expectedBranch")
            publishState(generation, UpdateState.Disabled(UpdateDisabledReason.MAJOR_IS_NEWER))
            return
        }

        // As of now the version string looks like this (CI builds only):
        // <major>.<branch>.(#<CI build number>)
        currentBuildNumber = BuildConfig.VERSION_DISPLAY_NAME
            .substringAfterLast("#")
            .removeSuffix(")")
            .toIntOrNull() ?: 0
        latestBuildNumber =
            asset?.name?.substringAfter("_")?.substringBefore("-")?.toIntOrNull() ?: 0

        if (asset != null && latestBuildNumber > currentBuildNumber) {
            val commitList = getCommitsSinceCurrentVersion()
            publishState(
                generation,
                UpdateState.Available(
                    asset.name,
                    asset.browserDownloadUrl,
                    changelogState = commitList?.let {
                        ChangelogState(
                            commits = it,
                            currentBuildNumber = currentBuildNumber,
                            latestBuildNumber = latestBuildNumber,
                        )
                    },
                    expectedSha256 = asset.sha256Hash,
                ),
            )
        } else {
            publishState(generation, UpdateState.UpToDate)
        }
    }

    fun downloadUpdate(installAfterDownload: Boolean = false) = synchronized(requests) {
        val currentState = updateState.value as? UpdateState.Available ?: return@synchronized
        val config = expressiveUpdateConfig
        val selectionRevision = ExpressiveUpdateChannelPreferences(applicationContext).selectionRevision()
        val generation = requests.advance()
        runningJob?.cancel()
        discardCompletedDownload()
        updateState.update { UpdateState.Downloading(0f) }
        runningJob = coroutineScope.launch(Dispatchers.IO) {
            var downloadedFile: File? = null
            try {
                val file = downloadApk(currentState) { progress ->
                    publishState(generation, UpdateState.Downloading(progress))
                }
                downloadedFile = file
                currentCoroutineContext().ensureActive()
                if (file == null) {
                    publishState(generation, UpdateState.Failed)
                    return@launch
                }
                requests.withCurrent(generation) {
                    completedDownload = file
                    if (config != null) {
                        validatedDownload = ValidatedDownload(file, currentState, generation, config, selectionRevision)
                    }
                    updateState.update { UpdateState.Downloaded(file) }
                    if (installAfterDownload) installUpdate(file)
                }
            } catch (e: CancellationException) {
                downloadedFile?.let(::deleteUnlessHandedToInstaller)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                publishState(generation, UpdateState.Failed)
            } finally {
                if (!requests.isCurrent(generation)) downloadedFile?.let(::deleteUnlessHandedToInstaller)
            }
        }
    }

    fun installUpdate(file: File, forceInstall: Boolean = false) = synchronized(requests) {
        // A callback from an old channel's sheet or download must never open the installer.
        if (expressiveUpdateConfig != null) {
            val download = validatedDownload ?: return@synchronized
            if (
                download.file != file || !requests.isCurrent(download.generation) ||
                download.config != expressiveUpdateConfig
            ) return@synchronized
            val isValid = runCatching {
                applicationContext.validateExpressiveUpdateApk(file, download.update, verifyBytes = true)
            }.getOrElse {
                Log.e(TAG, "Unable to revalidate the downloaded APK", it)
                false
            }
            if (!isValid) {
                updateState.update { UpdateState.Failed }
                discardCompletedDownload()
                return@synchronized
            }
        }
        val openInstaller = installer@{
            if (!forceInstall && applicationContext.isApkMajorVersionNewer(file)) {
                updateState.update { UpdateState.MajorUpdate(file) }
                return@installer
            }
            if (!applicationContext.hasInstallPermission()) {
                applicationContext.requestInstallPermission()
                return@installer
            }
            val uri = FileProvider.getUriForFile(
                applicationContext,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(intent)
            installerFiles += file
        }
        val download = validatedDownload
        if (expressiveUpdateConfig != null && download != null) {
            // Serialize only the final handoff with persisted selection, including A -> B -> A.
            ExpressiveUpdateChannelPreferences(applicationContext).withCurrentSelection(
                download.config,
                download.selectionRevision,
                openInstaller,
            )
        } else {
            openInstaller()
        }
        Unit
    }

    fun resetToDownloaded(file: File) = synchronized(requests) {
        if (expressiveUpdateConfig != null) {
            val download = validatedDownload ?: return@synchronized
            if (download.file != file || !requests.isCurrent(download.generation)) return@synchronized
        }
        updateState.update { UpdateState.Downloaded(file) }
    }

    private suspend fun getCommitsSinceCurrentVersion(): List<GitHubCommit>? {
        return try {
            val majorVersion = applicationContext.getApkVersionComparison().first[0]
            val branch = "$majorVersion-dev"

            // Get the latest commits (last 100)
            val commits = api.getRepositoryCommits("LawnchairLauncher", "lawnchair", branch)

            // Find the index of current commit
            val currentIndex = commits.indexOfFirst { it.sha.startsWith(currentCommitHash) }

            if (currentIndex > 0) {
                // Return all commits newer than current version
                commits.take(currentIndex)
            } else {
                // If current commit not found, show last N commits
                commits.take(MAX_FALLBACK_COMMITS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get commits", e)
            null
        }
    }

    private suspend fun downloadApk(update: UpdateState.Available, onProgress: (Float) -> Unit): File? {
        val apkDir = applicationContext.cacheDir.toPath().resolve("updates").createDirectories().toFile()
        // Separate paths keep a cancelled stream from corrupting the next channel's download.
        val apkFile = File.createTempFile("Expressive-update-", ".apk", apkDir)
        var verified = false
        try {
            api.downloadFile(update.url).use { responseBody ->
                val totalBytes = responseBody.contentLength().takeIf { it > 0L }
                    ?: update.expectedSizeBytes
                    ?: -1L
                if (totalBytes <= 0L) return null
                val messageDigest = MessageDigest.getInstance("SHA-256")
                responseBody.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesDownloaded = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            bytesDownloaded += bytesRead
                            if (update.expectedSizeBytes != null && bytesDownloaded > update.expectedSizeBytes) {
                                Log.e(TAG, "APK exceeds the expected size")
                                return null
                            }
                            output.write(buffer, 0, bytesRead)
                            messageDigest.update(buffer, 0, bytesRead)
                            onProgress((bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (update.expectedSha256 != null) {
                    val hash = messageDigest.digest().joinToString("") { "%02x".format(it) }
                    if (!hash.equals(update.expectedSha256, ignoreCase = true)) {
                        Log.e(TAG, "SHA256 verification failed")
                        return null
                    }
                }
                if (update.expectedSizeBytes != null && apkFile.length() != update.expectedSizeBytes) {
                    Log.e(TAG, "APK size verification failed")
                    return null
                }
                if (!applicationContext.validateExpressiveUpdateApk(apkFile, update)) return null
                verified = true
                return apkFile
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "APK download failed", e)
            return null
        } finally {
            if (!verified) apkFile.delete()
        }
    }

    companion object {
        private const val TAG = "NightlyBuildsRepository"
    }
}

private fun Context.validateExpressiveUpdateApk(
    apkFile: File,
    update: UpdateState.Available,
    verifyBytes: Boolean = false,
): Boolean {
    if (update.expectedPackageName == null && update.expectedVersionCode == null) return true

    if (!apkFile.isFile) return false
    if (verifyBytes) {
        if (apkFile.length() != update.expectedSizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        apkFile.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hash.equals(update.expectedSha256, ignoreCase = true)) return false
    }
    val candidate = packageManager.getPackageArchiveInfo(
        apkFile.absolutePath,
        PackageManager.GET_SIGNING_CERTIFICATES,
    ) ?: return false.also { Log.e("UpdateCheck", "Unable to parse downloaded APK") }
    if (
        candidate.packageName != EXPRESSIVE_UPDATE_PACKAGE_NAME ||
        candidate.packageName != packageName || candidate.packageName != update.expectedPackageName
    ) {
        Log.e("UpdateCheck", "Downloaded APK package does not match the update manifest")
        return false
    }
    if (PackageInfoCompat.getLongVersionCode(candidate) != update.expectedVersionCode) {
        Log.e("UpdateCheck", "Downloaded APK version does not match the update manifest")
        return false
    }

    val installed = packageManager.getPackageInfo(
        packageName,
        PackageManager.GET_SIGNING_CERTIFICATES,
    )
    if (PackageInfoCompat.getLongVersionCode(candidate) < PackageInfoCompat.getLongVersionCode(installed)) {
        Log.e("UpdateCheck", "Downloaded APK would downgrade the installed app")
        return false
    }
    if (!signingLineageAccepts(installed.signingDigests(), candidate.signingDigests())) {
        Log.e("UpdateCheck", "Downloaded APK signing certificate does not match the installed app")
        return false
    }
    return true
}

private fun PackageInfo.signingDigests(): Set<String> {
    val info = signingInfo ?: return emptySet()
    val signatures = if (info.hasMultipleSigners()) {
        info.apkContentsSigners
    } else {
        info.signingCertificateHistory
    }
    return signatures.mapTo(mutableSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

internal fun signingLineageAccepts(
    installedDigests: Set<String>,
    candidateLineageDigests: Set<String>,
): Boolean = installedDigests.isNotEmpty() && candidateLineageDigests.containsAll(installedDigests)

private fun Context.hasInstallPermission(): Boolean {
    return if (Utilities.ATLEAST_O) {
        packageManager.canRequestPackageInstalls()
    } else {
        true
    }
}

private fun Context.requestInstallPermission() {
    if (Utilities.ATLEAST_O) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:$packageName".toUri(),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}

/**
 * Checks if the downloaded APK file has a higher Major (AA) version than the currently
 * installed build.
 */
private fun Context.isApkMajorVersionNewer(apkFile: File): Boolean {
    val (currentParsed, apkParsed) = getApkVersionComparison(apkFile) ?: return false

    val apkMajor = apkParsed[0]
    val currentMajor = currentParsed[0]

    Log.d("UpdateCheck", "Current Major: $currentMajor, APK Major: $apkMajor")

    return apkMajor > currentMajor
}

private const val MAX_FALLBACK_COMMITS = 30
