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

        val slotSize = bounds.width() / 2f
        slots.forEachIndexed { slot, content ->
            val slotLeft = bounds.left + (slot % 2) * slotSize
            val slotTop = bounds.top + (slot / 2) * slotSize
            when {
                content >= 0 -> drawIcon(canvas, drawables[content], slotLeft, slotTop, slotSize, ICON_FRACTION)
                content == LargeFolders.SLOT_MORE -> drawMore(canvas, slotLeft, slotTop, slotSize)
            }
        }
        canvas.restoreToCount(save)
    }

    /** Previews up to four of the apps that don't have their own slot. */
    private fun drawMore(canvas: Canvas, left: Float, top: Float, size: Float) {
        val first = LargeFolders.DIRECT_SLOTS - 1
        val inner = size * ICON_FRACTION
        val innerLeft = left + (size - inner) / 2f
        val innerTop = top + (size - inner) / 2f
        val cell = inner / 2f
        for (i in 0 until 4) {
            val drawable = drawables.getOrNull(first + i) ?: break
            drawIcon(canvas, drawable, innerLeft + (i % 2) * cell, innerTop + (i / 2) * cell, cell, MINI_ICON_FRACTION)
        }
    }

    private fun drawIcon(canvas: Canvas, drawable: Drawable?, left: Float, top: Float, slot: Float, fraction: Float) {
        drawable ?: return
        val size = (slot * fraction).roundToInt()
        val x = (left + (slot - size) / 2f).roundToInt()
        val y = (top + (slot - size) / 2f).roundToInt()
        drawable.setBounds(x, y, x + size, y + size)
        drawable.draw(canvas)
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
