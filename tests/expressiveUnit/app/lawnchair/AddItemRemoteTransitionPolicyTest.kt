package app.lawnchair

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.uioverrides.SystemApiWrapper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AddItemRemoteTransitionPolicyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun unprivilegedProcess_createFadeOutAnimOptions_fallsBackSafelyWithoutException() {
        val wrapper = SystemApiWrapper(context)
        val options = wrapper.createFadeOutAnimOptions()

        assertThat(options).isNotNull()
        val bundle = options.toBundle()
        assertThat(bundle).isNotNull()
    }

    @Test
    fun staticApiWrapper_createFadeOutAnimOptions_returnsValidOptions() {
        val options = com.android.launcher3.uioverrides.ApiWrapper.createFadeOutAnimOptions(context)

        assertThat(options).isNotNull()
        assertThat(options.toBundle()).isNotNull()
    }
}
