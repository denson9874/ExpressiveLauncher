package app.lawnchair.search.algorithms

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.search.algorithms.data.SettingInfo
import app.lawnchair.search.algorithms.engine.SearchProvider
import app.lawnchair.search.algorithms.engine.SearchResult
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SeededProviderResultsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val web = FakeProvider("web")
    private val settings = FakeProvider("settings")
    private val results = SeededProviderResults(listOf(web, settings))

    @Test
    fun firstQuery_showsAnsweredSectionsWithoutWaitingForSlowProviders() = runBlocking {
        val emissions = search("a")
        assertThat(emissions.next()).isEmpty()

        settings.answer("a", setting("a"))
        assertThat(emissions.next()).containsExactly(setting("a"))
        emissions.stop()
    }

    @Test
    fun nextQuery_keepsEachSectionUntilItsProviderAnswers() = runBlocking {
        showAnswers("a")

        val emissions = search("ab")
        assertThat(emissions.next()).containsExactly(suggestion("a"), setting("a")).inOrder()

        web.answer("ab", suggestion("ab"))
        assertThat(emissions.next()).containsExactly(suggestion("ab"), setting("a")).inOrder()

        settings.answer("ab")
        assertThat(emissions.next()).containsExactly(suggestion("ab"))
        emissions.stop()
    }

    @Test
    fun providerFinishingWithoutAnswer_dropsThePreviousQueryResults() = runBlocking {
        showAnswers("a")

        val emissions = search("ab")
        assertThat(emissions.next()).containsExactly(suggestion("a"), setting("a")).inOrder()

        web.finishWithoutAnswer("ab")
        assertThat(emissions.next()).containsExactly(setting("a"))
        emissions.stop()
    }

    @Test
    fun identicalAnswer_isNotEmittedAgain() = runBlocking {
        showAnswers("a")

        val emissions = search("ab")
        assertThat(emissions.next()).containsExactly(suggestion("a"), setting("a")).inOrder()

        web.answer("ab", suggestion("a"))
        // Let the identical answer reach the output before the next one can be batched with it.
        repeat(10) { yield() }
        settings.answer("ab", setting("ab"))
        assertThat(emissions.next()).containsExactly(suggestion("a"), setting("ab")).inOrder()
        emissions.stop()
    }

    @Test
    fun clear_startsTheNextQueryEmpty() = runBlocking {
        showAnswers("a")
        results.clear()

        val emissions = search("ab")
        assertThat(emissions.next()).isEmpty()
        emissions.stop()
    }

    @Test
    fun clear_ignoresAnswersThatArriveForTheEndedSession() = runBlocking {
        val ended = search("a")
        assertThat(ended.next()).isEmpty()

        results.clear()
        web.answer("a", suggestion("a"))
        settings.answer("a", setting("a"))
        ended.awaitAnswers("a")
        ended.stop()

        val emissions = search("ab")
        assertThat(emissions.next()).isEmpty()
        emissions.stop()
    }

    @Test
    fun lateAnswerForOlderQuery_doesNotReplaceNewerQueryResults() = runBlocking {
        showAnswers("a")

        val older = search("ab")
        assertThat(older.next()).containsExactly(suggestion("a"), setting("a")).inOrder()
        showAnswers("abc")

        web.answer("ab", suggestion("ab"))
        settings.answer("ab", setting("ab"))
        older.awaitAnswers("ab")
        older.stop()

        val emissions = search("abcd")
        assertThat(emissions.next()).containsExactly(suggestion("abc"), setting("abc")).inOrder()
        emissions.stop()
    }

    /** Runs [query] until both providers have answered it and their results are shown. */
    private suspend fun CoroutineScope.showAnswers(query: String) {
        val emissions = search(query)
        web.answer(query, suggestion(query))
        settings.answer(query, setting(query))
        emissions.awaitAnswers(query)
        emissions.stop()
    }

    private suspend fun Emissions.awaitAnswers(query: String) {
        val answered = listOf(suggestion(query), setting(query))
        do {
            val shown = next()
        } while (shown != answered)
    }

    private fun CoroutineScope.search(query: String): Emissions {
        val channel = Channel<List<SearchResult>>(Channel.UNLIMITED)
        val job = launch { results.search(context, query).collect { channel.send(it) } }
        return Emissions(channel, job)
    }

    private class Emissions(
        private val channel: Channel<List<SearchResult>>,
        private val job: Job,
    ) {
        suspend fun next(): List<SearchResult> = withTimeout(5_000) { channel.receive() }

        suspend fun stop() = job.cancelAndJoin()
    }

    /** Answers each query only when the test says so, like a provider waiting on I/O. */
    private class FakeProvider(override val id: String) : SearchProvider {
        private val answers = ConcurrentHashMap<String, CompletableDeferred<List<SearchResult>?>>()

        fun answer(query: String, vararg results: SearchResult) {
            answerFor(query).complete(results.toList())
        }

        fun finishWithoutAnswer(query: String) {
            answerFor(query).complete(null)
        }

        private fun answerFor(query: String) = answers.getOrPut(query) { CompletableDeferred() }

        override fun search(context: Context, query: String): Flow<List<SearchResult>> = flow {
            answerFor(query).await()?.let { emit(it) }
        }
    }

    private fun suggestion(query: String) =
        SearchResult.WebSuggestion(suggestion = "$query suggestion", provider = "web")

    private fun setting(query: String) =
        SearchResult.Setting(SettingInfo(id = query, name = "$query setting", action = query))
}
