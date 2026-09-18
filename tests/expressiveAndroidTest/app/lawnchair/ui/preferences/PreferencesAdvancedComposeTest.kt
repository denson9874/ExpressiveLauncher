package app.lawnchair.ui.preferences

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.ui.preferences.navigation.Root
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesAdvancedComposeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun expandedAdvancedRows_haveSeparateOrderedTouchTargets() = withDashboard {
        toggleAdvanced()

        // Inspect all real rows at the same scroll position. Clipped bounds would hide
        // the overlap for rows below the viewport instead of detecting the layout bug.
        assertSeparateOrderedRows()
    }

    @Test
    fun touchingEachAdvancedRow_opensItsOwnSettingsScreen() = withDashboard {
        toggleAdvanced()

        val destinations = listOf(
            R.string.dock_label to R.string.show_hotseat_title,
            R.string.folders_label to R.string.folder_shape_label,
            R.string.gestures_label to R.string.gesture_double_tap,
            R.string.backup_and_restore_label to R.string.create_backup,
        )
        destinations.forEach { (rowLabel, destinationContent) ->
            // A semantic performClick would invoke an obscured row's callback directly.
            // Touch input checks which row actually receives a user's tap.
            row(rowLabel).performScrollTo().assertIsDisplayed().performTouchInput { click() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(context.getString(destinationContent))
                .performScrollTo()
                .assertIsDisplayed()

            pressBack()
            composeRule.waitForIdle()
            advancedLabels.forEach { row(it).assertExists() }
        }
    }

    @Test
    fun advancedCanCollapseAndReopen_andRestoresExpansionAfterRecreation() = withDashboard { scenario ->
        advancedLabels.forEach { row(it).assertDoesNotExist() }
        toggleAdvanced()
        assertSeparateOrderedRows()

        toggleAdvanced()
        advancedLabels.forEach { row(it).assertDoesNotExist() }

        toggleAdvanced()
        assertSeparateOrderedRows()
        scenario.recreate()
        composeRule.waitForIdle()
        assertSeparateOrderedRows()
    }

    private fun withDashboard(block: (ActivityScenario<PreferenceActivity>) -> Unit) {
        assumeTrue(
            "Expressive standard-home Advanced settings",
            BuildConfig.IS_EXPRESSIVE_PRODUCT && BuildConfig.STANDARD_HOME_ONLY,
        )
        // Each test owns a fresh production activity and explicitly starts at Root.
        // No test-only dashboard, replacement content, or preference mutation is used.
        val intent = PreferenceActivity.createIntent(context, Root)
        ActivityScenario.launch<PreferenceActivity>(intent).use { scenario ->
            composeRule.waitForIdle()
            block(scenario)
        }
    }

    private fun row(@StringRes label: Int): SemanticsNodeInteraction = composeRule.onNode(
        hasText(context.getString(label)) and hasClickAction(),
    )

    private fun toggleAdvanced() {
        row(R.string.advanced).performScrollTo().assertIsDisplayed().performTouchInput { click() }
        composeRule.waitForIdle()
    }

    private fun assertSeparateOrderedRows() {
        val labels = listOf(R.string.advanced) + advancedLabels + R.string.about_label
        val bounds = labels.map { label -> row(label).getUnclippedBoundsInRoot() }
        bounds.forEachIndexed { index, rect ->
            assertTrue("${context.getString(labels[index])} has positive width", rect.right > rect.left)
            assertTrue("${context.getString(labels[index])} has positive height", rect.bottom > rect.top)
        }
        bounds.zipWithNext().forEachIndexed { index, (previous, next) ->
            assertTrue(
                "${context.getString(labels[index])} overlaps ${context.getString(labels[index + 1])}: " +
                    "$previous then $next",
                previous.bottom <= next.top,
            )
        }
    }

    private companion object {
        val advancedLabels = listOf(
            R.string.dock_label,
            R.string.folders_label,
            R.string.gestures_label,
            R.string.backup_and_restore_label,
        )
    }
}
