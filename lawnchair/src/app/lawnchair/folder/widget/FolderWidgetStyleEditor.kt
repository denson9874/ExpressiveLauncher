package app.lawnchair.folder.widget

class FolderWidgetStyleEditor(private val isPro: Boolean) {
    fun withColumns(s: FolderWidgetStyle, c: Int?): FolderWidgetStyle = s.copy(columns = c)

    fun withShowLabels(s: FolderWidgetStyle, v: Boolean): FolderWidgetStyle = s.copy(showLabels = v)

    fun withShowHeader(s: FolderWidgetStyle, v: Boolean): FolderWidgetStyle = s.copy(showHeader = v)

    fun withShowNameBelow(s: FolderWidgetStyle, v: Boolean): FolderWidgetStyle = s.copy(showNameBelow = v)

    fun withBackgroundColor(s: FolderWidgetStyle, c: Int?): FolderWidgetStyle =
        if (isPro) s.copy(backgroundColor = c) else s

    fun withBackgroundOpacity(s: FolderWidgetStyle, o: Float?): FolderWidgetStyle =
        if (isPro) s.copy(backgroundOpacity = o) else s

    fun withCornerRadius(s: FolderWidgetStyle, r: Float?): FolderWidgetStyle =
        if (isPro) s.copy(cornerRadiusPx = r) else s

    fun withIconScale(s: FolderWidgetStyle, k: Float?): FolderWidgetStyle =
        if (isPro) s.copy(iconScale = k) else s

    fun reset(): FolderWidgetStyle = FolderWidgetStyle()
}
