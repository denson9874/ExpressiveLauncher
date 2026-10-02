package app.lawnchair.folder.widget

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class FolderWidgetPanelTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.LauncherTheme)
    private val metrics = FolderWidgetMetrics(
        spanX = 2, homeIconSizePx = 150, labelHeightPx = 45,
        iconLabelGapPx = 12, rowSpacingPx = 18, minTouchPx = 144, paddingPx = 24, headerHeightPx = 96,
    )
    private val style = FolderWidgetStyle().resolve(false, FolderWidgetDefaults(0x333333, 0.8f, 84f))

    private fun panel(items: Int, s: ResolvedFolderWidgetStyle = style) = FolderWidgetPanel(context).apply {
        bind("Google", FakeAdapter(items), s, metrics)
        measure(MeasureSpec.makeMeasureSpec(620, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(700, MeasureSpec.EXACTLY))
        layout(0, 0, 620, 700)
    }

    @Test fun gridIsCenteredHorizontally() {
        val p = panel(6)
        assertThat(p.recyclerView.left - 24).isWithin(1).of(620 - 24 - p.recyclerView.right)
        assertThat(p.recyclerView.top).isEqualTo(p.gridSpec.gridTopPx)
    }

    @Test fun scrollOnlyWhenOverflowing() {
        assertThat(panel(6).isScrollable).isFalse()
        val p = panel(40)
        assertThat(p.isScrollable).isTrue()
        assertThat(p.recyclerView.canScrollVertically(1)).isTrue()
    }

    @Test fun emptyAdapter_showsAddAppsButton() {
        val p = panel(0)
        assertThat(p.addAppsButton.visibility).isEqualTo(View.VISIBLE)
    }

    @Test fun hiddenHeader_showsOpenButton() {
        var opened = 0
        val p = panel(6, style.copy(showHeader = false)).apply { onOpenFolder = { opened++ } }
        assertThat(p.header.visibility).isEqualTo(View.GONE)
        assertThat(p.openButton.visibility).isEqualTo(View.VISIBLE)
        p.openButton.performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun headerClick_opensFolder() {
        var opened = 0
        val p = panel(6).apply { onOpenFolder = { opened++ } }
        p.header.performClick()
        assertThat(opened).isEqualTo(1)
    }

    private class FakeAdapter(private val count: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(TextView(parent.context)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            (holder.itemView as TextView).text = "App $position"
        }
    }
}
