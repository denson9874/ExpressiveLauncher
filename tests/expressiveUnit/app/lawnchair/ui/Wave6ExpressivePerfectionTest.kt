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

package app.lawnchair.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.os.Looper
import android.view.View
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.qsb.resolveQsbStrokeColor
import app.lawnchair.theme.UiColorMode
import app.lawnchair.theme.buildComposeColorScheme
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.theme.color.MonetColorSchemeCompat
import app.lawnchair.theme.color.SystemColorScheme
import app.lawnchair.theme.color.tokens.ColorTokens
import app.lawnchair.ui.popup.isQpr2HomeMenuDark
import app.lawnchair.ui.preferences.components.ThemeChoice
import app.lawnchair.ui.preferences.destinations.SettingsSearch
import app.lawnchair.ui.preferences.destinations.SettingsSearchIndex
import app.lawnchair.ui.preferences.destinations.SettingsSearchProduct
import app.lawnchair.ui.preferences.destinations.isQuickstepSettingsVisible
import app.lawnchair.ui.preferences.navigation.Quickstep
import app.lawnchair.ui.theme.GoogleSansFlex
import app.lawnchair.ui.theme.shouldUseDynamicSystemColorScheme
import com.android.launcher3.R
import com.android.launcher3.graphics.SysUiScrim
import com.android.launcher3.popup.RoundedArrowDrawable
import com.android.launcher3.shortcuts.DeepShortcutView
import com.android.systemui.monet.Style
import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Wave6ExpressivePerfectionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        val app = LawnchairApp()
        ReflectionHelpers.callInstanceMethod<Any>(
            app,
            "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context),
        )
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", app)
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", null)
    }

    @Test(timeout = 30_000)
    fun darkComposeColorScheme_bindsTertiaryContainerFromMonetNotDefaultPurple() {
        val monet = MonetColorSchemeCompat(0xFF006D3B.toInt(), Style.EXPRESSIVE)
        val darkScheme = monet.buildComposeColorScheme(isDark = true)
        val lightScheme = monet.buildComposeColorScheme(isDark = false)
        val defaultM3DarkTertiaryContainer = ComposeColor(0xFF633B48)
        val defaultM3DarkOnTertiaryContainer = ComposeColor(0xFFFFD8E4)

        assertThat(darkScheme.tertiaryContainer).isNotEqualTo(defaultM3DarkTertiaryContainer)
        assertThat(darkScheme.onTertiaryContainer).isNotEqualTo(defaultM3DarkOnTertiaryContainer)
        // Dark onTertiaryContainer and light tertiaryContainer both map to Monet tertiary(90).
        assertThat(darkScheme.onTertiaryContainer).isEqualTo(lightScheme.tertiaryContainer)
        assertThat(darkScheme.tertiaryContainer).isNotEqualTo(darkScheme.primaryContainer)
        assertThat(darkScheme.tertiaryContainer.alpha).isEqualTo(1f)
    }

    @Test(timeout = 30_000)
    fun dynamicSystemColorScheme_requiresApi34AndSystemAccent() {
        assertThat(
            shouldUseDynamicSystemColorScheme(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                accentColor = ColorOption.SystemAccent,
            ),
        ).isTrue()
        assertThat(
            shouldUseDynamicSystemColorScheme(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                accentColor = ColorOption.SystemAccent,
            ),
        ).isFalse()
        assertThat(
            shouldUseDynamicSystemColorScheme(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                accentColor = ColorOption.CustomColor(0xFF1A73E8.toInt()),
            ),
        ).isFalse()
        assertThat(
            shouldUseDynamicSystemColorScheme(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                accentColor = ColorOption.WallpaperPrimary,
            ),
        ).isFalse()
    }

    @Test(timeout = 30_000)
    fun systemSurfaceTokens_resolveSystemPaletteAndFallbackForCustomScheme() {
        val systemScheme = SystemColorScheme(context)
        val customScheme = MonetColorSchemeCompat(0xFFD32F2F.toInt(), Style.EXPRESSIVE)

        val systemDarkSurface = ColorTokens.SurfaceDark.resolveColor(context, systemScheme, UiColorMode.Dark)
        val expectedSystemSurface = context.getColor(android.R.color.system_surface_dark)
        assertThat(systemDarkSurface).isEqualTo(expectedSystemSurface)

        val systemWidgetCard = ColorTokens.WidgetListRowColor.resolveColor(context, systemScheme, UiColorMode.Dark)
        val expectedSystemCard = context.getColor(android.R.color.system_surface_container_high_dark)
        assertThat(systemWidgetCard).isEqualTo(expectedSystemCard)

        // Custom accent schemes stay on Monet fallback without crashing.
        val customDarkSurface = ColorTokens.SurfaceDark.resolveColor(context, customScheme, UiColorMode.Dark)
        assertThat(Color.alpha(customDarkSurface)).isEqualTo(255)
    }

    @Test(timeout = 30_000)
    fun quickstepSettingsVisibility_andSearchIndex_followRecentsAvailability() {
        assertThat(
            isQuickstepSettingsVisible(
                isRecentsEnabled = true,
                standardHomeOnly = true,
                isDebug = false,
            ),
        ).isTrue()
        assertThat(
            isQuickstepSettingsVisible(
                isRecentsEnabled = false,
                standardHomeOnly = true,
                isDebug = false,
            ),
        ).isFalse()
        assertThat(
            isQuickstepSettingsVisible(
                isRecentsEnabled = false,
                standardHomeOnly = false,
                isDebug = true,
            ),
        ).isTrue()

        val unrootedItems = SettingsSearchIndex.items(context, SettingsSearchProduct.Expressive)
        assertThat(SettingsSearch.filter(unrootedItems, "quickstep")).isEmpty()
        assertThat(SettingsSearch.filter(unrootedItems, "taskbar")).isEmpty()

        val recentsProduct = SettingsSearchProduct.Expressive.copy(recentsEnabled = true)
        val recentsItems = SettingsSearchIndex.items(context, recentsProduct)
        val quickstepMatches = SettingsSearch.filter(recentsItems, "quickstep")
        assertThat(quickstepMatches).isNotEmpty()
        assertThat(quickstepMatches.first().destination).isEqualTo(Quickstep)
        assertThat(SettingsSearch.filter(recentsItems, "taskbar").map { it.destination }).contains(Quickstep)
        assertThat(SettingsSearch.filter(recentsItems, "screenshot").map { it.destination }).contains(Quickstep)
    }

    @Test(timeout = 30_000)
    fun recentsIconMenu_adds10dpSnapshotGapAboveTaskCard() {
        val density = context.resources.displayMetrics.density
        val gapDp = (context.resources.getDimension(R.dimen.task_thumbnail_icon_menu_snapshot_gap) / density).roundToInt()
        val iconSizeDp = (context.resources.getDimension(R.dimen.task_thumbnail_icon_size) / density).roundToInt()

        assertThat(gapDp).isEqualTo(10)
        assertThat(iconSizeDp + gapDp).isAtLeast(52)
    }

    @Test(timeout = 30_000)
    fun qsbStrokeColor_handlesDefaultAndDarkPolarity() {
        assertThat(resolveQsbStrokeColor(context, ColorOption.Default, isDark = false)).isNull()
        assertThat(resolveQsbStrokeColor(context, ColorOption.Default, isDark = true)).isNull()

        val lightAccent = resolveQsbStrokeColor(context, ColorOption.SystemAccent, isDark = false)
        val darkAccent = resolveQsbStrokeColor(context, ColorOption.SystemAccent, isDark = true)
        assertThat(lightAccent).isEqualTo(ColorOption.SystemAccent.colorPreferenceEntry.lightColor(context))
        assertThat(darkAccent).isEqualTo(ColorOption.SystemAccent.colorPreferenceEntry.darkColor(context))
    }

    @Test(timeout = 30_000)
    fun qpr2HomeMenuPolarity_respectsLauncherThemeOverride() {
        assertThat(isQpr2HomeMenuDark(ThemeChoice.LIGHT, systemDark = true)).isFalse()
        assertThat(isQpr2HomeMenuDark(ThemeChoice.DARK, systemDark = false)).isTrue()
        assertThat(isQpr2HomeMenuDark(ThemeChoice.SYSTEM, systemDark = true)).isTrue()
        assertThat(isQpr2HomeMenuDark(ThemeChoice.SYSTEM, systemDark = false)).isFalse()
    }

    @Test(timeout = 30_000)
    fun deepShortcutIconGuard_classifiesWhiteOnWhiteWithoutTouchingColorfulIcons() {
        val solidWhite = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val whiteGlyph = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            for (y in 4 until 12) {
                for (x in 4 until 12) {
                    setPixel(x, y, Color.WHITE)
                }
            }
        }
        val colorfulIcon = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (y in 4 until 12) {
                for (x in 4 until 12) {
                    setPixel(x, y, Color.rgb(0, 140, 255))
                }
            }
        }
        try {
            assertThat(DeepShortcutView.classifyLowContrastIcon(solidWhite))
                .isEqualTo(DeepShortcutView.LOW_CONTRAST_WHITE_SOLID)
            assertThat(DeepShortcutView.classifyLowContrastIcon(whiteGlyph))
                .isEqualTo(DeepShortcutView.LOW_CONTRAST_WHITE_GLYPH)
            assertThat(DeepShortcutView.classifyLowContrastIcon(colorfulIcon))
                .isEqualTo(DeepShortcutView.LOW_CONTRAST_NONE)
        } finally {
            solidWhite.recycle()
            whiteGlyph.recycle()
            colorfulIcon.recycle()
        }
    }

    @Test(timeout = 30_000)
    fun popupArrowDimens_areSoftenedAndConvex() {
        val res = context.resources
        val density = res.displayMetrics.density
        val widthPx = res.getDimension(R.dimen.popup_arrow_width)
        val heightPx = res.getDimension(R.dimen.popup_arrow_height)
        val radiusPx = res.getDimension(R.dimen.popup_arrow_corner_radius)

        assertThat((widthPx / density).roundToInt()).isEqualTo(16)
        assertThat((heightPx / density).roundToInt()).isEqualTo(8)
        assertThat((radiusPx / density).roundToInt()).isEqualTo(3)

        val path = Path()
        RoundedArrowDrawable.addDownPointingRoundedTriangleToPath(widthPx, heightPx, radiusPx, path)
        assertThat(path.isEmpty).isFalse()
    }

    @Test(timeout = 30_000)
    fun googleSansFlex_deduplicatesFontDescriptors_andDpUnitIsLocalized() {
        val families = listOf(
            GoogleSansFlex.Display.Normal.Large,
            GoogleSansFlex.Display.Emphasized.Small,
            GoogleSansFlex.Headline.Emphasized.Large,
            GoogleSansFlex.Title.Normal.Large,
            GoogleSansFlex.Body.Normal.Medium,
            GoogleSansFlex.Label.Emphasized.Large,
        )
        families.forEach { family ->
            val listFamily = family as FontListFontFamily
            assertThat(listFamily.fonts).hasSize(1)
        }

        assertThat(context.getString(R.string.n_dp, 16)).isEqualTo("16 dp")
    }

    @Test(timeout = 30_000)
    fun sysUiScrim_alwaysCreatesTopMaskBitmap() {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        val scrim = SysUiScrim(View(launcher))
        val topMask = ReflectionHelpers.getField<Bitmap?>(scrim, "mTopMaskBitmap")
        assertThat(topMask).isNotNull()
    }
}
