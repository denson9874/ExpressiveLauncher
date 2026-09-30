package app.lawnchair.search

import android.os.SystemClock

/**
 * Decides what Enter / IME Search does in the drawer search box.
 *
 * - Enter pressed before the results for the typed query are on screen is remembered and runs
 *   once they arrive, instead of being ignored or opening a row left over from a shorter query.
 * - A hardware Enter can reach the search box twice (key event and editor action). A repeat for
 *   the same query right after a launch is swallowed so the web search opens once.
 */
class SearchSubmitGate(private val clock: () -> Long = SystemClock::uptimeMillis) {

    private var shownQuery: String? = null
    private var pendingQuery: String? = null
    private var pendingAt = 0L
    private var launchedQuery: String? = null
    private var launchedAt = 0L

    /** Results for [query] replaced the list; the quick-launch row now belongs to [query]. */
    fun onResultsShown(query: String) {
        shownQuery = query
    }

    /**
     * Enter for [query]. [launch] opens the current quick-launch row and reports whether it did.
     * Returns whether the key was consumed.
     */
    fun submit(query: String, launch: () -> Boolean): Boolean {
        if (query.isEmpty()) return false
        val now = clock()
        if (query == launchedQuery && now - launchedAt < REPEAT_WINDOW_MS) return true
        if (query == shownQuery && launch()) {
            pendingQuery = null
            markLaunched(query, now)
            return true
        }
        // Results (or their rows) for this query are still on the way.
        pendingQuery = query
        pendingAt = now
        return true
    }

    /** The quick-launch row for [query] is bound; run a waiting Enter for it. */
    fun onQuickLaunchReady(query: String, launch: () -> Boolean) {
        val pending = pendingQuery ?: return
        if (pending != query || query != shownQuery) return
        pendingQuery = null
        val now = clock()
        if (now - pendingAt > PENDING_WINDOW_MS) return
        if (launch()) markLaunched(query, now)
    }

    /** Search closed or was cleared. */
    fun reset() {
        shownQuery = null
        pendingQuery = null
        launchedQuery = null
    }

    val hasPendingSubmit: Boolean get() = pendingQuery != null

    private fun markLaunched(query: String, now: Long) {
        launchedQuery = query
        launchedAt = now
    }

    companion object {
        /** Longer than a key down/up pair plus an IME editor action for the same press. */
        const val REPEAT_WINDOW_MS = 800L

        /** A waiting Enter expires if results take longer than this to show. */
        const val PENDING_WINDOW_MS = 2_000L
    }
}
