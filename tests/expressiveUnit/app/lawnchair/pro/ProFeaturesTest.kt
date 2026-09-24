package app.lawnchair.pro

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ProFeaturesTest {

    private lateinit var context: Context

    private val validKey =
        "EXPR-PRO-8N80-20BA-PKND-T000-000F-ZZZZ-ZW64-8RBJ-F5P2-0H35-DSSP-YVJ8-6130-4880-KM85-324A-3RYT-4BMD-GWAA-KVZ1-6RG6-18QP-XE43-2TYJ-BCZ8-KA19-YNRG-4880-MSV2-RSNW-A83G-M9F5-G52B-EQ1C-53B3-G61R-A3Q4-G6ZW-4ADY-SN1P-8VNC-VNG"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProManager.resetForTests()
        ProManager.INSTANCE.get(context).deactivate()
    }

    @Test
    fun deepLinkUri_extractsKeyCorrectly() {
        val uri = Uri.parse("expressive://pro/activate?key=$validKey")
        assertThat(uri.scheme).isEqualTo("expressive")
        assertThat(uri.host).isEqualTo("pro")
        assertThat(uri.path).isEqualTo("/activate")
        assertThat(uri.getQueryParameter("key")).isEqualTo(validKey)
    }

    @Test
    fun unrelatedUri_doesNotMatchProActivation() {
        val uri = Uri.parse("lawnchair://settings")
        val isProActivate = uri.scheme == "expressive" && uri.host == "pro" && uri.path == "/activate"
        assertThat(isProActivate).isFalse()
    }

    @Test
    fun featureBitmask_matchesExpectedBits() {
        val details = ProLicenseVerifier.verify(validKey).getOrThrow()
        val experimentalFeatureMask = 0x1L
        val backupFeatureMask = 0x2L
        val customThemeMask = 0x4L

        assertThat(details.features and experimentalFeatureMask).isNotEqualTo(0L)
        assertThat(details.features and backupFeatureMask).isNotEqualTo(0L)
        assertThat(details.features and customThemeMask).isNotEqualTo(0L)
    }

    @Test
    fun proManager_reactivationCycle() {
        val manager = ProManager.INSTANCE.get(context)
        assertThat(manager.isPro.value).isFalse()

        // 1. Activate
        assertThat(manager.activate(validKey).isSuccess).isTrue()
        assertThat(manager.isPro.value).isTrue()

        // 2. Deactivate
        manager.deactivate()
        assertThat(manager.isPro.value).isFalse()

        // 3. Reactivate
        assertThat(manager.activate(validKey).isSuccess).isTrue()
        assertThat(manager.isPro.value).isTrue()
        assertThat(manager.licenseDetails.value?.recipient).isEqualTo("Daryl Denson")
    }
}
