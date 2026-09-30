package app.lawnchair.search.algorithms

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.allapps.views.SearchResultView.Companion.EXTRA_QUICK_LAUNCH
import app.lawnchair.search.adapter.MARKET_STORE
import app.lawnchair.search.adapter.START_PAGE
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory
import app.lawnchair.search.adapter.WEB_SUGGESTION
import app.lawnchair.search.algorithms.engine.ActionsSectionBuilder
import app.lawnchair.search.algorithms.engine.AppsAndShortcutsSectionBuilder
import app.lawnchair.search.algorithms.engine.SearchResult
import app.lawnchair.search.algorithms.engine.WebSuggestionsSectionBuilder
import app.lawnchair.search.algorithms.engine.provider.web.GoogleWebSearchProvider
import app.lawnchair.theme.ThemeProvider
import app.lawnchair.theme.color.SystemColorScheme
import com.android.launcher3.model.data.AppInfo
import com.google.common.truth.Truth.assertThat
import dev.kdrag0n.monet.theme.ColorScheme
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * The quick-launch row is what Enter / IME Go opens from the drawer search box. Local search
 * renders apps before web suggestions arrive, so the chosen row must not flip between the Play
 * Store and the web while suggestions load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [SearchQuickLaunchTargetTest.ShadowThemeProvider::class],
)
class SearchQuickLaunchTargetTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val factory by lazy { SearchTargetFactory(context) }

    @Before
    fun installPlayStore() {
        val store = ComponentName(STORE_PACKAGE, "$STORE_PACKAGE.SearchActivity")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(store)
            addIntentFilterForActivity(
                store,
                IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("market") },
            )
            addIntentFilterForActivity(
                store,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
        // Without a resolvable store the Play Store row is never built and the test proves nothing.
        assertThat(factory.createMarketSearchTarget(QUERY)).isNotNull()
    }

    @Test
    fun noAppMatch_whileWebSuggestionsLoad_enterOpensWebSearchNotPlayStore() {
        val targets = localSearchTargets(actionResults())

        setFirstItemQuickLaunch(targets)

        assertThat(targets.quickLaunchPackages()).containsExactly(START_PAGE)
    }

    @Test
    fun noAppMatch_afterWebSuggestionsArrive_enterOpensFirstSuggestion() {
        val suggestions = listOf(
            SearchResult.WebSuggestion("$QUERY today", GoogleWebSearchProvider.id),
            SearchResult.WebSuggestion("$QUERY tomorrow", GoogleWebSearchProvider.id),
        )
        val targets = localSearchTargets(suggestions + actionResults())

        setFirstItemQuickLaunch(targets)

        assertThat(targets.quickLaunchPackages()).containsExactly(WEB_SUGGESTION)
        assertThat(targets.single { it.isQuickLaunch }.searchAction?.title).isEqualTo("$QUERY today")
    }

    @Test
    fun appMatch_staysEnterTarget() {
        val app = AppInfo().apply {
            componentName = ComponentName(APP_PACKAGE, "$APP_PACKAGE.MainActivity")
            user = Process.myUserHandle()
        }
        val targets = localSearchTargets(listOf(SearchResult.App(app)) + actionResults())

        setFirstItemQuickLaunch(targets)

        assertThat(targets.quickLaunchPackages()).containsExactly(APP_PACKAGE)
    }

    @Test
    fun withoutWebSearchAction_playStoreStaysEnterTarget() {
        // App-only search, or local search with the web action turned off, has no web row.
        val targets = localSearchTargets(listOf(SearchResult.Action.MarketSearch(QUERY)))

        setFirstItemQuickLaunch(targets)

        assertThat(targets.quickLaunchPackages()).containsExactly(MARKET_STORE)
    }

    /** The actions LawnchairLocalSearchAlgorithm.generateActionResults() adds, in its order. */
    private fun actionResults(): List<SearchResult> = listOf(
        SearchResult.Action.WebSearch(
            query = QUERY,
            providerName = context.getString(GoogleWebSearchProvider.label),
            searchUrl = GoogleWebSearchProvider.getSearchUrl(QUERY),
            providerIconRes = GoogleWebSearchProvider.iconRes,
        ),
        SearchResult.Action.MarketSearch(query = QUERY),
        SearchResult.Action.SearchSettings,
    )

    /** The local-search sections that hold rows in these scenarios, in the algorithm's order. */
    private fun localSearchTargets(results: List<SearchResult>): List<SearchTargetCompat> =
        listOf(AppsAndShortcutsSectionBuilder, WebSuggestionsSectionBuilder, ActionsSectionBuilder)
            .flatMap { it.build(context, factory, results) }

    private val SearchTargetCompat.isQuickLaunch
        get() = extras.getBoolean(EXTRA_QUICK_LAUNCH, false)

    private fun List<SearchTargetCompat>.quickLaunchPackages() =
        filter { it.isQuickLaunch }.map { it.packageName }

    // Header and suggestion rows tint their icons from the theme. The bare test application does
    // not initialize launcher preferences or wallpaper-derived colors.
    @Implements(ThemeProvider::class)
    class ShadowThemeProvider {
        private lateinit var context: Context

        @Implementation
        fun __constructor__(context: Context) {
            this.context = context
        }

        @Implementation
        fun getColorScheme(): ColorScheme = SystemColorScheme(context)
    }

    private companion object {
        const val QUERY = "weather"
        const val STORE_PACKAGE = "com.android.vending"
        const val APP_PACKAGE = "com.example.weather"
    }
}
