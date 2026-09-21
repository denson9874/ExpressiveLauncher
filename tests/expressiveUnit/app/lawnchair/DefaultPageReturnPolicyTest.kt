package app.lawnchair

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.Workspace
import com.google.common.truth.Truth.assertThat
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DefaultPageReturnPolicyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun returnToDefaultPage_defaultsToTrue() {
        val prefs2 = PreferenceManager2.getInstance(context)
        val defaultValue = prefs2.returnToDefaultPage.firstBlocking()

        assertThat(defaultValue).isTrue()
    }

    @Test
    fun returnToDefaultPage_canBeToggled() {
        val prefs2 = PreferenceManager2.getInstance(context)
        prefs2.returnToDefaultPage.setBlocking(false)
        assertThat(prefs2.returnToDefaultPage.firstBlocking()).isFalse()

        prefs2.returnToDefaultPage.setBlocking(true)
        assertThat(prefs2.returnToDefaultPage.firstBlocking()).isTrue()
    }

    @Test
    fun defaultHomePage_defaultsToWorkspaceDefaultPage() {
        val prefs2 = PreferenceManager2.getInstance(context)
        val defaultPage = prefs2.defaultHomePage.firstBlocking()

        assertThat(defaultPage).isEqualTo(Workspace.DEFAULT_PAGE)
    }
}
