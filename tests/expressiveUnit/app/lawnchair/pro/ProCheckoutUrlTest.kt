package app.lawnchair.pro

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/**
 * The Donate button opens the activation worker's checkout, which makes the PayPal order itself so the
 * payment carries the device ID (PayPal's no-code link dropped it, so purchases never reached the app).
 */
class ProCheckoutUrlTest {

    @Test
    fun checkoutUrl_opensTheWorkerCheckoutForThisDevice() {
        assertThat(ProActivationService.checkoutUrl("DEV-AB12-CD34")).isEqualTo(
            "https://expressive-pro-activation.daryldenson-apps.workers.dev/api/checkout?device_id=DEV-AB12-CD34",
        )
    }

    @Test
    fun checkoutUrl_followsTheBaseUrlWithOrWithoutATrailingSlash() {
        val withSlash = ProActivationService.checkoutUrl("DEV-AB12-CD34", "https://worker.test/").toHttpUrl()
        val withoutSlash = ProActivationService.checkoutUrl("DEV-AB12-CD34", "https://worker.test").toHttpUrl()

        for (url in listOf(withSlash, withoutSlash)) {
            assertThat(url.host).isEqualTo("worker.test")
            assertThat(url.encodedPath).isEqualTo("/api/checkout")
            assertThat(url.queryParameterNames).containsExactly("device_id")
            assertThat(url.queryParameter("device_id")).isEqualTo("DEV-AB12-CD34")
        }
    }
}
