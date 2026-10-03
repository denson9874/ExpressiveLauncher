package com.android.launcher3.allapps

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.FastScrollRecyclerView
import com.android.launcher3.FeatureFlagsImpl
import com.android.launcher3.workprofile.PersonalWorkSlidingTabStrip
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FloatingHeaderViewScrollTest {

    private class TestRecyclerView(context: Context) : FastScrollRecyclerView(context) {
        override fun scrollToPositionAtProgress(touchFraction: Float): CharSequence = ""
        override fun onUpdateScrollbar(dy: Int) {}
    }

    @Test
    fun allAppsSheetForHandheld_returnsFalseForFullScreenDrawer() {
        val featureFlags = FeatureFlagsImpl()
        assertThat(featureFlags.allAppsSheetForHandheld()).isFalse()
    }

    @Test
    fun fastScrollRecyclerView_scrollToTop_resetsToPositionZero() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rv = TestRecyclerView(context)
        var scrolledTo = -1
        rv.layoutManager = object : androidx.recyclerview.widget.LinearLayoutManager(context) {
            override fun scrollToPosition(position: Int) {
                scrolledTo = position
            }
        }

        rv.scrollToTop()
        assertThat(scrolledTo).isEqualTo(0)
    }

    @Test
    fun floatingHeaderView_applyVerticalMove_capsTranslationYToMaxTranslation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val headerView = FloatingHeaderView(context)
        val tabLayout = PersonalWorkSlidingTabStrip(context, null)
        ReflectionHelpers.setField(headerView, "mTabLayout", tabLayout)

        // Set max translation to 120px
        ReflectionHelpers.setField(headerView, "mMaxTranslation", 120)

        // When translationY is negative beyond max translation (-200px)
        ReflectionHelpers.setField(headerView, "mTranslationY", -200)
        ReflectionHelpers.callInstanceMethod<Unit>(headerView, "applyVerticalMove")

        val translationY = ReflectionHelpers.getField<Int>(headerView, "mTranslationY")
        assertThat(translationY).isEqualTo(-120)
        assertThat(tabLayout.translationY).isEqualTo(-120f)
    }

    @Test
    fun floatingHeaderView_applyVerticalMove_doesNotProducePositiveTranslation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val headerView = FloatingHeaderView(context)
        val tabLayout = PersonalWorkSlidingTabStrip(context, null)
        ReflectionHelpers.setField(headerView, "mTabLayout", tabLayout)

        ReflectionHelpers.setField(headerView, "mMaxTranslation", 120)
        ReflectionHelpers.setField(headerView, "mTranslationY", 0)
        ReflectionHelpers.callInstanceMethod<Unit>(headerView, "applyVerticalMove")

        val translationY = ReflectionHelpers.getField<Int>(headerView, "mTranslationY")
        assertThat(translationY).isEqualTo(0)
        assertThat(tabLayout.translationY).isEqualTo(0f)
    }
}
