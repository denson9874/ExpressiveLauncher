package app.lawnchair.qsb

import android.app.Application
import android.os.Looper
import android.widget.FrameLayout
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * D01/L03 hardening: a window Recomposer that was bound to a destroyed lifecycle never starts.
 * The QSB hosts and Compose sheets drop it before they compose, so they get a live one instead of
 * freezing (and the dock search bar's focus ripple no longer throws on it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StaleWindowRecomposerTest {

    private class TestOwner(state: Lifecycle.State) : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        private val registry = LifecycleRegistry(this)

        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = state
        }

        override val lifecycle: Lifecycle
            get() = registry

        override val savedStateRegistry: SavedStateRegistry
            get() = controller.savedStateRegistry
    }

    private class Window(owner: TestOwner) {
        private val context = ApplicationProvider.getApplicationContext<Application>()
        val contentChild = FrameLayout(context)
        val composeHost = FrameLayout(context)

        init {
            FrameLayout(context).apply { id = android.R.id.content }.addView(contentChild)
            contentChild.addView(composeHost)
            contentChild.setViewTreeLifecycleOwner(owner)
            contentChild.setViewTreeSavedStateRegistryOwner(owner)
        }
    }

    @Test
    fun recomposerThatNeverStarted_underALiveLifecycle_isShutDownAndForgotten() {
        val window = Window(TestOwner(Lifecycle.State.RESUMED))
        // What the kept window held: created for the destroyed instance, so never started.
        val stale = Recomposer(EmptyCoroutineContext)
        window.contentChild.compositionContext = stale

        assertThat(dropStaleWindowRecomposer(window.composeHost)).isTrue()

        assertThat(window.contentChild.compositionContext).isNull()
        assertThat(stale.currentState.value).isEqualTo(Recomposer.State.ShutDown)
    }

    @Test
    fun preparingTheQsbWindow_dropsTheStaleRecomposerBeforeComposing() {
        val window = Window(TestOwner(Lifecycle.State.RESUMED))
        window.contentChild.compositionContext = Recomposer(EmptyCoroutineContext)

        assertThat(prepareQsbWindowOwners(window.composeHost)).isTrue()

        assertThat(window.contentChild.compositionContext).isNull()
    }

    @Test
    fun runningRecomposer_isKept() {
        val owner = TestOwner(Lifecycle.State.RESUMED)
        val window = Window(owner)
        val live = window.contentChild.createLifecycleAwareWindowRecomposer(lifecycle = owner.lifecycle)
        window.contentChild.compositionContext = live
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(live.currentState.value).isNotEqualTo(Recomposer.State.Inactive)

        assertThat(dropStaleWindowRecomposer(window.composeHost)).isFalse()

        assertThat(window.contentChild.compositionContext).isSameInstanceAs(live)
        live.cancel()
    }

    @Test
    fun recomposerWaitingForOnCreate_isKept() {
        // Launcher's onCreate composes before its lifecycle reaches CREATED; that one starts soon.
        val window = Window(TestOwner(Lifecycle.State.INITIALIZED))
        val waiting = Recomposer(EmptyCoroutineContext)
        window.contentChild.compositionContext = waiting

        assertThat(dropStaleWindowRecomposer(window.composeHost)).isFalse()

        assertThat(window.contentChild.compositionContext).isSameInstanceAs(waiting)
        assertThat(waiting.currentState.value).isEqualTo(Recomposer.State.Inactive)
    }
}
