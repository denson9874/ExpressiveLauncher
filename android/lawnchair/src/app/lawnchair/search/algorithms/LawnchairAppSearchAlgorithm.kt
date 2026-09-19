package app.lawnchair.search.algorithms

import android.content.Context
import android.os.Handler
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.search.adapter.SPACE
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory
import app.lawnchair.util.isDefaultLauncher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.ModelTaskController
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.search.SearchCallback
import com.android.launcher3.util.Executors
import java.util.concurrent.atomic.AtomicInteger

class LawnchairAppSearchAlgorithm(context: Context) : LawnchairSearchAlgorithm(context) {

    private val appState = LauncherAppState.getInstance(context)
    private val resultHandler = Handler(Executors.MAIN_EXECUTOR.looper)

    // todo maybe use D.I.?
    private val searchTargetFactory = SearchTargetFactory(context)

    private val prefs2 = PreferenceManager2.getInstance(context)
    private val requestGeneration = AtomicInteger()

    override fun doSearch(query: String, callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        val generation = requestGeneration.incrementAndGet()
        appState.model.enqueueModelUpdateTask(object : LauncherModel.ModelUpdateTask {
            override fun execute(app: ModelTaskController, dataModel: BgDataModel, apps: AllAppsList) {
                // Snapshot on the model thread, then perform fuzzy matching and shortcut lookup on
                // the shared worker pool. The old code did this work on Main and could deliver an
                // obsolete result after a newer query, causing UI jumps and stale adapter binds.
                val appSnapshot = ArrayList(apps.data)
                Executors.THREAD_POOL_EXECUTOR.execute {
                    val results = getResult(appSnapshot, query)
                    resultHandler.post {
                        if (requestGeneration.get() == generation) {
                            callback.onSearchResult(query, results)
                        }
                    }
                }
            }
        })
    }

    override fun cancel(interruptActiveRequests: Boolean) {
        requestGeneration.incrementAndGet()
        if (interruptActiveRequests) {
            resultHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun getResult(
        apps: MutableList<AppInfo>,
        query: String,
    ): ArrayList<BaseAllAppsAdapter.AdapterItem> {
        val enableFuzzySearch = prefs2.enableFuzzySearch.firstCached(prefs2)
        val hiddenApps = prefs2.hiddenApps.firstCached(prefs2)
        val hiddenAppsInSearch = prefs2.hiddenAppsInSearch.firstCached(prefs2)
        val maxResultsCount = prefs2.maxAppSearchResultCount.firstCached(prefs2)
        val appResults = if (enableFuzzySearch) {
            SearchUtils.fuzzySearch(apps, query, maxResultsCount, hiddenApps, hiddenAppsInSearch)
        } else {
            SearchUtils.normalSearch(apps, query, maxResultsCount, hiddenApps, hiddenAppsInSearch)
        }

        val searchTargets = mutableListOf<SearchTargetCompat>()

        // A hidden Private Space has no drawer entry to navigate back to. Offer Android's
        // non-disclosing setup/auth/settings destination only for the complete label.
        searchTargetFactory.createPrivateSpaceRecoveryTarget(query)?.let {
            searchTargets.add(it)
            searchTargets.add(searchTargetFactory.createHeaderTarget(SPACE))
        }

        if (appResults.isNotEmpty()) {
            if (appResults.size == 1 && context.isDefaultLauncher()) {
                val singleAppResult = appResults.firstOrNull()
                val shortcuts = singleAppResult?.let { SearchUtils.getShortcuts(it, context) }
                if (shortcuts != null && shortcuts.isNotEmpty()) {
                    // Show app as a row alongside its shortcuts (no duplicate icon)
                    singleAppResult.let { searchTargets.add(searchTargetFactory.createAppSearchTarget(it, true)) }
                    searchTargets.addAll(shortcuts.map(searchTargetFactory::createShortcutTarget))
                } else {
                    // No shortcuts: show as icon
                    appResults.mapTo(searchTargets, searchTargetFactory::createAppSearchTarget)
                }
            } else {
                // Multiple results: show as icons
                appResults.mapTo(searchTargets, searchTargetFactory::createAppSearchTarget)
            }
            searchTargets.add(searchTargetFactory.createHeaderTarget(SPACE))
        }

        searchTargetFactory.createMarketSearchTarget(query)?.let { searchTargets.add(it) }

        setFirstItemQuickLaunch(searchTargets)
        val adapterItems = transformSearchResults(searchTargets)
        return ArrayList(adapterItems)
    }
}
