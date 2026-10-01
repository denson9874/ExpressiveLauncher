package app.lawnchair.smartspace

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceTarget
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        BcSmartspaceDateClickTest.ShadowFontManager::class,
        BcSmartspaceDateClickTest.ShadowPreferenceManager::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class BcSmartspaceGlanceMessageTest {

    private val context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.LauncherTheme,
    )

    @Test
    fun dateOnlyCard_showsTheMessageUnderTheDate() {
        val card = inflateDateCard()

        card.setSmartspaceTarget(
            SmartspaceTarget(
                id = "dummyTarget",
                featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
                glanceMessage = "Good morning, Cakey ❤️",
            ),
            multipleCards = false,
        )

        assertThat(card.subtitle().text.toString()).isEqualTo("Good morning, Cakey ❤️")
        assertThat(card.subtitle().contentDescription.toString()).isEqualTo("Good morning, Cakey ❤️")
    }

    @Test
    fun weatherCard_keepsTheTemperatureFirstAndAddsTheMessage() {
        val card = inflateDateCard()

        card.setSmartspaceTarget(
            SmartspaceTarget(
                id = "weather",
                headerAction = SmartspaceAction(id = "weather-action", title = "", subtitle = "72°F"),
                featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
                glanceMessage = "Have a wonderful day, Laura 🍰",
            ),
            multipleCards = false,
        )

        assertThat(card.subtitle().text.toString()).isEqualTo("72°F · Have a wonderful day, Laura 🍰")
        assertThat(card.subtitle().maxLines).isEqualTo(1)
    }

    @Test
    fun rebindingWithoutAMessage_clearsThePreviousOne() {
        val card = inflateDateCard()
        card.setSmartspaceTarget(
            SmartspaceTarget(
                id = "dummyTarget",
                featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
                glanceMessage = "Sweet dreams, Cakey 🌙",
            ),
            multipleCards = false,
        )

        card.setSmartspaceTarget(
            SmartspaceTarget(id = "dummyTarget", featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER),
            multipleCards = false,
        )

        assertThat(card.subtitle().text.toString()).isEmpty()
    }

    @Test
    fun weatherCardWithoutAMessage_isUnchanged() {
        val card = inflateDateCard()

        card.setSmartspaceTarget(
            SmartspaceTarget(
                id = "weather",
                headerAction = SmartspaceAction(id = "weather-action", title = "", subtitle = "72°F"),
                featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
            ),
            multipleCards = false,
        )

        assertThat(card.subtitle().text.toString()).isEqualTo("72°F")
    }

    private fun inflateDateCard(): BcSmartspaceCard =
        LayoutInflater.from(context).inflate(
            R.layout.smartspace_card_date,
            FrameLayout(context),
            false,
        ) as BcSmartspaceCard

    private fun BcSmartspaceCard.subtitle(): TextView = findViewById(R.id.subtitle_text)
}
