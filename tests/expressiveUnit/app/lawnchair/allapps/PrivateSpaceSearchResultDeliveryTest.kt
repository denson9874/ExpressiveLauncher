package app.lawnchair.allapps

import android.app.Application
import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.views.ContactSearchAccessibilityTest
import com.android.launcher3.R
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
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
import org.robolectric.shadows.ShadowViewGroup
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        ContactSearchAccessibilityTest.ShadowFontManager::class,
        ContactSearchAccessibilityTest.ShadowPreferenceManager::class,
        ContactSearchAccessibilityTest.ShadowThemeProvider::class,
        PrivateSpaceSearchResultDeliveryTest.ShadowSearchInput::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateSpaceSearchResultDeliveryTest {

    @Test
    fun latePrivateSpaceResults_afterInputReset_returnBeforeAccessingResultViews() {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        val context = ContextThemeWrapper(launcher, R.style.LauncherTheme)
        val input = FallbackSearchInputView(context, null).apply { setText("private space") }
        val search = AllAppsSearchInput(context, null)
        ReflectionHelpers.setField(search, "input", input)

        // Android unlock causes a model search refresh; navigation then clears the actual input
        // before the queued result arrives. Keep apps/appsView deliberately unbound: the obsolete
        // callback must return before reading or mutating either result destination. The previous
        // implementation reaches the uninitialized apps field and fails this regression.
        input.setText("")
        search.onSearchResult("private space", arrayListOf(AdapterItem(0)))

        assertThat(input.text.toString()).isEmpty()
    }

    // Keep the real delivery method and Android EditText. Only skip construction of the unrelated
    // Compose surface, preferences, and providers; no delivery or query-matching logic is shadowed.
    @Implements(AllAppsSearchInput::class)
    class ShadowSearchInput : ShadowViewGroup() {
        @Implementation
        fun __constructor__(context: Context, attrs: AttributeSet?) = Unit
    }
}
