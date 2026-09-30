package app.lawnchair.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Enter in the drawer search box: pressed before the typed query's results show, it must wait
 * for them instead of being ignored or opening a row from the shorter query; one physical press
 * must open the web search once.
 */
class SearchSubmitGateTest {

    private var now = 1_000L
    private val gate = SearchSubmitGate { now }
    private val launched = mutableListOf<String>()

    private fun launcher(target: String?): () -> Boolean = {
        if (target != null) launched += target
        target != null
    }

    @Test
    fun enterWithCurrentResultsLaunchesImmediately() {
        gate.onResultsShown("weather")

        assertThat(gate.submit("weather", launcher("weather"))).isTrue()
        assertThat(launched).containsExactly("weather")
        assertThat(gate.hasPendingSubmit).isFalse()
    }

    @Test
    fun enterRightAfterTypingWaitsForThatQuerysResults() {
        gate.onResultsShown("wea")

        // Results on screen still belong to "wea"; they must not be launched for "weat".
        assertThat(gate.submit("weat", launcher("wea-row"))).isTrue()
        assertThat(launched).isEmpty()
        assertThat(gate.hasPendingSubmit).isTrue()

        now += 40
        gate.onResultsShown("weat")
        gate.onQuickLaunchReady("weat", launcher("weat-row"))

        assertThat(launched).containsExactly("weat-row")
        assertThat(gate.hasPendingSubmit).isFalse()
    }

    @Test
    fun enterBeforeRowsAreBoundWaitsForTheRow() {
        gate.onResultsShown("maps")

        assertThat(gate.submit("maps", launcher(null))).isTrue()
        assertThat(gate.hasPendingSubmit).isTrue()

        gate.onQuickLaunchReady("maps", launcher("maps"))
        assertThat(launched).containsExactly("maps")
    }

    @Test
    fun pendingEnterIgnoresRowsForAnotherQuery() {
        gate.submit("cal", launcher(null))
        gate.onResultsShown("ca")
        gate.onQuickLaunchReady("ca", launcher("ca-row"))

        assertThat(launched).isEmpty()
        assertThat(gate.hasPendingSubmit).isTrue()
    }

    @Test
    fun pendingEnterExpires() {
        gate.submit("slow", launcher(null))
        now += SearchSubmitGate.PENDING_WINDOW_MS + 1
        gate.onResultsShown("slow")
        gate.onQuickLaunchReady("slow", launcher("slow"))

        assertThat(launched).isEmpty()
        assertThat(gate.hasPendingSubmit).isFalse()
    }

    @Test
    fun repeatedEnterForSameQueryLaunchesOnce() {
        gate.onResultsShown("weather")
        gate.submit("weather", launcher("web"))

        now += 30 // key-up / IME editor action of the same press
        assertThat(gate.submit("weather", launcher("web"))).isTrue()
        assertThat(launched).containsExactly("web")

        now += SearchSubmitGate.REPEAT_WINDOW_MS
        gate.submit("weather", launcher("web"))
        assertThat(launched).containsExactly("web", "web")
    }

    @Test
    fun resetDropsPendingEnterAndEmptyQueryIsNotConsumed() {
        gate.submit("clock", launcher(null))
        gate.reset()
        gate.onResultsShown("clock")
        gate.onQuickLaunchReady("clock", launcher("clock"))

        assertThat(launched).isEmpty()
        assertThat(gate.submit("", launcher("x"))).isFalse()
    }
}
