package app.lawnchair.feed

import app.lawnchair.feed.ExpressiveFeedSetup.Kind
import app.lawnchair.feed.ExpressiveFeedSetup.SetupException
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExpressiveFeedSetupTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun metadata_acceptsTheExactBundledContract() {
        val value = parseFeedBundleMetadata(
            """{"schemaVersion":1,"packageName":"dev.launcher.expressive.feed","versionCode":10,
                "versionName":"1.0.9","sizeBytes":4,"sha256":"${"a".repeat(64)}",
                "fileName":"ExpressiveFeed.apk"}""",
        )
        assertThat(value.packageName).isEqualTo(ExpressiveFeedSetup.HELPER_PACKAGE)
        assertThat(value.versionCode).isEqualTo(10)
    }

    @Test
    fun metadata_rejectsMissingFieldsAndWrongJsonTypes() {
        for (json in listOf("{}", "[]", "not json", """{"schemaVersion":"one"}""")) {
            assertThrows(SetupException::class.java) { parseFeedBundleMetadata(json) }
        }
    }

    @Test
    fun metadata_rejectsOtherPackagesPathsUnsupportedSchemaAndInvalidIntegrity() {
        val valid = metadata()
        for (invalid in listOf(
            valid.copy(packageName = "com.google.android.googlequicksearchbox"),
            valid.copy(fileName = "../other.apk"),
            valid.copy(schemaVersion = 2),
            valid.copy(sizeBytes = 0),
            valid.copy(sizeBytes = 65L * 1024 * 1024),
            valid.copy(sha256 = "not-a-checksum"),
            valid.copy(versionCode = 0),
            valid.copy(versionCode = 2_100_000_001),
            valid.copy(versionName = " "),
        )) {
            assertThrows(SetupException::class.java) { validateFeedBundleMetadata(invalid) }
        }
    }

    @Test
    fun extraction_verifiesTheExactBytesBeforeKeepingTheFile() {
        val bytes = "the same bytes accepted by the packaged metadata".toByteArray()
        val output = outputFile()
        copyVerifiedFeedApk(ByteArrayInputStream(bytes), output, metadata(bytes))
        assertThat(output.readBytes()).isEqualTo(bytes)
    }

    @Test
    fun extraction_rejectsSameSizeTamperingAndDeletesThePartialApk() {
        val expected = "verified bytes".toByteArray()
        val output = outputFile()
        assertThrows(SetupException::class.java) {
            copyVerifiedFeedApk(ByteArrayInputStream(ByteArray(expected.size) { 1 }), output, metadata(expected))
        }
        assertThat(output.exists()).isFalse()
    }

    @Test
    fun extraction_rejectsTruncatedAndOversizedInputsAndDeletesThem() {
        val expected = "verified bytes".toByteArray()
        for (actual in listOf(expected.dropLast(1).toByteArray(), expected + byteArrayOf(1))) {
            val output = outputFile()
            assertThrows(SetupException::class.java) {
                copyVerifiedFeedApk(ByteArrayInputStream(actual), output, metadata(expected))
            }
            assertThat(output.exists()).isFalse()
        }
    }

    @Test
    fun extraction_ioFailureCannotLeaveAnInstallablePartialArtifact() {
        val output = outputFile()
        val broken = object : InputStream() {
            var count = 0
            override fun read(): Int {
                if (count++ == 0) return 1
                throw IOException("interrupted asset read")
            }
        }
        assertThrows(IOException::class.java) { copyVerifiedFeedApk(broken, output, metadata()) }
        assertThat(output.exists()).isFalse()
    }

    @Test
    fun archive_requiresActualLauncherSignerForBothDebugAndDurableBuilds() {
        for (signer in listOf("debug-certificate-digest", "durable-certificate-digest")) {
            validateFeedArchive(metadata(), archive().copy(signerDigests = setOf(signer)), setOf(signer))
        }
    }

    @Test
    fun archive_rejectsMismatchedMissingAndMultipleSigners() {
        for ((candidate, launcher) in listOf(
            setOf("other") to setOf("launcher"),
            emptySet<String>() to setOf("launcher"),
            setOf("launcher") to emptySet(),
            setOf("launcher", "other") to setOf("launcher", "other"),
            setOf("launcher", "other") to setOf("launcher"),
        )) {
            assertThrows(SetupException::class.java) {
                validateFeedArchive(metadata(), archive().copy(signerDigests = candidate), launcher)
            }
        }
    }

    @Test
    fun archive_rejectsMetadataMismatchAndUnusableNonDebuggableHelper() {
        for (invalid in listOf(
            archive().copy(packageName = "dev.launcher.expressive.l3"),
            archive().copy(versionCode = 9),
            archive().copy(versionName = "wrong"),
            archive().copy(debuggable = false),
        )) {
            assertThrows(SetupException::class.java) { validateFeedArchive(metadata(), invalid, setOf("launcher")) }
        }
    }

    @Test
    fun readiness_requiresEnabledMatchingSignerAndAvailableProtectedService() {
        assertThat(evaluateFeedHelper(10, null)).isEqualTo(Kind.HELPER_MISSING)
        assertThat(evaluateFeedHelper(10, installed().copy(signerMatches = false))).isEqualTo(Kind.HELPER_INCOMPATIBLE)
        assertThat(evaluateFeedHelper(10, installed().copy(enabled = false))).isEqualTo(Kind.HELPER_DISABLED)
        assertThat(evaluateFeedHelper(10, installed().copy(serviceAvailable = false))).isEqualTo(Kind.HELPER_UNAVAILABLE)
        assertThat(evaluateFeedHelper(10, installed())).isEqualTo(Kind.READY)
    }

    @Test
    fun updates_doNotDowngradeOrOfferToReplaceAnIncompatibleOrDisabledHelper() {
        assertThat(evaluateFeedHelper(10, installed().copy(versionCode = 9))).isEqualTo(Kind.UPDATE_AVAILABLE)
        assertThat(evaluateFeedHelper(10, installed().copy(versionCode = 11))).isEqualTo(Kind.READY)
        assertThat(evaluateFeedHelper(10, installed().copy(versionCode = 9, signerMatches = false)))
            .isEqualTo(Kind.HELPER_INCOMPATIBLE)
        assertThat(evaluateFeedHelper(10, installed().copy(versionCode = 9, enabled = false)))
            .isEqualTo(Kind.HELPER_DISABLED)
    }

    @Test
    fun cancellationAndRemoval_doNotRetainPreviousReadyState() {
        assertThat(evaluateFeedHelper(10, installed())).isEqualTo(Kind.READY)
        assertThat(evaluateFeedHelper(10, null)).isEqualTo(Kind.HELPER_MISSING)
        assertThat(evaluateFeedHelper(10, null)).isEqualTo(Kind.HELPER_MISSING)
        assertThat(evaluateFeedHelper(10, installed().copy(versionCode = 9))).isEqualTo(Kind.UPDATE_AVAILABLE)
    }

    private fun outputFile(): File = File(temporary.newFolder(), "helper.apk")

    private fun metadata(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) = FeedBundleMetadata(
        schemaVersion = 1,
        packageName = ExpressiveFeedSetup.HELPER_PACKAGE,
        versionCode = 10,
        versionName = "1.0.9",
        sizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        fileName = "ExpressiveFeed.apk",
    )

    private fun archive() = FeedArchiveIdentity(
        packageName = ExpressiveFeedSetup.HELPER_PACKAGE,
        versionCode = 10,
        versionName = "1.0.9",
        signerDigests = setOf("launcher"),
        debuggable = true,
    )

    private fun installed() = FeedInstalledState(
        enabled = true,
        signerMatches = true,
        serviceAvailable = true,
        versionCode = 10,
    )
}
