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
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class NightlyBuildsRepository(
    val applicationContext: Context,
    val api: GitHubService,
    private val expressiveUpdateConfig: ExpressiveUpdateConfig? = null,
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val updateState: StateFlow<UpdateState>
        field = MutableStateFlow<UpdateState>(UpdateState.UpToDate)

    private var currentBuildNumber: Int = 0
    private var latestBuildNumber: Int = 0
    private var currentCommitHash: String = BuildConfig.COMMIT_HASH

    fun checkForUpdate() {
        coroutineScope.launch(Dispatchers.Default) {
            updateState.update { UpdateState.Checking }
            try {
                if (expressiveUpdateConfig != null) {
                    checkExpressiveUpdate(expressiveUpdateConfig)
                } else {
                    checkNightlyUpdate()
                }
            } catch (e: Exception) {
                when (e) {
                    is IOException -> {
                        Log.e(TAG, "Network error during update check", e)
                    }

                    else -> {
                        Log.e(TAG, "Failed to check for update", e)
                    }
                }
                updateState.update { UpdateState.Failed }
            }
        }
    }

    private suspend fun checkExpressiveUpdate(config: ExpressiveUpdateConfig) {
        when (
            val decision = fetchExpressiveUpdateDecision(
                api = api,
                config = config,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                currentPackageName = BuildConfig.APPLICATION_ID,
            )
        ) {
            is ExpressiveUpdateDecision.Available -> {
                val update = decision.manifest
                updateState.update {
                    UpdateState.Available(
                        name = "Expressive Launcher ${update.versionName} (${config.channel.wireName.uppercase()})",
                        url = update.apkUrl,
                        changelogState = null,
                        expectedSha256 = update.sha256,
                        expectedSizeBytes = update.sizeBytes,
                        expectedVersionCode = update.versionCode,
                        expectedPackageName = update.packageName,
                        releaseNotes = update.releaseNotes,
                    )
                }
            }

            ExpressiveUpdateDecision.UpToDate -> updateState.update { UpdateState.UpToDate }

            is ExpressiveUpdateDecision.Rejected -> {
                throw IOException("Rejected Expressive update manifest: ${decision.reason}")
            }
        }
    }

    private suspend fun checkNightlyUpdate() {
        val releases = api.getReleases()
        val nightly = releases.firstOrNull { it.tagName == "nightly" }
        val asset = nightly?.assets?.firstOrNull()

        val majorVersion = applicationContext.getApkVersionComparison().first[0]
        val expectedBranch = "$majorVersion-dev"

        if (nightly != null && nightly.targetCommitish != expectedBranch) {
            Log.d(TAG, "Skipping update from branch ${nightly.targetCommitish}, expected $expectedBranch")
            updateState.update { UpdateState.Disabled(UpdateDisabledReason.MAJOR_IS_NEWER) }
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
            updateState.update {
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
                )
            }
        } else {
            updateState.update { UpdateState.UpToDate }
        }
    }

    fun downloadUpdate(installAfterDownload: Boolean = false) {
        val currentState = updateState.value
        if (currentState !is UpdateState.Available) return

        coroutineScope.launch(Dispatchers.IO) {
            updateState.update { UpdateState.Downloading(0f) }
            try {
                val file = downloadApk(currentState) { progress ->
                    updateState.update { UpdateState.Downloading(progress) }
                }
                if (file != null) {
                    updateState.update { UpdateState.Downloaded(file) }
                    if (installAfterDownload) {
                        installUpdate(file)
                    }
                } else {
                    Log.e(TAG, "Downloaded file is null")
                    updateState.update { UpdateState.Failed }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                updateState.update { UpdateState.Failed }
            }
        }
    }

    fun installUpdate(file: File, forceInstall: Boolean = false) {
        if (!forceInstall && applicationContext.isApkMajorVersionNewer(file)) {
            updateState.update { UpdateState.MajorUpdate(file) }
            return
        }
        if (!applicationContext.hasInstallPermission()) {
            // todo expose proper permission UI instead of requesting immediately on click
            applicationContext.requestInstallPermission()
            return
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
    }

    fun resetToDownloaded(file: File) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get commits", e)
            null
        }
    }

    private suspend fun downloadApk(update: UpdateState.Available, onProgress: (Float) -> Unit): File? {
        return try {
            val cacheDir = applicationContext.cacheDir
            val apkDirPath = cacheDir.toPath().resolve("updates").createDirectories()
            val apkFilePath = apkDirPath.resolve("Lawnchair-update.apk").apply { deleteIfExists() }

            val responseBody = api.downloadFile(update.url)
            val totalBytes = responseBody.contentLength().takeIf { it > 0L }
                ?: update.expectedSizeBytes
                ?: -1L
            if (totalBytes <= 0L) {
                Log.w(TAG, "The update download did not report a usable size")
                return null
            }

            val messageDigest = java.security.MessageDigest.getInstance("SHA-256")

            responseBody.byteStream().use { input ->
                apkFilePath.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesDownloaded = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        messageDigest.update(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        onProgress((bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }
            if (update.expectedSha256 != null) {
                val computedHash = messageDigest.digest().joinToString("") { "%02x".format(it) }
                if (!computedHash.equals(update.expectedSha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA256 verification failed")
                    apkFilePath.deleteIfExists()
                    return null
                }
                Log.d(TAG, "SHA256 verification passed")
            }

            val apkFile = apkFilePath.toFile()
            if (update.expectedSizeBytes != null && apkFile.length() != update.expectedSizeBytes) {
                Log.e(TAG, "APK size verification failed")
                apkFilePath.deleteIfExists()
                return null
            }
            if (!applicationContext.validateExpressiveUpdateApk(apkFile, update)) {
                apkFilePath.deleteIfExists()
                return null
            }

            apkFile
        } catch (e: Exception) {
            Log.e(TAG, "APK download failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "NightlyBuildsRepository"
    }
}

private fun Context.validateExpressiveUpdateApk(
    apkFile: File,
    update: UpdateState.Available,
): Boolean {
    if (update.expectedPackageName == null && update.expectedVersionCode == null) return true

    val candidate = packageManager.getPackageArchiveInfo(
        apkFile.absolutePath,
        PackageManager.GET_SIGNING_CERTIFICATES,
    ) ?: return false.also { Log.e("UpdateCheck", "Unable to parse downloaded APK") }
    if (candidate.packageName != update.expectedPackageName) {
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
