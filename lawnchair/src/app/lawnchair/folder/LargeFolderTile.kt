package app.lawnchair.folder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.android.launcher3.BubbleTextView
import com.android.launcher3.apppairs.AppPairIconDrawingParams
import com.android.launcher3.apppairs.AppPairIconGraphic
import com.android.launcher3.icons.BitmapInfo.Companion.FLAG_THEMED
import com.android.launcher3.model.data.AppPairInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.ItemInfoWithIcon
import com.android.launcher3.model.data.WorkspaceItemInfo
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a large (2x2) Home screen folder: a rounded tile with the first apps as full icons, and
 * with more than four apps a last slot that previews the rest. Also maps touches to those apps.
 */
class LargeFolderTile(private val context: Context) {

    /** The tile, in the folder icon's coordinates. */
    val bounds = RectF()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var items: List<ItemInfo> = emptyList()
    private var drawables: List<Drawable?> = emptyList()
    private var slots = LargeFolders.slotContents(0)

    /** Scale while an app is dragged over the folder, as feedback that it will be added. */
    var acceptScale = 1f

    /** Lays the tile out in a [width] wide view, starting [top] px down, at most [maxSize]. */
    fun layout(width: Int, top: Int, maxSize: Int) {
        val size = min(width, maxSize).coerceAtLeast(0).toFloat()
        val left = (width - size) / 2f
        bounds.set(left, top.toFloat(), left + size, top + size)
    }

    /** Folder contents in rank order. */
    fun setItems(contents: List<ItemInfo>) {
        items = contents.sortedBy { it.rank }
        drawables = items.map(::newIcon)
        slots = LargeFolders.slotContents(items.size)
    }

    fun draw(canvas: Canvas, backgroundColor: Int) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.scale(acceptScale, acceptScale, bounds.centerX(), bounds.centerY())

        paint.color = if (acceptScale > 1f) {
            ColorUtils.setAlphaComponent(backgroundColor, (backgroundColor ushr 24).coerceAtLeast(0xE6))
        } else {
            backgroundColor
        }
        val radius = bounds.width() * CORNER_FRACTION
        canvas.drawRoundRect(bounds, radius, radius, paint)

        val box = Box(bounds.left, bounds.top, bounds.width())
        if (highlightRank >= 0) {
            // The slot a hovering app will take: the next free slot, or the "more" slot.
            val slot = minOf(highlightRank, LargeFolders.DIRECT_SLOTS - 1)
            val hb = LargeFolderAnimationGeometry.slotBox(box, slot, 0.92f)
            val r = hb.size * CORNER_FRACTION
            paint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(backgroundColor, 0xFFFFFFFF.toInt(), 0.35f), 0xFF)
            canvas.drawRoundRect(hb.left, hb.top, hb.left + hb.size, hb.top + hb.size, r, r, paint)
        }
        slots.forEachIndexed { slot, content ->
            when {
                content >= 0 && content != hiddenRank ->
                    drawIconIn(canvas, drawables[content], LargeFolderAnimationGeometry.slotBox(box, slot))
                content == LargeFolders.SLOT_MORE -> for (i in 0 until 4) {
                    val rank = LargeFolders.DIRECT_SLOTS - 1 + i
                    val d = drawables.getOrNull(rank) ?: break
                    if (rank != hiddenRank) drawIconIn(canvas, d, LargeFolderAnimationGeometry.miniBox(box, i))
                }
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawIconIn(canvas: Canvas, drawable: Drawable?, b: Box) {
        drawable ?: return
        drawable.setBounds(
            b.left.roundToInt(),
            b.top.roundToInt(),
            (b.left + b.size).roundToInt(),
            (b.top + b.size).roundToInt(),
        )
        drawable.draw(canvas)
    }

    /** Bounds of the app at [rank] in this tile, or null when the tile doesn't draw it. */
    fun boxForRank(rank: Int): Box? =
        LargeFolderAnimationGeometry.boxForRank(Box(bounds.left, bounds.top, bounds.width()), rank, items.size)

    /** The drawable for [rank], for launch and drop animations. */
    fun drawableForRank(rank: Int): Drawable? = drawables.getOrNull(rank)

    /** One rank hidden while an animation draws it elsewhere (-1 = none). */
    var hiddenRank = -1

    /** Rank a hovering app would take, highlighted while it hovers (-1 = none). */
    var highlightRank = -1

    /** Where an app added at [rank] will be drawn (the "more" slot if past the direct slots). */
    fun boxForRankAfterAdd(rank: Int): Box {
        val box = Box(bounds.left, bounds.top, bounds.width())
        return LargeFolderAnimationGeometry.boxForRank(box, rank, items.size + 1)
            ?: LargeFolderAnimationGeometry.slotBox(box, LargeFolders.DIRECT_SLOTS - 1)
    }

    /**
     * The app shown at ([x], [y]), or null when the touch is on the preview slot, an empty slot or
     * outside the tile (those open the folder).
     */
    fun itemAt(x: Float, y: Float): ItemInfo? {
        val slot = LargeFolders.slotAt(x, y, bounds.left, bounds.top, bounds.width())
        if (slot < 0) return null
        return items.getOrNull(slots[slot])
    }

    private fun newIcon(item: ItemInfo): Drawable? = when (item) {
        is WorkspaceItemInfo -> item.newIcon(context, FLAG_THEMED)
        is AppPairInfo -> AppPairIconGraphic.composeDrawable(
            item,
            AppPairIconDrawingParams(context, BubbleTextView.DISPLAY_FOLDER),
        )
        is ItemInfoWithIcon -> item.newIcon(context, 0)
        else -> null
    }

    companion object {
        /** Corner radius as a fraction of the tile size, close to the folder icon's own shape. */
        const val CORNER_FRACTION = 0.22f

        /** App icon size as a fraction of its slot. */
        const val ICON_FRACTION = 0.74f

        /** Preview icon size as a fraction of its quarter of the preview slot. */
        const val MINI_ICON_FRACTION = 0.86f
    }
}
