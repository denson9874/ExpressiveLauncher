package app.lawnchair.ui.preferences.about

import android.graphics.BitmapFactory
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.About
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenComposeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun aboutRoute_rendersOwnedProfileAndFifthTapShowsThankYouCelebration() {
        assumeTrue("Expressive-only ownership test", BuildConfig.IS_EXPRESSIVE_PRODUCT)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = PreferenceActivity.createIntent(context, About)
        val avatar = requireNotNull(
            BitmapFactory.decodeResource(context.resources, R.drawable.about_daryl_denson),
        )

        assertEquals(256, avatar.width)
        assertEquals(256, avatar.height)

        ActivityScenario.launch<PreferenceActivity>(intent).use {
            composeRule.onNodeWithTag(ABOUT_EASTER_EGG_TARGET_TAG).assertIsDisplayed()
            assertTrue(composeRule.onAllNodesWithText("Amogh Lele").fetchSemanticsNodes().isEmpty())
            assertTrue(
                composeRule.onAllNodesWithText(context.getString(R.string.support_and_pr))
                    .fetchSemanticsNodes().isEmpty(),
            )
            assertTrue(
                composeRule.onAllNodesWithText(context.getString(R.string.community))
                    .fetchSemanticsNodes().isEmpty(),
            )
            composeRule.onNodeWithTag(ABOUT_DAILY_SPARK_CARD_TAG).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.about_daily_riddle_reveal_answer))
                .performClick()
            composeRule.onNodeWithTag(ABOUT_DAILY_RIDDLE_ANSWER_TAG).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.news)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.support)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.github)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.donate)).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Daryl Denson").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.design_and_development))
                .assertIsDisplayed()

            // Let the About screen finish its normal entry animations before taking manual
            // control of time for the deterministic five-tap celebration assertion.
            composeRule.onNodeWithTag(ABOUT_EASTER_EGG_TARGET_TAG).performScrollTo().assertIsDisplayed()
            composeRule.mainClock.autoAdvance = false
            repeat(5) {
                composeRule.onNodeWithTag(ABOUT_EASTER_EGG_TARGET_TAG).performClick()
            }

            composeRule.mainClock.advanceTimeBy(400)
            composeRule.onNodeWithTag(ABOUT_EASTER_EGG_CELEBRATION_TAG)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.PaneTitle,
                        context.getString(R.string.about_easter_egg_title),
                    ),
                )
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.LiveRegion,
                        LiveRegionMode.Assertive,
                    ),
                )
            composeRule.onNodeWithText(context.getString(R.string.about_easter_egg_title))
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.about_easter_egg_thank_you))
                .assertIsDisplayed()
        }
    }
}
