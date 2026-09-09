package app.lawnchair.ui.preferences.about

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AboutEasterEggTest {

    @Test
    fun fiveTaps_progressesThroughHints_thenCelebratesAndResets() {
        val expectedMessages = ABOUT_DAILY_SNARK_PACKS.first().messageResIds

        var tapCount = 0
        expectedMessages.forEachIndexed { index, expectedMessage ->
            val result = nextAboutEasterEggTap(tapCount, expectedMessages)
            assertThat(result.messageResId).isEqualTo(expectedMessage)
            assertThat(result.showCelebration).isEqualTo(index == expectedMessages.lastIndex)
            tapCount = result.nextTapCount
        }

        assertThat(tapCount).isEqualTo(0)
    }

    @Test
    fun outOfRangeTapCount_isSanitizedToSafeFinalTap() {
        val messages = ABOUT_DAILY_SNARK_PACKS.first().messageResIds
        val result = nextAboutEasterEggTap(Int.MAX_VALUE, messages)

        assertThat(result.messageResId).isEqualTo(messages.last())
        assertThat(result.showCelebration).isTrue()
        assertThat(result.nextTapCount).isEqualTo(0)
    }
}
