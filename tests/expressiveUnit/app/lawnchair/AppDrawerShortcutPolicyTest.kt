package app.lawnchair

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.gestures.ui.AppDrawerShortcutActivity
import app.lawnchair.gestures.ui.LawnchairShortcutActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AppDrawerShortcutPolicyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun createShortcutInfo_targetsOpenAppDrawerWithGesturePrefix() {
        val shortcutInfo = AppDrawerShortcutActivity.createShortcutInfo(context)

        assertThat(shortcutInfo).isNotNull()
        assertThat(shortcutInfo.id).startsWith(LawnchairShortcutActivity.GESTURE_SHORTCUT_ID_PREFIX)
        assertThat(shortcutInfo.id).contains("OpenAppDrawer")
        assertThat(shortcutInfo.shortLabel).isNotNull()
        assertThat(shortcutInfo.intent).isNotNull()
        assertThat(shortcutInfo.intent?.action).isEqualTo(LawnchairShortcutActivity.START_ACTION)
        val extraHandler = shortcutInfo.intent?.getStringExtra(LawnchairShortcutActivity.EXTRA_HANDLER)
        assertThat(extraHandler).isNotNull()
        assertThat(GestureHandlerConfig.fromString(extraHandler!!))
            .isEqualTo(app.lawnchair.gestures.config.GestureHandlerConfig.OpenAppDrawer)
    }

    @Test
    fun shouldSkipShortcutBadge_trueForAppDrawerShortcut() {
        val shortcutInfo = AppDrawerShortcutActivity.createShortcutInfo(context)
        val shouldSkip = LawnchairShortcutActivity.shouldSkipShortcutBadge(context, shortcutInfo)

        assertThat(shouldSkip).isTrue()
    }

    @Test
    fun createShortcutResultIntent_returnsValidIntent() {
        val resultIntent = AppDrawerShortcutActivity.createShortcutResultIntent(context)

        assertThat(resultIntent).isNotNull()
    }
}
