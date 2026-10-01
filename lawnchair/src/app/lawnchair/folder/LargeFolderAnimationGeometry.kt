package app.lawnchair.folder

data class Box(val left: Float, val top: Float, val size: Float) {
    val centerX: Float get() = left + size / 2f
    val centerY: Float get() = top + size / 2f
}

/** Large folders v2: where the tile draws each app, shared by drawing and every animation. */
object LargeFolderAnimationGeometry {

    @JvmStatic
    @JvmOverloads
    fun slotBox(tile: Box, slot: Int, iconFraction: Float = LargeFolderTile.ICON_FRACTION): Box {
        val slotSize = tile.size / 2f
        val slotLeft = tile.left + (slot % 2) * slotSize
        val slotTop = tile.top + (slot / 2) * slotSize
        val icon = slotSize * iconFraction
        return Box(slotLeft + (slotSize - icon) / 2f, slotTop + (slotSize - icon) / 2f, icon)
    }

    @JvmStatic
    fun miniBox(tile: Box, index: Int): Box {
        val more = slotBox(tile, LargeFolders.DIRECT_SLOTS - 1)
        val cell = more.size / 2f
        val icon = cell * LargeFolderTile.MINI_ICON_FRACTION
        val left = more.left + (index % 2) * cell + (cell - icon) / 2f
        val top = more.top + (index / 2) * cell + (cell - icon) / 2f
        return Box(left, top, icon)
    }

    @JvmStatic
    fun boxForRank(tile: Box, rank: Int, itemCount: Int): Box? {
        val slots = LargeFolders.slotContents(itemCount)
        val direct = slots.indexOf(rank)
        if (direct >= 0) return slotBox(tile, direct)
        val firstMini = LargeFolders.DIRECT_SLOTS - 1
        val mini = rank - firstMini
        return if (slots.contains(LargeFolders.SLOT_MORE) && mini in 0 until 4) miniBox(tile, mini) else null
    }

    @JvmStatic
    fun cornerRadius(tile: Box): Float = tile.size * LargeFolderTile.CORNER_FRACTION

    @JvmStatic
    fun fraction(t: Float): Float = t.coerceIn(0f, 1f)

    @JvmStatic
    fun lerp(a: Box, b: Box, t: Float): Box {
        val f = fraction(t)
        return Box(a.left + (b.left - a.left) * f, a.top + (b.top - a.top) * f, a.size + (b.size - a.size) * f)
    }
}
