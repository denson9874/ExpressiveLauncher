package app.lawnchair.data.folderwidget

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.lawnchair.folder.widget.FolderWidgetStyle

@Entity(tableName = "FolderWidgetStyles")
data class FolderWidgetStyleEntity(
    @PrimaryKey val folderId: Int,
    val columns: Int?,
    val showLabels: Boolean,
    val showHeader: Boolean,
    val showNameBelow: Boolean,
    val backgroundColor: Int?,
    val backgroundOpacity: Float?,
    val cornerRadiusPx: Float?,
    val iconScale: Float?,
) {
    fun toStyle(): FolderWidgetStyle = FolderWidgetStyle(
        columns = columns,
        showLabels = showLabels,
        showHeader = showHeader,
        showNameBelow = showNameBelow,
        backgroundColor = backgroundColor,
        backgroundOpacity = backgroundOpacity,
        cornerRadiusPx = cornerRadiusPx,
        iconScale = iconScale,
    )

    companion object {
        fun from(folderId: Int, style: FolderWidgetStyle): FolderWidgetStyleEntity =
            FolderWidgetStyleEntity(
                folderId = folderId,
                columns = style.columns,
                showLabels = style.showLabels,
                showHeader = style.showHeader,
                showNameBelow = style.showNameBelow,
                backgroundColor = style.backgroundColor,
                backgroundOpacity = style.backgroundOpacity,
                cornerRadiusPx = style.cornerRadiusPx,
                iconScale = style.iconScale,
            )
    }
}
