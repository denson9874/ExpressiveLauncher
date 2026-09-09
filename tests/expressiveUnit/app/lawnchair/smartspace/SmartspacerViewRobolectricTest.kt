package app.lawnchair.smartspace

import android.app.Application
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import com.kieronquinn.app.smartspacer.sdk.client.R as SmartspacerR
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
// AGP instruments the signed Conscrypt test JAR, invalidating its embedded digest.
// This layout-only test does not exercise TLS, so the platform provider is sufficient.
@ConscryptMode(ConscryptMode.Mode.OFF)
class SmartspacerViewRobolectricTest {

    @Test
    fun inflation_preservesNonZeroPagerHeight() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parent = FrameLayout(context)
        val view = LayoutInflater.from(context)
            .inflate(R.layout.smartspace_smartspacer, parent, false) as SmartspacerView
        val pager = view.findViewById<android.view.View>(SmartspacerR.id.smartspace_card_pager)

        assertThat(pager.layoutParams.height).isGreaterThan(0)
        assertThat(pager.layoutParams.height).isEqualTo(
            context.resources.getDimensionPixelSize(R.dimen.enhanced_smartspace_height),
        )
    }
}
