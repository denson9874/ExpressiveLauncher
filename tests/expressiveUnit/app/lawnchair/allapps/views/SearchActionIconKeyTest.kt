package app.lawnchair.allapps.views

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.search.adapter.MARKET_STORE
import app.lawnchair.search.adapter.START_PAGE
import app.lawnchair.search.adapter.WEB_SUGGESTION
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Search action rows ("Search on Google", web suggestions) rebind on every keystroke. Their icon is
 * cached by what it is, so a recycled row never shows the previous row's (Play Store) icon or an
 * empty icon while the model thread is busy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SearchActionIconKeyTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private fun key(
        pkg: String,
        icon: Icon?,
        theme: String = "1:32",
        primaryIconFromTitle: Boolean = false,
        badged: Boolean = false,
    ) = searchActionIconKey(pkg, "UserHandle{0}", "icon_row", icon, primaryIconFromTitle, badged, theme)

    @Test
    fun resourceIconsAreKeyedByResource() {
        val google = Icon.createWithResource(context, R.drawable.ic_launcher_home)
        val again = Icon.createWithResource(context, R.drawable.ic_launcher_home)
        val other = Icon.createWithResource(context, R.drawable.ic_allapps_search)

        assertThat(key(START_PAGE, google)).isNotNull()
        assertThat(key(START_PAGE, google)).isEqualTo(key(START_PAGE, again))
        assertThat(key(START_PAGE, google)).isNotEqualTo(key(START_PAGE, other))
        // The Play Store row never shares the web-search row's icon.
        assertThat(key(START_PAGE, google)).isNotEqualTo(key(MARKET_STORE, google))
    }

    @Test
    fun suggestionBitmapsShareOneKeyButOtherBitmapsAreNotCached() {
        val first = key(WEB_SUGGESTION, Icon.createWithAdaptiveBitmap(bitmap))
        assertThat(first).isNotNull()
        assertThat(key(WEB_SUGGESTION, Icon.createWithAdaptiveBitmap(bitmap))).isEqualTo(first)
        // Contact photos and file previews differ per result.
        assertThat(key("contacts", Icon.createWithBitmap(bitmap))).isNull()
    }

    @Test
    fun iconsThatNeedThePackageIconAreNotCached() {
        val icon = Icon.createWithResource(context, R.drawable.ic_launcher_home)
        assertThat(key(START_PAGE, null)).isNull()
        assertThat(key(START_PAGE, icon, primaryIconFromTitle = true)).isNull()
        assertThat(key(START_PAGE, icon, badged = true)).isNull()
    }

    @Test
    fun themeChangeUsesNewKeys() {
        val icon = Icon.createWithResource(context, R.drawable.ic_launcher_home)
        assertThat(key(START_PAGE, icon, theme = "1:16")).isNotEqualTo(key(START_PAGE, icon, theme = "1:32"))
        assertThat(key(START_PAGE, icon, theme = "2:32")).isNotEqualTo(key(START_PAGE, icon, theme = "1:32"))
    }

    @Test
    fun shortcutKeysChangeWhenTheShortcutChanges() {
        val home = searchShortcutIconKey("com.google.android.apps.maps", "home", "u0", 10L, "1:32")
        assertThat(searchShortcutIconKey("com.google.android.apps.maps", "home", "u0", 10L, "1:32")).isEqualTo(home)
        assertThat(searchShortcutIconKey("com.google.android.apps.maps", "work", "u0", 10L, "1:32")).isNotEqualTo(home)
        assertThat(searchShortcutIconKey("com.google.android.apps.maps", "home", "u0", 11L, "1:32")).isNotEqualTo(home)
        assertThat(searchShortcutIconKey("com.google.android.apps.maps", "home", "u10", 10L, "1:32")).isNotEqualTo(home)
    }
}
