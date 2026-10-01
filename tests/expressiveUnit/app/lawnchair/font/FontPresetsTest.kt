package app.lawnchair.font

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FontPresetsTest {

    @Test
    fun presetsAreOfflineSystemFamiliesWithReadableNames() {
        val families = FontPresets.SYSTEM_PRESETS.map { it.family }
        assertThat(families).containsNoDuplicates()
        // The three styles the picker always offered stay first, in the same order.
        assertThat(families.take(3)).containsExactly("sans-serif", "sans-serif-medium", "sans-serif-condensed").inOrder()
        assertThat(families).containsAtLeast("serif", "monospace", "casual", "cursive")
        FontPresets.SYSTEM_PRESETS.forEach { assertThat(it.displayName).isNotEmpty() }
        assertThat(FontPresets.SYSTEM_PRESETS.map { it.displayName }).containsNoDuplicates()
    }

    @Test
    fun presetsAreFreeAndImportsArePro() {
        assertThat(FontPresets.isFree(FontPresets.Source.SYSTEM)).isTrue()
        assertThat(FontPresets.isFree(FontPresets.Source.BUNDLED)).isTrue()
        assertThat(FontPresets.isFree(FontPresets.Source.GOOGLE_FONTS)).isFalse()
        assertThat(FontPresets.isFree(FontPresets.Source.IMPORTED)).isFalse()
    }

    @Test
    fun sectionsShownDependOnPro() {
        assertThat(FontPresets.sections(isPro = false)).containsExactly(FontPresets.Section.PRESETS)
        assertThat(FontPresets.sections(isPro = true))
            .containsExactly(FontPresets.Section.PRESETS, FontPresets.Section.IMPORTED, FontPresets.Section.CATALOG)
            .inOrder()
    }
}
