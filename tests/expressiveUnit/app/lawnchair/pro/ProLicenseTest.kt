package app.lawnchair.pro

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ProLicenseTest {

    private lateinit var context: Context

    private val validLifetimeKey =
        "EXPR-PRO-8N80-20BA-PKND-T000-000F-ZZZZ-ZW64-8RBJ-F5P2-0H35-DSSP-YVJ8-6130-4880-KM85-324A-3RYT-4BMD-GWAA-KVZ1-6RG6-18QP-XE43-2TYJ-BCZ8-KA19-YNRG-4880-MSV2-RSNW-A83G-M9F5-G52B-EQ1C-53B3-G61R-A3Q4-G6ZW-4ADY-SN1P-8VNC-VNG"

    private val expiredKey =
        "EXPR-PRO-8N80-20BA-PKNE-AQGB-W40F-ZZZZ-ZW74-AY3G-D5S6-AS10-AHJQ-6X35-E93K-0H82-440D-0HZP-M8FX-PS0Y-5HC3-H0PK-HZV0-KQHP-5S6F-NSWW-96K7-RG1E-GKCJ-QBG2-41RG-MGTR-Q196-WEX4-8FHD-8NHT-S3WA-R11F-5W5S-RBGP-Q6QN-6DS5-W566-F5Q2"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProManager.resetForTests()
        ProManager.INSTANCE.get(context).deactivate()
    }

    @Test
    fun validLifetimeKey_verifiesSuccessfully() {
        val result = ProLicenseVerifier.verify(validLifetimeKey)
        assertThat(result.isSuccess).isTrue()

        val details = result.getOrThrow()
        assertThat(details.recipient).isEqualTo("Daryl Denson")
        assertThat(details.type).isEqualTo(LicenseType.TESTER)
        assertThat(details.isLifetime).isTrue()
        assertThat(details.isExpired).isFalse()
        assertThat(details.features).isEqualTo(0xFFFFFFFFL)
    }

    @Test
    fun expiredKey_failsVerificationWithExplanation() {
        val result = ProLicenseVerifier.verify(expiredKey)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("expired")
    }

    @Test
    fun tamperedKey_failsVerification() {
        // Change one character in the middle
        val tampered = validLifetimeKey.replace("KM85", "KM86")
        val result = ProLicenseVerifier.verify(tampered)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun proManager_activationAndPersistenceFlow() {
        val manager = ProManager.INSTANCE.get(context)
        assertThat(manager.isPro.value).isFalse()
        assertThat(manager.licenseDetails.value).isNull()

        // Activate
        val activateResult = manager.activate(validLifetimeKey)
        assertThat(activateResult.isSuccess).isTrue()
        assertThat(manager.isPro.value).isTrue()
        assertThat(manager.licenseDetails.value?.recipient).isEqualTo("Daryl Denson")

        // Verify saved key in manager
        val saved = manager.getSavedKey()
        assertThat(saved).isEqualTo(validLifetimeKey)

        // Re-read / reboot persistence
        ProManager.resetForTests()
        val reloadedManager = ProManager.INSTANCE.get(context)
        val reloadedSaved = reloadedManager.getSavedKey()
        assertThat(reloadedSaved).isEqualTo(validLifetimeKey)
        assertThat(reloadedManager.isPro.value).isTrue()
        assertThat(reloadedManager.licenseDetails.value?.recipient).isEqualTo("Daryl Denson")

        // Deactivate
        reloadedManager.deactivate()
        assertThat(reloadedManager.isPro.value).isFalse()
        assertThat(reloadedManager.licenseDetails.value).isNull()

        ProManager.resetForTests()
        val afterDeactivation = ProManager.INSTANCE.get(context)
        assertThat(afterDeactivation.isPro.value).isFalse()
        assertThat(afterDeactivation.getSavedKey()).isNull()
    }
}
