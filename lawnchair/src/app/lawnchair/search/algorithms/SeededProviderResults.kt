package app.lawnchair.search.algorithms

import android.content.Context
import app.lawnchair.search.algorithms.engine.SearchProvider
import app.lawnchair.search.algorithms.engine.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Combines the results of [providers] without waiting for the slowest one.
 *
 * Each provider starts from the results it returned for the previous query of the same typing
 * session, so its section keeps its rows while the provider works on the new query instead of
 * vanishing and reappearing on every keystroke. The provider's answer to the new query replaces
 * those rows as soon as it arrives. [clear] ends the typing session.
 */
internal class SeededProviderResults(private val providers: List<SearchProvider>) {

    private val lock = Any()
    private val lastResults = mutableMapOf<String, List<SearchResult>>()

    // Bumped by every search and clear, so answers to an older query or to an ended session are
    // never kept as seeds.
    private var generation = 0

    fun search(context: Context, query: String): Flow<List<SearchResult>> {
        val (searchGeneration, seeds) = synchronized(lock) { ++generation to lastResults.toMap() }
        return combine(
            providers.map { it.seededSearch(context, query, seeds, searchGeneration) },
        ) { resultsArray ->
            resultsArray.toList().flatten()
        }.distinctUntilChanged()
    }

    fun clear() {
        synchronized(lock) {
            generation++
            lastResults.clear()
        }
    }

    private fun SearchProvider.seededSearch(
        context: Context,
        query: String,
        seeds: Map<String, List<SearchResult>>,
        searchGeneration: Int,
    ): Flow<List<SearchResult>> = flow {
        emit(seeds[id].orEmpty())
        var answered = false
        search(context, query).collect { results ->
            answered = true
            remember(id, results, searchGeneration)
            emit(results)
        }
        // A provider that finishes without answering has nothing for this query.
        if (!answered) {
            remember(id, emptyList(), searchGeneration)
            emit(emptyList())
        }
    }

    private fun remember(id: String, results: List<SearchResult>, searchGeneration: Int) {
        synchronized(lock) {
            if (searchGeneration == generation) lastResults[id] = results
        }
    }
}
