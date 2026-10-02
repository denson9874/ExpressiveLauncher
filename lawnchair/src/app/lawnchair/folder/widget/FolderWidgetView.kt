package app.lawnchair.folder.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.pro.ProManager
import app.lawnchair.util.resolveFolderBackgroundColor
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.ItemInfo
import java.util.function.Predicate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A Home folder shown as a Folder widget: a [FolderWidgetPanel] with the folder's apps as a grid
 * that fills the folder's cells. It is a [FolderIcon], so folder behaviour (open, drop to add,
 * dots, accessibility) keeps working; the 1x1 folder preview is never drawn.
 */
class FolderWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FolderIcon(context, attrs) {

    lateinit var panel: FolderWidgetPanel
        private set

    private var appsAdapter: FolderWidgetAppsAdapter? = null
    private var style: ResolvedFolderWidgetStyle? = null
    private var appliedSpec: FolderWidgetGridSpec? = null
    private var labelLineHeightPx = -1
    private var boundSpanX = -1

    /** The panel's corner radius, for the resize frame. */
    val cornerRadiusPx: Float get() = style?.cornerRadiusPx ?: 0f

    override fun onFinishInflate() {
        super.onFinishInflate()
        panel = findViewById(R.id.folder_widget_panel)
        panel.onOpenFolder = ::openFolder
        // FolderIcon draws its 1x1 preview, background and dot only while its icon is visible.
        super.setIconVisible(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bindPanel()
    }

    /** Applies a widget style (the defaults until per-widget styles are stored). */
    fun applyStyle(style: ResolvedFolderWidgetStyle) {
        this.style = style
        bindPanel()
    }

    private fun bindPanel() {
        val info = mInfo ?: return
        val activity = mActivity ?: return
        val adapter = appsAdapter
            ?: FolderWidgetAppsAdapter(activity, info, { panel.gridSpec }) { false }.also { appsAdapter = it }
        val style = style ?: defaultStyle().also { style = it }
        boundSpanX = currentSpanX()
        panel.bind(info.title ?: "", adapter, style, metrics(boundSpanX))
        setTextVisible(true)
    }

    /** While resizing, the cell span changes before the item's span is committed. */
    private fun currentSpanX(): Int =
        (layoutParams as? CellLayoutLayoutParams)?.cellHSpan?.takeIf { it > 0 } ?: mInfo.spanX

    private fun openFolder() {
        val folder = folder ?: return
        if (!folder.isOpen && !folder.isDestroyed) folder.animateOpen()
    }

    private fun defaultStyle(): ResolvedFolderWidgetStyle {
        val defaults = FolderWidgetDefaults(
            backgroundColor = resolveFolderBackgroundColor(context),
            backgroundOpacity = PreferenceManager2.getInstance(context).folderBackgroundOpacity.firstCached(),
            cornerRadiusPx = resources.getDimension(android.R.dimen.system_app_widget_background_radius),
        )
        return FolderWidgetStyle().resolve(ProManager.INSTANCE.get(context).isPro.value, defaults)
    }

    private fun metrics(spanX: Int): FolderWidgetMetrics {
        val dp = mActivity.deviceProfile
        val density = resources.displayMetrics.density
        fun px(value: Int) = (value * density).roundToInt()
        return FolderWidgetMetrics(
            spanX = spanX,
            homeIconSizePx = dp.iconSizePx,
            labelHeightPx = labelLineHeight(),
            iconLabelGapPx = dp.folderChildDrawablePaddingPx,
            rowSpacingPx = px(8),
            minTouchPx = px(48),
            paddingPx = px(12),
            headerHeightPx = px(40),
        )
    }

    /** One label line of a folder app icon, measured with that icon's own paint and font. */
    private fun labelLineHeight(): Int {
        if (labelLineHeightPx < 0) {
            val probe = LayoutInflater.from(context).inflate(R.layout.folder_application, null) as BubbleTextView
            val fm = probe.paint.fontMetrics
            labelLineHeightPx = ceil(fm.bottom - fm.top).toInt()
        }
        return labelLineHeightPx
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        if (boundSpanX != -1 && boundSpanX != currentSpanX()) bindPanel()
        val name = folderName ?: return panel.measure(exactly(width), exactly(height))
        (name.layoutParams as LayoutParams).apply {
            // FolderIcon places its label under a 1x1 icon; here it sits under the panel.
            topMargin = 0
            gravity = Gravity.BOTTOM
        }
        var panelHeight = height
        if (name.visibility != GONE) {
            name.measure(exactly(width), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            panelHeight = max(0, height - name.measuredHeight)
        }
        panel.measure(exactly(width), exactly(panelHeight))
        refreshGridIcons()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Folder animations read the 1x1 preview geometry, which FolderIcon computes while
        // drawing its preview; a widget never draws it, so compute it here.
        previewItemManager.recomputePreviewDrawingParams()
    }

    /** Re-sizes the bound icons when the grid's icon size, labels or row height change. */
    private fun refreshGridIcons() {
        val spec = panel.gridSpec
        if (spec == appliedSpec) return
        appliedSpec = spec
        val adapter = appsAdapter ?: return
        val grid = panel.recyclerView
        for (i in 0 until grid.childCount) {
            (grid.getChildAt(i) as? BubbleTextView)?.let(adapter::applyGridSpec)
        }
    }

    override fun getPreviewBounds(outBounds: Rect) {
        outBounds.set(panel.left, panel.top, panel.right, panel.bottom)
    }

    /** The panel's bounds in drag-layer coordinates. */
    fun panelRectInDragLayer(out: Rect) {
        mActivity.dragLayer.getDescendantRectRelativeToSelf(panel, out)
    }

    /** An app icon that matches and is fully visible in the grid, if any. */
    fun visibleIconFor(match: Predicate<ItemInfo>): View? {
        val grid = panel.recyclerView
        if (!grid.isShown) return null
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val info = child.tag as? ItemInfo ?: continue
            if (match.test(info) && child.top >= 0 && child.bottom <= grid.height) return child
        }
        return null
    }

    override fun onItemsChanged(animate: Boolean) {
        super.onItemsChanged(animate)
        appsAdapter?.notifyDataSetChanged()
    }

    override fun onTitleChanged(title: CharSequence?) {
        super.onTitleChanged(title)
        panel.header.text = title ?: ""
    }

    /** The folder name under the panel shows only with "Show name below". */
    override fun setTextVisible(visible: Boolean) {
        val name = folderName ?: return
        name.visibility = when {
            style?.showNameBelow != true -> GONE
            visible -> VISIBLE
            else -> INVISIBLE
        }
    }

    override fun updateDotInfo() {
        super.updateDotInfo()
        if (!::panel.isInitialized) return
        val grid = panel.recyclerView
        for (i in 0 until grid.childCount) {
            val icon = grid.getChildAt(i) as? BubbleTextView ?: continue
            (icon.tag as? ItemInfo)?.let { icon.applyDotState(it, true) }
        }
    }

    /** The panel stays as the leave-behind while the folder is open; its content hides. */
    override fun setIconVisible(visible: Boolean) {
        super.setIconVisible(false)
        if (::panel.isInitialized) panel.contentVisible = visible
    }

    override fun getIconVisible(): Boolean = ::panel.isInitialized && panel.contentVisible

    override fun drawLeaveBehindIfExists() {
        panel.contentVisible = false
    }

    override fun clearLeaveBehindIfExists() {
        panel.contentVisible = true
    }

    private companion object {
        fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(max(0, size), MeasureSpec.EXACTLY)
    }
}
