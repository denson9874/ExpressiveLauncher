package app.lawnchair.folder.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import app.lawnchair.util.resolveFolderBackgroundColor
import app.lawnchair.util.resolveFolderPreviewColor
import com.android.launcher3.BubbleTextView
import com.android.launcher3.CellLayout
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.apppairs.AppPairIcon
import com.android.launcher3.folder.Folder
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.views.ActivityContext
import com.android.launcher3.views.BaseDragLayer

data class RoundRect(val rect: RectF, val radius: Float)

object FolderWidgetAnimations {

    @JvmStatic
    fun revealEndpoints(
        widget: RectF,
        panel: RectF,
        widgetRadius: Float,
        panelRadius: Float,
        opening: Boolean,
    ): Pair<RoundRect, RoundRect> {
        val widgetRR = RoundRect(widget, widgetRadius)
        val panelRR = RoundRect(panel, panelRadius)
        return if (opening) {
            Pair(widgetRR, panelRR)
        } else {
            Pair(panelRR, widgetRR)
        }
    }

    @JvmStatic
    fun flightDelta(
        widgetChildCoordInDragLayer: Float,
        panelOriginInDragLayer: Float,
        folderItemCoordInFolder: Float,
    ): Float = widgetChildCoordInDragLayer - panelOriginInDragLayer - folderItemCoordInFolder

    @JvmStatic
    fun create(folder: Folder, widget: FolderWidgetView, opening: Boolean): AnimatorSet {
        val context = folder.context
        val res = context.resources
        val duration = res.getInteger(R.integer.config_materialFolderExpandDuration)
        val openInterpolator = AnimationUtils.loadInterpolator(context, R.interpolator.standard_interpolator)
        val closeInterpolator = AnimationUtils.loadInterpolator(context, R.interpolator.standard_interpolator)
        val interpolator = if (opening) openInterpolator else closeInterpolator

        val asSet = AnimatorSet()
        val lp = folder.layoutParams as? BaseDragLayer.LayoutParams
        val activityContext = ActivityContext.lookupContextNoThrow<Launcher>(context)
        val dragLayer = activityContext?.dragLayer
        if (lp == null || dragLayer == null) {
            asSet.duration = duration.toLong()
            return asSet
        }

        val panelRect = Rect()
        widget.panelRectInDragLayer(panelRect)
        val widgetRadius = widget.cornerRadiusPx

        val folderBg = folder.background as? GradientDrawable
        val folderRadius = folderBg?.cornerRadius ?: 0f

        val initialColor = widget.style?.backgroundColor ?: resolveFolderPreviewColor(context)
        val finalColor = resolveFolderBackgroundColor(context)
        folderBg?.mutate()
        folderBg?.setColor(if (opening) initialColor else finalColor)

        // The reveal geometry inside the folder view:
        val widgetRectF = RectF(0f, 0f, panelRect.width().toFloat(), panelRect.height().toFloat())
        val folderRectF = RectF(0f, 0f, lp.width.toFloat(), lp.height.toFloat())
        val (startRR, endRR) = revealEndpoints(widgetRectF, folderRectF, widgetRadius, folderRadius, opening)
        val revealAnimator = RoundRectRevealAnimator(folder, startRR, endRR, reversed = false).create()
        asSet.play(revealAnimator)

        // Translation of the folder view from/to the widget's location in DragLayer
        val xDistance = (panelRect.left - lp.x).toFloat()
        val yDistance = (panelRect.top - lp.y).toFloat()
        val transX = if (opening) {
            ObjectAnimator.ofFloat(folder, View.TRANSLATION_X, xDistance, 0f)
        } else {
            ObjectAnimator.ofFloat(folder, View.TRANSLATION_X, 0f, xDistance)
        }
        val transY = if (opening) {
            ObjectAnimator.ofFloat(folder, View.TRANSLATION_Y, yDistance, 0f)
        } else {
            ObjectAnimator.ofFloat(folder, View.TRANSLATION_Y, 0f, yDistance)
        }
        asSet.play(transX)
        asSet.play(transY)

        if (folderBg != null) {
            val bgAnim = ObjectAnimator.ofArgb(
                folderBg,
                "color",
                if (opening) initialColor else finalColor,
                if (opening) finalColor else initialColor,
            )
            asSet.play(bgAnim)
        }

        // Header and footer alpha
        val nameAnim = ObjectAnimator.ofFloat(
            folder.getFolderName(),
            View.ALPHA,
            if (opening) 0f else 1f,
            if (opening) 1f else 0f,
        )
        asSet.play(nameAnim)

        val footerAnim = ObjectAnimator.ofFloat(
            folder.mFooter,
            View.ALPHA,
            if (opening) 0f else 1f,
            if (opening) 1f else 0f,
        )
        asSet.play(footerAnim)

        // Items animation: match visible widget grid icons with folder icons on current page
        val page = if (opening) folder.getContent().currentPage else folder.getContent().destinationPage
        val folderItems = folder.getItemsOnPage(page)
        val grid = widget.panel.recyclerView

        class FlightState(
            val view: View,
            val btv: BubbleTextView?,
            val deltaX: Float,
            val deltaY: Float,
            val startScale: Float,
        )

        val fliers = mutableListOf<FlightState>()

        for (v in folderItems) {
            val btv = getBubbleTextView(v)
            if (btv != null && opening) {
                btv.setTextVisibility(false)
            }
            if (btv != null) {
                val textAnim = btv.createTextAlphaAnimator(opening)
                asSet.play(textAnim)
            }

            val itemInfo = v.tag as? ItemInfo
            val widgetChild = if (itemInfo != null) findVisibleWidgetChild(grid, itemInfo) else null
            if (widgetChild != null) {
                val widgetBtv = getBubbleTextView(widgetChild)
                val folderBounds = Rect()
                btv?.getIconBounds(folderBounds)
                val widgetBounds = Rect()
                widgetBtv?.getIconBounds(widgetBounds)

                val ptWidget = floatArrayOf(widgetBounds.left.toFloat(), widgetBounds.top.toFloat())
                Utilities.getDescendantCoordRelativeToAncestor(widgetChild, dragLayer, ptWidget, false)
                val ptFolder = floatArrayOf(folderBounds.left.toFloat(), folderBounds.top.toFloat())
                Utilities.getDescendantCoordRelativeToAncestor(v, folder, ptFolder, false)

                val deltaX = flightDelta(ptWidget[0], panelRect.left.toFloat(), ptFolder[0])
                val deltaY = flightDelta(ptWidget[1], panelRect.top.toFloat(), ptFolder[1])
                val startScale = if (folderBounds.width() > 0 && widgetBounds.width() > 0) {
                    widgetBounds.width().toFloat() / folderBounds.width().toFloat()
                } else 1f

                fliers.add(FlightState(v, btv, deltaX, deltaY, startScale))
            } else {
                val fade = ObjectAnimator.ofFloat(v, View.ALPHA, if (opening) 0f else 1f, if (opening) 1f else 0f)
                asSet.play(fade)
            }
        }

        if (fliers.isNotEmpty()) {
            val flight = ValueAnimator.ofFloat(if (opening) 0f else 1f, if (opening) 1f else 0f)
            flight.addUpdateListener { anim ->
                val p = anim.animatedValue as Float
                for (st in fliers) {
                    st.view.translationX = st.deltaX * (1f - p)
                    st.view.translationY = st.deltaY * (1f - p)
                    val s = st.startScale + (1f - st.startScale) * p
                    st.view.scaleX = s
                    st.view.scaleY = s
                }
            }
            asSet.play(flight)
        }

        if (!opening) {
            val closeFade = ValueAnimator.ofFloat(0f, 1f).apply {
                addUpdateListener { anim ->
                    val p = anim.animatedValue as Float
                    // Smoothly fade out the closing folder over the final 25% of the transition
                    folder.alpha = if (p < 0.75f) 1f else (1f - (p - 0.75f) / 0.25f)
                    // Smoothly fade in the resting widget panel contents over the final 30%
                    val widgetAlpha = if (p < 0.70f) 0f else ((p - 0.70f) / 0.30f)
                    widget.panel.header.alpha = widgetAlpha
                    widget.panel.recyclerView.alpha = widgetAlpha
                    widget.panel.openButton.alpha = widgetAlpha
                }
            }
            asSet.play(closeFade)
        }

        asSet.addListener(object : AnimatorListenerAdapter() {
            private var cellLayout: CellLayout? = null
            private var contentClipChildren = false
            private var cellLayoutClipChildren = false

            override fun onAnimationStart(animation: Animator) {
                cellLayout = folder.getContent().currentCellLayout
                contentClipChildren = folder.getContent().clipChildren
                cellLayoutClipChildren = cellLayout?.clipChildren ?: false
                folder.getContent().clipChildren = false
                cellLayout?.clipChildren = false

                widget.panel.contentVisible = false

                if (opening) {
                    folder.visibility = View.VISIBLE
                    folder.alpha = 1f
                    folder.translationX = xDistance
                    folder.translationY = yDistance
                    folder.getFolderName().alpha = 0f
                    folder.mFooter.alpha = 0f
                    for (st in fliers) {
                        st.view.translationX = st.deltaX
                        st.view.translationY = st.deltaY
                        st.view.scaleX = st.startScale
                        st.view.scaleY = st.startScale
                    }
                }
            }

            override fun onAnimationEnd(animation: Animator) {
                folder.getContent().clipChildren = contentClipChildren
                cellLayout?.clipChildren = cellLayoutClipChildren

                if (!opening) {
                    folder.visibility = View.GONE
                    folder.alpha = 0f
                    widget.panel.contentVisible = true
                    widget.panel.header.alpha = 1f
                    widget.panel.recyclerView.alpha = 1f
                    widget.panel.openButton.alpha = 1f
                } else {
                    folder.translationX = 0f
                    folder.translationY = 0f
                    folder.getFolderName().alpha = 1f
                    folder.mFooter.alpha = 1f
                    for (v in folderItems) {
                        v.alpha = 1f
                        v.translationX = 0f
                        v.translationY = 0f
                        v.scaleX = 1f
                        v.scaleY = 1f
                        getBubbleTextView(v)?.setTextVisibility(true)
                    }
                }
            }
        })

        asSet.duration = duration.toLong()
        for (anim in asSet.childAnimations) {
            anim.interpolator = interpolator
        }
        return asSet
    }

    private fun findVisibleWidgetChild(grid: RecyclerView, target: ItemInfo): View? {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            val info = child.tag as? ItemInfo ?: continue
            val sameItem = info.id == target.id ||
                (info.cellX == target.cellX && info.cellY == target.cellY && info.rank == target.rank) ||
                (info.targetComponent != null && info.targetComponent == target.targetComponent)
            if (sameItem && child.top < grid.height && child.bottom > 0) {
                return child
            }
        }
        return null
    }

    private fun getBubbleTextView(v: View): BubbleTextView? = when (v) {
        is BubbleTextView -> v
        is AppPairIcon -> v.titleTextView
        else -> null
    }
}
