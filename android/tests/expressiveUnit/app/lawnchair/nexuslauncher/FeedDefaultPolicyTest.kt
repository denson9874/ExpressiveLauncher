package app.lawnchair.nexuslauncher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FeedDefaultPolicyTest {

    @Test
    fun cleanExpressiveInstall_enablesPixelStyleLeftFeed() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources

        assertThat(resources.getBoolean(R.bool.config_default_enable_feed)).isTrue()
    }
}
