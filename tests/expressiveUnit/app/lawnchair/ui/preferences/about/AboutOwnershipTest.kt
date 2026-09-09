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
        val links = expressiveProductLinks()

        assertThat(links.map { it.labelResId }).containsExactly(
            R.string.news,
            R.string.support,
            R.string.github,
            R.string.donate,
        ).inOrder()
        assertThat(links.map { it.url }).containsExactly(
            "https://github.com/denson9874/ExpressiveLauncher/releases",
            "https://github.com/denson9874/ExpressiveLauncher/issues",
            "https://github.com/denson9874/ExpressiveLauncher",
            "https://www.paypal.com/ncp/payment/9RB3TYYQ6FWE2",
        ).inOrder()
    }

    @Test
    fun newsDestination_usesOwnedGitHubReleases() {
        assertThat(AboutDestinations.RELEASE_BUILDS_URL)
            .isEqualTo("https://github.com/denson9874/ExpressiveLauncher/releases")
    }

    @Test
    fun brandingSelection_scopesDarylOwnershipToExpressiveBuilds() {
        val expressive = aboutBranding(
            isExpressiveProduct = true,
        )
        val lawnchair = aboutBranding(
            isExpressiveProduct = false,
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
