package app.lawnchair.ui.preferences.components.layout

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Vertical placement of a scroll thumb inside its track, in pixels. */
data class ScrollThumb(val top: Float, val height: Float)

/**
 * Settings pages previously gave no hint that more rows sat below the fold. These helpers size
 * a passive scroll thumb so overflow is visible at a glance, and draw nothing when a page fits.
 */
object ScrollIndicatorGeometry {

    /**
     * @param viewportHeight visible height of the scroll container
     * @param contentHeight total height of the scrollable content
     * @param scrollOffset distance scrolled from the top
     * @param minThumbHeight smallest thumb that remains easy to see
     * @param trackHeight height available to the thumb, e.g. the viewport minus the top app bar
     * @return null when the content fits and there is nothing to indicate
     */
    fun thumb(
        viewportHeight: Float,
        contentHeight: Float,
        scrollOffset: Float,
        minThumbHeight: Float,
        trackHeight: Float = viewportHeight,
    ): ScrollThumb? {
        if (viewportHeight <= 0f || trackHeight <= 0f || contentHeight <= viewportHeight) return null
        val maxScroll = contentHeight - viewportHeight
        val height = (trackHeight * viewportHeight / contentHeight)
            .coerceIn(minOf(minThumbHeight, trackHeight), trackHeight)
        val progress = (scrollOffset / maxScroll).coerceIn(0f, 1f)
        return ScrollThumb(top = progress * (trackHeight - height), height = height)
    }

    fun thumb(
        viewportHeight: Float,
        maxScroll: Int,
        scrollOffset: Int,
        minThumbHeight: Float,
        trackHeight: Float = viewportHeight,
    ): ScrollThumb? = thumb(
        viewportHeight = viewportHeight,
        contentHeight = viewportHeight + maxScroll.coerceAtLeast(0),
        scrollOffset = scrollOffset.toFloat(),
        minThumbHeight = minThumbHeight,
        trackHeight = trackHeight,
    )

    /**
     * Lazy lists only measure visible rows, so content height is estimated from the average
     * visible row height. That is accurate for settings pages, whose rows are similar in size.
     */
    fun lazyThumb(
        viewportHeight: Float,
        totalItems: Int,
        visibleItems: Int,
        visibleItemsHeight: Float,
        firstVisibleIndex: Int,
        firstVisibleOffset: Int,
        canScroll: Boolean,
        minThumbHeight: Float,
        trackHeight: Float = viewportHeight,
    ): ScrollThumb? {
        if (!canScroll || totalItems <= 0 || visibleItems <= 0 || visibleItemsHeight <= 0f) return null
        val averageItemHeight = visibleItemsHeight / visibleItems
        val contentHeight = maxOf(averageItemHeight * totalItems, viewportHeight + 1f)
        val scrollOffset = firstVisibleIndex * averageItemHeight + firstVisibleOffset
        return thumb(viewportHeight, contentHeight, scrollOffset, minThumbHeight, trackHeight)
    }
}

private val ThumbWidth = 4.dp
private val ThumbEndPadding = 3.dp
private val MinThumbHeight = 32.dp
private const val IdleAlpha = 0.28f
private const val ScrollingAlpha = 0.6f

/**
 * @param insets padding that content scrolls beneath (top app bar, navigation bar); the thumb
 * stays clear of it so it is never hidden behind the bar.
 */
@Composable
fun Modifier.scrollIndicator(state: ScrollState, insets: PaddingValues = PaddingValues()): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) ScrollingAlpha else IdleAlpha,
        animationSpec = tween(durationMillis = 250),
        label = "scroll indicator alpha",
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val layoutDirection = LocalLayoutDirection.current
    return drawWithContent {
        drawContent()
        val top = insets.calculateTopPadding().toPx()
        val bottom = insets.calculateBottomPadding().toPx()
        val thumb = ScrollIndicatorGeometry.thumb(
            viewportHeight = size.height,
            maxScroll = state.maxValue,
            scrollOffset = state.value,
            minThumbHeight = MinThumbHeight.toPx(),
            trackHeight = size.height - top - bottom,
        ) ?: return@drawWithContent
        drawThumb(thumb, top, color.copy(alpha = color.alpha * alpha), layoutDirection)
    }
}

@Composable
fun Modifier.scrollIndicator(state: LazyListState, insets: PaddingValues = PaddingValues()): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) ScrollingAlpha else IdleAlpha,
        animationSpec = tween(durationMillis = 250),
        label = "scroll indicator alpha",
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val layoutDirection = LocalLayoutDirection.current
    return drawWithContent {
        drawContent()
        val top = insets.calculateTopPadding().toPx()
        val bottom = insets.calculateBottomPadding().toPx()
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        val thumb = ScrollIndicatorGeometry.lazyThumb(
            viewportHeight = size.height,
            totalItems = info.totalItemsCount,
            visibleItems = visible.size,
            visibleItemsHeight = visible.sumOf { it.size }.toFloat(),
            firstVisibleIndex = state.firstVisibleItemIndex,
            firstVisibleOffset = state.firstVisibleItemScrollOffset,
            canScroll = state.canScrollForward || state.canScrollBackward,
            minThumbHeight = MinThumbHeight.toPx(),
            trackHeight = size.height - top - bottom,
        ) ?: return@drawWithContent
        drawThumb(thumb, top, color.copy(alpha = color.alpha * alpha), layoutDirection)
    }
}

private fun DrawScope.drawThumb(
    thumb: ScrollThumb,
    trackTop: Float,
    color: Color,
    layoutDirection: LayoutDirection,
) {
    val width = ThumbWidth.toPx()
    val endPadding = ThumbEndPadding.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) endPadding else size.width - endPadding - width
    drawRoundRect(
        color = color,
        topLeft = Offset(x, trackTop + thumb.top),
        size = Size(width, thumb.height),
        cornerRadius = CornerRadius(width / 2f),
    )
}
