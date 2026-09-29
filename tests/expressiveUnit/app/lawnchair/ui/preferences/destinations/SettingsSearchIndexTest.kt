package app.lawnchair.ui.preferences.destinations

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.ui.preferences.navigation.AppDrawerHiddenApps
import app.lawnchair.ui.preferences.navigation.CreateBackup
import app.lawnchair.ui.preferences.navigation.DockSearchProvider
import app.lawnchair.ui.preferences.navigation.GeneralIconShape
import app.lawnchair.ui.preferences.navigation.HomeScreen
import app.lawnchair.ui.preferences.navigation.HomeScreenPopupEditor
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SettingsSearchIndexTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val expressive = SettingsSearchProduct.Expressive

    private fun expressiveItems() = SettingsSearchIndex.items(context, expressive)

    private fun titlesFor(query: String) = SettingsSearch.filter(expressiveItems(), query).map { it.title }

    @Test
    fun everyEntryResolvesToReadableText() {
        val items = expressiveItems()

        assertThat(items).isNotEmpty()
        items.forEach { item ->
            assertThat(item.title).isNotEmpty()
            assertThat(item.title).doesNotContain("%")
            assertThat(item.category).isNotEmpty()
            item.description?.let {
                assertThat(it).isNotEmpty()
                assertThat(it).doesNotContain("%")
            }
        }
    }

    @Test
    fun settingsAddedInRecentReleasesAreSearchable() {
        val expectedByQuery = mapOf(
            "return to default" to "Return to default page",
            "suggestions in dock" to "Suggestions in dock",
            "solid status bar" to "Solid status bar background",
            "dark status bar" to "Dark status bar icons",
            "match app icon shape" to "Match app icon shape",
            "google search bar" to "Google search bar",
            "discover" to "Show Google Discover",
            "app drawer icon" to "Add app drawer icon",
            "rotation" to "Home screen rotation",
            "themed icons" to "Themed icons",
            "update channel" to "Update channel",
        )

        expectedByQuery.forEach { (query, title) ->
            assertThat(titlesFor(query)).contains(title)
        }
    }

    @Test
    fun titleMatchesComeBeforeMatchesThatOnlyAppearInADescription() {
        val results = SettingsSearch.filter(expressiveItems(), "dock")

        assertThat(results).isNotEmpty()
        val titleHasDock = results.map { it.title.contains("dock", ignoreCase = true) }
        assertThat(titleHasDock.first()).isTrue()
        // Once a result without "dock" in its title appears, no title match may follow it.
        assertThat(titleHasDock.dropWhile { it }.none { it }).isTrue()
    }

    @Test
    fun exactTitleFirstThenTitlesThatStartWithIt_thenTitlesThatMentionItLater() {
        val titles = titlesFor("dock")

        assertThat(titles.first()).isEqualTo("Dock")
        val startsWithDock = titles.filter { it.startsWith("Dock ", ignoreCase = true) }
        assertThat(startsWithDock).isNotEmpty()
        val laterMention = titles.indexOf("Suggestions in dock")
        assertThat(laterMention).isGreaterThan(titles.indexOf(startsWithDock.last()))
        assertThat(titlesFor("rotation").first()).isEqualTo("Home screen rotation")
    }

    @Test
    fun matchingIgnoresCaseAndAccents() {
        assertThat(titlesFor("DÓCK")).isEqualTo(titlesFor("dock"))
        assertThat(titlesFor("dock")).isNotEmpty()
    }

    @Test
    fun everyWordMustMatch() {
        val results = titlesFor("dock suggestions")

        assertThat(results).contains("Suggestions in dock")
        assertThat(results).doesNotContain("Dock icons")
    }

    @Test
    fun aBlankQueryListsEverything() {
        val items = expressiveItems()

        assertThat(items).isNotEmpty()
        assertThat(SettingsSearch.filter(items, "   ")).isEqualTo(items)
    }

    @Test
    fun expressiveDoesNotListSettingsItNeverShows() {
        // These exist only in the non-standard-home products: Quickstep/Recents and Live information.
        assertThat(titlesFor("live information")).isEmpty()
        assertThat(titlesFor("quickstep")).isEmpty()
        assertThat(titlesFor("recents")).isEmpty()
        // Left over from the old hand-written list; no such gesture exists.
        assertThat(titlesFor("pinch")).isEmpty()

        val lawnchairTitles = SettingsSearchIndex.items(context, SettingsSearchProduct.Lawnchair).map { it.title }
        assertThat(lawnchairTitles).contains("Live information")
    }

    @Test
    fun resultsOpenTheScreenTheSettingLivesOn() {
        val byTitle = expressiveItems().associateBy { it.title }

        assertThat(byTitle.getValue("Return to default page").destination).isEqualTo(HomeScreen)
        assertThat(byTitle.getValue("Hidden apps").destination).isEqualTo(AppDrawerHiddenApps)
        assertThat(byTitle.getValue("Create backup").destination).isEqualTo(CreateBackup)
        assertThat(byTitle.getValue("Edit pop-up menu items").destination).isEqualTo(HomeScreenPopupEditor)
        assertThat(byTitle.getValue("Search provider").destination).isEqualTo(DockSearchProvider)
        assertThat(byTitle.getValue("Icon shape").destination).isInstanceOf(GeneralIconShape::class.java)
    }

    @Test
    fun noTwoEntriesShareATitleOnTheSamePage() {
        val keys = expressiveItems().map { it.category to it.title }

        assertThat(keys).isNotEmpty()
        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun theEmptyStateTalksAboutSettingsNotWidgets() {
        val message = context.getString(R.string.settings_search_no_results)

        assertThat(message.lowercase()).contains("settings")
        assertThat(message.lowercase()).doesNotContain("widget")
    }
}
