package app.lawnchair.data.category

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The drawer's FloatingHeaderView places the app list below the sum of its rows' expected
 * heights, whether or not a row is shown. With Settings > App list > Smart Categories off, the
 * tab row is GONE but still reported a tab row of height, so the apps started below an empty
 * band (reported in the Expressive feedback chat, reproduced on an emulator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AllAppsCategoryTabsTest {

    private val context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.LauncherTheme)
    private val tabRowHeight =
        context.resources.getDimensionPixelSize(R.dimen.all_apps_header_pill_height)

    @Test
    fun smartCategoriesOff_takesNoRoomInTheHeader() {
        val tabs = AllAppsCategoryTabs(context)

        tabs.visibility = View.GONE

        assertThat(tabs.expectedHeight).isEqualTo(0)
        assertThat(tabs.shouldDraw()).isFalse()

        // The header scrolls every row on each drawer scroll and search transition; that must
        // not bring the row, and its height, back.
        tabs.setVerticalScroll(0, false)
        tabs.setVerticalScroll(0, true)
        assertThat(tabs.visibility).isEqualTo(View.GONE)
        assertThat(tabs.expectedHeight).isEqualTo(0)
    }

    @Test
    fun smartCategoriesOn_keepsItsRowEvenWhileScrolledOut() {
        val tabs = AllAppsCategoryTabs(context)
        assertThat(tabRowHeight).isGreaterThan(0)

        assertThat(tabs.expectedHeight).isEqualTo(tabRowHeight)

        // The header hides a scrolled-out row with INVISIBLE; the list must not jump up.
        tabs.setVerticalScroll(0, true)
        assertThat(tabs.visibility).isEqualTo(View.INVISIBLE)
        assertThat(tabs.expectedHeight).isEqualTo(tabRowHeight)
    }
}
