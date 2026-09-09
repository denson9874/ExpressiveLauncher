package app.lawnchair.feed

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.lawnchair.FeedBridge
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Manages the invisible Discover helper; Android owns every install confirmation. */
object ExpressiveFeedSetup {
    const val HELPER_PACKAGE = "dev.launcher.expressive.feed"
    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    private const val ASSET_DIRECTORY = "expressive-feed"
    private const val ASSET_APK = "$ASSET_DIRECTORY/ExpressiveFeed.apk"
    private const val ASSET_METADATA = "$ASSET_DIRECTORY/metadata.json"
    private const val MAX_METADATA_BYTES = 16 * 1024
    private const val BRIDGE_SERVICE = "$HELPER_PACKAGE.ExpressiveFeedBridgeService"

    enum class Kind {
        GOOGLE_MISSING,
        GOOGLE_DISABLED,
        HELPER_MISSING,
        UPDATE_AVAILABLE,
        HELPER_DISABLED,
        HELPER_INCOMPATIBLE,
        HELPER_UNAVAILABLE,
        READY,
        BUNDLE_INVALID,
    }

    data class Status(
        val kind: Kind,
        val bundledVersionName: String? = null,
        val installedVersionName: String? = null,
    )

    data class PreparedInstall(val file: File, val intent: Intent)

    class SetupException(message: String) : Exception(message)

    /** Always re-reads PackageManager state, including after cancellation, replacement, and resume. */
    @WorkerThread
    fun inspect(context: Context): Status {
        val app = context.applicationContext
        val metadata = try {
            readMetadata(app)
        } catch (_: Exception) {
            return Status(Kind.BUNDLE_INVALID)
        }
        return try {
            inspectInstalled(app, metadata)
        } catch (_: Exception) {
            Status(Kind.HELPER_UNAVAILABLE, metadata.versionName)
        }
    }

    /**
     * Extracts only the trusted bundled APK, verifies bytes and Android package identity, and
     * returns a content URI install intent. It never starts an activity or grants install access.
     */
    @WorkerThread
    fun prepareInstall(context: Context): PreparedInstall {
        val app = context.applicationContext
        var temporary: File? = null
        try {
            val metadata = readMetadata(app)
            val status = inspectInstalled(app, metadata)
            ensure(status.kind == Kind.HELPER_MISSING || status.kind == Kind.UPDATE_AVAILABLE,
                "The helper is not currently eligible for installation")
            ensure(canRequestPackageInstalls(app), "Android has not allowed this app to request installations")

            val cache = File(app.cacheDir, ASSET_DIRECTORY)
            ensure(cache.isDirectory || cache.mkdirs(), "Cannot create the helper installation cache")
            val staged = File.createTempFile("verified-helper-", ".apk", cache)
            temporary = staged
            app.assets.open(ASSET_APK).use { input ->
                copyVerifiedFeedApk(input, staged, metadata)
            }
            val pm = app.packageManager
            val candidate = pm.getPackageArchiveInfo(
                staged.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SERVICES,
            ) ?: throw SetupException("Android could not verify the bundled helper APK")
            val launcher = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            validateFeedArchive(metadata, candidate.feedIdentity(), launcher.currentSigningDigests())
            val service = candidate.services?.singleOrNull { it.name == BRIDGE_SERVICE }
            ensure(service != null && service.enabled && service.exported &&
                service.permission == FeedBridge.FIRST_PARTY_CONNECT_PERMISSION,
                "The bundled helper does not expose the expected protected service")

            // An install/uninstall can finish while the APK is being checked. Never propose a
            // downgrade, reinstall a now-ready helper, or ignore a newly incompatible signer.
            val current = inspectInstalled(app, metadata)
            ensure(current.kind == Kind.HELPER_MISSING || current.kind == Kind.UPDATE_AVAILABLE,
                "Helper installation state changed; inspect it again")
            val target = File(cache, "ExpressiveFeed-${metadata.sha256}.apk")
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            temporary = null
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("Expressive Discover helper", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ensure(intent.resolveActivity(pm) != null, "No Android package installer is available")
            return PreparedInstall(target, intent)
        } catch (error: SetupException) {
            throw error
        } catch (error: Exception) {
            throw SetupException("Unable to prepare the bundled helper (${error.javaClass.simpleName})")
        } finally {
            temporary?.delete()
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun installationPermissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    fun appDetailsIntent(packageName: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    )

    private fun readMetadata(context: Context): FeedBundleMetadata {
        val bytes = context.assets.open(ASSET_METADATA).use { it.readBytesBounded(MAX_METADATA_BYTES) }
        return parseFeedBundleMetadata(bytes.toString(Charsets.UTF_8))
    }

    private fun inspectInstalled(context: Context, metadata: FeedBundleMetadata): Status {
        val pm = context.packageManager
        val google = pm.packageOrNull(GOOGLE_PACKAGE)
        if (google == null) return Status(Kind.GOOGLE_MISSING, metadata.versionName)
        if (!pm.isAppEnabled(google)) return Status(Kind.GOOGLE_DISABLED, metadata.versionName)
        val helper = pm.packageOrNull(HELPER_PACKAGE)
            ?: return Status(Kind.HELPER_MISSING, metadata.versionName)
        val signed = pm.checkSignatures(context.packageName, HELPER_PACKAGE) == PackageManager.SIGNATURE_MATCH
        val resolved = pm.resolveService(
            FeedBridge.createOverlayIntent(context, HELPER_PACKAGE),
            PackageManager.GET_META_DATA,
        )?.serviceInfo
        val usable = resolved != null && resolved.packageName == HELPER_PACKAGE && resolved.name == BRIDGE_SERVICE &&
            resolved.enabled && resolved.exported &&
            resolved.permission == FeedBridge.FIRST_PARTY_CONNECT_PERMISSION &&
            pm.checkPermission(FeedBridge.FIRST_PARTY_CONNECT_PERMISSION, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val snapshot = FeedInstalledState(
            enabled = pm.isAppEnabled(helper),
            signerMatches = signed,
            serviceAvailable = usable,
            versionCode = PackageInfoCompat.getLongVersionCode(helper),
        )
        return Status(evaluateFeedHelper(metadata.versionCode, snapshot), metadata.versionName, helper.versionName)
    }

    private fun PackageManager.packageOrNull(packageName: String): PackageInfo? = try {
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.MATCH_DISABLED_COMPONENTS)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun PackageManager.isAppEnabled(info: PackageInfo): Boolean {
        return when (getApplicationEnabledSetting(info.packageName)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> info.applicationInfo?.enabled == true
            else -> false
        }
    }
}

@Serializable
internal data class FeedBundleMetadata(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val sizeBytes: Long,
    val sha256: String,
    val fileName: String,
)

internal fun parseFeedBundleMetadata(json: String): FeedBundleMetadata {
    val metadata = try {
        Json.decodeFromString<FeedBundleMetadata>(json)
    } catch (_: Exception) {
        throw ExpressiveFeedSetup.SetupException("Bundled helper metadata is malformed")
    }
    validateFeedBundleMetadata(metadata)
    return metadata
}

internal fun validateFeedBundleMetadata(metadata: FeedBundleMetadata) {
    ensure(metadata.schemaVersion == 1, "Unsupported helper metadata schema")
    ensure(metadata.packageName == ExpressiveFeedSetup.HELPER_PACKAGE, "Unexpected helper package")
    ensure(metadata.fileName == "ExpressiveFeed.apk", "Unexpected helper asset filename")
    ensure(metadata.versionCode in 1..2_100_000_000L && metadata.versionName.isNotBlank(),
        "Invalid helper version")
    ensure(metadata.sizeBytes in 1..64L * 1024 * 1024, "Invalid helper APK size")
    ensure(metadata.sha256.matches(Regex("[0-9a-f]{64}")), "Invalid helper APK checksum")
}

internal data class FeedInstalledState(
    val enabled: Boolean,
    val signerMatches: Boolean,
    val serviceAvailable: Boolean,
    val versionCode: Long,
)

internal fun evaluateFeedHelper(bundledVersion: Long, installed: FeedInstalledState?): ExpressiveFeedSetup.Kind {
    return when {
        installed == null -> ExpressiveFeedSetup.Kind.HELPER_MISSING
        !installed.signerMatches -> ExpressiveFeedSetup.Kind.HELPER_INCOMPATIBLE
        !installed.enabled -> ExpressiveFeedSetup.Kind.HELPER_DISABLED
        installed.versionCode < bundledVersion -> ExpressiveFeedSetup.Kind.UPDATE_AVAILABLE
        !installed.serviceAvailable -> ExpressiveFeedSetup.Kind.HELPER_UNAVAILABLE
        else -> ExpressiveFeedSetup.Kind.READY
    }
}

internal data class FeedArchiveIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signerDigests: Set<String>,
    val debuggable: Boolean,
)

internal fun validateFeedArchive(metadata: FeedBundleMetadata, archive: FeedArchiveIdentity, launcherSigners: Set<String>) {
    ensure(archive.packageName == metadata.packageName && archive.versionCode == metadata.versionCode &&
        archive.versionName == metadata.versionName, "Bundled helper identity differs from its metadata")
    ensure(launcherSigners.size == 1 && archive.signerDigests == launcherSigners,
        "Bundled helper is not signed by this installed launcher")
    ensure(archive.debuggable, "Bundled helper cannot connect to the Discover service")
}

internal fun copyVerifiedFeedApk(input: InputStream, output: File, metadata: FeedBundleMetadata) {
    validateFeedBundleMetadata(metadata)
    var completed = false
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        output.outputStream().use { destination ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                size += count
                ensure(size <= metadata.sizeBytes, "Bundled helper APK exceeds its expected size")
                digest.update(buffer, 0, count)
                destination.write(buffer, 0, count)
            }
        }
        ensure(size == metadata.sizeBytes, "Bundled helper APK is incomplete")
        ensure(digest.digest().hexDigest() == metadata.sha256, "Bundled helper APK checksum mismatch")
        completed = true
    } finally {
        if (!completed) output.delete()
    }
}

private fun PackageInfo.feedIdentity() = FeedArchiveIdentity(
    packageName = packageName,
    versionCode = PackageInfoCompat.getLongVersionCode(this),
    versionName = versionName,
    signerDigests = currentSigningDigests(),
    debuggable = (applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0,
)

private fun PackageInfo.currentSigningDigests(): Set<String> = signingInfo?.apkContentsSigners
    ?.mapTo(mutableSetOf()) { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hexDigest() }
    ?: emptySet()

private fun InputStream.readBytesBounded(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        ensure(result.size() + count <= limit, "Bundled helper metadata is too large")
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}

private fun ByteArray.hexDigest(): String = joinToString("") { "%02x".format(it) }

private fun ensure(condition: Boolean, message: String) {
    if (!condition) throw ExpressiveFeedSetup.SetupException(message)
}
