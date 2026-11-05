package app.lawnchair.smartspace

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.smartspace.model.Smartspacer
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

    @Test
    fun shortHomeRow_keepsCardsInsideTheStartPadding() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cardHeight = context.resources.getDimensionPixelSize(SmartspacerR.dimen.smartspace_height)
        val rowWidth = cardHeight * 4
        // Shorter than a card, so the SDK view measures itself larger and scales itself down.
        val rowHeight = cardHeight * 3 / 4
        val host = LayoutInflater.from(context)
            .inflate(Smartspacer.layoutResourceId, FrameLayout(context), false)

        // ShortcutAndWidgetContainer measures and lays out the At a Glance row at the cell size.
        host.measure(exactly(rowWidth), exactly(rowHeight))
        host.layout(0, 0, rowWidth, rowHeight)

        val smartspacer = host.findViewById<SmartspacerView>(R.id.bc_smartspace_view)
        val pager = smartspacer.findViewById<View>(SmartspacerR.id.smartspace_card_pager)

        assertThat(smartspacer.scaleX).isLessThan(1f)
        // Laid out at the size its scale was computed for, not at the cell size.
        assertThat(smartspacer.left).isEqualTo(0)
        assertThat(smartspacer.width).isEqualTo(smartspacer.measuredWidth)
        assertThat(smartspacer.height).isEqualTo(smartspacer.measuredHeight)
        // The cards start at the start padding instead of being centred past it and clipped.
        assertThat(pager.left).isEqualTo(smartspacer.paddingLeft)
        assertThat(pager.right).isEqualTo(smartspacer.width - smartspacer.paddingRight)
        // Scaled back down, the view spans the row and stays vertically centred in it.
        assertThat(smartspacer.width * smartspacer.scaleX).isWithin(1f).of(rowWidth.toFloat())
        val visualTop = smartspacer.top + smartspacer.pivotY * (1f - smartspacer.scaleY)
        val visualBottom = visualTop + smartspacer.height * smartspacer.scaleY
        assertThat(visualTop).isWithin(1f).of(0f)
        assertThat(visualBottom).isWithin(1f).of(rowHeight.toFloat())
    }

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
