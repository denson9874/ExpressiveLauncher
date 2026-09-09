package app.lawnchair.ui.preferences.about

import app.lawnchair.util.kotlinxJson
import com.android.launcher3.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val EXPRESSIVE_UPDATE_SCHEMA_VERSION = 1

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
): ExpressiveUpdateConfig? {
    val config = when (buildType) {
        "qa" -> ExpressiveUpdateConfig(ExpressiveUpdateChannel.QA, qaManifestUrl)
        "release" -> ExpressiveUpdateConfig(ExpressiveUpdateChannel.RELEASE, releaseManifestUrl)
        else -> return null
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
): ExpressiveUpdateDecision {
    val manifest = api.downloadFile(config.manifestUrl).use { response ->
        kotlinxJson.decodeFromString<ExpressiveUpdateManifest>(response.string())
    }
    return evaluateExpressiveUpdate(
        manifest = manifest,
        config = config,
        currentVersionCode = currentVersionCode,
        currentPackageName = currentPackageName,
    )
}

internal sealed interface ExpressiveUpdateDecision {
    data class Available(val manifest: ExpressiveUpdateManifest) : ExpressiveUpdateDecision
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
): ExpressiveUpdateDecision {
    val rejection = when {
        manifest.schemaVersion != EXPRESSIVE_UPDATE_SCHEMA_VERSION -> ExpressiveUpdateRejection.UNSUPPORTED_SCHEMA
        manifest.channel != config.channel.wireName -> ExpressiveUpdateRejection.WRONG_CHANNEL
        manifest.packageName != currentPackageName -> ExpressiveUpdateRejection.WRONG_PACKAGE
        manifest.versionCode < 1L || manifest.versionName.isBlank() -> ExpressiveUpdateRejection.INVALID_VERSION
        !manifest.apkUrl.isSecureHttpsUrl() -> ExpressiveUpdateRejection.INVALID_APK_URL
        !manifest.sha256.matches(Regex("[0-9a-fA-F]{64}")) -> ExpressiveUpdateRejection.INVALID_SHA256
        manifest.sizeBytes < 1L -> ExpressiveUpdateRejection.INVALID_SIZE
        manifest.releaseNotes.isBlank() -> ExpressiveUpdateRejection.MISSING_RELEASE_NOTES
        else -> null
    }
    if (rejection != null) return ExpressiveUpdateDecision.Rejected(rejection)

    return if (manifest.versionCode > currentVersionCode) {
        ExpressiveUpdateDecision.Available(manifest)
    } else {
        ExpressiveUpdateDecision.UpToDate
    }
}

private fun String.isSecureHttpsUrl(): Boolean = toHttpUrlOrNull()?.let { url ->
    url.isHttps && url.username.isEmpty() && url.password.isEmpty()
} == true
