package app.lawnchair.folder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.views.BaseDragLayer
import com.android.launcher3.folder.FolderIcon
import com.android.launcher3.util.Themes

/**
 * Large folders v2: a frame with four handles that snaps a Home folder between 1x1 and 2x2.
 * Opened by releasing a long-pressed Home folder in place, like the widget resize frame.
 */
class FolderResizeFrame(context: Context, attrs: AttributeSet?) : AbstractFloatingView(context, attrs) {

    private lateinit var launcher: Launcher
    private lateinit var icon: FolderIcon
    private val handles = linkedMapOf<ResizeHandle, View>()
    private var active: ResizeHandle? = null
    private var downX = 0f
    private var downY = 0f
    private var snapped = false
    private val handleSize = resources.displayMetrics.density * 24
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2
        color = Themes.getAttrColor(context, android.R.attr.colorAccent)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        handles[ResizeHandle.LEFT] = findViewById(R.id.folder_handle_left)
        handles[ResizeHandle.TOP] = findViewById(R.id.folder_handle_top)
        handles[ResizeHandle.RIGHT] = findViewById(R.id.folder_handle_right)
        handles[ResizeHandle.BOTTOM] = findViewById(R.id.folder_handle_bottom)
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(handleSize.toInt(), MeasureSpec.EXACTLY)
        handles.values.forEach { it.measure(spec, spec) }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    /** Handles sit centred on the edges of the folder's cells, which are inset by half a handle. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val s = handleSize.toInt()
        handles[ResizeHandle.LEFT]?.layout(0, (h - s) / 2, s, (h + s) / 2)
        handles[ResizeHandle.TOP]?.layout((w - s) / 2, 0, (w + s) / 2, s)
        handles[ResizeHandle.RIGHT]?.layout(w - s, (h - s) / 2, w, (h + s) / 2)
        handles[ResizeHandle.BOTTOM]?.layout((w - s) / 2, h - s, (w + s) / 2, h)
    }

    private fun current(): CellRect = LargeFolderLayout.rectOf(icon)

    private fun cellLayout(): CellLayout? = launcher.workspace.getScreenWithId(icon.mInfo.screenId)

    private fun allowed(handle: ResizeHandle): Boolean {
        val layout = cellLayout() ?: return false
        val target = FolderResizeMath.targetFor(current(), handle)
        if (target.spanX > 1 && !LargeFolders.canGrow(launcher.deviceProfile.panelCount)) return false
        return FolderResizeMath.isAllowed(target, layout.countX, layout.countY) { x, y ->
            LargeFolderLayout.ownerAt(layout, icon, x, y)
        }
    }

    /** Places the frame over the folder's cells in DragLayer coordinates. */
    fun snapToFolder() {
        val layout = cellLayout() ?: return close(false)
        val r = current()
        val cells = Rect()
        layout.cellToRect(r.x, r.y, r.spanX, r.spanY, cells)
        val corner = intArrayOf(cells.left, cells.top)
        val scale = launcher.dragLayer.getDescendantCoordRelativeToSelf(layout, corner)
        val half = (handleSize / 2).toInt()
        val lp = layoutParams as BaseDragLayer.LayoutParams
        lp.x = corner[0] - half
        lp.y = corner[1] - half
        lp.width = (cells.width() * scale).toInt() + 2 * half
        lp.height = (cells.height() * scale).toInt() + 2 * half
        val large = r.spanX > 1
        handles.forEach { (h, v) ->
            v.contentDescription = context.getString(
                if (large) {
                    R.string.large_folder_handle_shrink
                } else {
                    when (h) {
                        ResizeHandle.LEFT -> R.string.large_folder_handle_left
                        ResizeHandle.TOP -> R.string.large_folder_handle_top
                        ResizeHandle.RIGHT -> R.string.large_folder_handle_right
                        ResizeHandle.BOTTOM -> R.string.large_folder_handle_bottom
                    }
                },
            )
            v.alpha = if (allowed(h)) 1f else DISABLED_ALPHA
        }
        requestLayout()
        invalidate()
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && !launcher.dragLayer.isEventOverView(this, ev)) {
            close(true)
            return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = handles.entries.firstOrNull { (_, v) -> v.isHit(ev.x, ev.y) }?.key ?: return false
                downX = ev.x
                downY = ev.y
                snapped = false
            }
            MotionEvent.ACTION_MOVE -> {
                val h = active ?: return false
                val horizontal = h == ResizeHandle.LEFT || h == ResizeHandle.RIGHT
                val drag = if (horizontal) ev.x - downX else ev.y - downY
                val layout = cellLayout() ?: return false
                val cellPx = (if (horizontal) layout.cellWidth else layout.cellHeight).toFloat()
                val ok = allowed(h)
                val snap = ok && FolderResizeMath.shouldSnap(drag, cellPx, current(), h)
                if (snap != snapped) {
                    snapped = snap
                    if (snap) performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                }
                val offset = if (ok) drag else FolderResizeMath.rubberBand(drag, cellPx)
                handles[h]?.let { v -> if (horizontal) v.translationX = offset else v.translationY = offset }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val h = active ?: return false
                handles.values.forEach { it.animate().translationX(0f).translationY(0f).start() }
                if (snapped && ev.actionMasked == MotionEvent.ACTION_UP) commit(h)
                active = null
                snapped = false
                invalidate()
            }
        }
        return true
    }

    private fun View.isHit(x: Float, y: Float): Boolean {
        val slop = resources.displayMetrics.density * 16
        return x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
    }

    private fun commit(handle: ResizeHandle) {
        val target = FolderResizeMath.targetFor(current(), handle)
        if (LargeFolderController.applySize(launcher, icon, target)) {
            post { snapToFolder() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = handleSize / 2
        outlinePaint.strokeWidth = resources.displayMetrics.density * (if (snapped) 3 else 2)
        canvas.drawRect(half, half, width - half, height - half, outlinePaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        handles.forEach { (h, v) ->
            if (allowed(h)) info.addAction(AccessibilityNodeInfo.AccessibilityAction(v.id, v.contentDescription))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val h = handles.entries.firstOrNull { it.value.id == action }?.key
        if (h != null && allowed(h)) {
            commit(h)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun handleClose(animate: Boolean) {
        launcher.dragLayer.removeView(this)
    }

    override fun isOfType(type: Int): Boolean = type and TYPE_WIDGET_RESIZE_FRAME != 0

    companion object {
        private const val DISABLED_ALPHA = 0.38f

        @JvmStatic
        fun show(launcher: Launcher, icon: FolderIcon) {
            if (!LargeFolderController.canResize(icon) || icon.parent == null) return
            closeAllOpenViews(launcher)
            val frame = launcher.layoutInflater
                .inflate(R.layout.folder_resize_frame, launcher.dragLayer, false) as FolderResizeFrame
            frame.launcher = launcher
            frame.icon = icon
            frame.contentDescription = launcher.getString(R.string.large_folder_resize_frame, icon.mInfo.title)
            (frame.layoutParams as BaseDragLayer.LayoutParams).customPosition = true
            launcher.dragLayer.addView(frame)
            frame.mIsOpen = true
            frame.post { frame.snapToFolder() }
        }
    }
}
