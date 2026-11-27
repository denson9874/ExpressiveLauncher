/*
 * Copyright 2026, Expressive Launcher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.theme

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairApp
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.theme.color.ColorStyle
import app.lawnchair.theme.color.Expressive
import app.lawnchair.theme.color.MonetColorSchemeCompat
import app.lawnchair.theme.color.SystemColorScheme
import app.lawnchair.theme.color.TonalSpot
import app.lawnchair.theme.color.Vibrant
import com.android.systemui.monet.Style
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ThemeProviderSystemPaletteTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var prefs2: PreferenceManager2
    private var themeProvider: ThemeProvider? = null

    @Before
    fun setUp() {
        val app = LawnchairApp()
        ReflectionHelpers.callInstanceMethod<Any>(
            app,
            "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context),
        )
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", app)

        prefs2 = PreferenceManager2.getInstance(context)
        clearColorPreferences()
        prefs2.refreshCachedPreferences()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        themeProvider?.close()
        themeProvider = null
        clearColorPreferences()
        prefs2.refreshCachedPreferences()
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", null)
    }

    @Test(timeout = 30_000)
    fun systemAccent_onAndroid12Plus_usesSystemColorSchemeWithDefaultExpressiveStyle() {
        // Expressive 5.2 defaults color_style to "expressive" while accent_color defaults to SystemAccent.
        assertThat(prefs2.accentColor.defaultValue).isEqualTo(ColorOption.SystemAccent)
        assertThat(prefs2.colorStyle.defaultValue).isEqualTo(Expressive)

        val provider = createThemeProvider()
        val resolved = provider.colorScheme
        val expectedSystem = SystemColorScheme(context)
        val brokenFallback = MonetColorSchemeCompat(0, Style.EXPRESSIVE)

        assertThat(resolved).isInstanceOf(SystemColorScheme::class.java)
        assertThat(resolved.accent1[600]).isEqualTo(expectedSystem.accent1[600])
        assertThat(resolved.accent1[100]).isEqualTo(expectedSystem.accent1[100])
        assertThat(resolved.neutral1[50]).isEqualTo(expectedSystem.neutral1[50])
        assertThat(resolved.neutral1[900]).isEqualTo(expectedSystem.neutral1[900])
        assertThat(resolved.accent1[600]).isNotEqualTo(brokenFallback.accent1[600])
    }

    @Config(sdk = [36, 37])
    @Test(timeout = 30_000)
    fun systemAccent_onAndroid12Plus_usesSystemColorSchemeAcrossAllColorStyles() {
        val provider = createThemeProvider()

        for (style in ColorStyle.values()) {
            writeColorPreferences(accent = ColorOption.SystemAccent, style = style)
            assertThat(provider.colorScheme).isInstanceOf(SystemColorScheme::class.java)
        }
    }

    @Config(sdk = [36, 37])
    @Test(timeout = 30_000)
    fun systemAccent_onOverlayChanged_refreshesSystemColorSchemeAndNotifiesListeners() {
        val provider = createThemeProvider()
        var changeCount = 0
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            ThemeProvider.ColorSchemeChangeListener::class.java.classLoader,
            arrayOf(ThemeProvider.ColorSchemeChangeListener::class.java),
        ) { _, method, _ ->
            if (method.name == "onColorSchemeChanged") {
                changeCount++
            }
            null
        } as ThemeProvider.ColorSchemeChangeListener
        provider.addListener(listener)

        val initialScheme = provider.colorScheme
        assertThat(initialScheme).isInstanceOf(SystemColorScheme::class.java)
        assertThat(provider.colorScheme).isSameInstanceAs(initialScheme)

        // Unrelated package overlays must not disturb the cached system palette.
        context.sendBroadcast(
            Intent("android.intent.action.OVERLAY_CHANGED").apply {
                data = Uri.parse("package:com.example.unrelated")
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(provider.colorScheme).isSameInstanceAs(initialScheme)

        val notificationsBeforeOverlay = changeCount
        val overlayIntent = Intent("android.intent.action.OVERLAY_CHANGED").apply {
            data = Uri.parse("package:android")
        }
        context.sendBroadcast(overlayIntent)
        shadowOf(Looper.getMainLooper()).idle()

        val refreshedScheme = provider.colorScheme
        assertThat(changeCount).isGreaterThan(notificationsBeforeOverlay)
        assertThat(refreshedScheme).isInstanceOf(SystemColorScheme::class.java)
        assertThat(refreshedScheme).isNotSameInstanceAs(initialScheme)
    }

    @Test(timeout = 30_000)
    fun customAccent_usesConfiguredColorStyle_andReturningToSystemRestoresSystemPalette() {
        val customSeed = 0xFFD32F2F.toInt()
        val provider = createThemeProvider()

        writeColorPreferences(accent = ColorOption.CustomColor(customSeed), style = Expressive)
        val customScheme = provider.colorScheme
        assertThat(customScheme).isInstanceOf(MonetColorSchemeCompat::class.java)
        assertThat((customScheme as MonetColorSchemeCompat).style).isEqualTo(Style.EXPRESSIVE)
        assertThat(customScheme.accent1[600])
            .isEqualTo(MonetColorSchemeCompat(customSeed, Style.EXPRESSIVE).accent1[600])

        writeColorPreferences(accent = ColorOption.CustomColor(customSeed), style = Vibrant)
        val vibrantScheme = provider.colorScheme
        assertThat(vibrantScheme).isInstanceOf(MonetColorSchemeCompat::class.java)
        assertThat((vibrantScheme as MonetColorSchemeCompat).style).isEqualTo(Style.VIBRANT)

        // Switching back to System must ignore the lingering Vibrant style and return SystemColorScheme.
        writeColorPreferences(accent = ColorOption.SystemAccent, style = Vibrant)
        assertThat(provider.colorScheme).isInstanceOf(SystemColorScheme::class.java)

        // TonalSpot remains intact for explicit selection as well.
        writeColorPreferences(accent = ColorOption.CustomColor(customSeed), style = TonalSpot)
        val tonalScheme = provider.colorScheme
        assertThat(tonalScheme).isInstanceOf(MonetColorSchemeCompat::class.java)
        assertThat((tonalScheme as MonetColorSchemeCompat).style).isEqualTo(Style.TONAL_SPOT)
    }

    private fun createThemeProvider(): ThemeProvider {
        return ThemeProvider(context).also {
            themeProvider = it
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun writeColorPreferences(accent: ColorOption, style: ColorStyle) {
        runBlocking {
            prefs2.preferencesDataStore.edit {
                it[stringPreferencesKey("accent_color")] = accent.toString()
                it[stringPreferencesKey("color_style")] = style.toString()
            }
        }
        prefs2.refreshCachedPreferences()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clearColorPreferences() {
        runBlocking {
            prefs2.preferencesDataStore.edit {
                it.remove(stringPreferencesKey("accent_color"))
                it.remove(stringPreferencesKey("color_style"))
            }
        }
    }
}
