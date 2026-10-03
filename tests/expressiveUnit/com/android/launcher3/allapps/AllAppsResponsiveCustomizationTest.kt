package com.android.launcher3.allapps

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.deviceprofile.AllAppsProfile
import com.android.launcher3.responsive.CalculatedCellSpec
import com.android.launcher3.responsive.CalculatedResponsiveSpec
import com.android.launcher3.responsive.CellSpec
import com.android.launcher3.responsive.ResponsiveSpec
import com.android.launcher3.responsive.ResponsiveSpec.Companion.ResponsiveSpecType
import com.android.launcher3.responsive.ResponsiveSpec.DimensionType
import com.android.launcher3.responsive.SizeSpec
import com.android.launcher3.util.IconSizeSteps
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AllAppsResponsiveCustomizationTest {

    private fun createTestSpecs(
        iconSize: Int = 60,
        iconTextSize: Int = 14,
        iconDrawablePadding: Int = 8,
        iconTextMaxLineCount: Int = 1,
        cellWidth: Int = 120,
        cellHeight: Int = 140,
        gutter: Int = 16,
    ): Triple<CalculatedCellSpec, CalculatedResponsiveSpec, CalculatedResponsiveSpec> {
        val dummyCellSpec = CellSpec(
            maxAvailableSize = 1000,
            dimensionType = DimensionType.HEIGHT,
            specType = ResponsiveSpecType.Cell,
            iconSize = SizeSpec(fixedSize = 60f),
            iconTextSize = SizeSpec(fixedSize = 14f),
            iconDrawablePadding = SizeSpec(fixedSize = 8f),
            iconTextMaxLineCount = 1,
            iconTextMaxLineCountMatchesWorkspace = false,
        )

        val calculatedCellSpec = CalculatedCellSpec(
            availableSpace = 800,
            spec = dummyCellSpec,
            iconSize = iconSize,
            iconTextSize = iconTextSize,
            iconDrawablePadding = iconDrawablePadding,
            iconTextMaxLineCount = iconTextMaxLineCount,
            iconTextMaxLineCountMatchesWorkspace = false,
        )

        val widthSpecInner = ResponsiveSpec(
            maxAvailableSize = 1000,
            dimensionType = DimensionType.WIDTH,
            specType = ResponsiveSpecType.Workspace,
            startPadding = SizeSpec(fixedSize = 16f),
            endPadding = SizeSpec(fixedSize = 16f),
            gutter = SizeSpec(fixedSize = gutter.toFloat()),
            cellSize = SizeSpec(fixedSize = cellWidth.toFloat()),
        )
        val widthSpec = CalculatedResponsiveSpec(1f, 1000, 5, widthSpecInner)

        val heightSpecInner = ResponsiveSpec(
            maxAvailableSize = 1000,
            dimensionType = DimensionType.HEIGHT,
            specType = ResponsiveSpecType.Workspace,
            startPadding = SizeSpec(fixedSize = 16f),
            endPadding = SizeSpec(fixedSize = 16f),
            gutter = SizeSpec(fixedSize = gutter.toFloat()),
            cellSize = SizeSpec(fixedSize = cellHeight.toFloat()),
        )
        val heightSpec = CalculatedResponsiveSpec(1f, 1000, 5, heightSpecInner)

        return Triple(calculatedCellSpec, widthSpec, heightSpec)
    }

    @Test
    fun createAllAppsWithResponsive_appliesIconSizeFactor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val iconSizeSteps = IconSizeSteps(context.resources)
        val (cellSpec, widthSpec, heightSpec) = createTestSpecs()

        val defaultProfile = AllAppsProfile.createAllAppsWithResponsive(
            responsiveAllAppsCellSpec = cellSpec,
            responsiveAllAppsWidthSpec = widthSpec,
            responsiveAllAppsHeightSpec = heightSpec,
            iconSizeSteps = iconSizeSteps,
            isVerticalBarLayout = false,
            allAppsIconSizeFactor = 1.0f,
            allAppsIconTextSizeFactor = 1.0f,
        )
        assertThat(defaultProfile.iconSizePx).isEqualTo(60)

        val scaledUpProfile = AllAppsProfile.createAllAppsWithResponsive(
            responsiveAllAppsCellSpec = cellSpec,
            responsiveAllAppsWidthSpec = widthSpec,
            responsiveAllAppsHeightSpec = heightSpec,
            iconSizeSteps = iconSizeSteps,
            isVerticalBarLayout = false,
            allAppsIconSizeFactor = 1.25f,
            allAppsIconTextSizeFactor = 1.0f,
        )
        assertThat(scaledUpProfile.iconSizePx).isEqualTo(75)

        val scaledDownProfile = AllAppsProfile.createAllAppsWithResponsive(
            responsiveAllAppsCellSpec = cellSpec,
            responsiveAllAppsWidthSpec = widthSpec,
            responsiveAllAppsHeightSpec = heightSpec,
            iconSizeSteps = iconSizeSteps,
            isVerticalBarLayout = false,
            allAppsIconSizeFactor = 0.8f,
            allAppsIconTextSizeFactor = 1.0f,
        )
        assertThat(scaledDownProfile.iconSizePx).isEqualTo(48)
    }

    @Test
    fun createAllAppsWithResponsive_hidesLabelsWhenTextSizeFactorIsZero() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val iconSizeSteps = IconSizeSteps(context.resources)
        val (cellSpec, widthSpec, heightSpec) = createTestSpecs()

        val noLabelsProfile = AllAppsProfile.createAllAppsWithResponsive(
            responsiveAllAppsCellSpec = cellSpec,
            responsiveAllAppsWidthSpec = widthSpec,
            responsiveAllAppsHeightSpec = heightSpec,
            iconSizeSteps = iconSizeSteps,
            isVerticalBarLayout = false,
            allAppsIconSizeFactor = 1.0f,
            allAppsIconTextSizeFactor = 0f,
        )
        assertThat(noLabelsProfile.iconTextSizePx).isEqualTo(0f)
        assertThat(noLabelsProfile.iconDrawablePaddingPx).isEqualTo(0)
        assertThat(noLabelsProfile.maxAllAppsTextLineCount).isEqualTo(0)
    }

    @Test
    fun floatingHeaderView_setInsets_appliesExactLeftAndRightPadding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val headerView = FloatingHeaderView(context)
        headerView.setPadding(0, 10, 0, 20)

        headerView.setPadding(32, headerView.paddingTop, 48, headerView.paddingBottom)
        assertThat(headerView.paddingLeft).isEqualTo(32)
        assertThat(headerView.paddingRight).isEqualTo(48)
        assertThat(headerView.paddingTop).isEqualTo(10)
        assertThat(headerView.paddingBottom).isEqualTo(20)
    }
}
