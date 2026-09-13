package app.lawnchair.ui.preferences.about

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Test

class ExpressiveUpdateRequestGenerationTest {
    @Test
    fun lateQaResponse_cannotOverwriteStableResultAfterChannelChange() {
        val requests = ExpressiveUpdateRequestGeneration()
        val oldQa = requests.advance()
        val responseReady = CountDownLatch(1)
        val finishOldRequest = CountDownLatch(1)
        var state = "Checking QA"
        val worker = thread {
            responseReady.countDown()
            check(finishOldRequest.await(5, TimeUnit.SECONDS))
            requests.withCurrent(oldQa) { state = "Old QA available" }
        }
        check(responseReady.await(5, TimeUnit.SECONDS))
        val stable = requests.advance()
        requests.withCurrent(stable) { state = "Waiting for Stable" }
        finishOldRequest.countDown()
        worker.join(5_000)

        assertThat(worker.isAlive).isFalse()
        assertThat(state).isEqualTo("Waiting for Stable")
    }

    @Test
    fun returningToQa_cannotResurrectOriginalQaDownloadOrInstallerCallback() {
        val requests = ExpressiveUpdateRequestGeneration()
        val oldQaDownload = requests.advance()
        requests.advance() // Switch to Stable.
        val newQa = requests.advance() // Switch back to QA.
        val installerLaunches = mutableListOf<String>()

        assertThat(requests.withCurrent(oldQaDownload) { installerLaunches += "old APK" }).isFalse()
        assertThat(requests.withCurrent(newQa) { installerLaunches += "new APK" }).isTrue()
        assertThat(installerLaunches).containsExactly("new APK")
    }

    @Test
    fun clearingRepository_invalidatesAllOutstandingProgressAndFailureCallbacks() {
        val requests = ExpressiveUpdateRequestGeneration()
        val download = requests.advance()
        requests.advance()
        val states = mutableListOf<String>()

        requests.withCurrent(download) { states += "Downloaded" }
        requests.withCurrent(download) { states += "Failed" }

        assertThat(states).isEmpty()
    }
}
