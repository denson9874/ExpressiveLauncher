package app.lawnchair.folder.widget

/**
 * A Folder widget's own settings. Null values follow the defaults; the background color, opacity,
 * corner radius and icon scale apply only for Pro users (they are kept, not cleared, otherwise).
 */
data class FolderWidgetStyle(
    val columns: Int? = null,
    val showLabels: Boolean = true,
    val showHeader: Boolean = true,
    val showNameBelow: Boolean = false,
    val backgroundColor: Int? = null,
    val backgroundOpacity: Float? = null,
    val cornerRadiusPx: Float? = null,
    val iconScale: Float? = null,
) {
    companion object {
        const val MIN_ICON_SCALE = 0.7f
        const val MAX_ICON_SCALE = 1.1f
    }
}

/** Defaults that match Home: the folder color and opacity and the system widget corner radius. */
data class FolderWidgetDefaults(
    val backgroundColor: Int,
    val backgroundOpacity: Float,
    val cornerRadiusPx: Float,
)

data class ResolvedFolderWidgetStyle(
    val columns: Int?,
    val showLabels: Boolean,
    val showHeader: Boolean,
    val showNameBelow: Boolean,
    val backgroundColor: Int,
    val backgroundOpacity: Float,
    val cornerRadiusPx: Float,
    val iconScale: Float,
)

fun FolderWidgetStyle.resolve(isPro: Boolean, defaults: FolderWidgetDefaults): ResolvedFolderWidgetStyle =
    ResolvedFolderWidgetStyle(
        columns = columns?.coerceIn(FolderWidgetGridMath.MIN_COLUMN_OVERRIDE, FolderWidgetGridMath.MAX_COLUMNS),
        showLabels = showLabels,
        showHeader = showHeader,
        showNameBelow = showNameBelow,
        backgroundColor = backgroundColor?.takeIf { isPro } ?: defaults.backgroundColor,
        backgroundOpacity = (backgroundOpacity?.takeIf { isPro } ?: defaults.backgroundOpacity).coerceIn(0f, 1f),
        cornerRadiusPx = (cornerRadiusPx?.takeIf { isPro } ?: defaults.cornerRadiusPx).coerceAtLeast(0f),
        iconScale = (iconScale?.takeIf { isPro } ?: 1f)
            .coerceIn(FolderWidgetStyle.MIN_ICON_SCALE, FolderWidgetStyle.MAX_ICON_SCALE),
    )
