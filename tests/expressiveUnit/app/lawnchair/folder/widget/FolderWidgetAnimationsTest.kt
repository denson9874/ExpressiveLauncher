package app.lawnchair.folder.widget

import android.app.Application
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetAnimationsTest {
    private val widget = RectF(0f, 0f, 600f, 600f)
    private val panel = RectF(50f, 300f, 1290f, 1500f)

    @Test
    fun opening_startsAtTheWidgetAndEndsAtThePanel() {
        val (start, end) = FolderWidgetAnimations.revealEndpoints(widget, panel, 84f, 60f, opening = true)
        assertThat(start).isEqualTo(RoundRect(widget, 84f))
        assertThat(end).isEqualTo(RoundRect(panel, 60f))
    }

    @Test
    fun closing_isTheReverse() {
        val (start, end) = FolderWidgetAnimations.revealEndpoints(widget, panel, 84f, 60f, opening = false)
        assertThat(start).isEqualTo(RoundRect(panel, 60f))
        assertThat(end).isEqualTo(RoundRect(widget, 84f))
    }

    @Test
    fun flightDelta_relativeToPanelOriginNotLp() {
        val ptWidget = 250f
        val panelLeft = 200f
        val ptFolder = 10f
        assertThat(FolderWidgetAnimations.flightDelta(ptWidget, panelLeft, ptFolder)).isEqualTo(40f)
    }

    @Test
    fun revealAnimator_notReversedWhenEndpointsAlreadyOrdered() {
        val (start, end) = FolderWidgetAnimations.revealEndpoints(widget, panel, 84f, 60f, opening = false)
        val view = android.view.View(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val animator = RoundRectRevealAnimator(view, start, end, reversed = false).create()
        animator.setCurrentFraction(0f)
        assertThat(animator.animatedValue).isEqualTo(0f)
        animator.setCurrentFraction(1f)
        assertThat(animator.animatedValue).isEqualTo(1f)
    }
}
