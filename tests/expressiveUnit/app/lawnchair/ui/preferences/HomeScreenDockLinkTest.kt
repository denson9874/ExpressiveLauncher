package app.lawnchair.ui.preferences

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.ui.preferences.navigation.Dock
import app.lawnchair.ui.preferences.navigation.PreferenceRoute
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class HomeScreenDockLinkTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun dockLink_targetsTheExistingDockDestination() {
        val route: PreferenceRoute = Dock
        assertThat(Dock.deepLink).endsWith("/dock")
        assertThat(route).isSameInstanceAs(Dock)
    }

    @Test
    fun dockLink_usesTheDockTitleAndListsWhatItControls() {
        assertThat(context.getString(R.string.dock_label)).isEqualTo("Dock")
        val subtitle = context.getString(R.string.home_screen_dock_link_description)
        assertThat(subtitle).contains("Search bar")
        assertThat(subtitle).contains("suggestions")
    }
}
