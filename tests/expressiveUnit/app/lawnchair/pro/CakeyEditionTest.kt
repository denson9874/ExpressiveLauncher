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
class CakeyEditionTest {

    private lateinit var context: Context

    private val cakeyLifetimeKey =
        "EXPR-PRO-8N80-21BA-PMY1-E000-000F-ZZZZ-ZW6M-RRBN-E9GJ-0A23-C5NP-AY99-8WR4-A0H1-025X-N74K-5C6Q-KXE4-4BK3-YY73-3TTB-ACBS-Z4CS-XFZS-0FWJ-TKWZ-T3ST-G0H0-F9CG-R69A-YAWA-M25T-Y2SA-XJQM-FN39-RFNQ-089G-RH12-28RB-4YV4-MPXC-H5G"

    private val standardTesterKey =
        "EXPR-PRO-8N80-20BA-PKND-T000-000F-ZZZZ-ZW64-8RBJ-F5P2-0H35-DSSP-YVJ8-6130-4880-KM85-324A-3RYT-4BMD-GWAA-KVZ1-6RG6-18QP-XE43-2TYJ-BCZ8-KA19-YNRG-4880-MSV2-RSNW-A83G-M9F5-G52B-EQ1C-53B3-G61R-A3Q4-G6ZW-4ADY-SN1P-8VNC-VNG"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProManager.resetForTests()
        ProManager.INSTANCE.get(context).deactivate()
    }

    @Test
    fun validCakeyKey_verifiesSuccessfullyAndIdentifiesCakey() {
        val result = ProLicenseVerifier.verify(cakeyLifetimeKey)
        assertThat(result.isSuccess).isTrue()

        val details = result.getOrThrow()
        assertThat(details.recipient).isEqualTo("Laura (Cakey)")
        assertThat(details.type).isEqualTo(LicenseType.CAKEY)
        assertThat(details.isCakey).isTrue()
        assertThat(details.isLifetime).isTrue()
        assertThat(details.isExpired).isFalse()
        assertThat(details.features).isEqualTo(0xFFFFFFFFL)
    }

    @Test
    fun standardTesterKey_isNotCakey() {
        val result = ProLicenseVerifier.verify(standardTesterKey)
        assertThat(result.isSuccess).isTrue()

        val details = result.getOrThrow()
        assertThat(details.recipient).isEqualTo("Daryl Denson")
        assertThat(details.type).isEqualTo(LicenseType.TESTER)
        assertThat(details.isCakey).isFalse()
    }

    @Test
    fun proManager_activatesAndPersistsCakeyState() {
        val manager = ProManager.INSTANCE.get(context)
        assertThat(manager.isPro.value).isFalse()
        assertThat(manager.isCakey.value).isFalse()
        assertThat(manager.licenseDetails.value).isNull()

        // Activate Cakey Key
        val activateResult = manager.activate(cakeyLifetimeKey)
        assertThat(activateResult.isSuccess).isTrue()
        assertThat(manager.isPro.value).isTrue()
        assertThat(manager.isCakey.value).isTrue()
        assertThat(manager.licenseDetails.value?.recipient).isEqualTo("Laura (Cakey)")
        assertThat(manager.licenseDetails.value?.isCakey).isTrue()

        // Persistence test across manager reloads
        ProManager.resetForTests()
        val reloadedManager = ProManager.INSTANCE.get(context)
        assertThat(reloadedManager.isPro.value).isTrue()
        assertThat(reloadedManager.isCakey.value).isTrue()
        assertThat(reloadedManager.licenseDetails.value?.recipient).isEqualTo("Laura (Cakey)")

        // Deactivation clears both Pro and Cakey states
        reloadedManager.deactivate()
        assertThat(reloadedManager.isPro.value).isFalse()
        assertThat(reloadedManager.isCakey.value).isFalse()
        assertThat(reloadedManager.licenseDetails.value).isNull()

        ProManager.resetForTests()
        val resetManager = ProManager.INSTANCE.get(context)
        assertThat(resetManager.isPro.value).isFalse()
        assertThat(resetManager.isCakey.value).isFalse()
    }

    @Test
    fun cakeyGreetings_returnsValidGreetingText() {
        val greeting = CakeyGreetings.getGreeting(context)
        assertThat(greeting).isNotEmpty()
        assertThat(greeting.contains("Cakey") || greeting.contains("Laura")).isTrue()
    }
}
