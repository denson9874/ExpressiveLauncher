package app.lawnchair.folder.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.data.folderwidget.FolderWidgetStyleRepository
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.pro.ProManager
import app.lawnchair.util.resolveFolderBackgroundColor
import com.android.app.animation.Interpolators
import com.android.launcher3.BubbleTextView
import com.android.launcher3.DropTarget.DragObject
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState.NORMAL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.android.launcher3.R
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.dragndrop.BaseItemDragListener
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.model.data.AppPairInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceItemFactory
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.touch.ItemLongClickListener
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
    internal var style: ResolvedFolderWidgetStyle? = null
    private var appliedSpec: FolderWidgetGridSpec? = null
    private var labelLineHeightPx = -1
    private var boundSpanX = -1

    /** The panel's corner radius, for the resize frame. */
    val cornerRadiusPx: Float
        get() = style?.cornerRadiusPx
            ?: runCatching { resources.getDimension(android.R.dimen.system_app_widget_background_radius) }.getOrDefault(0f)

    override fun onFinishInflate() {
        super.onFinishInflate()
        panel = findViewById(R.id.folder_widget_panel)
        panel.onOpenFolder = ::openFolder
        panel.onAddApps = {
            (mActivity as? Launcher)?.let { launcher ->
                FolderWidgetController.showAppPicker(launcher, this)
            }
        }
        // FolderIcon draws its 1x1 preview, background and dot only while its icon is visible.
        super.setIconVisible(false)
    }

    private var styleObservationJob: Job? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bindPanel()
        observeStyle()
    }

    override fun onDetachedFromWindow() {
        styleObservationJob?.cancel()
        styleObservationJob = null
        super.onDetachedFromWindow()
    }

    private fun observeStyle() {
        val info = mInfo ?: return
        styleObservationJob?.cancel()
        val scope = (mActivity as? LifecycleOwner)?.lifecycleScope
            ?: CoroutineScope(Dispatchers.Main.immediate)
        val prefs = PreferenceManager2.getInstance(context)
        styleObservationJob = scope.launch {
            combine(
                FolderWidgetStyleRepository.INSTANCE.get(context).observe(info.id),
                ProManager.INSTANCE.get(context).isPro,
                prefs.folderColor.get(),
                prefs.folderBackgroundOpacity.get(),
            ) { storedStyle, isPro, _, opacity ->
                val defaults = FolderWidgetDefaults(
                    backgroundColor = resolveFolderBackgroundColor(context),
                    backgroundOpacity = opacity,
                    cornerRadiusPx = runCatching {
                        resources.getDimension(android.R.dimen.system_app_widget_background_radius)
                    }.getOrDefault(0f),
                )
                storedStyle.resolve(isPro, defaults)
            }.collect { resolvedStyle ->
                applyStyle(resolvedStyle)
            }
        }
    }

    /** Applies a widget style. */
    fun applyStyle(style: ResolvedFolderWidgetStyle) {
        if (this.style == style) return
        this.style = style
        bindPanel()
    }

    private fun bindPanel() {
        val info = mInfo ?: return
        val activity = mActivity ?: return
        val adapter = appsAdapter
            ?: FolderWidgetAppsAdapter(activity, info, { panel.gridSpec }, ::onAppLongClick).also {
                appsAdapter = it
                installTouchHandling()
            }
        val style = style ?: defaultStyle().also { style = it }
        boundSpanX = currentSpanX()
        panel.bind(info.title ?: "", adapter, style, metrics(boundSpanX))
        contentDescription = getAccessiblityTitle(info.title)
        setTextVisible(true)
    }

    /** While resizing, the cell span changes before the item's span is committed. */
    internal fun currentSpanX(): Int =
        (layoutParams as? CellLayoutLayoutParams)?.cellHSpan?.takeIf { it > 0 } ?: mInfo.spanX

    /**
     * Long-press on empty grid space or the header picks up the widget; a vertical drag in a grid
     * that scrolls stays with the grid (Home's swipe controllers don't take it), as for
     * scrollable app widgets.
     */
    private fun installTouchHandling() {
        val pickUp = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (panel.recyclerView.findChildViewUnder(e.x, e.y) == null) pickUpWidget()
                }
            },
        )
        panel.recyclerView.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN && panel.isScrollable) {
                        mActivity.dragLayer.requestDisallowInterceptTouchEvent(true)
                    }
                    pickUp.onTouchEvent(e)
                    return false
                }
            },
        )
        panel.header.setOnLongClickListener { pickUpWidget() }
    }

    /** The widget's own long-press: its menu and a drag of the whole widget. */
    private fun pickUpWidget(): Boolean {
        // The drag layer must see the rest of the gesture to move the drag.
        mActivity.dragLayer.requestDisallowInterceptTouchEvent(false)
        return performLongClick()
    }

    /** Long-press on an app: its shortcuts menu, and dragging takes it out of the widget. */
    private fun onAppLongClick(icon: BubbleTextView): Boolean {
        val launcher = mActivity as? Launcher ?: return false
        if (!ItemLongClickListener.canStartDrag(launcher) || !launcher.isInState(NORMAL)) return false
        val folder = folder ?: return false
        mActivity.dragLayer.requestDisallowInterceptTouchEvent(false)
        // Workspace.beginDragShared adds the shortcuts popup as the pre-drag condition.
        return folder.startDragFromWidget(icon, DragOptions())
    }

    override fun acceptDrop(dragInfo: ItemInfo): Boolean {
        val folder = folder ?: return false
        return !folder.isDestroyed && !folder.isOpen && dragInfo !== mInfo && FolderWidgets.acceptsDrop(dragInfo)
    }

    /** An app being dragged out of this widget (it keeps the folder as container until dropped). */
    private fun isOwnApp(dragInfo: ItemInfo) = dragInfo.container == mInfo.id

    override fun onDragEnter(dragInfo: ItemInfo) {
        // Hovering an app being dragged out of this widget adds nothing, so shows nothing.
        if (!acceptDrop(dragInfo) || isOwnApp(dragInfo)) return
        panel.dropOutlineVisible = true
        springPanelTo(DROP_HOVER_SCALE)
        panel.scrollToEnd()
    }

    override fun onDragExit() {
        panel.dropOutlineVisible = false
        springPanelTo(1f)
    }

    /**
     * Appends a dropped app, its drag view animating into the app's cell. An app dragged out of
     * this widget and dropped back on it goes back to its place (the drop is a cancel).
     */
    override fun onDrop(d: DragObject, itemReturnedOnFailedDrop: Boolean) {
        val folder = folder ?: return
        val dragInfo = d.dragInfo
        val returning = itemReturnedOnFailedDrop || isOwnApp(dragInfo)
        val item: ItemInfo = when {
            dragInfo is WorkspaceItemFactory -> dragInfo.makeWorkspaceItem(context)
            d.dragSource is BaseItemDragListener ->
                if (dragInfo is AppPairInfo) AppPairInfo(dragInfo) else WorkspaceItemInfo(dragInfo as WorkspaceItemInfo)
            else -> dragInfo
        }
        folder.notifyDrop()
        item.cellX = -1
        item.cellY = -1
        panel.dropOutlineVisible = false
        springPanelTo(1f)
        // An app returned after a failed drop that never left the folder isn't added twice.
        if (itemReturnedOnFailedDrop && FolderWidgets.isInFolder(mInfo, item)) return
        val index = if (returning) item.rank else mInfo.getContents().size
        val launcher = mActivity as? Launcher
        val dragView = d.dragView
        if (itemReturnedOnFailedDrop || dragView == null || launcher == null) {
            folder.addFolderContent(item, index, !itemReturnedOnFailedDrop)
            return
        }
        val to = Rect()
        val scaleToDragLayer = launcher.dragLayer.getDescendantRectRelativeToSelf(this, to)
        val center: IntArray
        val finalAlpha: Float
        var finalScale: Float
        if (returning) {
            // Back in place: the drag view fades into the panel; the grid shows the app at once.
            folder.addFolderContent(item, index, true)
            center = intArrayOf(panel.left + panel.width / 2, panel.top + panel.height / 2)
            finalAlpha = 0f
            finalScale = RETURN_SCALE * scaleToDragLayer
        } else {
            appsAdapter?.hiddenItem = item
            folder.addFolderContent(item, index, true)
            // Instant, so the cell matches where the drag view lands.
            panel.scrollToEnd(smooth = false)
            center = iconCenterFor(index)
            finalAlpha = 1f
            val dp = launcher.deviceProfile
            finalScale = panel.gridSpec.iconSizePx.toFloat() / dp.iconSizePx * scaleToDragLayer
            if (d.dragSource is ActivityAllAppsContainerView<*>) {
                finalScale *= dp.iconSizePx.toFloat() / dp.allAppsProfile.iconSizePx
            }
        }
        to.offset(
            (center[0] * scaleToDragLayer).roundToInt() - dragView.measuredWidth / 2,
            (center[1] * scaleToDragLayer).roundToInt() - dragView.measuredHeight / 2,
        )
        launcher.dragLayer.animateView(
            dragView, to, finalAlpha, finalScale, finalScale, DROP_IN_ANIMATION_DURATION,
            Interpolators.DECELERATE_2, { appsAdapter?.hiddenItem = null },
            DragLayer.ANIMATION_END_DISAPPEAR, null,
        )
    }

    /** Where the icon of the app at [index] will be, in this view's coordinates, with the grid scrolled to the end. */
    private fun iconCenterFor(index: Int): IntArray {
        val spec = panel.gridSpec
        val grid = panel.recyclerView
        val columns = spec.columns.coerceAtLeast(1)
        val column = index % columns
        val row = index / columns
        val contentHeight = (row + 1) * spec.rowHeightPx
        val rowTop = if (contentHeight > grid.height) grid.height - spec.rowHeightPx else row * spec.rowHeightPx
        val rowSpacing = metrics(boundSpanX).rowSpacingPx
        val iconTop = if (spec.labelsVisible) rowSpacing / 2 else (spec.rowHeightPx - spec.iconSizePx) / 2
        return intArrayOf(
            panel.left + grid.left + column * spec.columnWidthPx + spec.columnWidthPx / 2,
            panel.top + grid.top + rowTop + iconTop + spec.iconSizePx / 2,
        )
    }

    private val panelSprings by lazy {
        listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y).map { property ->
            SpringAnimation(panel, property).setSpring(
                SpringForce(1f)
                    .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY),
            )
        }
    }

    private fun springPanelTo(scale: Float) = panelSprings.forEach { it.animateToFinalPosition(scale) }

    fun openFolder() {
        if (mInfo.getContents().isEmpty()) {
            (mActivity as? Launcher)?.let { launcher ->
                FolderWidgetController.showAppPicker(launcher, this)
            }
            return
        }
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

    internal fun metrics(spanX: Int): FolderWidgetMetrics {
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

    /** Updated apps (icons, labels, suspended or disabled states) refresh their grid icons too. */
    override fun updatePreviewItems(itemCheck: Predicate<ItemInfo>) {
        super.updatePreviewItems(itemCheck)
        val adapter = appsAdapter ?: return
        mInfo.getContents().forEachIndexed { index, item ->
            if (itemCheck.test(item)) adapter.notifyItemChanged(index)
        }
    }

    override fun onItemsChanged(animate: Boolean) {
        super.onItemsChanged(animate)
        appsAdapter?.notifyDataSetChanged()
    }

    override fun onTitleChanged(title: CharSequence?) {
        super.onTitleChanged(title)
        panel.header.text = title ?: ""
    }

    override fun getAccessiblityTitle(title: CharSequence?): String {
        val name = if (title.isNullOrBlank()) context.getString(R.string.unnamed_folder) else title
        val size = mInfo?.getContents()?.size ?: 0
        return resources.getQuantityString(R.plurals.folder_widget_description, size, name, size)
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
        const val DROP_HOVER_SCALE = 1.03f
        const val RETURN_SCALE = 0.5f

        /** As FolderIcon's drop-in animation. */
        const val DROP_IN_ANIMATION_DURATION = 400

        fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(max(0, size), MeasureSpec.EXACTLY)
    }
}
