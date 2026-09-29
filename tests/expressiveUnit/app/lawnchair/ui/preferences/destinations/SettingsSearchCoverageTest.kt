package app.lawnchair.ui.preferences.destinations

import com.android.launcher3.R
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The search index is written by hand, so it can silently fall behind the settings screens (that
 * is how "Return to default page" and "Suggestions in dock" went missing). This scans the screens'
 * sources for labelled rows and fails when one is neither searchable nor deliberately excluded.
 */
class SettingsSearchCoverageTest {

    /** Screens whose rows the index must cover, relative to the preferences source folder. */
    private val scannedFiles = listOf(
        "destinations/GeneralPreferences.kt",
        "destinations/IconPackPreferences.kt",
        "destinations/HomeScreenPreferences.kt",
        "destinations/WidgetPreferences.kt",
        "destinations/SmartspacePreferences.kt",
        "destinations/AppDrawerPreferences.kt",
        "destinations/PredictionsPreferences.kt",
        "destinations/DockPreferences.kt",
        "destinations/FolderPreferences.kt",
        "destinations/GesturePreferences.kt",
        "destinations/BackupAndRestorePreference.kt",
        "components/search/DockSearchPreferences.kt",
        "components/search/DrawerSearchPreferences.kt",
        "components/search/SearchProviderPreference.kt",
        "components/search/FileSearchProvider.kt",
        "components/search/WebSearchProvider.kt",
        "components/GoogleSearchBarPreference.kt",
        "components/AppDrawerHapticFeedbackPreference.kt",
        "components/ExpressiveFeedPreferences.kt",
        "components/HiddenAppsInSearchPreference.kt",
    )

    private val rowComponents = listOf(
        "SwitchPreference",
        "SwitchPreferenceWithPreview",
        "TwoTargetSwitchPreference",
        "MainSwitchPreference",
        "SliderPreference",
        "ListPreference",
        "NavigationActionPreference",
        "ClickablePreference",
        "GestureHandlerPreference",
        "OverlayHandlerPreference",
    )

    /**
     * Labels that are intentionally not searchable, each with the reason. Anything else that a
     * screen labels must be added to [SettingsSearchIndex].
     */
    private val excluded = mapOf(
        // Format wrappers around another label ("Icon size (folded)").
        "state_folded" to "wrapper",
        "state_unfolded" to "wrapper",
        // Generic one-word labels on the grid sub-screen; reached through "Home screen grid".
        "columns" to "grid sub-screen, covered by Home screen grid",
        "rows" to "grid sub-screen, covered by Home screen grid",
        // A generic label for the layout preview switch, not a setting people would search for.
        "layout" to "generic preview switch",
        // Only drawn for nightly builds, which the Expressive product never is.
        "auto_updater_label" to "nightly-only",
        // Only drawn while at least one custom icon exists, so it cannot be a stable destination.
        "reset_custom_icons" to "conditional row",
    )

    private val labelPattern = Regex("""\blabel\s*=\s*(?:stringResource\(\s*(?:id\s*=\s*)?)?\(?R\.string\.(\w+)""")

    @Test
    fun everyLabelledSettingIsSearchableOrDeliberatelyExcluded() {
        val root = preferencesSourceRoot()
        val indexed = (
            SettingsSearchIndex.entries(SettingsSearchProduct.Expressive) +
                SettingsSearchIndex.entries(SettingsSearchProduct.Lawnchair)
            ).map { it.title }.toSet()

        val missing = sortedSetOf<String>()
        scannedFiles.forEach { relative ->
            val source = File(root, relative).readText()
            labelsOfRows(source).forEach { name ->
                if (name !in excluded && resourceId(name) !in indexed) missing += "$name (${relative.substringAfterLast('/')})"
            }
        }

        assertWithMessage(
            "These settings are on a screen but not in SettingsSearchIndex. Add them there, or to " +
                "`excluded` in this test with a reason:\n" + missing.joinToString("\n"),
        ).that(missing).isEmpty()
    }

    @Test
    fun theScanActuallyFindsRows() {
        // Guards the guard: if the pattern or the file list rots, the test above would pass vacuously.
        val root = preferencesSourceRoot()
        val labels = scannedFiles.flatMap { labelsOfRows(File(root, it).readText()) }.toSet()

        assertWithMessage("labels found by the scan").that(labels.size).isAtLeast(100)
        assertWithMessage("known row").that(labels).contains("return_to_default_page")
        assertWithMessage("known row").that(labels).contains("show_suggested_apps_in_dock")
    }

    private fun labelsOfRows(source: String): List<String> {
        val labels = mutableListOf<String>()
        Regex("""\b(${rowComponents.joinToString("|")})\s*\(""").findAll(source).forEach { match ->
            var depth = 1
            var index = match.range.last + 1
            val start = index
            while (index < source.length && depth > 0) {
                when (source[index]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                index++
            }
            labelPattern.find(source.substring(start, index))?.let { labels += it.groupValues[1] }
        }
        return labels
    }

    private fun resourceId(name: String): Int = R.string::class.java.getField(name).getInt(null)

    private fun preferencesSourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "lawnchair/src/app/lawnchair/ui/preferences")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("Could not find lawnchair/src/app/lawnchair/ui/preferences from ${File("").absolutePath}")
    }
}
