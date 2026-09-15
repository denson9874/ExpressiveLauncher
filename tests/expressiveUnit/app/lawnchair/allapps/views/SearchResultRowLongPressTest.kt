package app.lawnchair.allapps.views

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import app.lawnchair.LawnchairLauncher
import app.lawnchair.search.adapter.CALCULATOR
import app.lawnchair.search.adapter.SETTINGS
import app.lawnchair.search.adapter.SearchActionCompat
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory.Companion.PRIVATE_SPACE_RECOVERY_ACTION_ID
import app.lawnchair.search.adapter.WEB_SUGGESTION
import com.android.app.search.LayoutType
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTextView
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        ContactSearchAccessibilityTest.ShadowFontManager::class,
        ContactSearchAccessibilityTest.ShadowPreferenceManager::class,
        ContactSearchAccessibilityTest.ShadowThemeProvider::class,
        SearchResultRowLongPressTest.ShadowIcon::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class SearchResultRowLongPressTest {
    @Test
    fun appRow_longPressUsesItsIconAndConsumesTheGesture() {
        assertLongPressForwarded(item(SearchTargetCompat.RESULT_TYPE_APPLICATION))
    }

    @Test
    fun shortcutRow_longPressUsesItsIconAndConsumesTheGesture() {
        assertLongPressForwarded(item(SearchTargetCompat.RESULT_TYPE_SHORTCUT))
    }

    @Test
    fun recycledActionRows_removeLongPressUntilReboundToAnApp() {
        val row = inflateRow()
        val icon = row.findViewById<SearchResultIcon>(R.id.icon)
        var holds = 0
        icon.setOnLongClickListener { holds++; false }
        val actions = listOf(
            action(SearchTargetCompat.RESULT_TYPE_SETTING_TILE, LayoutType.ICON_SLICE, SETTINGS),
            action(SearchTargetCompat.RESULT_TYPE_SUGGESTIONS, LayoutType.HORIZONTAL_MEDIUM_TEXT, WEB_SUGGESTION),
            action(SearchTargetCompat.RESULT_TYPE_CALCULATOR, LayoutType.CALCULATOR, CALCULATOR),
            action(SearchTargetCompat.RESULT_TYPE_SETTING_TILE, LayoutType.ICON_SLICE, SETTINGS, PRIVATE_SPACE_RECOVERY_ACTION_ID),
        )

        for (target in actions) {
            row.bind(item(SearchTargetCompat.RESULT_TYPE_APPLICATION), emptyList())
            assertThat(row.isLongClickable).isTrue()
            row.bind(target, emptyList())
            assertThat(row.isLongClickable).isFalse()
            assertThat(row.performLongClick()).isFalse()
        }
        assertThat(holds).isEqualTo(0)

        row.bind(item(SearchTargetCompat.RESULT_TYPE_SHORTCUT), emptyList())
        assertThat(row.performLongClick()).isTrue()
        assertThat(holds).isEqualTo(1)
    }

    @Test
    fun ordinaryTapAndKeyboardLaunch_stillUseTheCurrentResult() {
        val row = inflateRow()
        val icon = Shadow.extract<ShadowIcon>(row.findViewById(R.id.icon))
        row.bind(item(SearchTargetCompat.RESULT_TYPE_APPLICATION), emptyList())
        assertThat(row.performClick()).isTrue()
        row.bind(item(SearchTargetCompat.RESULT_TYPE_SHORTCUT), emptyList())
        assertThat(row.launch()).isTrue()

        assertThat(icon.clickedIds).containsExactly("item_1", "item_2").inOrder()
    }

    private fun assertLongPressForwarded(target: SearchTargetCompat) {
        val row = inflateRow()
        val icon = row.findViewById<SearchResultIcon>(R.id.icon)
        row.bind(target, emptyList())
        val heldViews = mutableListOf<View>()
        // All Apps' existing drag handler returns false even after starting its pre-drag menu.
        icon.setOnLongClickListener { heldViews.add(it); false }

        assertThat(row.performLongClick()).isTrue()

        assertThat(heldViews).containsExactly(icon)
        assertThat(Shadow.extract<ShadowIcon>(icon).clickedIds).isEmpty()
    }

    private fun inflateRow(): SearchResultIconRow {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        ReflectionHelpers.setField(launcher, "mDeviceProfile", DeviceProfile())
        val context = ContextThemeWrapper(launcher, R.style.LauncherTheme)
        val row = LayoutInflater.from(context).inflate(
            R.layout.search_result_small_icon_row,
            FrameLayout(context),
            false,
        ) as SearchResultIconRow
        // Android's unhandled long-click fallback requires an attached parent.
        Robolectric.buildActivity(Activity::class.java).setup().visible().get().setContentView(row)
        return row
    }

    private fun item(type: Int) = SearchTargetCompat.Builder(type, LayoutType.SMALL_ICON_HORIZONTAL_TEXT, "item_$type")
        .setPackageName("dev.expressive.test")
        .setUserHandle(Process.myUserHandle())
        .setExtras(Bundle())
        .build()

    private fun action(type: Int, layout: String, packageName: String, id: String = packageName) =
        SearchTargetCompat.Builder(type, layout, id)
            .setPackageName(packageName)
            .setUserHandle(Process.myUserHandle())
            .setExtras(Bundle())
            .setSearchAction(SearchActionCompat.Builder(id, id).setIntent(Intent(Intent.ACTION_VIEW)).build())
            .build()

    // Exercise the real inflated row and Android listener dispatch. Replace only the icon's
    // model/artwork binding and app launch boundary; device QA exercises its real menu and drag.
    @Implements(SearchResultIcon::class)
    class ShadowIcon : ShadowTextView() {
        @RealObject private lateinit var realIcon: SearchResultIcon
        val clickedIds = mutableListOf<String>()
        private var boundId = ""

        @Implementation
        fun bind(target: SearchTargetCompat, callback: (ItemInfoWithIcon) -> Unit) {
            boundId = target.id
            val info = AppInfo().apply { title = target.id }
            realIcon.tag = info
            callback(info)
        }

        @Implementation
        fun onClick(view: View) {
            clickedIds.add(boundId)
        }
    }
}
