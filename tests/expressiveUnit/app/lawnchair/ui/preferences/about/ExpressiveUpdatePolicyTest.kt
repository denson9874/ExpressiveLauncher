package app.lawnchair.ui.preferences.about

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpressiveUpdatePolicyTest {

    @Test
    fun qaBuild_usesOnlyQaManifest() {
        assertThat(expressiveUpdateConfig("qa", QA_URL, RELEASE_URL)).isEqualTo(
            ExpressiveUpdateConfig(ExpressiveUpdateChannel.QA, QA_URL),
        )
        assertThat(expressiveUpdateConfig("debug", QA_URL, RELEASE_URL)).isNull()
    }

    @Test
    fun releaseBuild_usesOnlyReleaseManifest() {
        assertThat(expressiveUpdateConfig("release", QA_URL, RELEASE_URL)).isEqualTo(
            ExpressiveUpdateConfig(ExpressiveUpdateChannel.RELEASE, RELEASE_URL),
        )
    }

    @Test
    fun newerMatchingManifest_isAvailable() {
        val manifest = manifest(versionCode = 7)

        assertThat(
            evaluateExpressiveUpdate(
                manifest = manifest,
                config = ExpressiveUpdateConfig(ExpressiveUpdateChannel.QA, QA_URL),
                currentVersionCode = 6,
                currentPackageName = QA_PACKAGE,
            ),
        ).isEqualTo(ExpressiveUpdateDecision.Available(manifest))
    }

    @Test
    fun sameOrOlderVersion_isUpToDate() {
        val config = ExpressiveUpdateConfig(ExpressiveUpdateChannel.QA, QA_URL)

        assertThat(evaluateExpressiveUpdate(manifest(6), config, 6, QA_PACKAGE))
            .isEqualTo(ExpressiveUpdateDecision.UpToDate)
        assertThat(evaluateExpressiveUpdate(manifest(5), config, 6, QA_PACKAGE))
            .isEqualTo(ExpressiveUpdateDecision.UpToDate)
    }

    @Test
    fun wrongChannelOrPackage_isRejected() {
        val config = ExpressiveUpdateConfig(ExpressiveUpdateChannel.QA, QA_URL)

        assertThat(
            evaluateExpressiveUpdate(manifest(7).copy(channel = "release"), config, 6, QA_PACKAGE),
        ).isEqualTo(ExpressiveUpdateDecision.Rejected(ExpressiveUpdateRejection.WRONG_CHANNEL))
        assertThat(
            evaluateExpressiveUpdate(manifest(7).copy(packageName = RELEASE_PACKAGE), config, 6, QA_PACKAGE),
        ).isEqualTo(ExpressiveUpdateDecision.Rejected(ExpressiveUpdateRejection.WRONG_PACKAGE))
    }

    @Test
    fun invalidTransportIntegrityOrSize_isRejected() {
        val config = ExpressiveUpdateConfig(ExpressiveUpdateChannel.QA, QA_URL)

        assertThat(evaluateExpressiveUpdate(manifest(7).copy(apkUrl = "http://unsafe"), config, 6, QA_PACKAGE))
            .isEqualTo(ExpressiveUpdateDecision.Rejected(ExpressiveUpdateRejection.INVALID_APK_URL))
        assertThat(evaluateExpressiveUpdate(manifest(7).copy(sha256 = "bad"), config, 6, QA_PACKAGE))
            .isEqualTo(ExpressiveUpdateDecision.Rejected(ExpressiveUpdateRejection.INVALID_SHA256))
        assertThat(evaluateExpressiveUpdate(manifest(7).copy(sizeBytes = 0), config, 6, QA_PACKAGE))
            .isEqualTo(ExpressiveUpdateDecision.Rejected(ExpressiveUpdateRejection.INVALID_SIZE))
        assertThat(evaluateExpressiveUpdate(manifest(7).copy(releaseNotes = " "), config, 6, QA_PACKAGE))
            .isEqualTo(ExpressiveUpdateDecision.Rejected(ExpressiveUpdateRejection.MISSING_RELEASE_NOTES))
    }

    @Test
    fun malformedOrCredentialBearingUrls_areRejectedBeforeCheckingOrDownloading() {
        val config = ExpressiveUpdateConfig(ExpressiveUpdateChannel.QA, QA_URL)
        listOf("https://", "https://[broken", "https://user:password@github.com/owner/repo").forEach { url ->
            assertThat(expressiveUpdateConfig("qa", url, RELEASE_URL)).isNull()
            assertThat(evaluateExpressiveUpdate(manifest(7).copy(apkUrl = url), config, 6, QA_PACKAGE))
                .isEqualTo(ExpressiveUpdateDecision.Rejected(ExpressiveUpdateRejection.INVALID_APK_URL))
        }
    }

    @Test
    fun certificateLineage_mustContainInstalledSigner() {
        assertThat(signingLineageAccepts(setOf("current"), setOf("current", "rotated"))).isTrue()
        assertThat(signingLineageAccepts(setOf("current"), setOf("other"))).isFalse()
        assertThat(signingLineageAccepts(emptySet(), setOf("candidate"))).isFalse()
    }

    private fun manifest(versionCode: Long) = ExpressiveUpdateManifest(
        schemaVersion = EXPRESSIVE_UPDATE_SCHEMA_VERSION,
        channel = "qa",
        versionCode = versionCode,
        versionName = "1.0.$versionCode",
        packageName = QA_PACKAGE,
        apkUrl = "https://github.com/denson9874/ExpressiveLauncher/releases/download/qa-1.0.11-12/ExpressiveLauncher-qa.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 1234,
        releaseNotes = "Updater improvements",
    )

    private companion object {
        const val QA_URL = "https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa/latest.json"
        const val RELEASE_URL = "https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/release/latest.json"
        const val QA_PACKAGE = "dev.launcher.expressive.l3.debug"
        const val RELEASE_PACKAGE = "dev.launcher.expressive.l3"
    }
}
