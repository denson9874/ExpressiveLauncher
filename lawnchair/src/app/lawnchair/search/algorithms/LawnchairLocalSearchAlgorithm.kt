package app.lawnchair.search.algorithms

import android.content.Context
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.search.adapter.SPACE
import app.lawnchair.search.adapter.SearchLinksTarget
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory
import app.lawnchair.search.algorithms.engine.ActionsSectionBuilder
import app.lawnchair.search.algorithms.engine.AppsAndShortcutsSectionBuilder
import app.lawnchair.search.algorithms.engine.CalculationSectionBuilder
import app.lawnchair.search.algorithms.engine.ContactsSectionBuilder
import app.lawnchair.search.algorithms.engine.EmptyStateSectionBuilder
import app.lawnchair.search.algorithms.engine.FilesSectionBuilder
import app.lawnchair.search.algorithms.engine.HistorySectionBuilder
import app.lawnchair.search.algorithms.engine.SearchProvider
import app.lawnchair.search.algorithms.engine.SearchResult
import app.lawnchair.search.algorithms.engine.SearchSettingsSectionBuilder
import app.lawnchair.search.algorithms.engine.SectionBuilder
import app.lawnchair.search.algorithms.engine.SettingsSectionBuilder
import app.lawnchair.search.algorithms.engine.WebSuggestionsSectionBuilder
import app.lawnchair.search.algorithms.engine.provider.CalculatorSearchProvider
import app.lawnchair.search.algorithms.engine.provider.ContactsSearchProvider
import app.lawnchair.search.algorithms.engine.provider.FileSearchProvider
import app.lawnchair.search.algorithms.engine.provider.HistorySearchProvider
import app.lawnchair.search.algorithms.engine.provider.SettingsSearchProvider
import app.lawnchair.search.algorithms.engine.provider.ShortcutSearchProvider
import app.lawnchair.search.algorithms.engine.provider.apps.AppSearchProvider
import app.lawnchair.search.algorithms.engine.provider.web.CustomWebSearchProvider
import app.lawnchair.search.algorithms.engine.provider.web.WebSuggestionProvider
import com.android.launcher3.LauncherAppState
import com.android.launcher3.R
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.search.SearchCallback
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LawnchairLocalSearchAlgorithm(context: Context) : LawnchairSearchAlgorithm(context) {

    private val appState = LauncherAppState.getInstance(context)

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    private val appSearchProvider = AppSearchProvider
    private val shortcutSearchProvider = ShortcutSearchProvider
    private val historySearchProvider = HistorySearchProvider

    private val searchProviders: List<SearchProvider> = listOf(
        SettingsSearchProvider,
        FileSearchProvider,
        ContactsSearchProvider,
        WebSuggestionProvider,
    )

    private val nonAppProviderResults = SeededProviderResults(searchProviders)

    // Bumped when a search session ends (cancel(true), zero state). A search queued on the model
    // thread before that must not run afterwards, or its results would reach the next session.
    private val session = AtomicInteger()

    // Guards currentJob and the session check, so a session can't end between a model-thread
    // search checking the session and launching its job.
    private val jobLock = Any()

    override fun doSearch(query: String, callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        val requestSession = session.get()
        appState.model.enqueueModelUpdateTask { _, _, apps ->
            if (session.get() != requestSession) return@enqueueModelUpdateTask
            val appResults = appSearchProvider.search(context, query, apps)
            val shortcutResults = shortcutSearchProvider.search(context, appResults)

            synchronized(jobLock) {
                if (session.get() != requestSession) return@enqueueModelUpdateTask
                currentJob?.cancel()
                currentJob = launchSearch(query, requestSession, appResults, shortcutResults, callback)
            }
        }
    }

    private fun launchSearch(
        query: String,
        requestSession: Int,
        appResults: List<SearchResult>,
        shortcutResults: List<SearchResult>,
        callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>,
    ): Job = coroutineScope.launch {
        val calcResult = CalculatorSearchProvider.search(context, query)
            .firstOrNull()
            .orEmpty()
        val actionResults = generateActionResults(query)

        // Seed every provider with what it returned for the previous query so apps,
        // shortcuts and calculations show as soon as they are ready while the other
        // sections keep their rows until their provider answers. Without the seed,
        // combine() waits for the slowest provider (web suggestions go over the network).
        nonAppProviderResults.search(context, query).collectLatest { nonAppResults ->
            val allResults = appResults + shortcutResults + calcResult + nonAppResults + actionResults

            val searchTargets = translateToSearchTargets(query, allResults)
            setFirstItemQuickLaunch(searchTargets)
            val adapterItems = transformSearchResults(searchTargets)
            withContext(Dispatchers.Main) {
                if (session.get() == requestSession) {
                    callback.onSearchResult(query, ArrayList(adapterItems))
                }
            }
        }
    }

    override fun doZeroStateSearch(callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        endSession()

        val prefs = PreferenceManager.getInstance(context)
        val historyEnabled = prefs.searchResulRecentSuggestion.get()

        if (!historyEnabled) {
            callback.clearSearchResult()
        } else {
            val historyJob = coroutineScope.launch {
                val prefs2 = PreferenceManager2.getInstance(context)
                val maxHistory = prefs2.maxRecentResultCount.firstCached()

                val historyResults = historySearchProvider.getRecentKeywords(context, maxHistory)

                val resultsToTranslate = if (historyResults.isNotEmpty()) {
                    historyResults + listOf(SearchResult.Action.SearchSettings)
                } else {
                    listOf(
                        SearchResult.Action.EmptyState(
                            titleRes = R.string.search_empty_state_title,
                            subtitleRes = R.string.search_empty_state_no_history_subtitle,
                        ),
                        SearchResult.Action.SearchSettings,
                    )
                }

                val searchTargets = translateToSearchTargets("", resultsToTranslate)
                val adapterItems = transformSearchResults(searchTargets)
                withContext(Dispatchers.Main) {
                    callback.onSearchResult("", ArrayList(adapterItems))
                }
            }
            synchronized(jobLock) { currentJob = historyJob }
        }
    }

    override fun cancel(interruptActiveRequests: Boolean) {
        // Typing cancels with false before each new query; true ends the search session.
        if (interruptActiveRequests) {
            endSession()
        } else {
            synchronized(jobLock) { currentJob?.cancel() }
        }
    }

    private fun endSession() {
        synchronized(jobLock) {
            session.incrementAndGet()
            currentJob?.cancel()
        }
        nonAppProviderResults.clear()
    }

    private fun generateActionResults(query: String): List<SearchResult.Action> {
        val actions = mutableListOf<SearchResult.Action>()
        val prefs = PreferenceManager.getInstance(context)
        val prefs2 = PreferenceManager2.getInstance(context)

        if (prefs.searchResultStartPageSuggestion.get()) {
            val provider = prefs2.webSuggestionProvider.firstCached()
            val webProvider = provider.configure(context)

            val providerName = if (webProvider is CustomWebSearchProvider) {
                webProvider.getDisplayName()
            } else {
                context.getString(webProvider.label)
            }

            actions.add(
                SearchResult.Action.WebSearch(
                    query = query,
                    providerName = providerName,
                    searchUrl = webProvider.getSearchUrl(query),
                    providerIconRes = webProvider.iconRes,
                    tintIcon = webProvider is CustomWebSearchProvider,
                ),
            )
        }

        if (SearchLinksTarget.resolveMarketSearchActivity(context) != null) {
            actions.add(SearchResult.Action.MarketSearch(query = query))
        }

        actions.add(SearchResult.Action.SearchSettings)

        return actions
    }

    private val sectionBuilders: List<SectionBuilder> = listOf(
        AppsAndShortcutsSectionBuilder,
        CalculationSectionBuilder,
        WebSuggestionsSectionBuilder,
        ContactsSectionBuilder,
        FilesSectionBuilder,
        SettingsSectionBuilder,
        HistorySectionBuilder,
        ActionsSectionBuilder,
        EmptyStateSectionBuilder,
        SearchSettingsSectionBuilder,
    )

    private fun translateToSearchTargets(
        query: String,
        results: List<SearchResult>,
    ): List<SearchTargetCompat> {
        val factory = SearchTargetFactory(context)

        return buildList {
            // Keep the recovery tile independent from providers: Settings search may be disabled,
            // delayed, or unable to see a hidden profile, but Android can still expose its secure
            // Private Space setup/auth/settings entry point.
            factory.createPrivateSpaceRecoveryTarget(query)?.let {
                add(it)
                add(factory.createHeaderTarget(SPACE))
            }
            sectionBuilders.flatMapTo(this) { builder ->
                builder.build(context, factory, results)
            }
        }
    }
}
