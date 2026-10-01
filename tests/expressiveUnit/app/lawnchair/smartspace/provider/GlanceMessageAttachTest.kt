package app.lawnchair.smartspace.provider

import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceTarget
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlanceMessageAttachTest {

    @Test
    fun message_joinsTheDateCardInsteadOfAddingACard() {
        val weather = SmartspaceTarget(
            id = "weather",
            headerAction = SmartspaceAction(id = "weather-action", title = "", subtitle = "72°F"),
            featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
        )
        val battery = target("battery", SmartspaceTarget.FeatureType.FEATURE_CALENDAR)

        val result = attachGlanceMessage(listOf(message("Good morning, Cakey"), weather, battery))

        assertThat(result.map { it.id }).containsExactly("weather", "battery").inOrder()
        assertThat(result.first().glanceMessage.toString()).isEqualTo("Good morning, Cakey")
        assertThat(result.first().headerAction).isEqualTo(weather.headerAction)
        assertThat(result.last().glanceMessage).isNull()
    }

    @Test
    fun message_reachesTheDateOnlyCardThatHasNoHeader() {
        val dateOnly = target("dummyTarget", SmartspaceTarget.FeatureType.FEATURE_WEATHER)

        val result = attachGlanceMessage(listOf(dateOnly, message("You've got this")))

        assertThat(result).hasSize(1)
        assertThat(result.single().id).isEqualTo("dummyTarget")
        assertThat(result.single().glanceMessage.toString()).isEqualTo("You've got this")
    }

    @Test
    fun withoutADateCard_theMessageStaysAsItsOwnCard() {
        val battery = target("battery", SmartspaceTarget.FeatureType.FEATURE_CALENDAR)
        val standalone = message("Happy Halloween")

        assertThat(attachGlanceMessage(listOf(battery, standalone)))
            .containsExactly(battery, standalone)
            .inOrder()
    }

    @Test
    fun withoutAMessage_targetsAreUnchanged() {
        val targets = listOf(
            target("dummyTarget", SmartspaceTarget.FeatureType.FEATURE_WEATHER),
            target("battery", SmartspaceTarget.FeatureType.FEATURE_CALENDAR),
        )

        assertThat(attachGlanceMessage(targets)).isEqualTo(targets)
    }

    private fun message(text: String) = glanceMessageTarget(text)

    private fun target(id: String, featureType: SmartspaceTarget.FeatureType) =
        SmartspaceTarget(id = id, featureType = featureType)
}
