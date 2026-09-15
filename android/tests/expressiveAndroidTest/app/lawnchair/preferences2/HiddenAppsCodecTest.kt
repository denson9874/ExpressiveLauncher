package app.lawnchair.preferences2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HiddenAppsCodecTest {

    @Test
    fun encryptedValueRoundTripsWithoutExposingComponentNames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val codec = HiddenAppsCodec(context)
        val values = setOf(
            "com.example/.HiddenActivity#UserHandle{0}",
            "com.example.second/.MainActivity#UserHandle{0}",
        )

        val encrypted = codec.encrypt(values)

        assertThat(encrypted).doesNotContain("com.example")
        assertThat(codec.decrypt(encrypted)).containsExactlyElementsIn(values)
    }

    @Test
    fun corruptCiphertextFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(HiddenAppsCodec(context).decrypt("v1:not-valid:not-valid")).isEmpty()
    }
}
