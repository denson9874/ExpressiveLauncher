package app.lawnchair.allapps.views

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import app.lawnchair.LawnchairLauncher
import app.lawnchair.font.FontManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.search.adapter.CONTACT
import app.lawnchair.search.adapter.FILES
import app.lawnchair.search.adapter.SearchActionCompat
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.theme.ThemeProvider
import app.lawnchair.theme.color.SystemColorScheme
import com.android.app.search.LayoutType
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.util.Executors
import com.google.common.truth.Truth.assertThat
import dev.kdrag0n.monet.theme.ColorScheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        ContactSearchAccessibilityTest.ShadowFontManager::class,
        ContactSearchAccessibilityTest.ShadowPreferenceManager::class,
        ContactSearchAccessibilityTest.ShadowThemeProvider::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class ContactSearchAccessibilityTest {

    @Test
    fun contactActions_nameTheirActionAndCurrentContactAfterRebinding() {
        val row = inflateRow()
        row.bind(contact("Alex Parity", "2025550100"), emptyList())
        assertActionDescriptions(row, "Alex Parity")

        row.bind(contact("Jordan Example", "2025550101"), emptyList())
        assertActionDescriptions(row, "Jordan Example")
        assertThat(row.findViewById<View>(R.id.icon1).visibility).isEqualTo(View.VISIBLE)
        assertThat(row.findViewById<View>(R.id.icon2).visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun fileRow_keepsContactActionsHidden() {
        val row = inflateRow()
        row.bind(
            target("Report.pdf", "", FILES, SearchTargetCompat.RESULT_TYPE_FILE_TILE, LayoutType.THUMBNAIL),
            emptyList(),
        )

        assertThat(row.titleText.toString()).isEqualTo("Report.pdf")
        assertThat(row.findViewById<View>(R.id.icon1).visibility).isEqualTo(View.GONE)
        assertThat(row.findViewById<View>(R.id.icon2).visibility).isEqualTo(View.GONE)
        assertThat(row.findViewById<View>(R.id.files_preview).visibility).isEqualTo(View.VISIBLE)
    }

    private fun assertActionDescriptions(row: SearchResultRightLeftIcon, name: String) {
        assertThat(accessibleDescription(row.findViewById(R.id.icon1)))
            .isEqualTo("Message $name")
        assertThat(accessibleDescription(row.findViewById(R.id.icon2)))
            .isEqualTo("Call $name")
    }

    private fun accessibleDescription(view: View): String? {
        val node = AccessibilityNodeInfo()
        view.onInitializeAccessibilityNodeInfo(node)
        return node.contentDescription?.toString()
    }

    private fun inflateRow(): SearchResultRightLeftIcon {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        ReflectionHelpers.setField(launcher, "mDeviceProfile", DeviceProfile())
        // Async artwork loading is unrelated to accessible names and requires the full model.
        shadowOf(Executors.MODEL_EXECUTOR.looper).pause()
        val context = ContextThemeWrapper(launcher, R.style.LauncherTheme)
        val row = LayoutInflater.from(context).inflate(
            R.layout.search_result_icon_right_left,
            FrameLayout(context),
            false,
        ) as SearchResultRightLeftIcon
        // Android fills accessibility nodes only after a view is attached to a window.
        Robolectric.buildActivity(Activity::class.java).setup().visible().get().setContentView(row)
        return row
    }

    private fun contact(name: String, number: String) =
        target(name, number, CONTACT, SearchTargetCompat.RESULT_TYPE_CONTACT_TILE, LayoutType.PEOPLE_TILE)

    private fun target(name: String, subtitle: String, packageName: String, resultType: Int, layoutType: String) =
        SearchTargetCompat.Builder(resultType, layoutType, "$name:$subtitle")
            .setPackageName(packageName)
            .setUserHandle(Process.myUserHandle())
            .setExtras(Bundle())
            .setSearchAction(
                SearchActionCompat.Builder(name, name)
                    .setSubtitle(subtitle)
                    .setIntent(Intent(Intent.ACTION_VIEW))
                    .build(),
            )
            .build()

    // Keep the actual row and its bind/click listeners. The lightweight host does not initialize
    // font subscriptions, launcher preferences, or wallpaper-derived colors.
    @Implements(FontManager::class)
    class ShadowFontManager {
        @Implementation
        fun __constructor__(context: Context) = Unit

        @Implementation
        fun overrideFont(textView: TextView, attrs: AttributeSet?) = Unit

        @Implementation
        fun setCustomFont(textView: TextView, type: Int, style: Int) = Unit
    }

    @Implements(PreferenceManager2::class)
    class ShadowPreferenceManager {
        @Implementation
        fun __constructor__(context: Context) = Unit
    }

    @Implements(ThemeProvider::class)
    class ShadowThemeProvider {
        private lateinit var context: Context

        @Implementation
        fun __constructor__(context: Context) {
            this.context = context
        }

        @Implementation
        fun getColorScheme(): ColorScheme = SystemColorScheme(context)
    }
}
