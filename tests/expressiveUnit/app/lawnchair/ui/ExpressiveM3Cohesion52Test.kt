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

import android.animation.ValueAnimator
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.LinearLayout
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairApp
import app.lawnchair.allapps.views.ContactSearchAccessibilityTest
import app.lawnchair.theme.color.ColorStyle
import app.lawnchair.theme.color.Expressive
import app.lawnchair.ui.preferences.components.controls.SliderHapticOutcome
import app.lawnchair.ui.preferences.components.controls.SliderThreshold
import app.lawnchair.ui.preferences.components.controls.evaluateSliderHaptic
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItemCornerRadii
import app.lawnchair.ui.theme.ExpressiveGroupInnerCornerRadius
import app.lawnchair.ui.theme.ExpressiveGroupOuterCornerRadius
import app.lawnchair.ui.theme.GoogleSansFlex
import app.lawnchair.ui.theme.Typography
import app.lawnchair.util.BackdropGlass
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.popup.ArrowPopup
import com.android.launcher3.shortcuts.DeepShortcutView
import com.android.launcher3.util.BaseContext
import com.android.launcher3.util.Themes
import com.android.launcher3.util.TouchController
import com.android.launcher3.views.BaseDragLayer
import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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
class ExpressiveM3Cohesion52Test {

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
    fun expressiveFlavor_defaultsColorStyleToExpressive() {
        val defaultStyleKey = context.getString(R.string.config_default_color_style)
        assertThat(defaultStyleKey).isEqualTo("expressive")
        assertThat(ColorStyle.fromString(defaultStyleKey)).isEqualTo(Expressive)
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Test(timeout = 30_000)
    fun typography_usesSmallOpticalFamiliesForSmallDisplayAndHeadline() {
        assertThat(Typography.displaySmall.fontFamily).isEqualTo(GoogleSansFlex.Display.Emphasized.Small)
        assertThat(Typography.displaySmallEmphasized.fontFamily).isEqualTo(GoogleSansFlex.Display.Emphasized.Small)
        assertThat(Typography.headlineSmall.fontFamily).isEqualTo(GoogleSansFlex.Headline.Emphasized.Small)
        assertThat(Typography.headlineSmallEmphasized.fontFamily).isEqualTo(GoogleSansFlex.Headline.Emphasized.Small)
    }

    @Test(timeout = 30_000)
    fun preferenceSegmentedGroup_uses24dpOuterAnd4dpInnerRadii() {
        assertThat(ExpressiveGroupOuterCornerRadius).isEqualTo(24.dp)
        assertThat(ExpressiveGroupInnerCornerRadius).isEqualTo(4.dp)

        assertThat(preferenceGroupItemCornerRadii(cutTop = false, cutBottom = false))
            .isEqualTo(24.dp to 24.dp)
        assertThat(preferenceGroupItemCornerRadii(cutTop = false, cutBottom = true))
            .isEqualTo(24.dp to 4.dp)
        assertThat(preferenceGroupItemCornerRadii(cutTop = true, cutBottom = true))
            .isEqualTo(4.dp to 4.dp)
        assertThat(preferenceGroupItemCornerRadii(cutTop = true, cutBottom = false))
            .isEqualTo(4.dp to 24.dp)
    }

    @Test(timeout = 30_000)
    fun sliderHapticDecision_gatesDiscreteStepsAndSingleFiresBoundaries() {
        val range = 0f..1f
        val step = 0.25f

        // Sub-step drag jitter must not spam discrete step haptics.
        val jitter = evaluateSliderHaptic(
            newValue = 0.08f,
            valueRange = range,
            step = step,
            previousThreshold = null,
            lastHapticValue = 0f,
        )
        assertThat(jitter.outcome).isEqualTo(SliderHapticOutcome.NONE)
        assertThat(jitter.nextLastHapticValue).isEqualTo(0f)

        // Crossing past midpoint snaps to 0.25f and emits exactly one discrete tick.
        val stepTick = evaluateSliderHaptic(
            newValue = 0.21f,
            valueRange = range,
            step = step,
            previousThreshold = jitter.nextThreshold,
            lastHapticValue = jitter.nextLastHapticValue,
        )
        assertThat(stepTick.outcome).isEqualTo(SliderHapticOutcome.STEP_DISCRETE)
        assertThat(stepTick.nextLastHapticValue).isEqualTo(0.25f)

        // Further movement within the same snapped step stays silent.
        val sameStep = evaluateSliderHaptic(
            newValue = 0.29f,
            valueRange = range,
            step = step,
            previousThreshold = stepTick.nextThreshold,
            lastHapticValue = stepTick.nextLastHapticValue,
        )
        assertThat(sameStep.outcome).isEqualTo(SliderHapticOutcome.NONE)

        // Hitting upper limit fires LIMIT_END once, then stays silent while held at limit.
        val hitEnd = evaluateSliderHaptic(
            newValue = 1f,
            valueRange = range,
            step = step,
            previousThreshold = sameStep.nextThreshold,
            lastHapticValue = sameStep.nextLastHapticValue,
        )
        assertThat(hitEnd.outcome).isEqualTo(SliderHapticOutcome.LIMIT_END)
        assertThat(hitEnd.nextThreshold).isEqualTo(SliderThreshold.END)

        val repeatEnd = evaluateSliderHaptic(
            newValue = 1f,
            valueRange = range,
            step = step,
            previousThreshold = hitEnd.nextThreshold,
            lastHapticValue = hitEnd.nextLastHapticValue,
        )
        assertThat(repeatEnd.outcome).isEqualTo(SliderHapticOutcome.NONE)
    }

    @Test(timeout = 30_000)
    fun backdropGlass_enforcesWcagAlphaFloorsForLightAndDarkSurfaces() {
        val lightSurface = Color.rgb(245, 245, 250)
        val darkSurface = Color.rgb(28, 28, 34)

        assertThat(BackdropGlass.pieceAlphaForBase(lightSurface)).isAtLeast(199) // >= 78%
        assertThat(BackdropGlass.pieceAlphaForBase(darkSurface)).isAtLeast(210) // >= 82%
    }

    @Test(timeout = 30_000)
    fun resourceTokens_alignSheetPopupSearchAndOverviewRadii() {
        val res = context.resources
        val density = res.displayMetrics.density

        fun dpPx(dimenRes: Int): Int = (res.getDimension(dimenRes) / density).roundToInt()

        assertThat(dpPx(R.dimen.lawnchair_dialog_corner_radius)).isEqualTo(28)
        assertThat(dpPx(R.dimen.default_dialog_corner_radius)).isEqualTo(28)
        assertThat((Themes.getDialogCornerRadius(context) / density).roundToInt()).isEqualTo(28)

        assertThat(dpPx(R.dimen.popup_outer_radius)).isEqualTo(24)
        assertThat(dpPx(R.dimen.popup_smaller_radius)).isEqualTo(4)
        assertThat(dpPx(R.dimen.popup_margin)).isEqualTo(2)
        assertThat(dpPx(R.dimen.task_menu_corner_radius)).isEqualTo(24)
        assertThat(dpPx(R.dimen.task_menu_item_corner_radius)).isEqualTo(4)

        assertThat(dpPx(R.dimen.search_group_radius)).isEqualTo(24)
        assertThat(dpPx(R.dimen.search_result_radius)).isEqualTo(4)
        assertThat(dpPx(R.dimen.all_apps_header_pill_corner_radius)).isEqualTo(100)
        assertThat(dpPx(R.dimen.overview_actions_button_radius)).isEqualTo(1000)

        val blurPillColor = ContextCompat.getColor(context, R.color.overview_action_button_blur_bg)
        assertThat(Color.alpha(blurPillColor)).isAtLeast(199)
    }

    @Test(timeout = 30_000)
    fun arrowPopup_segmentsMultiRowShortcutContainersAndHonorsReducedMotion() {
        val uiContext = TestPopupContext(context)
        val popup = TestArrowPopup(uiContext)
        val inflater = LayoutInflater.from(uiContext)
        val rowsContainer = inflater.inflate(
            R.layout.deep_shortcut_container,
            popup,
            false,
        ) as LinearLayout
        popup.addView(rowsContainer)

        val rows = (0 until 3).map {
            (inflater.inflate(R.layout.deep_shortcut, rowsContainer, false) as DeepShortcutView).also { row ->
                rowsContainer.addView(row)
            }
        }

        popup.assignMarginsAndBackgrounds(popup)

        val expectedOuter = uiContext.resources.getDimension(R.dimen.popup_outer_radius)
        val expectedInner = uiContext.resources.getDimensionPixelSize(R.dimen.popup_smaller_radius).toFloat()
        val expectedGap = uiContext.resources.getDimensionPixelSize(R.dimen.popup_margin)

        // Parent wrapper background must be cleared so 2dp gaps remain transparent.
        assertThat(rowsContainer.background).isNull()

        val firstRadii = (rows[0].background as GradientDrawable).cornerRadii!!
        assertThat(firstRadii.take(4)).containsExactly(expectedOuter, expectedOuter, expectedOuter, expectedOuter)
        assertThat(firstRadii.drop(4)).containsExactly(expectedInner, expectedInner, expectedInner, expectedInner)
        assertThat((rows[0].layoutParams as LinearLayout.LayoutParams).bottomMargin).isEqualTo(expectedGap)

        assertThat(rows[1].background).isInstanceOf(GradientDrawable::class.java)
        assertThat((rows[1].layoutParams as LinearLayout.LayoutParams).bottomMargin).isEqualTo(expectedGap)

        val lastRadii = (rows[2].background as GradientDrawable).cornerRadii!!
        assertThat(lastRadii.take(4)).containsExactly(expectedInner, expectedInner, expectedInner, expectedInner)
        assertThat(lastRadii.drop(4)).containsExactly(expectedOuter, expectedOuter, expectedOuter, expectedOuter)
        assertThat((rows[2].layoutParams as LinearLayout.LayoutParams).bottomMargin).isEqualTo(0)

        val previousScale = ValueAnimator.getDurationScale()
        try {
            ValueAnimator.setDurationScale(0f)
            popup.scaleX = 0.5f
            popup.scaleY = 0.5f
            popup.alpha = 0f
            popup.openForTest()
            assertThat(popup.alpha).isEqualTo(1f)
            assertThat(popup.scaleX).isEqualTo(1f)
            assertThat(popup.scaleY).isEqualTo(1f)

            popup.closeForTest()
            assertThat(popup.isOpen).isFalse()
        } finally {
            ValueAnimator.setDurationScale(previousScale)
        }
    }

    private class TestPopupContext(base: Context) :
        BaseContext(base, R.style.LauncherTheme, destroyOnDetach = false) {
        private val profile = DeviceProfile()
        private val dragLayer = object : BaseDragLayer<TestPopupContext>(this, null, 1) {
            override fun recreateControllers() {
                super.recreateControllers()
                mControllers = emptyArray<TouchController>()
            }
        }

        override fun getDragLayer(): BaseDragLayer<*> = dragLayer

        override fun getDeviceProfile(): DeviceProfile = profile
    }

    private class TestArrowPopup(uiContext: TestPopupContext) : ArrowPopup<TestPopupContext>(uiContext) {
        override fun onControllerInterceptTouchEvent(ev: MotionEvent?): Boolean = false

        override fun isOfType(type: Int): Boolean = (type and TYPE_ACTION_POPUP) != 0

        override fun getTargetObjectLocation(outPos: Rect) {
            outPos.set(40, 200, 140, 300)
        }

        fun openForTest() {
            mIsOpen = true
            animateOpen()
        }

        fun closeForTest() {
            animateClose()
        }
    }
}
