package app.lawnchair.search.algorithms.engine.provider.web

import android.app.Application
import android.util.Log
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog

/**
 * R05: the built-in providers used to Uri.encode the query and then hand it to Retrofit's @Query,
 * which encodes it again, so "YT Music" reached Google as q=YT%2520Music and the drawer showed
 * "youtube 20music". These tests read the request each provider actually issues.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class WebSuggestionRequestEncodingTest {

    @After
    fun tearDown() {
        WebSuggestionHttp.transportForTests = null
    }

    @Test
    fun google_sendsEachQueryEncodedOnce() {
        assertProviderEncodesOnce(
            GoogleWebSearchProvider,
            suggestEndpoint = "https://www.google.com/complete/search",
            searchParam = "q",
            body = OPENSEARCH_BODY,
        )
    }

    @Test
    fun duckDuckGo_sendsEachQueryEncodedOnce() {
        assertProviderEncodesOnce(
            DuckDuckGoWebSearchProvider,
            suggestEndpoint = "https://ac.duckduckgo.com/ac/",
            searchParam = "q",
            body = DUCKDUCKGO_BODY,
        )
    }

    @Test
    fun startPage_sendsEachQueryEncodedOnce() {
        assertProviderEncodesOnce(
            StartPageWebSearchProvider,
            suggestEndpoint = "https://www.startpage.com/suggestions",
            searchParam = "query",
            body = OPENSEARCH_BODY,
        )
    }

    @Test
    fun startPageEu_sendsEachQueryEncodedOnce() {
        assertProviderEncodesOnce(
            StartPageEUWebSearchProvider,
            suggestEndpoint = "https://eu.startpage.com/suggestions",
            searchParam = "query",
            body = OPENSEARCH_BODY,
        )
    }

    @Test
    fun kagi_sendsEachQueryEncodedOnce() {
        assertProviderEncodesOnce(
            KagiWebSearchProvider,
            suggestEndpoint = "https://kagi.com/api/autosuggest",
            searchParam = "q",
            body = OPENSEARCH_BODY,
        )
    }

    @Test
    fun customTemplate_encodesEachQueryOnce() {
        QUERIES.forEach { (query, wireValue) ->
            // The same conversion CustomWebSearchProvider's OkHttp request applies to the filled URL.
            val url = Request.Builder()
                .url(CustomWebSearchProvider.fillUrlTemplate(CUSTOM_TEMPLATE, query))
                .build()
                .url

            assertThat(url.host).isEqualTo("suggest.example.test")
            assertQueryEncodedOnce(url, "q", query, wireValue)
        }
    }

    @Test
    fun cancelledFetch_isNotLoggedAsAnError() {
        val transport = StalledTransport()
        WebSuggestionHttp.transportForTests = transport.client
        ShadowLog.clear()

        try {
            runBlocking {
                val collecting = launch(Dispatchers.Default) {
                    GoogleWebSearchProvider.getSuggestions("YT Music").collect {}
                }
                assertThat(transport.started.await(5, TimeUnit.SECONDS)).isTrue()
                collecting.cancelAndJoin()
            }
        } finally {
            transport.release.countDown()
        }

        val errors = ShadowLog.getLogsForTag(GOOGLE_TAG).filter { it.type == Log.ERROR }
        assertWithMessage("a cancelled suggestion fetch was logged as an error").that(errors).isEmpty()
    }

    private fun assertProviderEncodesOnce(
        provider: WebSearchProvider,
        suggestEndpoint: String,
        searchParam: String,
        body: String,
    ) {
        QUERIES.forEach { (query, wireValue) ->
            val transport = RecordingTransport(body)
            WebSuggestionHttp.transportForTests = transport.client

            val suggestions = runBlocking { provider.getSuggestions(query).first() }

            assertWithMessage("$provider suggestions for '$query'")
                .that(suggestions)
                .containsExactly("first suggestion", "second suggestion")
                .inOrder()
            assertWithMessage("$provider requests for '$query'").that(transport.urls).hasSize(1)
            val url = transport.urls.single()
            assertThat("${url.scheme}://${url.host}${url.encodedPath}").isEqualTo(suggestEndpoint)
            assertQueryEncodedOnce(url, "q", query, wireValue)

            // getSearchUrl builds its URL by hand, so it keeps exactly one Uri.encode.
            assertQueryEncodedOnce(provider.getSearchUrl(query).toHttpUrl(), searchParam, query, wireValue)
        }
    }

    private fun assertQueryEncodedOnce(url: HttpUrl, param: String, query: String, wireValue: String) {
        assertWithMessage("$param decoded once for '$query' in $url")
            .that(url.queryParameter(param))
            .isEqualTo(query)
        assertWithMessage("$param on the wire for '$query'")
            .that(url.encodedQuery.orEmpty().split('&'))
            .contains("$param=$wireValue")
        assertWithMessage("double-encoded escape for '$query'")
            .that(url.toString())
            .doesNotContain("%25")
    }

    private class RecordingTransport(private val body: String) : Interceptor {
        val urls = CopyOnWriteArrayList<HttpUrl>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            urls.add(chain.request().url)
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    /** Holds the request open until the test releases it, so the fetch can be cancelled in flight. */
    private class StalledTransport : Interceptor {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

        override fun intercept(chain: Interceptor.Chain): Response {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("stalled request released")
        }
    }

    private companion object {
        const val GOOGLE_TAG = "GoogleWebSearchProvider"
        const val CUSTOM_TEMPLATE = "https://suggest.example.test/complete?client=lawnchair&q=%s"
        const val OPENSEARCH_BODY = """["query",["first suggestion","second suggestion"]]"""
        const val DUCKDUCKGO_BODY = """[{"phrase":"first suggestion"},{"phrase":"second suggestion"}]"""

        /** Each query and its value on the wire when percent-encoded exactly once. */
        val QUERIES = listOf(
            "a b&c" to "a%20b%26c",
            "YT Music" to "YT%20Music",
            "café 東京" to "caf%C3%A9%20%E6%9D%B1%E4%BA%AC",
            "c++" to "c%2B%2B",
        )
    }
}
