package app.lawnchair.folder.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R
import com.android.launcher3.util.Themes
import kotlin.math.max
import kotlin.math.roundToInt

/** Sizes a Folder widget panel takes from Home (see [FolderWidgetGridMath]). */
data class FolderWidgetMetrics(
    val spanX: Int,
    val homeIconSizePx: Int,
    val labelHeightPx: Int,
    val iconLabelGapPx: Int,
    val rowSpacingPx: Int,
    val minTouchPx: Int,
    val paddingPx: Int,
    val headerHeightPx: Int,
)

/**
 * The visible part of a Folder widget: a rounded panel that fills its cells, the folder name on
 * top and a centered grid of the folder's apps that scrolls vertically when they don't all fit.
 * Every grid item is sized to one grid cell, so the grid lines up with [gridSpec].
 */
class FolderWidgetPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val textColor = Themes.getAttrColor(context, R.attr.folderTextColor)
    private val panelBackground = GradientDrawable()
    private val gridLayout = CellSizedGridLayoutManager(context)

    val recyclerView: RecyclerView = RecyclerView(context).apply {
        layoutManager = gridLayout
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = true
        scrollBarStyle = SCROLLBARS_INSIDE_OVERLAY
        setVerticalScrollbarThumbDrawable(
            GradientDrawable().apply {
                setColor(ColorUtils.setAlphaComponent(textColor, 0x66))
                cornerRadius = context.dp(2).toFloat()
            },
        )
    }

    val header: TextView = TextView(context).apply {
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
    }

    val openButton: ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_folder_widget_open)
        imageTintList = ColorStateList.valueOf(textColor)
        contentDescription = context.getString(R.string.folder_widget_open_folder)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = context.dp(7)
        setPadding(inset, inset, inset, inset)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(textColor, 0x22))
        }
    }

    val addAppsButton: TextView = TextView(context).apply {
        text = context.getString(R.string.folder_widget_add_apps)
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        val h = context.dp(16)
        val v = context.dp(8)
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(20).toFloat()
            setColor(ColorUtils.setAlphaComponent(textColor, 0x22))
        }
        visibility = GONE
    }

    var onOpenFolder: (() -> Unit)? = null
    var onAddApps: (() -> Unit)? = null

    var gridSpec: FolderWidgetGridSpec = FolderWidgetGridMath.compute(
        FolderWidgetGridInput(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1),
    )
        private set

    val isScrollable: Boolean get() = gridSpec.scrollable

    private var style: ResolvedFolderWidgetStyle? = null
    private var metrics: FolderWidgetMetrics? = null

    private val contentObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = onContentChanged()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = onContentChanged()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = onContentChanged()
    }

    init {
        background = panelBackground
        addView(header)
        addView(recyclerView)
        addView(addAppsButton)
        addView(openButton)
        header.setOnClickListener { onOpenFolder?.invoke() }
        openButton.setOnClickListener { onOpenFolder?.invoke() }
        addAppsButton.setOnClickListener { onAddApps?.invoke() }
    }

    fun bind(
        title: CharSequence,
        adapter: RecyclerView.Adapter<*>,
        style: ResolvedFolderWidgetStyle,
        metrics: FolderWidgetMetrics,
    ) {
        header.text = title
        if (recyclerView.adapter !== adapter) {
            recyclerView.adapter?.unregisterAdapterDataObserver(contentObserver)
            adapter.registerAdapterDataObserver(contentObserver)
            recyclerView.adapter = adapter
        }
        this.style = style
        this.metrics = metrics
        panelBackground.cornerRadius = style.cornerRadiusPx
        panelBackground.setColor(style.backgroundColor)
        panelBackground.alpha = (style.backgroundOpacity * 255).roundToInt()
        header.visibility = if (style.showHeader) VISIBLE else GONE
        openButton.visibility = if (style.showHeader) GONE else VISIBLE
        onContentChanged()
    }

    fun scrollToEnd() {
        val count = recyclerView.adapter?.itemCount ?: 0
        if (count > 0) recyclerView.smoothScrollToPosition(count - 1)
    }

    private fun onContentChanged() {
        addAppsButton.visibility = if ((recyclerView.adapter?.itemCount ?: 0) == 0) VISIBLE else GONE
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val m = metrics ?: return
        val s = style ?: return
        val headerHeight = if (s.showHeader) m.headerHeightPx else 0
        val spec = FolderWidgetGridMath.compute(
            FolderWidgetGridInput(
                widthPx = width,
                heightPx = height,
                spanX = m.spanX,
                itemCount = recyclerView.adapter?.itemCount ?: 0,
                paddingPx = m.paddingPx,
                headerHeightPx = headerHeight,
                homeIconSizePx = m.homeIconSizePx,
                labelHeightPx = m.labelHeightPx,
                iconLabelGapPx = m.iconLabelGapPx,
                rowSpacingPx = m.rowSpacingPx,
                minTouchPx = m.minTouchPx,
                columnOverride = s.columns,
                showLabels = s.showLabels,
                iconScale = s.iconScale,
            ),
        )
        applySpec(spec)
        val innerWidth = max(0, width - 2 * m.paddingPx)
        val gridHeight = max(0, spec.viewportHeightPx - (spec.gridTopPx - m.paddingPx - headerHeight))
        header.measure(exactly(innerWidth), exactly(headerHeight))
        recyclerView.measure(exactly(spec.gridWidthPx), exactly(gridHeight))
        val button = context.dp(OPEN_BUTTON_DP)
        openButton.measure(exactly(button), exactly(button))
        addAppsButton.measure(
            MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(max(0, spec.viewportHeightPx), MeasureSpec.AT_MOST),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val m = metrics ?: return
        val s = style ?: return
        val spec = gridSpec
        val width = right - left
        val height = bottom - top
        val headerHeight = if (s.showHeader) m.headerHeightPx else 0
        header.layout(m.paddingPx, m.paddingPx, m.paddingPx + header.measuredWidth, m.paddingPx + headerHeight)
        recyclerView.layout(
            spec.gridLeftPx,
            spec.gridTopPx,
            spec.gridLeftPx + recyclerView.measuredWidth,
            spec.gridTopPx + recyclerView.measuredHeight,
        )
        openButton.layout(
            width - m.paddingPx - openButton.measuredWidth,
            height - m.paddingPx - openButton.measuredHeight,
            width - m.paddingPx,
            height - m.paddingPx,
        )
        val centerX = width / 2
        val centerY = m.paddingPx + headerHeight + spec.viewportHeightPx / 2
        val addWidth = addAppsButton.measuredWidth
        val addHeight = addAppsButton.measuredHeight
        addAppsButton.layout(
            centerX - addWidth / 2,
            centerY - addHeight / 2,
            centerX - addWidth / 2 + addWidth,
            centerY - addHeight / 2 + addHeight,
        )
    }

    private fun applySpec(spec: FolderWidgetGridSpec) {
        gridSpec = spec
        gridLayout.scrollEnabled = spec.scrollable
        recyclerView.isNestedScrollingEnabled = spec.scrollable
        if (gridLayout.spanCount != spec.columns) gridLayout.spanCount = spec.columns
        if (gridLayout.rowHeightPx != spec.rowHeightPx) {
            gridLayout.rowHeightPx = spec.rowHeightPx
            for (i in 0 until recyclerView.childCount) {
                recyclerView.getChildAt(i).layoutParams.height = spec.rowHeightPx
            }
        }
    }

    /** Sizes every item to one grid cell and scrolls only when the apps overflow the panel. */
    private class CellSizedGridLayoutManager(context: Context) : GridLayoutManager(context, 1) {
        var rowHeightPx = ViewGroup.LayoutParams.WRAP_CONTENT
        var scrollEnabled = false

        override fun canScrollVertically() = scrollEnabled && super.canScrollVertically()

        override fun checkLayoutParams(lp: RecyclerView.LayoutParams?) = super.checkLayoutParams(lp) &&
            lp!!.width == ViewGroup.LayoutParams.MATCH_PARENT && lp.height == rowHeightPx

        override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
            super.generateDefaultLayoutParams().cellSized()

        override fun generateLayoutParams(lp: ViewGroup.LayoutParams?): RecyclerView.LayoutParams =
            super.generateLayoutParams(lp).cellSized()

        override fun generateLayoutParams(c: Context?, attrs: AttributeSet?): RecyclerView.LayoutParams =
            super.generateLayoutParams(c, attrs).cellSized()

        private fun RecyclerView.LayoutParams.cellSized() = apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = rowHeightPx
        }
    }

    private companion object {
        const val OPEN_BUTTON_DP = 32

        fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(max(0, size), MeasureSpec.EXACTLY)
    }
}

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
