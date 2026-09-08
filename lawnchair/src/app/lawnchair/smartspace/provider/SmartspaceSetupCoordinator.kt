package app.lawnchair.smartspace.provider

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import app.lawnchair.util.dropWhileBusy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex

/** Coordinates permission flows shared by every open At a Glance preferences screen. */
internal class SmartspaceSetupCoordinator<T>(
    private val isEnabled: suspend (T) -> Boolean,
    private val requiresSetup: suspend (T) -> Boolean,
    private val onSetupDone: suspend (T) -> Unit,
) {
    private val setupMutex = Mutex()

    suspend fun collectRequests(
        requests: Flow<List<T>>,
        lifecycle: Lifecycle,
        startSetup: suspend (T) -> Unit,
    ) {
        requests
            // Gate new requests, not the downstream operation: showing Android's permission
            // activity pauses this screen, but its pending activity result must still complete.
            .flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
            .dropWhileBusy()
            .collect { sources ->
                if (!setupMutex.tryLock()) return@collect
                try {
                    for (source in sources) {
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) break
                        if (!isEnabled(source)) continue

                        // Another screen may already have completed this replayed request.
                        val stillRequiresSetup = requiresSetup(source)
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) break
                        if (stillRequiresSetup) startSetup(source)
                        if (isEnabled(source)) onSetupDone(source)
                    }
                } finally {
                    setupMutex.unlock()
                }
            }
    }
}
