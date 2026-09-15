package app.lawnchair.ui.preferences.about

import android.app.Application
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import retrofit2.HttpException
import retrofit2.create

/** Exercises the actual Retrofit fetch/JSON path shared by About and background update jobs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExpressiveGitHubUpdateFetchTest {

    @Test
    fun githubManifest_providesVerifiedDownloadMetadataAndOneNotification() = runBlocking {
        val transport = RecordingTransport(manifestJson())
        val decision = fetchExpressiveUpdateDecision(transport.api, QA_CONFIG, 11, QA_PACKAGE)

        assertThat(transport.requests).containsExactly(QA_URL)
        assertThat(decision).isInstanceOf(ExpressiveUpdateDecision.Available::class.java)
        val update = (decision as ExpressiveUpdateDecision.Available).manifest
        assertThat(update.apkUrl).isEqualTo(APK_URL)
        assertThat(update.versionCode).isEqualTo(12)
        assertThat(update.versionName).isEqualTo("1.0.11")
        assertThat(update.sha256).isEqualTo("a".repeat(64))
        assertThat(update.sizeBytes).isEqualTo(21_794_676)
        assertThat(update.releaseNotes).isEqualTo("GitHub updates are available.")
        assertThat(
            shouldPostExpressiveUpdateNotification(
                11,
                update.versionCode,
                ExpressiveUpdateNotificationSnapshot(0, 0, 0),
                1_000,
            ),
        ).isTrue()
        assertThat(
            shouldPostExpressiveUpdateNotification(
                11,
                update.versionCode,
                ExpressiveUpdateNotificationSnapshot(update.versionCode, 0, 0),
                1_000,
            ),
        ).isFalse()
    }

    @Test
    fun equalAndOlderGithubVersions_doNotOfferAnUpdate() = runBlocking {
        val transport = RecordingTransport(manifestJson())

        listOf(12L, 13L).forEach { installedVersion ->
            assertThat(fetchExpressiveUpdateDecision(transport.api, QA_CONFIG, installedVersion, QA_PACKAGE))
                .isEqualTo(ExpressiveUpdateDecision.UpToDate)
        }
        assertThat(transport.requests).containsExactly(QA_URL, QA_URL).inOrder()
    }

    @Test
    fun releaseBuild_rejectsQaManifestWithoutTryingTheQaFeed() = runBlocking {
        val transport = RecordingTransport(manifestJson())
        val releaseConfig = ExpressiveUpdateConfig(ExpressiveUpdateChannel.RELEASE, RELEASE_URL)

        assertThat(fetchExpressiveUpdateDecision(transport.api, releaseConfig, 11, RELEASE_PACKAGE))
            .isEqualTo(ExpressiveUpdateDecision.Rejected(ExpressiveUpdateRejection.WRONG_CHANNEL))
        assertThat(transport.requests).containsExactly(RELEASE_URL).inOrder()
    }

    @Test
    fun githubHttpFailures_propagateWithoutOfferingOrFallingBack() {
        listOf(403, 404, 503).forEach { status ->
            val transport = RecordingTransport("GitHub request failed", status)

            val failure = assertThrows(HttpException::class.java) {
                runBlocking { fetchExpressiveUpdateDecision(transport.api, QA_CONFIG, 11, QA_PACKAGE) }
            }

            assertThat(failure.code()).isEqualTo(status)
            assertThat(transport.requests).containsExactly(QA_URL)
        }
    }

    @Test
    fun githubHtmlOrIncompleteManifest_cannotBecomeAnUpdate() {
        listOf("<html>Sign in</html>", """{"schemaVersion":1,"channel":"qa"}""").forEach { body ->
            val transport = RecordingTransport(body)

            assertThrows(SerializationException::class.java) {
                runBlocking { fetchExpressiveUpdateDecision(transport.api, QA_CONFIG, 11, QA_PACKAGE) }
            }
            assertThat(transport.requests).containsExactly(QA_URL)
        }
    }

    @Test
    fun githubClient_followsCdnRedirectsWithoutPermittingHttpsDowngrade() {
        val client = gitHubApiRetrofit.callFactory() as OkHttpClient

        assertThat(client.followRedirects).isTrue()
        assertThat(client.followSslRedirects).isFalse()
    }

    private class RecordingTransport(
        private val body: String,
        private val status: Int = 200,
    ) : Interceptor {
        val requests = mutableListOf<String>()
        val api: GitHubService = gitHubApiRetrofit.newBuilder()
            .client(
                (gitHubApiRetrofit.callFactory() as OkHttpClient).newBuilder()
                    .addInterceptor(this)
                    .build(),
            )
            .build()
            .create()

        override fun intercept(chain: Interceptor.Chain): Response {
            requests += chain.request().url.toString()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "Request failed")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun manifestJson(): String = """
        {
          "schemaVersion": 1,
          "channel": "qa",
          "versionCode": 12,
          "versionName": "1.0.11",
          "packageName": "$QA_PACKAGE",
          "apkUrl": "$APK_URL",
          "sha256": "${"a".repeat(64)}",
          "sizeBytes": 21794676,
          "releaseNotes": "GitHub updates are available.",
          "sourceRevision": "0123456789abcdef0123456789abcdef01234567"
        }
    """.trimIndent()

    private companion object {
        const val QA_URL = "https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/qa/latest.json"
        const val RELEASE_URL = "https://raw.githubusercontent.com/denson9874/ExpressiveLauncher/updates/release/latest.json"
        const val APK_URL = "https://github.com/denson9874/ExpressiveLauncher/releases/download/qa-1.0.11-12/ExpressiveLauncher-qa.apk"
        const val QA_PACKAGE = "dev.launcher.expressive.l3.debug"
        const val RELEASE_PACKAGE = "dev.launcher.expressive.l3"
        val QA_CONFIG = ExpressiveUpdateConfig(ExpressiveUpdateChannel.QA, QA_URL)
    }
}
