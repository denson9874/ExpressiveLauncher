package app.lawnchair.ui.preferences

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.test.SmartspaceHostActivity
import app.lawnchair.ui.theme.EdgeToEdge
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceEdgeToEdgeComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<SmartspaceHostActivity>()

    @Test
    fun preferencesRenderWithSystemBarInsetsAvailable() {
        composeRule.setContent {
            composeRule.activity.EdgeToEdge()
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .testTag(SAFE_CONTENT_TAG),
            )
        }
        composeRule.onNodeWithTag(SAFE_CONTENT_TAG).assertExists()
        composeRule.waitForIdle()

        val decorView = composeRule.activity.window.decorView
        composeRule.runOnUiThread { ViewCompat.requestApplyInsets(decorView) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(decorView) != null
        }
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(decorView))
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        assertThat(insets.left).isAtLeast(0)
        assertThat(insets.top).isAtLeast(0)
        assertThat(insets.right).isAtLeast(0)
        assertThat(insets.bottom).isAtLeast(0)

        val bounds = composeRule.onNodeWithTag(SAFE_CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        assertThat(bounds.top).isAtLeast(insets.top.toFloat())
        assertThat(bounds.left).isAtLeast(insets.left.toFloat())
        assertThat(bounds.right).isAtMost(decorView.width.toFloat() - insets.right)
        assertThat(bounds.bottom).isAtMost(decorView.height.toFloat() - insets.bottom)
    }

    private companion object {
        const val SAFE_CONTENT_TAG = "edge-to-edge-safe-content"
    }
}
