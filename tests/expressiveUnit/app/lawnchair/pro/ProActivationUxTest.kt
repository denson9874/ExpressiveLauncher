package app.lawnchair.pro

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class ProActivationUxTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun transactionHint_doesNotContainButtonIdPlaceholder() {
        val hint = context.getString(R.string.expressive_pro_transaction_id_hint)
        // Ensure hint does not trick users into entering the PayPal button ID
        assertThat(hint).doesNotContain("9RB3TYYQ6FWE2")
        assertThat(hint).contains("17-char")
    }

    @Test
    fun buttonIdWarning_formatsProperly() {
        val warning = context.getString(R.string.expressive_pro_button_id_warning, "9RB3TYYQ6FWE2")
        assertThat(warning).contains("9RB3TYYQ6FWE2")
        assertThat(warning).contains("button ID")
        assertThat(warning).contains("17-character")
    }

    @Test
    fun supportStrings_areConfiguredWithSupportEmail() {
        val footer = context.getString(R.string.expressive_pro_support_footer)
        assertThat(footer).contains("daryldenson0405@gmail.com")

        val unreachable = context.getString(R.string.expressive_pro_server_unreachable_desc)
        assertThat(unreachable).contains("offline Pro license key")
    }
}
