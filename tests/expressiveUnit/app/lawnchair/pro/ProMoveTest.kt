package app.lawnchair.pro

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/** A device-bound key from before a factory reset or reinstall is moved to the new device ID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ProMoveTest {

    private lateinit var context: Context
    private lateinit var keyPair: KeyPair

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        ProLicenseVerifier.publicKeyForTests = keyPair.public
        context.getSharedPreferences("expressive_pro_license", Context.MODE_PRIVATE).edit()
            .clear()
            .putString("device_install_id", NEW_DEVICE)
            .commit()
        ProManager.resetForTests()
    }

    @After
    fun tearDown() {
        ProLicenseVerifier.publicKeyForTests = null
        ProManager.resetForTests()
    }

    private fun manager() = ProManager.INSTANCE.get(context)

    @Test
    fun keyForOldDevice_isMovedAndTheNewKeyActivated() = runBlocking {
        val oldKey = signedKey(LicenseType.CAKEY.id, "device:$OLD_DEVICE")
        val newKey = signedKey(LicenseType.CAKEY.id, "device:$NEW_DEVICE")
        val service = FakeService { LicenseResponse(success = true, key = newKey) }

        val result = manager().activateOrMove(oldKey, service)

        assertThat(result.isSuccess).isTrue()
        assertThat(service.requests.single()).isEqualTo(RebindRequest(oldKey, NEW_DEVICE))
        assertThat(manager().isPro.value).isTrue()
        assertThat(manager().isCakey.value).isTrue()
        assertThat(manager().getSavedKey()).isEqualTo(newKey)
    }

    @Test
    fun keyForThisDevice_activatesWithoutAskingTheService() = runBlocking {
        val service = FakeService { error("must not be called") }
        val result = manager().activateOrMove(signedKey(LicenseType.VIP.id, "device:$NEW_DEVICE"), service)
        assertThat(result.isSuccess).isTrue()
        assertThat(service.requests).isEmpty()
    }

    @Test
    fun invalidKey_failsWithoutAskingTheService() = runBlocking {
        val service = FakeService { error("must not be called") }
        assertThat(manager().activateOrMove("EXPR-PRO-NOT-A-KEY", service).isFailure).isTrue()
        assertThat(service.requests).isEmpty()
    }

    @Test
    fun moveLimit_reportsItAndStaysFree() = runBlocking {
        val limit = HttpException(Response.error<LicenseResponse>(409, "{}".toResponseBody(null)))
        val result = manager().activateOrMove(signedKey(LicenseType.VIP.id, "device:$OLD_DEVICE"), FakeService { throw limit })
        assertThat(result.exceptionOrNull()?.message).contains("3 new devices")
        assertThat(manager().isPro.value).isFalse()
    }

    @Test
    fun offline_asksToConnect() = runBlocking {
        val result = manager().activateOrMove(signedKey(LicenseType.VIP.id, "device:$OLD_DEVICE"), FakeService { throw IOException("offline") })
        assertThat(result.exceptionOrNull()?.message).contains("Connect to the internet")
        assertThat(manager().isPro.value).isFalse()
    }

    @Test
    fun refusedMove_pointsToSupportWithTheDeviceId() = runBlocking {
        val result = manager().activateOrMove(
            signedKey(LicenseType.VIP.id, "device:$OLD_DEVICE"),
            FakeService { LicenseResponse(success = false, error = "invalid_key") },
        )
        assertThat(result.exceptionOrNull()?.message).contains(NEW_DEVICE)
        assertThat(manager().isPro.value).isFalse()
    }

    /** Builds an EXPR-PRO key in the worker's format, signed with this test's key pair. */
    private fun signedKey(type: Int, recipient: String): String {
        val recipientBytes = recipient.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(17 + recipientBytes.size).apply {
            put('E'.code.toByte()); put('P'.code.toByte()); put(1); put(type.toByte())
            putInt(1_791_000_000); putInt(0); putInt(-1)
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

    private class FakeService(private val move: () -> LicenseResponse) : ProActivationService {
        val requests = mutableListOf<RebindRequest>()
        override suspend fun checkLicense(deviceId: String?, email: String?) = error("unused")
        override suspend fun verifyDonation(request: VerifyDonationRequest) = error("unused")
        override suspend fun startTrial(request: TrialRequest) = error("unused")
        override suspend fun rebind(request: RebindRequest): LicenseResponse {
            requests += request
            return move()
        }
    }

    private companion object {
        const val OLD_DEVICE = "DEV-AB12-CD34"
        const val NEW_DEVICE = "DEV-ZZ99-XY00"
    }
}
