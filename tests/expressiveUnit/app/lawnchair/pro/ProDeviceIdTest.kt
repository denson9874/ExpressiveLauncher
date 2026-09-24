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
class ProDeviceIdTest {

    private lateinit var context: Context

    private val testDeviceId = "DEV-A1B2-C3D4"

    // Valid key bound to DEV-A1B2-C3D4
    private val boundToA1B2Key =
        "EXPR-PRO-8N80-20BA-PMNP-8000-000F-ZZZZ-ZWA6-8SBP-D5HP-AEJ4-8NB2-TG9H-88S2-TGSK-8GT4-EC25-08G6-Y8J5-SPKS-5N45-707P-VSP0-SYKR-WWVC-YRHN-KTCT-PC4C-73YT-0RK7-G6R2-4409-BY2T-4NWN-GQFA-80NF-7TSQ-FG5T-EJ01-T72S-JXW8-27TY-SXHY-9W91-8V4M-X0"

    // Valid universal (offline) key not bound to any device
    private val universalLifetimeKey =
        "EXPR-PRO-8N80-20BA-PKND-T000-000F-ZZZZ-ZW64-8RBJ-F5P2-0H35-DSSP-YVJ8-6130-4880-KM85-324A-3RYT-4BMD-GWAA-KVZ1-6RG6-18QP-XE43-2TYJ-BCZ8-KA19-YNRG-4880-MSV2-RSNW-A83G-M9F5-G52B-EQ1C-53B3-G61R-A3Q4-G6ZW-4ADY-SN1P-8VNC-VNG"

    // Valid key bound to account email supporter@example.com
    private val accountBoundKey =
        "EXPR-PRO-8N80-20VA-PMNP-C000-000F-ZZZZ-ZWEP-2RV3-DXTP-WX1T-EDTQ-0W3F-E9T6-AWJ0-CNW6-2VBG-DHJJ-WRVF-DN33-0H02-417P-HKHD-QAX2-CYHY-XXX0-EP7C-F687-Z5ZM-1BNA-2ZPD-YZR0-9Y67-XKD4-80H0-668P-0H94-WHWV-CMY4-6RAQ-Z1RK-VKH4-Z9QJ-8128-A8K6-BFF9-D441-KN5Q-GZG"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("expressive_pro_license", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ProManager.resetForTests()
    }

    @Test
    fun getDeviceId_generatesValidFormatAndPersists() {
        val deviceId1 = ProDeviceId.get(context)
        assertThat(deviceId1).matches("^DEV-[0-9A-HJKMNP-Z]{4}-[0-9A-HJKMNP-Z]{4}$")

        // Calling again should return the exact same persisted device ID
        val deviceId2 = ProDeviceId.get(context)
        assertThat(deviceId2).isEqualTo(deviceId1)
    }

    @Test
    fun deviceBoundKey_succeedsOnMatchingDevice() {
        // Set device ID to match license
        val prefs = context.getSharedPreferences("expressive_pro_license", Context.MODE_PRIVATE)
        prefs.edit().putString("device_install_id", testDeviceId).commit()

        val manager = ProManager.INSTANCE.get(context)
        val result = manager.activate(boundToA1B2Key)
        assertThat(result.isSuccess).isTrue()

        val details = result.getOrThrow()
        assertThat(details.isDeviceBound).isTrue()
        assertThat(details.boundDeviceId).isEqualTo(testDeviceId)
        assertThat(manager.isPro.value).isTrue()
    }

    @Test
    fun deviceBoundKey_failsOnDifferentDevice() {
        // Set device ID to a different device
        val prefs = context.getSharedPreferences("expressive_pro_license", Context.MODE_PRIVATE)
        prefs.edit().putString("device_install_id", "DEV-DIFFERENT-1").commit()

        val manager = ProManager.INSTANCE.get(context)
        val result = manager.activate(boundToA1B2Key)
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("another device")
        assertThat(manager.isPro.value).isFalse()
    }

    @Test
    fun universalKey_succeedsOnAnyDevice() {
        // Set arbitrary device ID
        val prefs = context.getSharedPreferences("expressive_pro_license", Context.MODE_PRIVATE)
        prefs.edit().putString("device_install_id", "DEV-RANDOM-9999").commit()

        val manager = ProManager.INSTANCE.get(context)
        val result = manager.activate(universalLifetimeKey)
        assertThat(result.isSuccess).isTrue()

        val details = result.getOrThrow()
        assertThat(details.isDeviceBound).isFalse()
        assertThat(details.boundDeviceId).isNull()
        assertThat(manager.isPro.value).isTrue()
    }

    @Test
    fun accountBoundKey_identifiesAccountEmail() {
        val details = ProLicenseVerifier.verify(accountBoundKey).getOrThrow()
        assertThat(details.isAccountBound).isTrue()
        assertThat(details.boundAccountEmail).isEqualTo("supporter@example.com")
        assertThat(details.isDeviceBound).isFalse()
    }
}
