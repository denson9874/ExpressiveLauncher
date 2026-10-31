package app.lawnchair.pro

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pro is included when the launcher runs on ExpressiveOS (platform signed with its release key). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class RomEntitlementTest {

    private lateinit var context: Context
    private lateinit var keyPair: KeyPair
    private var wallMs = T0 * 1000L
    private var elapsedMs = 1_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        ProLicenseVerifier.publicKeyForTests = keyPair.public
        context.getSharedPreferences("expressive_pro_license", Context.MODE_PRIVATE).edit().clear().commit()
        ProManager.resetForTests()
        ProManager.clock = { wallMs }
        ProManager.elapsedClock = { elapsedMs }
    }

    @After
    fun tearDown() {
        ProLicenseVerifier.publicKeyForTests = null
        ProManager.resetForTests()
    }

    private fun onExpressiveOs() {
        RomEntitlement.platformCertDigestsForTests = RomEntitlement.EXPRESSIVE_OS_PLATFORM_CERTS
    }

    private fun onOtherRom() {
        RomEntitlement.platformCertDigestsForTests = setOf(AOSP_TEST_PLATFORM_CERT)
    }

    private fun manager() = ProManager.INSTANCE.get(context)

    @Test
    fun expressiveOsPlatformCert_isIncluded() {
        onExpressiveOs()
        assertThat(RomEntitlement.isIncluded(context)).isTrue()
    }

    @Test
    fun otherPlatformCert_isNotIncluded() {
        onOtherRom()
        assertThat(RomEntitlement.isIncluded(context)).isFalse()
    }

    @Test
    fun realPackageManager_doesNotThrow_andIsNotExpressiveOs() {
        // Robolectric's "android" package is not signed with the ExpressiveOS key.
        assertThat(RomEntitlement.isIncluded(context)).isFalse()
    }

    @Test
    fun onExpressiveOs_withoutLicense_isPro() {
        onExpressiveOs()
        assertThat(manager().isIncludedWithRom).isTrue()
        assertThat(manager().isPro.value).isTrue()
        assertThat(manager().licenseDetails.value).isNull()
    }

    @Test
    fun onOtherRom_withoutLicense_isFree() {
        onOtherRom()
        assertThat(manager().isIncludedWithRom).isFalse()
        assertThat(manager().isPro.value).isFalse()
    }

    @Test
    fun onExpressiveOs_deactivatingALicense_keepsPro() {
        onExpressiveOs()
        manager().activate(signedKey(LicenseType.TESTER.id, expiresAt = 0, recipient = "Tester")).getOrThrow()
        assertThat(manager().licenseDetails.value).isNotNull()

        manager().deactivate()
        assertThat(manager().isPro.value).isTrue()
        assertThat(manager().licenseDetails.value).isNull()
    }

    @Test
    fun onExpressiveOs_trialEnding_keepsPro_withoutTrialEndedNotice() {
        onExpressiveOs()
        manager().activate(trialKey(T0 + 7 * DAY)).getOrThrow()

        wallMs += 8 * DAY * 1000L
        elapsedMs += 8 * DAY * 1000L
        manager().refreshState()

        assertThat(manager().isPro.value).isTrue()
        assertThat(manager().getSavedKey()).isNull()
        assertThat(manager().trialEndedNotice.value).isFalse()
    }

    @Test
    fun onOtherRom_trialEnding_stillReturnsToFree() {
        onOtherRom()
        manager().activate(trialKey(T0 + 7 * DAY)).getOrThrow()

        wallMs += 8 * DAY * 1000L
        elapsedMs += 8 * DAY * 1000L
        manager().refreshState()

        assertThat(manager().isPro.value).isFalse()
        assertThat(manager().trialEndedNotice.value).isTrue()
    }

    private fun trialKey(expiresAt: Long) =
        signedKey(LicenseType.TRIAL.id, expiresAt, "device:${ProDeviceId.get(context)}")

    /** Builds an EXPR-PRO key in the worker's format, signed with this test's key pair. */
    private fun signedKey(type: Int, expiresAt: Long, recipient: String): String {
        val recipientBytes = recipient.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(17 + recipientBytes.size).apply {
            put('E'.code.toByte()); put('P'.code.toByte()); put(1); put(type.toByte())
            putInt(T0.toInt()); putInt(expiresAt.toInt()); putInt(-1)
            put(recipientBytes.size.toByte()); put(recipientBytes)
        }.array()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private); update(payload); sign()
        }
        val body = payload + byteArrayOf(signature.size.toByte()) + signature
        val crc = ProLicenseVerifier.crc16Ccitt(body)
        val packet = body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
        return "EXPR-PRO-" + crockford(packet).chunked(4).joinToString("-")
    }

    private fun crockford(bytes: ByteArray): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val out = StringBuilder()
        var buf = 0
        var bits = 0
        for (b in bytes) {
            buf = (buf shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buf shr bits) and 0x1F])
            }
        }
        if (bits > 0) out.append(alphabet[(buf shl (5 - bits)) and 0x1F])
        return out.toString()
    }

    private companion object {
        const val T0 = 1_791_000_000L
        const val DAY = 86_400L

        /** AOSP's public test platform key (build/make/target/product/security/platform.x509.pem). */
        const val AOSP_TEST_PLATFORM_CERT = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
    }
}
