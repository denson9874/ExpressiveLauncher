package app.lawnchair.pro

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.ui.preferences.about.AboutDestinations
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After Donate, coming back to the launcher or the Pro sheet fetches the license the worker signed for
 * the checkout, so Pro turns on without "Check Activation Status".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ProCheckoutPendingTest {

    private lateinit var context: Context
    private lateinit var keyPair: KeyPair
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        ProLicenseVerifier.publicKeyForTests = keyPair.public
        context.getSharedPreferences("expressive_pro_license", Context.MODE_PRIVATE).edit()
            .clear()
            .putString("device_install_id", DEVICE)
            .commit()
        ProManager.resetForTests()
        ProManager.clock = { now }
    }

    @After
    fun tearDown() {
        ProLicenseVerifier.publicKeyForTests = null
        ProManager.resetForTests()
    }

    private fun manager() = ProManager.INSTANCE.get(context)

    @Test
    fun withoutACheckout_nothingIsFetched() = runBlocking {
        val service = FakeService { error("must not be called") }
        assertThat(manager().hasPendingCheckout).isFalse()
        assertThat(manager().claimPendingCheckout(service)).isNull()
        assertThat(service.checks).isEqualTo(0)
    }

    @Test
    fun afterCheckout_thePaidLicenseIsActivatedAndTheWaitEnds() = runBlocking {
        val paid = signedKey(LicenseType.VIP.id, "device:$DEVICE")
        manager().markCheckoutStarted()

        val result = manager().claimPendingCheckout(FakeService { LicenseResponse(success = true, key = paid) })

        assertThat(result?.isSuccess).isTrue()
        assertThat(manager().isPro.value).isTrue()
        assertThat(manager().getSavedKey()).isEqualTo(paid)
        assertThat(manager().hasPendingCheckout).isFalse()
    }

    @Test
    fun nothingPaidYet_waitsQuietlyAndAsksAtMostOnceAMinute() = runBlocking {
        val service = FakeService { LicenseResponse(success = false, message = "No active license") }
        manager().markCheckoutStarted()

        assertThat(manager().claimPendingCheckout(service)).isNull()
        assertThat(manager().claimPendingCheckout(service)).isNull()
        assertThat(service.checks).isEqualTo(1)

        now += ProManager.CHECKOUT_CHECK_INTERVAL_MS
        assertThat(manager().claimPendingCheckout(service)).isNull()
        assertThat(service.checks).isEqualTo(2)
        assertThat(manager().hasPendingCheckout).isTrue()
        assertThat(manager().isPro.value).isFalse()
    }

    @Test
    fun offline_waitsQuietly() = runBlocking {
        manager().markCheckoutStarted()
        assertThat(manager().claimPendingCheckout(FakeService { throw IOException("offline") })).isNull()
        assertThat(manager().hasPendingCheckout).isTrue()
    }

    @Test
    fun theWaitEndsAfterADay() {
        manager().markCheckoutStarted()
        now += ProManager.CHECKOUT_PENDING_MS + 1
        assertThat(manager().hasPendingCheckout).isFalse()
    }

    @Test
    fun aTrialKeepsWaitingForThePurchase() {
        val trial = signedKey(LicenseType.TRIAL.id, "device:$DEVICE", expiresAt = (now / 1000 + 86_400).toInt())
        assertThat(manager().activate(trial).isSuccess).isTrue()
        manager().markCheckoutStarted()
        assertThat(manager().hasPendingCheckout).isTrue()

        assertThat(manager().activate(signedKey(LicenseType.VIP.id, "device:$DEVICE")).isSuccess).isTrue()
        assertThat(manager().hasPendingCheckout).isFalse()
    }

    @Test
    fun aboutDonate_opensTheWorkerCheckoutForThisDevice() {
        val url = AboutDestinations.launchUrl(context, AboutDestinations.PAYPAL_PAYMENT_URL)

        assertThat(url).isEqualTo(ProActivationService.checkoutUrl(DEVICE))
        assertThat(manager().hasPendingCheckout).isTrue()
        assertThat(AboutDestinations.launchUrl(context, AboutDestinations.GITHUB_PROFILE_URL))
            .isEqualTo(AboutDestinations.GITHUB_PROFILE_URL)
    }

    /** Builds an EXPR-PRO key in the worker's format, signed with this test's key pair. */
    private fun signedKey(type: Int, recipient: String, expiresAt: Int = 0): String {
        val recipientBytes = recipient.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(17 + recipientBytes.size).apply {
            put('E'.code.toByte()); put('P'.code.toByte()); put(1); put(type.toByte())
            putInt(1_791_000_000); putInt(expiresAt); putInt(-1)
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

    private class FakeService(private val license: () -> LicenseResponse) : ProActivationService {
        var checks = 0
        override suspend fun checkLicense(deviceId: String?, email: String?): LicenseResponse {
            checks++
            return license()
        }
        override suspend fun verifyDonation(request: VerifyDonationRequest) = error("unused")
        override suspend fun startTrial(request: TrialRequest) = error("unused")
        override suspend fun rebind(request: RebindRequest) = error("unused")
    }

    private companion object {
        const val DEVICE = "DEV-ZZ99-XY00"
    }
}
