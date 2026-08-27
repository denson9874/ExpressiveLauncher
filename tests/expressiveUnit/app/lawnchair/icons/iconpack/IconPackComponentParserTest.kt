package app.lawnchair.icons.iconpack

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IconPackComponentParserTest {

    @Test
    fun componentInfoWrapper_isNormalized() {
        val normalized = normalizeIconPackComponentName(
            "  ComponentInfo{com.example/.MainActivity}  ",
        )

        assertThat(normalized).isEqualTo("com.example/.MainActivity")
    }

    @Test
    fun flattenedComponent_isPreserved() {
        assertThat(normalizeIconPackComponentName("com.example/com.example.MainActivity"))
            .isEqualTo("com.example/com.example.MainActivity")
    }

    @Test
    fun blankOrEmptyWrapper_isRejected() {
        assertThat(normalizeIconPackComponentName("   ")).isNull()
        assertThat(normalizeIconPackComponentName("ComponentInfo{}")).isNull()
    }
}
