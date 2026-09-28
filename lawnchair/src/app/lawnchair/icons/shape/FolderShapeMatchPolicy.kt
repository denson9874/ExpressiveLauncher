package app.lawnchair.icons.shape

/**
 * Whether folder icons borrow the app icon shape.
 *
 * [AUTO] keeps folders in step with the app icon shape until the user picks a folder shape of
 * their own, so existing customized folder shapes are never overridden by an update.
 */
enum class FolderShapeMatch(val key: String) {
    AUTO("auto"),
    ON("on"),
    OFF("off"),
    ;

    companion object {
        fun fromKey(value: String?): FolderShapeMatch = entries.firstOrNull { it.key == value } ?: AUTO
    }
}

object FolderShapeMatchPolicy {

    /**
     * @param folderShapeIsDefault true while the stored folder shape still equals the shipped default,
     * i.e. the user has not chosen a folder shape of their own.
     */
    fun matchesIconShape(match: FolderShapeMatch, folderShapeIsDefault: Boolean): Boolean = when (match) {
        FolderShapeMatch.ON -> true
        FolderShapeMatch.OFF -> false
        FolderShapeMatch.AUTO -> folderShapeIsDefault
    }

    fun resolve(
        match: FolderShapeMatch,
        iconShape: IconShape,
        folderShape: IconShape,
        defaultFolderShape: IconShape,
    ): IconShape {
        val folderShapeIsDefault = folderShape.toString() == defaultFolderShape.toString()
        return if (matchesIconShape(match, folderShapeIsDefault)) iconShape else folderShape
    }
}
