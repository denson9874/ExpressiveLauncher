package app.lawnchair.ui.preferences.about

import app.lawnchair.util.kotlinxJson
import com.android.launcher3.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val EXPRESSIVE_UPDATE_SCHEMA_VERSION = 1
internal const val EXPRESSIVE_UPDATE_PACKAGE_NAME = "dev.launcher.expressive.l3"
internal const val EXPRESSIVE_PLAY_PACKAGE_NAME = "com.denson9874.Expressive_Launcher_L3"

@Serializable
internal data class ExpressiveUpdateManifest(
    val schemaVersion: Int,
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
)

internal enum class ExpressiveUpdateChannel(val wireName: String) {
    QA("qa"),
    RELEASE("release"),
}

internal data class ExpressiveUpdateConfig(
    val channel: ExpressiveUpdateChannel,
    val manifestUrl: String,
)

internal fun expressiveUpdateConfig(
    buildType: String,
    qaManifestUrl: String,
    releaseManifestUrl: String,
    selectedChannel: String? = null,
): ExpressiveUpdateConfig? {
    val defaultChannel = when (buildType) {
        "qa" -> ExpressiveUpdateChannel.QA
        "release" -> ExpressiveUpdateChannel.RELEASE
        else -> return null
    }
    val channel = ExpressiveUpdateChannel.entries.firstOrNull { it.wireName == selectedChannel }
        ?: defaultChannel
    val config = when (channel) {
        ExpressiveUpdateChannel.QA -> ExpressiveUpdateConfig(channel, qaManifestUrl)
        ExpressiveUpdateChannel.RELEASE -> ExpressiveUpdateConfig(channel, releaseManifestUrl)
    }
    return config.takeIf { it.manifestUrl.isSecureHttpsUrl() }
}

internal fun installedExpressiveUpdateConfig(): ExpressiveUpdateConfig? = if (BuildConfig.IS_EXPRESSIVE_PRODUCT) {
    expressiveUpdateConfig(
        buildType = BuildConfig.BUILD_TYPE,
        qaManifestUrl = BuildConfig.EXPRESSIVE_QA_UPDATE_MANIFEST_URL,
        releaseManifestUrl = BuildConfig.EXPRESSIVE_RELEASE_UPDATE_MANIFEST_URL,
    )
} else {
    null
}

internal suspend fun fetchExpressiveUpdateDecision(
    api: GitHubService,
    config: ExpressiveUpdateConfig,
    currentVersionCode: Long,
    currentPackageName: String,
    installedChannel: ExpressiveUpdateChannel = config.channel,
): ExpressiveUpdateDecision {
    val manifest = api.downloadFile(config.manifestUrl).use { response ->
        kotlinxJson.decodeFromString<ExpressiveUpdateManifest>(response.string())
    }
    return evaluateExpressiveUpdate(
        manifest = manifest,
        config = config,
        currentVersionCode = currentVersionCode,
        currentPackageName = currentPackageName,
        installedChannel = installedChannel,
    )
}

internal sealed interface ExpressiveUpdateDecision {
    data class Available(val manifest: ExpressiveUpdateManifest) : ExpressiveUpdateDecision
    data class WaitingForChannel(val manifest: ExpressiveUpdateManifest) : ExpressiveUpdateDecision
    data object UpToDate : ExpressiveUpdateDecision
    data class Rejected(val reason: ExpressiveUpdateRejection) : ExpressiveUpdateDecision
}

internal enum class ExpressiveUpdateRejection {
    UNSUPPORTED_SCHEMA,
    WRONG_CHANNEL,
    WRONG_PACKAGE,
    INVALID_VERSION,
    INVALID_APK_URL,
    INVALID_SHA256,
    INVALID_SIZE,
    MISSING_RELEASE_NOTES,
}

internal fun evaluateExpressiveUpdate(
    manifest: ExpressiveUpdateManifest,
    config: ExpressiveUpdateConfig,
    currentVersionCode: Long,
    currentPackageName: String,
    installedChannel: ExpressiveUpdateChannel = config.channel,
): ExpressiveUpdateDecision {
    val rejection = when {
        manifest.schemaVersion != EXPRESSIVE_UPDATE_SCHEMA_VERSION -> ExpressiveUpdateRejection.UNSUPPORTED_SCHEMA
        manifest.channel != config.channel.wireName -> ExpressiveUpdateRejection.WRONG_CHANNEL
        (manifest.packageName != EXPRESSIVE_UPDATE_PACKAGE_NAME &&
            manifest.packageName != EXPRESSIVE_PLAY_PACKAGE_NAME) ||
            manifest.packageName != currentPackageName -> ExpressiveUpdateRejection.WRONG_PACKAGE
        manifest.versionCode < 1L || manifest.versionName.isBlank() -> ExpressiveUpdateRejection.INVALID_VERSION
        !manifest.apkUrl.isSecureHttpsUrl() -> ExpressiveUpdateRejection.INVALID_APK_URL
        !manifest.sha256.matches(Regex("[0-9a-fA-F]{64}")) -> ExpressiveUpdateRejection.INVALID_SHA256
        manifest.sizeBytes < 1L -> ExpressiveUpdateRejection.INVALID_SIZE
        manifest.releaseNotes.isBlank() -> ExpressiveUpdateRejection.MISSING_RELEASE_NOTES
        else -> null
    }
    if (rejection != null) return ExpressiveUpdateDecision.Rejected(rejection)

    return when {
        manifest.versionCode > currentVersionCode -> ExpressiveUpdateDecision.Available(manifest)
        manifest.versionCode == currentVersionCode && config.channel != installedChannel ->
            ExpressiveUpdateDecision.Available(manifest)
        manifest.versionCode < currentVersionCode && config.channel != installedChannel ->
            ExpressiveUpdateDecision.WaitingForChannel(manifest)
        else -> ExpressiveUpdateDecision.UpToDate
    }
}

private fun String.isSecureHttpsUrl(): Boolean = toHttpUrlOrNull()?.let { url ->
    url.isHttps && url.username.isEmpty() && url.password.isEmpty()
} == true

/** Serializes result publication with channel changes, including an A -> B -> A selection. */
internal class ExpressiveUpdateRequestGeneration {
    private var generation = 0L

    @Synchronized
    fun advance(): Long = ++generation

    @Synchronized
    fun isCurrent(request: Long): Boolean = request == generation

    @Synchronized
    fun withCurrent(request: Long, action: () -> Unit): Boolean {
        if (!isCurrent(request)) return false
        action()
        return true
    }
}
