package app.lawnchair.folder.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Clips [view] to a rounded rect that moves from [start] with radius [r0] to
 * [end] with radius [r1] (reversed when closing), restoring the view's own outline afterwards.
 */
class RoundRectRevealAnimator(
    private val view: View,
    private val start: Rect,
    private val end: Rect,
    private val r0: Float,
    private val r1: Float,
    private val reversed: Boolean,
) {
    constructor(view: View, start: RoundRect, end: RoundRect, reversed: Boolean) : this(
        view = view,
        start = Rect(start.rect.left.toInt(), start.rect.top.toInt(), start.rect.right.toInt(), start.rect.bottom.toInt()),
        end = Rect(end.rect.left.toInt(), end.rect.top.toInt(), end.rect.right.toInt(), end.rect.bottom.toInt()),
        r0 = start.radius,
        r1 = end.radius,
        reversed = reversed,
    )

    fun create(): ValueAnimator {
        val cur = Rect(if (reversed) end else start)
        var radius = if (reversed) r1 else r0
        val provider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(cur, radius)
        }
        var previousProvider: ViewOutlineProvider? = null
        var previousClip = false
        return ValueAnimator.ofFloat(if (reversed) 1f else 0f, if (reversed) 0f else 1f).apply {
            addUpdateListener {
                val t = it.animatedValue as Float
                cur.set(
                    (start.left + (end.left - start.left) * t).toInt(),
                    (start.top + (end.top - start.top) * t).toInt(),
                    (start.right + (end.right - start.right) * t).toInt(),
                    (start.bottom + (end.bottom - start.bottom) * t).toInt(),
                )
                radius = r0 + (r1 - r0) * t
                view.invalidateOutline()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    previousProvider = view.outlineProvider
                    previousClip = view.clipToOutline
                    view.outlineProvider = provider
                    view.clipToOutline = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.clipToOutline = previousClip
                    view.outlineProvider = previousProvider
                }
            })
        }
    }
}
