package app.lawnchair.ui.preferences.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.create

/** Product-owned destinations shown by the About screen. */
internal object AboutDestinations {
    const val GITHUB_PROFILE_URL = "https://github.com/denson9874"
    const val PAYPAL_PAYMENT_URL = "https://www.paypal.com/ncp/payment/9RB3TYYQ6FWE2"

    /**
     * Debug builds append `.debug` to the production package ID. Removing only that known suffix
     * keeps the News tile aimed at the production Play listing during device QA.
     */
    fun playStoreListingUrl(applicationId: String): String = "https://play.google.com/store/apps/details?id=${applicationId.removeSuffix(".debug")}"
}

internal fun expressiveProductOwners(): List<TeamMember> = listOf(
    TeamMember(
        name = "Daryl Denson",
        role = Role.DesignAndDevelopment,
        photoResId = R.drawable.about_daryl_denson,
        socialUrl = AboutDestinations.GITHUB_PROFILE_URL,
    ),
)

internal fun expressiveProductLinks(applicationId: String): List<Link> = listOf(
    Link(
        iconResId = R.drawable.ic_new_releases,
        labelResId = R.string.news,
        url = AboutDestinations.playStoreListingUrl(applicationId),
    ),
    Link(
        iconResId = R.drawable.ic_help,
        labelResId = R.string.support,
        url = AboutDestinations.GITHUB_PROFILE_URL,
    ),
    Link(
        iconResId = R.drawable.ic_github,
        labelResId = R.string.github,
        url = AboutDestinations.GITHUB_PROFILE_URL,
    ),
    Link(
        iconResId = R.drawable.ic_donate,
        labelResId = R.string.donate,
        url = AboutDestinations.PAYPAL_PAYMENT_URL,
    ),
)

internal data class AboutBranding(
    val coreTeam: List<TeamMember>,
    val supportAndPr: List<TeamMember>,
    val topLinks: List<Link>,
    val bottomLinks: List<Link>,
)

/** Keeps Expressive ownership isolated without changing attribution in sibling Lawnchair builds. */
internal fun aboutBranding(
    isExpressiveProduct: Boolean,
    applicationId: String,
): AboutBranding = if (isExpressiveProduct) {
    AboutBranding(
        coreTeam = expressiveProductOwners(),
        supportAndPr = emptyList(),
        topLinks = expressiveProductLinks(applicationId),
        bottomLinks = emptyList(),
    )
} else {
    AboutBranding(
        coreTeam = lawnchairTeam,
        supportAndPr = lawnchairSupportAndPr,
        topLinks = lawnchairTopLinks,
        bottomLinks = lawnchairBottomLinks,
    )
}

class AboutViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val api: GitHubService = gitHubApiRetrofit.create()
    private val prefs: PreferenceManager = PreferenceManager.getInstance(application)
    private val prefs2: PreferenceManager2 = PreferenceManager2.getInstance(application)

    private val nightlyBuildsRepository = NightlyBuildsRepository(
        applicationContext = application,
        api = api,
    )

    val uiState: StateFlow<AboutUiState>
        field = MutableStateFlow(AboutUiState())

    val updateState = nightlyBuildsRepository.updateState

    init {
        val branding = aboutBranding(
            isExpressiveProduct = BuildConfig.IS_EXPRESSIVE_PRODUCT,
            applicationId = BuildConfig.APPLICATION_ID,
        )
        uiState.update {
            it.copy(
                versionName = if (prefs.hideVersionInfo.get()) {
                    prefs.pseudonymVersion.get() + " (pseudonym)"
                } else {
                    BuildConfig.VERSION_NAME
                },
                commitHash = BuildConfig.COMMIT_HASH,
                coreTeam = branding.coreTeam,
                supportAndPr = branding.supportAndPr,
                topLinks = branding.topLinks,
                bottomLinks = branding.bottomLinks,
            )
        }

        // Expressive has its own single-owner card and must never query Lawnchair's activity feed.
        // Other flavors retain their existing attribution and active-contributor behavior.
        if (!BuildConfig.IS_EXPRESSIVE_PRODUCT) {
            viewModelScope.launch(Dispatchers.Default) {
                val activeContributors = fetchActiveContributors()
                val updatedCoreTeam = uiState.value.coreTeam.map { member ->
                    val status = if (
                        member.githubUsername != null &&
                        activeContributors.contains(member.githubUsername.lowercase())
                    ) {
                        ContributorStatus.Active
                    } else {
                        ContributorStatus.Idle
                    }
                    member.copy(status = status)
                }
                uiState.update { it.copy(coreTeam = updatedCoreTeam) }
            }
        }

        if (BuildConfig.APPLICATION_ID.contains("nightly") && prefs2.autoUpdaterNightly.firstCached()) {
            nightlyBuildsRepository.checkForUpdate()
            viewModelScope.launch {
                nightlyBuildsRepository.updateState.collect { state ->
                    uiState.update { it.copy(updateState = state) }
                }
            }
        }
    }

    fun downloadUpdate() {
        nightlyBuildsRepository.downloadUpdate()
    }

    fun installUpdate(file: File, forceInstall: Boolean = false) {
        nightlyBuildsRepository.installUpdate(file, forceInstall)
    }

    fun resetToDownloaded(file: File) {
        nightlyBuildsRepository.resetToDownloaded(file)
    }

    private suspend fun fetchActiveContributors(): Set<String> {
        return runCatching {
            nightlyBuildsRepository.api.getRepositoryEvents("LawnchairLauncher", "lawnchair")
                .map { it.actor.login.lowercase() }
                .toSet()
        }.getOrDefault(emptySet())
    }
}
