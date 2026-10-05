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

/** TG-008: 7-day Pro trial — key format, expiry back to Free, clock-rollback resistance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ProTrialTest {

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

    private fun manager() = ProManager.INSTANCE.get(context)

    private fun advance(seconds: Long) {
        wallMs += seconds * 1000L
        elapsedMs += seconds * 1000L
    }

    @Test
    fun trialKey_verifiesAsTrialWithSevenDaysLeft() {
        val details = ProLicenseVerifier.verify(trialKey(T0 + 7 * DAY), nowSeconds = T0).getOrThrow()
        assertThat(details.type).isEqualTo(LicenseType.TRIAL)
        assertThat(details.isTrial).isTrue()
        assertThat(details.daysLeftAt(T0)).isEqualTo(7)
        assertThat(details.daysLeftAt(T0 + 6 * DAY + 1)).isEqualTo(1)
    }

    @Test
    fun trialExpiry_returnsToFree_andShowsOneTimeNotice() {
        assertThat(manager().activate(trialKey(T0 + 7 * DAY)).isSuccess).isTrue()
        assertThat(manager().isPro.value).isTrue()

        advance(6 * DAY)
        manager().refreshState()
        assertThat(manager().isPro.value).isTrue()

        advance(2 * DAY)
        manager().refreshState()
        assertThat(manager().isPro.value).isFalse()
        assertThat(manager().getSavedKey()).isNull()
        assertThat(manager().trialEndedNotice.value).isTrue()

        manager().dismissTrialEndedNotice()
        assertThat(manager().trialEndedNotice.value).isFalse()
    }

    @Test
    fun settingTheClockBack_doesNotExtendTheTrial() {
        manager().activate(trialKey(T0 + 7 * DAY)).getOrThrow()
        advance(5 * DAY)
        manager().refreshState()

        // The user sets the wall clock back to install day while real time keeps passing.
        wallMs = T0 * 1000L
        elapsedMs += 3 * DAY * 1000L
        manager().refreshState()

        assertThat(manager().isPro.value).isFalse()
        assertThat(manager().trialEndedNotice.value).isTrue()
    }

    @Test
    fun trustedClockPersistsAcrossRestart_soRollbackAfterRebootStillHolds() {
        manager().activate(trialKey(T0 + 7 * DAY)).getOrThrow()
        advance(6 * DAY)
        manager().refreshState() // Home resumes, recording a trusted time of day 6

        // Reboot (elapsed time restarts) with the wall clock set back to install day.
        var rebootElapsedMs = 5_000L
        ProManager.resetForTests()
        ProManager.clock = { T0 * 1000L }
        ProManager.elapsedClock = { rebootElapsedMs }
        val rebooted = ProManager.INSTANCE.get(context)
        assertThat(rebooted.isPro.value).isTrue() // resumes from day 6, not day 0

        rebootElapsedMs += 2 * DAY * 1000L
        rebooted.refreshState()
        assertThat(rebooted.isPro.value).isFalse() // day 8 by real elapsed time
    }

    @Test
    fun nonTrialTimeLimitedKey_keepsUsingTheWallClock() {
        // A tester key must not be cut short by a trusted time that once ran ahead.
        manager().activate(trialKey(T0 + 30 * DAY)).getOrThrow()
        advance(20 * DAY)
        manager().refreshState()
        manager().deactivate()
        wallMs = T0 * 1000L

        val tester = signedKey(LicenseType.TESTER.id, expiresAt = T0 + 10 * DAY, recipient = "Tester")
        assertThat(manager().activate(tester).isSuccess).isTrue()
    }

    @Test
    fun startTrial_activatesServerKey_andRemembersTrialUse() = runBlocking {
        val key = trialKey(T0 + 7 * DAY)
        val result = manager().startTrial(FakeService { TrialResponse(success = true, key = key, expiresAt = T0 + 7 * DAY) })
        assertThat(result.isSuccess).isTrue()
        assertThat(manager().isPro.value).isTrue()
        assertThat(manager().hasUsedTrial).isTrue()
    }

    @Test
    fun startTrial_onConflict_reportsAlreadyUsed() = runBlocking {
        val conflict = HttpException(Response.error<TrialResponse>(409, "{}".toResponseBody(null)))
        val result = manager().startTrial(FakeService { throw conflict })
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("already used")
        assertThat(manager().isPro.value).isFalse()
        assertThat(manager().hasUsedTrial).isTrue()
    }

    @Test
    fun fingerprint_isStableHexAndNeverContainsTheRawIdentifier() {
        val a = ProTrialFingerprint.hash("1234abcd", "dev.launcher.expressive.l3")
        assertThat(a).matches("[0-9a-f]{64}")
        assertThat(a).isEqualTo(ProTrialFingerprint.hash("1234abcd", "dev.launcher.expressive.l3"))
        assertThat(a).doesNotContain("1234abcd")
        assertThat(ProTrialFingerprint.compute(context)).matches("[0-9a-f]{64}")
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

    private class FakeService(private val trial: () -> TrialResponse) : ProActivationService {
        override suspend fun checkLicense(deviceId: String?, email: String?) = error("unused")
        override suspend fun verifyDonation(request: VerifyDonationRequest) = error("unused")
        override suspend fun startTrial(request: TrialRequest): TrialResponse {
            check(request.trialFingerprint.matches(Regex("[0-9a-f]{64}")))
            return trial()
        }
    }

    private companion object {
        const val T0 = 1_791_000_000L
        const val DAY = 86_400L
    }
}
