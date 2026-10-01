package app.lawnchair.font

/**
 * Preloaded font styles (XDA suggestion, 2026-10-01): offline styles everyone can pick, while
 * importing font files and the downloadable Google Fonts catalog stay Expressive Pro.
 */
object FontPresets {

    /** A system font family that ships with Android, so it works offline. */
    data class Preset(val family: String, val displayName: String)

    val SYSTEM_PRESETS = listOf(
        Preset("sans-serif", "System Sans"),
        Preset("sans-serif-medium", "System Sans Medium"),
        Preset("sans-serif-condensed", "System Sans Condensed"),
        Preset("sans-serif-light", "System Sans Light"),
        Preset("sans-serif-black", "System Sans Black"),
        Preset("sans-serif-smallcaps", "System Small Caps"),
        Preset("serif", "System Serif"),
        Preset("monospace", "System Mono"),
        Preset("serif-monospace", "System Typewriter"),
        Preset("casual", "System Casual"),
        Preset("cursive", "System Cursive"),
    )

    /** Where a font comes from. */
    enum class Source { SYSTEM, BUNDLED, GOOGLE_FONTS, IMPORTED }

    /** Picker sections, in display order. */
    enum class Section { PRESETS, IMPORTED, CATALOG }

    @JvmStatic
    fun isFree(source: Source): Boolean = source == Source.SYSTEM || source == Source.BUNDLED

    @JvmStatic
    fun sections(isPro: Boolean): List<Section> =
        if (isPro) listOf(Section.PRESETS, Section.IMPORTED, Section.CATALOG) else listOf(Section.PRESETS)
}
