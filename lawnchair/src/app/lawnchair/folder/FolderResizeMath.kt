package app.lawnchair.folder

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

enum class ResizeHandle { LEFT, TOP, RIGHT, BOTTOM }

/** Large folders v2: resize-frame math. A folder is either 1x1 or 2x2. */
object FolderResizeMath {

    private const val SNAP_FRACTION = 0.5f
    private const val RUBBER_BAND_FRACTION = 0.15f

    private fun outwardSign(handle: ResizeHandle): Float = when (handle) {
        ResizeHandle.RIGHT, ResizeHandle.BOTTOM -> 1f
        ResizeHandle.LEFT, ResizeHandle.TOP -> -1f
    }

    @JvmStatic
    fun targetFor(current: CellRect, handle: ResizeHandle): CellRect {
        return if (current.spanX == 1) {
            when (handle) {
                ResizeHandle.RIGHT, ResizeHandle.BOTTOM -> CellRect(current.x, current.y, 2, 2)
                ResizeHandle.LEFT -> CellRect(current.x - 1, current.y, 2, 2)
                ResizeHandle.TOP -> CellRect(current.x, current.y - 1, 2, 2)
            }
        } else {
            when (handle) {
                ResizeHandle.RIGHT, ResizeHandle.BOTTOM -> CellRect(current.x, current.y, 1, 1)
                ResizeHandle.LEFT -> CellRect(current.x + 1, current.y, 1, 1)
                ResizeHandle.TOP -> CellRect(current.x, current.y + 1, 1, 1)
            }
        }
    }

    @JvmStatic
    fun shouldSnap(dragPx: Float, cellPx: Float, current: CellRect, handle: ResizeHandle): Boolean {
        val wanted = if (current.spanX == 1) outwardSign(handle) else -outwardSign(handle)
        return dragPx * wanted > cellPx * SNAP_FRACTION
    }

    @JvmStatic
    fun isAllowed(target: CellRect, countX: Int, countY: Int, ownerAt: (Int, Int) -> CellOwner): Boolean =
        if (target.spanX == 1) {
            target.x in 0 until countX && target.y in 0 until countY &&
                ownerAt(target.x, target.y) == CellOwner.EMPTY
        } else {
            LargeFolderOverlap.canPlace(target, countX, countY, ownerAt)
        }

    @JvmStatic
    fun rubberBand(dragPx: Float, cellPx: Float): Float {
        val max = cellPx * RUBBER_BAND_FRACTION
        return sign(dragPx) * max * tanh(abs(dragPx) / (2 * max))
    }
}
