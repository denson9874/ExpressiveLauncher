package app.lawnchair.ui.preferences.about

import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AboutOwnershipTest {

    @Test
    fun productOwner_isOnlyDarylWithBundledAvatarAndGitHubProfile() {
        val owners = expressiveProductOwners()

        assertThat(owners).hasSize(1)
        assertThat(owners.single()).isEqualTo(
            TeamMember(
                name = "Daryl Denson",
                role = Role.DesignAndDevelopment,
                photoResId = R.drawable.about_daryl_denson,
                socialUrl = "https://github.com/denson9874",
            ),
        )
    }

    @Test
    fun productLinks_useOwnedDestinations() {
        val links = expressiveProductLinks("dev.launcher.expressive.l3.debug")

        assertThat(links.map { it.labelResId }).containsExactly(
            R.string.news,
            R.string.support,
            R.string.github,
            R.string.donate,
        ).inOrder()
        assertThat(links.map { it.url }).containsExactly(
            "https://play.google.com/store/apps/details?id=dev.launcher.expressive.l3",
            "https://github.com/denson9874",
            "https://github.com/denson9874",
            "https://www.paypal.com/ncp/payment/9RB3TYYQ6FWE2",
        ).inOrder()
    }

    @Test
    fun playListing_preservesCustomReleaseApplicationId() {
        assertThat(AboutDestinations.playStoreListingUrl("com.example.expressive"))
            .isEqualTo("https://play.google.com/store/apps/details?id=com.example.expressive")
    }

    @Test
    fun brandingSelection_scopesDarylOwnershipToExpressiveBuilds() {
        val expressive = aboutBranding(
            isExpressiveProduct = true,
            applicationId = "dev.launcher.expressive.l3.debug",
        )
        val lawnchair = aboutBranding(
            isExpressiveProduct = false,
            applicationId = "app.lawnchair.play.debug",
        )

        assertThat(expressive.coreTeam.map { it.name }).containsExactly("Daryl Denson")
        assertThat(expressive.supportAndPr).isEmpty()
        assertThat(expressive.bottomLinks).isEmpty()
        assertThat(lawnchair.coreTeam.map { it.name }).contains("Amogh Lele")
        assertThat(lawnchair.coreTeam.map { it.name }).doesNotContain("Daryl Denson")
        assertThat(lawnchair.topLinks.map { it.url })
            .contains("https://github.com/LawnchairLauncher/lawnchair")
    }
}
