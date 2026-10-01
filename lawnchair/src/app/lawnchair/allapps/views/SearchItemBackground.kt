package app.lawnchair.allapps.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import app.lawnchair.theme.color.tokens.ColorTokens
import com.android.launcher3.R
import com.android.systemui.shared.system.BlurUtils
import com.android.systemui.util.dpToPx
import kotlin.math.roundToInt

class SearchItemBackground(
    context: Context,
    showBackground: Boolean,
    roundTop: Boolean,
    roundBottom: Boolean,
) {
    private val resources = context.resources

    private val searchDecorationPadding = resources.getDimensionPixelSize(R.dimen.search_decoration_padding)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpPath = Path()
    private val tmpRect = RectF()

    val supportBlur = BlurUtils.supportsBlursOnWindows()
    val focusHighlight = if (supportBlur) {
        ColorTokens.FocusHighlightBlur.resolveColor(context)
    } else {
        ColorTokens.FocusHighlight.resolveColor(context)
    }
    val groupHighlight = if (showBackground) {
        if (supportBlur) {
            ColorTokens.GroupHighlightBlur.resolveColor(context)
        } else {
            ColorTokens.GroupHighlight.resolveColor(context)
        }
    } else {
        0
    }

    val cornerRadii: FloatArray

    init {
        val searchGroupRadius = resources.getDimensionPixelSize(R.dimen.search_group_radius).toFloat()
        val searchResultRadius = resources.getDimensionPixelSize(R.dimen.search_result_radius).toFloat()

        val topRadius = if (roundTop) searchGroupRadius else searchResultRadius
        val bottomRadius = if (roundBottom) searchGroupRadius else searchResultRadius

        cornerRadii = floatArrayOf(
            topRadius,
            topRadius,
            topRadius,
            topRadius,
            bottomRadius,
            bottomRadius,
            bottomRadius,
            bottomRadius,
        )
    }

    fun draw(c: Canvas, child: View, isFocused: Boolean) {
        val color = if (isFocused) focusHighlight else groupHighlight
        if (color == 0) return

        // The search-enter animation fades, scales (pivot at the top) and moves each row. Draw the
        // card where and as opaque as its content is drawn; a full card around invisible content
        // showed up as blank cards while the first results animated in.
        val alpha = searchCardAlpha(Color.alpha(color), rowContentAlpha(child))
        if (alpha == 0) return
        paint.color = color
        paint.alpha = alpha

        var left = child.left.toFloat() + searchDecorationPadding
        var top = child.y + searchDecorationPadding
        var right = child.right.toFloat() - searchDecorationPadding
        var bottom = child.y + child.height * child.scaleY - searchDecorationPadding

        if (child is SearchResultIcon) {
            val iconSize = child.iconSize.toFloat()
            val desiredWidth = iconSize + 48.dpToPx(resources)
            val cellWidth = child.width.toFloat()
            if (desiredWidth < cellWidth) {
                val inset = (cellWidth - desiredWidth) / 2
                left += inset
                right -= inset
            }
            val isTwoLine = child.lineCount > 1
            val insetTop = 6.dpToPx(resources)
            val insetBottom = if (isTwoLine) 6.dpToPx(resources) else 0.dpToPx(resources)
            top += insetTop
            bottom -= insetBottom
        }

        if (bottom <= top) return
        tmpRect.set(left, top, right, bottom)

        tmpPath.reset()
        tmpPath.addRoundRect(tmpRect, cornerRadii, Path.Direction.CW)

        c.drawPath(tmpPath, paint)
    }
}

/** Card alpha for a row drawn at [rowAlpha]: the card fades with its content. */
internal fun searchCardAlpha(colorAlpha: Int, rowAlpha: Float): Int =
    (colorAlpha * rowAlpha.coerceIn(0f, 1f)).roundToInt()

/**
 * How opaque a row's content is drawn. For rows with a background (the result ripple) the
 * animation keeps the row opaque and fades its children instead (RecyclerViewAnimationController).
 */
internal fun rowContentAlpha(row: View): Float {
    if (row !is ViewGroup || row.background == null || row.childCount == 0) return row.alpha
    var childAlpha = 0f
    for (i in 0 until row.childCount) {
        val child = row.getChildAt(i)
        if (child.visibility == View.VISIBLE) childAlpha = maxOf(childAlpha, child.alpha)
    }
    return row.alpha * childAlpha
}
