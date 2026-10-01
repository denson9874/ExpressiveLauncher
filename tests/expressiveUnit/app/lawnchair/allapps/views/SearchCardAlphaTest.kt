package app.lawnchair.allapps.views

import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Result cards fade in with their content instead of showing empty while the content is hidden. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SearchCardAlphaTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun cardFollowsRowAlpha() {
        assertThat(searchCardAlpha(200, 0f)).isEqualTo(0)
        assertThat(searchCardAlpha(200, 0.5f)).isEqualTo(100)
        assertThat(searchCardAlpha(200, 1f)).isEqualTo(200)
        assertThat(searchCardAlpha(200, 1.5f)).isEqualTo(200)
        assertThat(searchCardAlpha(200, -1f)).isEqualTo(0)
    }

    @Test
    fun plainRowUsesItsOwnAlpha() {
        val row = View(context).apply { alpha = 0.25f }
        assertThat(rowContentAlpha(row)).isEqualTo(0.25f)
    }

    @Test
    fun rowWithRippleUsesItsChildrensAlpha() {
        // The enter animation keeps such a row opaque and fades its children.
        val icon = View(context).apply { alpha = 0f }
        val title = View(context).apply { alpha = 0.4f }
        val hidden = View(context).apply {
            alpha = 1f
            visibility = View.GONE
        }
        val row = LinearLayout(context).apply {
            background = ColorDrawable(0)
            addView(icon)
            addView(title)
            addView(hidden)
        }
        assertThat(rowContentAlpha(row)).isEqualTo(0.4f)

        title.alpha = 0f
        assertThat(rowContentAlpha(row)).isEqualTo(0f)
    }

    @Test
    fun groupWithoutBackgroundUsesItsOwnAlpha() {
        val row = LinearLayout(context).apply {
            alpha = 0.6f
            addView(View(context).apply { alpha = 0f })
        }
        assertThat(rowContentAlpha(row)).isEqualTo(0.6f)
    }
}
