package com.android.launcher3.widget.picker

import android.app.Application
import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.font.FontManager
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.theme.ThemeProvider
import app.lawnchair.theme.color.SystemColorScheme
import com.android.launcher3.R
import com.android.launcher3.views.StickyHeaderLayout
import com.android.launcher3.widget.picker.search.WidgetsSearchBar
import com.android.launcher3.workprofile.PersonalWorkPagedView
import com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.kdrag0n.monet.theme.ColorScheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLinearLayout

/**
 * WidgetsTwoPaneSheet inflates one of two left-pane layouts into @id/recycler_view_container
 * (widgets_two_pane_sheet_paged_view with a work profile, widgets_two_pane_sheet_recyclerview
 * without one), then WidgetsFullSheet.setupViews and WidgetsTwoPaneSheet look up views in it by
 * id and store them in typed fields. A missing id or a different view type crashes Home as soon
 * as the widget picker opens on a tablet or unfolded foldable, so both layouts are checked
 * against those lookups here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        WidgetsTwoPaneSheetLayoutTest.ShadowFontManager::class,
        WidgetsTwoPaneSheetLayoutTest.ShadowPreferenceManager::class,
        WidgetsTwoPaneSheetLayoutTest.ShadowPreferenceManager2::class,
        WidgetsTwoPaneSheetLayoutTest.ShadowPersonalWorkSlidingTabStrip::class,
        WidgetsTwoPaneSheetLayoutTest.ShadowThemeProvider::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetsTwoPaneSheetLayoutTest {

    @Test
    fun noWorkProfileLayout_providesEveryViewTheSheetLooksUp() {
        val pane = inflate(R.layout.widgets_two_pane_sheet_recyclerview)

        assertSharedPaneViews(pane)
        // Without a work profile the sheet never binds, shows or hides a pager or tab bar, so this
        // layout must not carry them.
        assertAbsent(pane, R.id.widgets_view_pager, R.id.work_widgets_list_view, R.id.tabs)
    }

    @Test
    fun workProfileLayout_providesEveryViewTheSheetLooksUp() {
        val pane = inflate(R.layout.widgets_two_pane_sheet_paged_view)

        val searchContainer = assertSharedPaneViews(pane)

        // setupViews (mHasWorkProfile branch).
        val pager = requireView<PersonalWorkPagedView>(pane, R.id.widgets_view_pager)
        requireView<WidgetsRecyclerView>(pane, R.id.work_widgets_list_view)
        requireView<Button>(pane, R.id.tab_personal)
        requireView<Button>(pane, R.id.tab_work)
        val tabs = requireView<PersonalWorkSlidingTabStrip>(searchContainer, R.id.tabs)

        // tab_personal snaps to page 0 and tab_work to page 1, which must be the lists that
        // AdapterHolder.PRIMARY and AdapterHolder.WORK are bound to.
        assertThat(pager.childCount).isEqualTo(2)
        assertThat(pager.getChildAt(0).id).isEqualTo(R.id.primary_widgets_list_view)
        assertThat(pager.getChildAt(1).id).isEqualTo(R.id.work_widgets_list_view)

        // setupViews calls initParentViews(sheet) and then uses the page indicator as the tabs.
        pager.initParentViews(pane)
        assertThat(pager.pageIndicator).isSameInstanceAs(tabs)
    }

    /** Checks the lookups both layouts must satisfy and returns the search container. */
    private fun assertSharedPaneViews(pane: View): LinearLayout {
        // WidgetsFullSheet.mSearchScrollView is a LinearLayout. The two-pane layouts stack it
        // above the lists; a StickyHeaderLayout here would make every list reserve an empty
        // header row (AdapterHolder.getEmptySpaceHeight) as if the header floated over it.
        val searchContainer =
            requireView<LinearLayout>(pane, R.id.search_and_recommendations_container)
        assertWithMessage("@id/search_and_recommendations_container type")
            .that(searchContainer)
            .isNotInstanceOf(StickyHeaderLayout::class.java)

        // AdapterHolder.setup(WidgetsRecyclerView) for the PRIMARY and SEARCH adapters.
        requireView<WidgetsRecyclerView>(pane, R.id.primary_widgets_list_view)
        requireView<WidgetsRecyclerView>(pane, R.id.search_widgets_list_view)

        // setupViews looks these up inside the search container.
        requireView<View>(searchContainer, R.id.search_bar_container)
        requireView<WidgetsSearchBar>(searchContainer, R.id.widgets_search_bar)

        // setupSuggestedWidgets stores the header in a FrameLayout field and adds to it.
        requireView<FrameLayout>(searchContainer, R.id.suggestions_header)

        // setupSheet wires the options menu click listener.
        requireView<View>(pane, R.id.widget_picker_widget_options_menu)

        // The sheet (widgets_two_pane_sheet) owns these. The left pane comes before the right
        // pane in the view tree, so a copy here would shadow the right pane's view in
        // mContent.findViewById and recommendations would bind into the left pane.
        assertAbsent(
            pane,
            R.id.container,
            R.id.collapse_handle,
            R.id.title,
            R.id.widget_picker_description,
            R.id.linear_layout_container,
            R.id.recycler_view_container,
            R.id.fast_scroller,
            R.id.fast_scroller_popup,
            R.id.right_pane_container,
            R.id.right_pane_scroll_view,
            R.id.right_pane,
            R.id.no_widgets_text,
            R.id.widget_recommendations_container,
            R.id.widget_recommendations_view,
        )
        return searchContainer
    }

    private inline fun <reified T> requireView(root: View, @IdRes id: Int): T {
        val view = root.findViewById<View>(id)
        val name = "@id/${root.resources.getResourceEntryName(id)}"
        assertWithMessage("$name is missing").that(view).isNotNull()
        assertWithMessage("$name type").that(view).isInstanceOf(T::class.java)
        return view as T
    }

    private fun assertAbsent(root: View, @IdRes vararg ids: Int) {
        for (id in ids) {
            assertWithMessage("@id/${root.resources.getResourceEntryName(id)} must not be declared")
                .that(root.findViewById<View>(id))
                .isNull()
        }
    }

    private fun inflate(@LayoutRes layoutRes: Int): FrameLayout {
        // The sheet applies ?attr/widgetsTheme on top of the launcher theme.
        val context = ContextThemeWrapper(
            ContextThemeWrapper(
                ApplicationProvider.getApplicationContext<Application>(),
                R.style.LauncherTheme,
            ),
            R.style.WidgetContainerTheme,
        )
        // Stands in for the sheet's @id/recycler_view_container.
        val pane = FrameLayout(context)
        LayoutInflater.from(context).inflate(layoutRes, pane, true)
        return pane
    }

    // The search bar resolves fonts and its background token while inflating. The lightweight
    // host does not initialize font subscriptions, launcher preferences, or wallpaper colors.
    @Implements(FontManager::class)
    class ShadowFontManager {
        @Implementation
        fun __constructor__(context: Context) = Unit

        @Implementation
        fun overrideFont(textView: TextView, attrs: AttributeSet?) = Unit

        @Implementation
        fun setCustomFont(textView: TextView, type: Int, style: Int) = Unit
    }

    // PagedView (the work-profile pager) reads both preference managers in its field
    // initializers but uses them only while scrolling. The real managers start the display and
    // flag graph, whose shell flags are unavailable on the Robolectric bootclasspath.
    @Implements(PreferenceManager::class)
    class ShadowPreferenceManager {
        @Implementation
        fun __constructor__(context: Context) = Unit
    }

    @Implements(PreferenceManager2::class)
    class ShadowPreferenceManager2 {
        @Implementation
        fun __constructor__(context: Context) = Unit
    }

    // The tab strip styles its tabs from the work-profile tab color preference, which needs a
    // running LawnchairApp. Its view type and children are what the sheet depends on.
    @Implements(PersonalWorkSlidingTabStrip::class)
    class ShadowPersonalWorkSlidingTabStrip : ShadowLinearLayout() {
        @Implementation
        fun onFinishInflate() = Unit
    }

    @Implements(ThemeProvider::class)
    class ShadowThemeProvider {
        private lateinit var context: Context

        @Implementation
        fun __constructor__(context: Context) {
            this.context = context
        }

        @Implementation
        fun getColorScheme(): ColorScheme = SystemColorScheme(context)
    }
}
