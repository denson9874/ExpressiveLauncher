package com.android.launcher3.widget.picker.search

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.Process
import android.view.View
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.ExtendedEditText
import com.android.launcher3.icons.cache.BaseIconCache
import com.android.launcher3.model.WidgetItem
import com.android.launcher3.model.data.PackageItemInfo
import com.android.launcher3.pm.ShortcutConfigActivityInfo
import com.android.launcher3.search.SearchCallback
import com.android.launcher3.util.ApplicationInfoWrapper
import com.android.launcher3.widget.model.WidgetsListBaseEntry
import com.android.launcher3.widget.model.WidgetsListContentEntry
import com.android.launcher3.widget.model.WidgetsListHeaderEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetSearchWhitespaceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val clock = header("test.clock", "Clock", "Analog clock", "Digital clock", "Stacked")
    private val calendar = header("test.calendar", "Calendar", "Month", "Schedule")
    private val provider = WidgetsSearchBar.WidgetsSearchDataProvider {
        listOf(
            clock,
            WidgetsListContentEntry(clock.mPkgItem, "C", clock.mWidgets),
            calendar,
            WidgetsListContentEntry(calendar.mPkgItem, "C", calendar.mWidgets),
        )
    }

    @Test
    fun appNameWithSurroundingSpaces_keepsEveryItemAndItsSearchHeader() {
        for (query in listOf(" clock", "clock ", "  clock  ")) {
            assertThat(search(query)).containsExactlyElementsIn(expectedEntries(clock)).inOrder()
        }
    }

    @Test
    fun itemLabelWithSurroundingSpaces_returnsOnlyItsMatchingItem() {
        val digital = clock.mWidgets.filter { it.label == "Digital clock" }

        assertThat(search("  digital clock  "))
            .containsExactlyElementsIn(expectedEntries(clock, digital)).inOrder()
    }

    @Test
    fun nonbreakingSpacesAroundAppName_doNotHideItsItems() {
        assertThat(search("\u00a0clock\u00a0"))
            .containsExactlyElementsIn(expectedEntries(clock)).inOrder()
    }

    @Test
    fun interiorSpacesInAnItemLabel_preserveTheExistingMatchingRules() {
        val digital = clock.mWidgets.filter { it.label == "Digital clock" }

        assertThat(search("digital clock"))
            .containsExactlyElementsIn(expectedEntries(clock, digital)).inOrder()
        assertThat(search("digital  clock")).isEmpty()
        assertThat(search("digitalclock")).isEmpty()
    }

    @Test
    fun emptyOrWhitespaceOnlyQuery_doesNotMatchEveryItem() {
        for (query in listOf("", "  ", "\t", "\u00a0\u00a0")) {
            assertThat(search(query)).isEmpty()
        }
    }

    @Test
    fun asynchronousSearch_preservesTheOriginalCallbackQuery() {
        val query = " clock "
        var deliveredQuery: String? = null
        var deliveredItems: List<WidgetsListBaseEntry>? = null
        val callback = object : SearchCallback<WidgetsListBaseEntry> {
            override fun onSearchResult(query: String, items: ArrayList<WidgetsListBaseEntry>) {
                deliveredQuery = query
                deliveredItems = items
            }

            override fun clearSearchResult() = Unit
        }

        SimpleWidgetsSearchAlgorithm(provider).doSearch(query, callback)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(deliveredQuery).isEqualTo(query)
        assertThat(deliveredItems).containsExactlyElementsIn(expectedEntries(clock)).inOrder()
    }

    @Test
    fun controller_keepsTypedSpacesAndClearButtonWhileResultsUseTheTrimmedQuery() {
        val input = ExtendedEditText(context)
        val clearButton = ImageButton(context)
        val listener = RecordingSearchModeListener()
        val controller = WidgetsSearchBarController(
            SimpleWidgetsSearchAlgorithm(provider),
            input,
            clearButton,
            listener,
        )
        try {
            input.setText("clock ")
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(input.text.toString()).isEqualTo("clock ")
            assertThat(clearButton.visibility).isEqualTo(View.VISIBLE)
            assertThat(listener.inSearchMode).isTrue()
            assertThat(listener.results).containsExactlyElementsIn(expectedEntries(clock)).inOrder()

            input.setText("  ")
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(input.text.toString()).isEqualTo("  ")
            assertThat(clearButton.visibility).isEqualTo(View.VISIBLE)
            assertThat(listener.inSearchMode).isTrue()
            assertThat(listener.results).isEmpty()

            clearButton.performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertThat(input.text.toString()).isEmpty()
            assertThat(clearButton.visibility).isEqualTo(View.GONE)
            assertThat(listener.inSearchMode).isFalse()
        } finally {
            controller.onDestroy()
        }
    }

    private fun search(query: String) = SimpleWidgetsSearchAlgorithm.getFilteredWidgets(provider, query)

    private fun expectedEntries(
        header: WidgetsListHeaderEntry,
        items: List<WidgetItem> = header.mWidgets,
    ): List<WidgetsListBaseEntry> = listOf(
        WidgetsListHeaderEntry.createForSearch(header.mPkgItem, header.mTitleSectionName, items),
        WidgetsListContentEntry(header.mPkgItem, header.mTitleSectionName, items),
    )

    private fun header(packageName: String, title: String, vararg labels: String): WidgetsListHeaderEntry {
        val user = Process.myUserHandle()
        val packageInfo = PackageItemInfo(packageName, user).apply { this.title = title }
        val items = labels.mapIndexed { index, label ->
            // Picker shortcuts use the same label-matching path as widgets. Construct real items
            // without starting the unrelated icon cache, launcher model or device profile.
            val info = object : ShortcutConfigActivityInfo(
                ComponentName(packageName, "$packageName.Item$index"),
                user,
                ApplicationInfoWrapper(null as ApplicationInfo?),
            ) {
                override fun getLabel(): CharSequence = label
                override fun isPersistable() = false
                override fun getFullResIcon(cache: BaseIconCache): Drawable? = null
            }
            WidgetItem(info, null)
        }
        return WidgetsListHeaderEntry.create(packageInfo, "C", items)
    }

    private class RecordingSearchModeListener : SearchModeListener {
        var inSearchMode = false
        var results: List<WidgetsListBaseEntry> = emptyList()

        override fun enterSearchMode(shouldLog: Boolean) {
            inSearchMode = true
        }

        override fun exitSearchMode() {
            inSearchMode = false
            results = emptyList()
        }

        override fun onSearchResults(entries: List<WidgetsListBaseEntry>) {
            results = entries
        }
    }
}
