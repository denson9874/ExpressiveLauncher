package app.lawnchair.qsb

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GoogleSearchBarSetupTest {

    @Test
    fun withoutGoogleSearchWidget_theSwitchCannotTurnOn() {
        assertThat(googleSearchBarStep(providerAvailable = false, alreadyBound = false, boundWithoutAsking = false))
            .isEqualTo(GoogleSearchBarStep.UNAVAILABLE)
    }

    @Test
    fun anExistingOrSilentlyAllowedBindingSwitchesWithoutAPrompt() {
        assertThat(googleSearchBarStep(providerAvailable = true, alreadyBound = true, boundWithoutAsking = false))
            .isEqualTo(GoogleSearchBarStep.READY)
        assertThat(googleSearchBarStep(providerAvailable = true, alreadyBound = false, boundWithoutAsking = true))
            .isEqualTo(GoogleSearchBarStep.READY)
    }

    @Test
    fun otherwiseAndroidIsAskedOnlyAfterTheUserTurnsTheSwitchOn() {
        assertThat(googleSearchBarStep(providerAvailable = true, alreadyBound = false, boundWithoutAsking = false))
            .isEqualTo(GoogleSearchBarStep.ASK_ANDROID)
    }
}
