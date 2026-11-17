package app.lawnchair.qsb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QsbClassicStyleTest {

    @Test
    fun innerRadiusIsFrameRadiusLessInset() {
        assertThat(classicInnerCornerRadiusPx(frameCornerRadiusPx = 78f, insetPx = 15f)).isEqualTo(63f)
    }

    @Test
    fun innerRadiusNeverGoesNegative() {
        assertThat(classicInnerCornerRadiusPx(frameCornerRadiusPx = 6f, insetPx = 15f)).isEqualTo(0f)
        assertThat(classicInnerCornerRadiusPx(frameCornerRadiusPx = 0f, insetPx = 15f)).isEqualTo(0f)
    }
}
