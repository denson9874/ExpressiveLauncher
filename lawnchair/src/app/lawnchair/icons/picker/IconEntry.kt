package app.lawnchair.icons.picker

data class IconEntry(
    val packPackageName: String,
    val name: String,
    val type: IconType,
) {
    fun resolveDynamicCalendar(day: Int): IconEntry {
        check(type == IconType.Calendar) { "type is not calendar" }
        return IconEntry(packPackageName, "$name${day + 1}", IconType.Normal)
    }
}

enum class IconType {
    Normal,
    Calendar,

    /** A square image the user cropped; [IconEntry.name] is its file in CustomIconImages. */
    Image,
}
