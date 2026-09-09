package app.lawnchair.smartspace.provider

import app.lawnchair.smartspace.model.SmartspaceTarget
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmartspaceProviderPolicyTest {

    @Test
    fun expressiveProduct_leadsWithDateAndPreservesFunctionalCards() {
        val setup = target("setup", SmartspaceTarget.FeatureType.FEATURE_TIPS, score = 999f)
        val onboarding = target(
            "onboarding",
            SmartspaceTarget.FeatureType.FEATURE_ONBOARDING,
            score = 1000f,
        )
        val date = target("date", SmartspaceTarget.FeatureType.FEATURE_WEATHER)
        val battery = target("battery", SmartspaceTarget.FeatureType.FEATURE_CALENDAR, score = 2f)

        val result = applyExpressiveSmartspaceTargetPolicy(
            listOf(setup, onboarding, date, battery),
            isExpressiveProduct = true,
        ).sortedByDescending { it.score }

        assertThat(result.map { it.id }).containsExactly("date", "setup", "battery").inOrder()
        assertThat(result.first().featureType)
            .isEqualTo(SmartspaceTarget.FeatureType.FEATURE_WEATHER)
    }

    @Test
    fun expressiveProduct_promotesLiveWeatherAsTheDateCard() {
        val weather = target("live-weather", SmartspaceTarget.FeatureType.FEATURE_WEATHER, score = 1f)
        val setup = target("setup", SmartspaceTarget.FeatureType.FEATURE_TIPS, score = 999f)

        val result = applyExpressiveSmartspaceTargetPolicy(
            listOf(setup, weather),
            isExpressiveProduct = true,
        ).sortedByDescending { it.score }

        assertThat(result.map { it.id }).containsExactly("live-weather", "setup").inOrder()
    }

    @Test
    fun nonExpressiveProduct_keepsOriginalTargetsAndScores() {
        val targets = listOf(
            target("onboarding", SmartspaceTarget.FeatureType.FEATURE_ONBOARDING, score = 1000f),
            target("date", SmartspaceTarget.FeatureType.FEATURE_WEATHER),
        )

        assertThat(
            applyExpressiveSmartspaceTargetPolicy(targets, isExpressiveProduct = false),
        ).isEqualTo(targets)
    }

    private fun target(
        id: String,
        featureType: SmartspaceTarget.FeatureType,
        score: Float = 0f,
    ) = SmartspaceTarget(
        id = id,
        score = score,
        featureType = featureType,
    )
}
