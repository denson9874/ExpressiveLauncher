package com.android.launcher3.widget.picker

import android.app.Application
import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.font.FontManager
import app.lawnchair.theme.ThemeProvider
import app.lawnchair.theme.color.SystemColorScheme
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import dev.kdrag0n.monet.theme.ColorScheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        WidgetsTwoPaneSheetLayoutTest.ShadowFontManager::class,
        WidgetsTwoPaneSheetLayoutTest.ShadowThemeProvider::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetsTwoPaneSheetLayoutTest {

    @Test
    fun noWorkProfileLayout_suggestionsHeaderIsFrameLayout() {
        val pane = inflate(R.layout.widgets_two_pane_sheet_recyclerview)

        // WidgetsTwoPaneSheet.setupSuggestedWidgets looks the header up in this container and
        // stores it in a FrameLayout field, so any other container type crashes the picker.
        val searchContainer = pane.findViewById<View>(R.id.search_and_recommendations_container)
        val header = searchContainer.findViewById<View>(R.id.suggestions_header)

        assertThat(header).isInstanceOf(FrameLayout::class.java)
    }

    private fun inflate(layoutRes: Int): FrameLayout {
        // The sheet applies ?attr/widgetsTheme on top of the launcher theme.
        val context = ContextThemeWrapper(
            ContextThemeWrapper(
                ApplicationProvider.getApplicationContext<Application>(),
                R.style.LauncherTheme,
            ),
            R.style.WidgetContainerTheme,
        )
        val pane = FrameLayout(context)
        LayoutInflater.from(context).inflate(layoutRes, pane, true)
        return pane
    }

    // The search bar resolves fonts and its background token while inflating. The lightweight
    // host does not initialize font subscriptions, launcher preferences, or wallpaper colors.
    @Implements(FontManager::class)
    class ShadowFontManager {
        @Implementation
        fun __constructor__(context: Context) = Unit

        @Implementation
        fun overrideFont(textView: TextView, attrs: AttributeSet?) = Unit

        @Implementation
        fun setCustomFont(textView: TextView, type: Int, style: Int) = Unit
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
