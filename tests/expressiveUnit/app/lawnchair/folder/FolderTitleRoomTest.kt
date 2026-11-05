package app.lawnchair.folder

import android.app.Application
import android.content.Context
import android.view.View
import android.view.View.MeasureSpec
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderTitleRoomTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // A 2-column Home folder's footer on a Pixel 7 Pro: 454 px of content, a 112 px ⋮ button,
    // and folder_title_min_width (100 dp) at 350 px.
    private val footerContentWidth = 454
    private val buttonWidth = 112
    private val minTitleWidth = 350

    private fun fixedView(width: Int, visibility: Int = View.VISIBLE) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(width, 40)
        this.visibility = visibility
    }

    private fun title() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 40, 1f)
    }

    private fun footer(vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(20, 0, 20, 0)
        children.forEach { addView(it) }
        measure(
            MeasureSpec.makeMeasureSpec(footerContentWidth + 40, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(132, MeasureSpec.EXACTLY),
        )
    }

    @Test
    fun overflowButton_doesNotHideTheTitleOfATwoColumnFolder() {
        val title = title()
        val overflow = fixedView(buttonWidth)
        val footer = footer(title, fixedView(0, View.GONE), overflow)

        // What upstream measured: the title alone is squeezed under the minimum.
        assertThat(title.measuredWidth).isEqualTo(footerContentWidth - buttonWidth)
        assertThat(title.measuredWidth).isLessThan(minTitleWidth)

        val room = FolderTitleRoom.roomForTitle(footer, title, overflow)
        assertThat(room).isEqualTo(footerContentWidth)
        assertThat(FolderTitleRoom.shouldHideTitle(room, minTitleWidth)).isFalse()
    }

    @Test
    fun widgetFolderButtons_doNotHideTheTitle() {
        val title = title()
        val overflow = fixedView(buttonWidth)
        val addApps = fixedView(buttonWidth)
        val customize = fixedView(buttonWidth)
        val footer = footer(title, overflow, addApps, customize)

        val room = FolderTitleRoom.roomForTitle(footer, title, overflow, addApps, customize, null)
        assertThat(FolderTitleRoom.shouldHideTitle(room, minTitleWidth)).isFalse()
    }

    @Test
    fun aWidePageIndicator_stillHidesTheTitle() {
        val title = title()
        val overflow = fixedView(buttonWidth)
        val indicator = fixedView(200)
        val footer = footer(title, indicator, overflow)

        val room = FolderTitleRoom.roomForTitle(footer, title, overflow)
        assertThat(room).isEqualTo(footerContentWidth - 200)
        assertThat(FolderTitleRoom.shouldHideTitle(room, minTitleWidth)).isTrue()
    }

    @Test
    fun aGoneTitle_isJudgedByTheFooterRoomNotItsStaleWidth() {
        val title = title().apply { visibility = View.GONE }
        val footer = footer(title, fixedView(buttonWidth))

        val room = FolderTitleRoom.roomForTitle(footer, title, footer.getChildAt(1))
        assertThat(FolderTitleRoom.shouldHideTitle(room, minTitleWidth)).isFalse()
    }
}
