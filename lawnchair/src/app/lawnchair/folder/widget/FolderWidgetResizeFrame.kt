package app.lawnchair.folder.widget

import android.animation.AnimatorSet
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import app.lawnchair.theme.color.tokens.ColorTokens
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.AppWidgetResizeFrame
import com.android.launcher3.CellLayout
import com.android.launcher3.FirstFrameAnimatorHelper
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherAnimUtils.LAYOUT_HEIGHT
import com.android.launcher3.LauncherAnimUtils.LAYOUT_WIDTH
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.accessibility.DragViewStateAnnouncer
import com.android.launcher3.celllayout.CellLayoutLayoutParams
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.BaseDragLayer
import com.android.launcher3.views.BaseDragLayer.LAYOUT_X
import com.android.launcher3.views.BaseDragLayer.LAYOUT_Y
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The resize frame of a Folder widget: Launcher3's AppWidgetResizeFrame behaviour (handles on four
 * sides, cell-by-cell steps past [RESIZE_THRESHOLD] of a cell, other items pushed with
 * CellLayout.createAreaForResize, the final commit on dismiss), with a minimum of two cells.
 */
class FolderWidgetResizeFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractFloatingView(context, attrs, defStyleAttr), View.OnKeyListener {

    private val launcher: Launcher = Launcher.getLauncher(context)
    // Null when accessibility is off.
    private val stateAnnouncer: DragViewStateAnnouncer? = DragViewStateAnnouncer.createFor(this)
    private val firstFrameAnimatorHelper = FirstFrameAnimatorHelper(this)
    private val dragHandles = arrayOfNulls<View>(HANDLE_COUNT)
    private val gestureExclusionRects = List(HANDLE_COUNT) { Rect() }
    private val backgroundPadding = resources.getDimensionPixelSize(R.dimen.resize_frame_background_padding)
    private val touchTargetWidth = 2 * backgroundPadding

    private lateinit var widgetView: FolderWidgetView
    private lateinit var cellLayout: CellLayout
    private lateinit var dragLayer: DragLayer

    private var directionVector = IntArray(2)
    private var lastDirectionVector = IntArray(2)
    private val tempRange = IntRange()
    private val deltaXRange = IntRange()
    private val baselineX = IntRange()
    private val deltaYRange = IntRange()
    private val baselineY = IntRange()

    private var leftBorderActive = false
    private var rightBorderActive = false
    private var topBorderActive = false
    private var bottomBorderActive = false
    private var horizontalResizeActive = false
    private var verticalResizeActive = false

    private var runningHInc = 0
    private var runningVInc = 0
    private var deltaX = 0
    private var deltaY = 0
    private var deltaXAddOn = 0
    private var deltaYAddOn = 0
    private var topTouchRegionAdjustment = 0
    private var bottomTouchRegionAdjustment = 0
    private var xDown = 0
    private var yDown = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        dragHandles[INDEX_LEFT] = findViewById(R.id.widget_resize_left_handle)
        dragHandles[INDEX_TOP] = findViewById(R.id.widget_resize_top_handle)
        dragHandles[INDEX_RIGHT] = findViewById(R.id.widget_resize_right_handle)
        dragHandles[INDEX_BOTTOM] = findViewById(R.id.widget_resize_bottom_handle)
        val accent = ColorTokens.WorkspaceAccentColor.resolveColor(context)
        dragHandles.forEach { (it as ImageView).setColorFilter(accent) }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        for (i in 0 until HANDLE_COUNT) {
            val handle = dragHandles[i]!!
            gestureExclusionRects[i].set(handle.left, handle.top, handle.right, handle.bottom)
        }
        setSystemGestureExclusionRects(gestureExclusionRects)
    }

    private fun setupFor(widget: FolderWidgetView, layout: CellLayout, dl: DragLayer) {
        widgetView = widget
        cellLayout = layout
        dragLayer = dl

        horizontalResizeActive = layout.countX > 1
        verticalResizeActive = layout.countY > 1
        if (!horizontalResizeActive) {
            dragHandles[INDEX_LEFT]!!.visibility = GONE
            dragHandles[INDEX_RIGHT]!!.visibility = GONE
        }
        if (!verticalResizeActive) {
            dragHandles[INDEX_TOP]!!.visibility = GONE
            dragHandles[INDEX_BOTTOM]!!.visibility = GONE
        }

        val lp = widget.layoutParams as CellLayoutLayoutParams
        val info = widget.tag as ItemInfo
        val presenterPos = launcher.cellPosMapper.mapModelToPresenter(info)
        lp.cellX = presenterPos.cellX
        lp.tmpCellX = presenterPos.cellX
        lp.cellY = presenterPos.cellY
        lp.tmpCellY = presenterPos.cellY
        lp.cellHSpan = info.spanX
        lp.cellVSpan = info.spanY
        lp.isLockedToGrid = true

        // As for widgets, the cells are freed while the frame shows; the final commit on dismiss
        // marks the resulting cells again.
        layout.markCellsAsUnoccupiedForView(widget)

        setOnKeyListener(this)
        val frame = findViewById<ImageView>(R.id.widget_resize_frame).drawable
        if (frame is GradientDrawable) frame.mutate().let { (it as GradientDrawable).cornerRadius = widget.cornerRadiusPx }

        // When the size steps, the panel re-lays out with a short transition, as widgets do.
        widget.layoutTransition = resizeTransition()
        widget.panel.layoutTransition = resizeTransition()
    }

    private fun resizeTransition() = LayoutTransition().apply {
        setDuration(RESIZE_TRANSITION_DURATION_MS.toLong())
        enableTransitionType(LayoutTransition.CHANGING)
    }

    private fun beginResizeIfPointInRegion(x: Int, y: Int): Boolean {
        leftBorderActive = x < touchTargetWidth && horizontalResizeActive
        rightBorderActive = x > width - touchTargetWidth && horizontalResizeActive
        topBorderActive = y < touchTargetWidth + topTouchRegionAdjustment && verticalResizeActive
        bottomBorderActive = y > height - touchTargetWidth + bottomTouchRegionAdjustment && verticalResizeActive

        val anyBordersActive = leftBorderActive || rightBorderActive || topBorderActive || bottomBorderActive
        if (anyBordersActive) {
            dragHandles[INDEX_LEFT]!!.alpha = if (leftBorderActive) 1f else DIMMED_HANDLE_ALPHA
            dragHandles[INDEX_RIGHT]!!.alpha = if (rightBorderActive) 1f else DIMMED_HANDLE_ALPHA
            dragHandles[INDEX_TOP]!!.alpha = if (topBorderActive) 1f else DIMMED_HANDLE_ALPHA
            dragHandles[INDEX_BOTTOM]!!.alpha = if (bottomBorderActive) 1f else DIMMED_HANDLE_ALPHA
        }

        when {
            leftBorderActive -> deltaXRange.set(-left, width - 2 * touchTargetWidth)
            rightBorderActive -> deltaXRange.set(2 * touchTargetWidth - width, dragLayer.width - right)
            else -> deltaXRange.set(0, 0)
        }
        baselineX.set(left, right)
        when {
            topBorderActive -> deltaYRange.set(-top, height - 2 * touchTargetWidth)
            bottomBorderActive -> deltaYRange.set(2 * touchTargetWidth - height, dragLayer.height - bottom)
            else -> deltaYRange.set(0, 0)
        }
        baselineY.set(top, bottom)
        return anyBordersActive
    }

    /** Moves the frame with the finger and steps the widget's size when a threshold is crossed. */
    private fun visualizeResizeForDelta(dx: Int, dy: Int) {
        deltaX = deltaXRange.clamp(dx)
        deltaY = deltaYRange.clamp(dy)
        val lp = layoutParams as BaseDragLayer.LayoutParams
        baselineX.applyDelta(leftBorderActive, rightBorderActive, deltaX, tempRange)
        lp.x = tempRange.start
        lp.width = tempRange.size()
        baselineY.applyDelta(topBorderActive, bottomBorderActive, deltaY, tempRange)
        lp.y = tempRange.start
        lp.height = tempRange.size()
        resizeWidgetIfNeeded(false)
        requestLayout()
    }

    private fun resizeWidgetIfNeeded(onDismiss: Boolean) {
        val lp = widgetView.layoutParams as? CellLayoutLayoutParams ?: return
        val dp = launcher.deviceProfile
        val xThreshold = (cellLayout.cellWidth + dp.cellLayoutBorderSpacePx.x).toFloat()
        val yThreshold = (cellLayout.cellHeight + dp.cellLayoutBorderSpacePx.y).toFloat()
        val hSpanInc = spanIncrement((deltaX + deltaXAddOn) / xThreshold - runningHInc)
        val vSpanInc = spanIncrement((deltaY + deltaYAddOn) / yThreshold - runningVInc)
        if (!onDismiss && hSpanInc == 0 && vSpanInc == 0) return

        val current = GridRect(
            if (lp.useTmpCoords) lp.tmpCellX else lp.cellX,
            if (lp.useTmpCoords) lp.tmpCellY else lp.cellY,
            lp.cellHSpan,
            lp.cellVSpan,
        )
        val next = FolderWidgetResizeMath.step(
            current,
            leftBorderActive,
            topBorderActive,
            rightBorderActive,
            bottomBorderActive,
            hSpanInc,
            vSpanInc,
            cellLayout.countX,
            cellLayout.countY,
        )
        val hSpanDelta = when {
            rightBorderActive -> next.spanX - current.spanX
            leftBorderActive -> current.spanX - next.spanX
            else -> 0
        }
        val vSpanDelta = when {
            bottomBorderActive -> next.spanY - current.spanY
            topBorderActive -> current.spanY - next.spanY
            else -> 0
        }
        val direction = intArrayOf(
            if (hSpanDelta != 0) (if (leftBorderActive) -1 else 1) else 0,
            if (vSpanDelta != 0) (if (topBorderActive) -1 else 1) else 0,
        )
        if (!onDismiss && hSpanDelta == 0 && vSpanDelta == 0) return

        // The final commit always matches the last feedback the user saw.
        if (onDismiss) {
            directionVector = lastDirectionVector.copyOf()
        } else {
            directionVector = direction
            lastDirectionVector = direction.copyOf()
        }

        if (cellLayout.createAreaForResize(
                next.x, next.y, next.spanX, next.spanY, widgetView, directionVector, onDismiss,
            )
        ) {
            if (lp.cellHSpan != next.spanX || lp.cellVSpan != next.spanY) {
                stateAnnouncer?.announce(launcher.getString(R.string.widget_resized, next.spanX, next.spanY))
            }
            lp.tmpCellX = next.x
            lp.tmpCellY = next.y
            lp.cellHSpan = next.spanX
            lp.cellVSpan = next.spanY
            runningVInc += vSpanDelta
            runningHInc += hSpanDelta
        }
        widgetView.requestLayout()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Done resizing: commit the size and position (and any pushed items) to the model.
        resizeWidgetIfNeeded(true)
    }

    private fun onTouchUp() {
        val dp = launcher.deviceProfile
        val xThreshold = cellLayout.cellWidth + dp.cellLayoutBorderSpacePx.x
        val yThreshold = cellLayout.cellHeight + dp.cellLayoutBorderSpacePx.y
        deltaXAddOn = runningHInc * xThreshold
        deltaYAddOn = runningVInc * yThreshold
        deltaX = 0
        deltaY = 0
        post { snapToWidget(true) }
    }

    private fun snapToWidget(animate: Boolean) {
        val rect = Rect()
        dragLayer.getViewRectRelativeToSelf(widgetView, rect)
        val newWidth = 2 * backgroundPadding + rect.width()
        val newHeight = 2 * backgroundPadding + rect.height()
        val newX = rect.left - backgroundPadding
        val newY = rect.top - backgroundPadding

        // Keep the touch regions inside the drag layer; the handles themselves may be clipped.
        topTouchRegionAdjustment = if (newY < 0) -newY else 0
        bottomTouchRegionAdjustment =
            if (newY + newHeight > dragLayer.height) -(newY + newHeight - dragLayer.height) else 0

        val lp = layoutParams as BaseDragLayer.LayoutParams
        if (!animate) {
            lp.width = newWidth
            lp.height = newHeight
            lp.x = newX
            lp.y = newY
            dragHandles.forEach { it!!.alpha = 1f }
            requestLayout()
        } else {
            val oa = ObjectAnimator.ofPropertyValuesHolder(
                lp,
                PropertyValuesHolder.ofInt(LAYOUT_WIDTH, lp.width, newWidth),
                PropertyValuesHolder.ofInt(LAYOUT_HEIGHT, lp.height, newHeight),
                PropertyValuesHolder.ofInt(LAYOUT_X, lp.x, newX),
                PropertyValuesHolder.ofInt(LAYOUT_Y, lp.y, newY),
            )
            firstFrameAnimatorHelper.addTo(oa).addUpdateListener { requestLayout() }
            val set = AnimatorSet()
            set.play(oa)
            dragHandles.forEach { set.play(firstFrameAnimatorHelper.addTo(ObjectAnimator.ofFloat(it, ALPHA, 1f))) }
            set.duration = SNAP_DURATION_MS.toLong()
            set.start()
        }
        isFocusableInTouchMode = true
        requestFocus()
    }

    override fun onKey(v: View, keyCode: Int, event: KeyEvent): Boolean {
        // A directional key closes the frame and gives focus to the widget.
        if (AppWidgetResizeFrame.shouldConsume(keyCode)) {
            close(false)
            widgetView.requestFocus()
            return true
        }
        return false
    }

    private fun handleTouchDown(ev: MotionEvent): Boolean {
        val hitRect = Rect()
        val x = ev.x.toInt()
        val y = ev.y.toInt()
        getHitRect(hitRect)
        if (hitRect.contains(x, y) && beginResizeIfPointInRegion(x - left, y - top)) {
            xDown = x
            yDown = y
            return true
        }
        return false
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x.toInt()
        val y = ev.y.toInt()
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> return handleTouchDown(ev)
            MotionEvent.ACTION_MOVE -> visualizeResizeForDelta(x - xDown, y - yDown)
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                visualizeResizeForDelta(x - xDown, y - yDown)
                onTouchUp()
                xDown = 0
                yDown = 0
            }
        }
        return true
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && handleTouchDown(ev)) return true
        // A touch elsewhere closes the frame, and the widget's menu too unless the touch is on it.
        val menu = AbstractFloatingView.getOpenView<AbstractFloatingView>(launcher, AbstractFloatingView.TYPE_OPTIONS_POPUP)
        if (menu != null && !dragLayer.isEventOverView(menu, ev)) menu.close(true)
        close(false)
        return false
    }

    override fun handleClose(animate: Boolean) {
        widgetView.layoutTransition = null
        widgetView.panel.layoutTransition = null
        dragLayer.removeView(this)
    }

    override fun isOfType(type: Int): Boolean = (type and TYPE_WIDGET_RESIZE_FRAME) != 0

    private fun markOpen(): FolderWidgetResizeFrame = apply { mIsOpen = true }

    /** A mutable [start, end) range, as AppWidgetResizeFrame.IntRange. */
    private class IntRange {
        var start = 0
        var end = 0

        fun clamp(value: Int) = Utilities.boundToRange(value, start, end)

        fun set(s: Int, e: Int) {
            start = s
            end = e
        }

        fun size() = end - start

        fun applyDelta(moveStart: Boolean, moveEnd: Boolean, delta: Int, out: IntRange) {
            out.start = if (moveStart) start + delta else start
            out.end = if (moveEnd) end + delta else end
        }
    }

    companion object {
        const val RESIZE_THRESHOLD = 0.66f
        const val SNAP_DURATION_MS = 150
        const val RESIZE_TRANSITION_DURATION_MS = 150
        private const val DIMMED_HANDLE_ALPHA = 0f
        private const val HANDLE_COUNT = 4
        private const val INDEX_LEFT = 0
        private const val INDEX_TOP = 1
        private const val INDEX_RIGHT = 2
        private const val INDEX_BOTTOM = 3

        private fun spanIncrement(deltaFrac: Float): Int =
            if (abs(deltaFrac) > RESIZE_THRESHOLD) deltaFrac.roundToInt() else 0

        /** Shows the resize frame around [widget], closing other floating views first. */
        @JvmStatic
        fun show(launcher: Launcher, widget: FolderWidgetView) {
            // Without a parent the frame can't be placed around the widget.
            val layout = widget.parent?.parent as? CellLayout ?: return
            // The widget's long-press menu stays open with the frame.
            AbstractFloatingView.closeOpenViews(
                launcher,
                true,
                AbstractFloatingView.TYPE_ALL and AbstractFloatingView.TYPE_OPTIONS_POPUP.inv(),
            )
            val dl = launcher.dragLayer
            val frame = launcher.layoutInflater
                .inflate(R.layout.folder_widget_resize_frame, dl, false) as FolderWidgetResizeFrame
            frame.setupFor(widget, layout, dl)
            // The item as tag lets the accessibility delegate offer the item's actions here too.
            frame.tag = widget.tag
            frame.accessibilityDelegate = launcher.accessibilityDelegate
            frame.contentDescription = launcher.getString(R.string.widget_frame_name, widget.contentDescription)
            (frame.layoutParams as BaseDragLayer.LayoutParams).customPosition = true
            dl.addView(frame)
            frame.markOpen()
            frame.post { frame.snapToWidget(false) }
        }
    }
}
