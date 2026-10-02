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
}
