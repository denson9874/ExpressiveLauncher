package app.lawnchair.smartspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmartspacerConfigTest {

    @Test
    fun targetCount_isClampedToSdkSafeRange() {
        assertThat(sanitizeSmartspacerTargetCount(Int.MIN_VALUE)).isEqualTo(1)
        assertThat(sanitizeSmartspacerTargetCount(5)).isEqualTo(5)
        assertThat(sanitizeSmartspacerTargetCount(Int.MAX_VALUE)).isEqualTo(20)
    }
}
