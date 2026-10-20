package app.lawnchair.smartspace.provider

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.smartspace.model.SmartspaceTarget
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SmartspaceEmissionTest {

    private suspend fun drain() {
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            yield()
        }
    }

    @Test
    fun testCombinedTargetsEmission() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = SmartspaceProvider(app)

        val collectedTargets = mutableListOf<List<SmartspaceTarget>>()
        val job = launch(Dispatchers.Unconfined) {
            provider.targets.collect {
                println("TARGETS EMISSION: size=${it.size}, ids=${it.map { t -> t.id }}")
                collectedTargets.add(it)
            }
        }

        val collectedPreview = mutableListOf<List<SmartspaceTarget>>()
        val previewJob = launch(Dispatchers.Unconfined) {
            provider.previewTargets.collect {
                collectedPreview.add(it)
            }
        }

        // Providers emit from background dispatchers; wait for the expected emissions instead of a
        // fixed number of looper idles, which raced on loaded CI machines.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            (collectedTargets.lastOrNull().isNullOrEmpty() ||
                collectedPreview.lastOrNull()?.any { it.id == "dummyTarget" } != true)
        ) {
            drain()
            delay(50)
        }

        println("COLLECTED: count=${collectedTargets.size}, all=${collectedTargets.map { it.map { t -> t.id } }}")
        println("PREVIEW COLLECTED: count=${collectedPreview.size}, all=${collectedPreview.map { it.map { t -> t.id } }}")
        job.cancel()
        previewJob.cancel()

        val lastEmission = collectedTargets.lastOrNull()
        assertThat(lastEmission).isNotNull()
        assertThat(lastEmission).isNotEmpty()

        val lastPreview = collectedPreview.lastOrNull()
        assertThat(lastPreview).isNotNull()
        assertThat(lastPreview).isNotEmpty()
        assertThat(lastPreview!!.map { it.id }).contains("dummyTarget")
    }
}
