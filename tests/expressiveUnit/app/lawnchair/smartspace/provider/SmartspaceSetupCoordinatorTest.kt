package app.lawnchair.smartspace.provider

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
class SmartspaceSetupCoordinatorTest {

    @Test
    fun overlappingScreens_launchOnePermissionRequestAndCompleteOnce() = runBlocking {
        val fixture = Fixture()
        val first = fixture.observe(this, Screen(Lifecycle.State.RESUMED))
        val second = fixture.observe(this, Screen(Lifecycle.State.RESUMED))
        try {
            fixture.requests.emit(listOf(fixture.source))
            drain()
            assertThat(fixture.source.starts).isEqualTo(1)

            fixture.source.needsSetup = false
            fixture.source.permissionResult.complete(Unit)
            drain()
            assertThat(fixture.source.completions).isEqualTo(1)
            assertThat(fixture.source.enabled).isTrue()
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun stoppedScreen_doesNotLaunchAndPausingForPermissionPreservesTheResult() = runBlocking {
        val fixture = Fixture()
        val background = Screen(Lifecycle.State.CREATED)
        val foreground = Screen(Lifecycle.State.RESUMED)
        var backgroundStarts = 0
        val first = fixture.observe(this, background) { backgroundStarts++ }
        val second = fixture.observe(this, foreground)
        try {
            fixture.requests.emit(listOf(fixture.source))
            drain()
            assertThat(backgroundStarts).isEqualTo(0)
            assertThat(fixture.source.starts).isEqualTo(1)

            // Android's permission activity pauses preferences before returning its result.
            foreground.state = Lifecycle.State.CREATED
            drain()
            fixture.source.needsSetup = false
            fixture.source.permissionResult.complete(Unit)
            drain()
            assertThat(fixture.source.completions).isEqualTo(1)
            assertThat(fixture.source.enabled).isTrue()

            // Replaying the old request as another screen resumes must not reopen permission.
            background.state = Lifecycle.State.RESUMED
            drain()
            assertThat(backgroundStarts).isEqualTo(0)
            assertThat(fixture.source.starts).isEqualTo(1)
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test
    fun stoppedDuringPreferenceRead_doesNotLaunchFromTheBackground() = runBlocking {
        val preferenceRead = CompletableDeferred<Unit>()
        val fixture = Fixture(beforeEnabledRead = { preferenceRead.await() })
        val screen = Screen(Lifecycle.State.RESUMED)
        val observer = fixture.observe(this, screen)
        try {
            fixture.requests.emit(listOf(fixture.source))
            drain()
            screen.state = Lifecycle.State.CREATED
            preferenceRead.complete(Unit)
            drain()
            assertThat(fixture.source.starts).isEqualTo(0)
            assertThat(fixture.source.completions).isEqualTo(0)

            screen.state = Lifecycle.State.RESUMED
            drain()
            assertThat(fixture.source.starts).isEqualTo(1)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun stoppedDuringSetupCheck_doesNotLaunchFromTheBackground() = runBlocking {
        val setupCheck = CompletableDeferred<Unit>()
        val fixture = Fixture(beforeSetupCheck = { setupCheck.await() })
        val screen = Screen(Lifecycle.State.RESUMED)
        val observer = fixture.observe(this, screen)
        try {
            fixture.requests.emit(listOf(fixture.source))
            drain()
            screen.state = Lifecycle.State.CREATED
            setupCheck.complete(Unit)
            drain()
            assertThat(fixture.source.starts).isEqualTo(0)
            assertThat(fixture.source.completions).isEqualTo(0)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun replayedRequest_checksCurrentEnablementAndBindingBeforeLaunching() = runBlocking {
        val fixture = Fixture()
        fixture.source.enabled = false
        val observer = fixture.observe(this, Screen(Lifecycle.State.RESUMED))
        try {
            fixture.requests.emit(listOf(fixture.source))
            drain()
            assertThat(fixture.source.starts).isEqualTo(0)
            assertThat(fixture.source.completions).isEqualTo(0)

            fixture.source.enabled = true
            fixture.source.needsSetup = false
            fixture.requests.emit(listOf(fixture.source))
            drain()
            assertThat(fixture.source.starts).isEqualTo(0)
            assertThat(fixture.source.completions).isEqualTo(1)
            assertThat(fixture.source.enabled).isTrue()
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test
    fun canceledCollector_releasesTheGuardForTheNextScreen() = runBlocking {
        val fixture = Fixture()
        val first = fixture.observe(this, Screen(Lifecycle.State.RESUMED))
        fixture.requests.emit(listOf(fixture.source))
        drain()
        assertThat(fixture.source.starts).isEqualTo(1)
        first.cancelAndJoin()
        assertThat(fixture.source.completions).isEqualTo(0)

        val second = fixture.observe(this, Screen(Lifecycle.State.RESUMED))
        try {
            drain()
            assertThat(fixture.source.starts).isEqualTo(2)
            fixture.source.needsSetup = false
            fixture.source.permissionResult.complete(Unit)
            drain()
            assertThat(fixture.source.completions).isEqualTo(1)
        } finally {
            second.cancelAndJoin()
        }
    }

    @Test
    fun declinedPermission_canBeRetriedWithoutDuplicateDialogs() = runBlocking {
        val fixture = Fixture()
        val first = fixture.observe(this, Screen(Lifecycle.State.RESUMED))
        val second = fixture.observe(this, Screen(Lifecycle.State.RESUMED))
        try {
            fixture.requests.emit(listOf(fixture.source))
            drain()
            fixture.source.permissionResult.complete(Unit)
            drain()
            fixture.requests.emit(listOf(fixture.source))
            drain()
            assertThat(fixture.source.starts).isEqualTo(1)
            assertThat(fixture.source.completions).isEqualTo(1)
            assertThat(fixture.source.enabled).isFalse()

            fixture.source.enabled = true
            fixture.source.permissionResult = CompletableDeferred()
            fixture.requests.emit(listOf(fixture.source))
            drain()
            assertThat(fixture.source.starts).isEqualTo(2)
            fixture.source.needsSetup = false
            fixture.source.permissionResult.complete(Unit)
            drain()
            assertThat(fixture.source.completions).isEqualTo(2)
            assertThat(fixture.source.enabled).isTrue()
        } finally {
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    private suspend fun drain() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idle()
            yield()
        }
    }

    private class Screen(initialState: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
        var state: Lifecycle.State
            get() = registry.currentState
            set(value) {
                registry.currentState = value
            }

        init {
            state = initialState
        }
    }

    private class Source {
        var enabled = true
        var needsSetup = true
        var starts = 0
        var completions = 0
        var permissionResult = CompletableDeferred<Unit>()
    }

    private class Fixture(
        beforeEnabledRead: suspend () -> Unit = {},
        beforeSetupCheck: suspend () -> Unit = {},
    ) {
        val source = Source()
        val requests = MutableSharedFlow<List<Source>>(replay = 1)
        private val coordinator = SmartspaceSetupCoordinator<Source>(
            isEnabled = {
                beforeEnabledRead()
                it.enabled
            },
            requiresSetup = {
                beforeSetupCheck()
                it.needsSetup
            },
            onSetupDone = {
                it.completions++
                if (it.needsSetup) it.enabled = false
            },
        )

        fun observe(scope: CoroutineScope, screen: Screen, onStart: () -> Unit = {}): Job =
            scope.launch(Dispatchers.Unconfined) {
                coordinator.collectRequests(requests, screen.lifecycle) {
                    onStart()
                    it.starts++
                    it.permissionResult.await()
                }
            }
    }
}
