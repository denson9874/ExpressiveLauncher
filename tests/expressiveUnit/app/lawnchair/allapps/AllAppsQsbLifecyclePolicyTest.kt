package app.lawnchair.allapps

import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AllAppsQsbLifecyclePolicyTest {

    @Test
    fun attachedLiveTree_recreatesComposition() {
        assertThat(
            canRecreateAllAppsQsbComposition(
                searchBarHidden = false,
                isAttached = true,
                lifecycleState = Lifecycle.State.RESUMED,
                hasSavedStateOwner = true,
            ),
        ).isTrue()
    }

    @Test
    fun explicitlyHiddenSearchBar_doesNotRecreateComposition() {
        assertThat(
            canRecreateAllAppsQsbComposition(
                searchBarHidden = true,
                isAttached = true,
                lifecycleState = Lifecycle.State.RESUMED,
                hasSavedStateOwner = true,
            ),
        ).isFalse()
    }

    @Test
    fun detachedOrDestroyedTree_doesNotCreateComposition() {
        assertThat(
            canRecreateAllAppsQsbComposition(
                searchBarHidden = false,
                isAttached = false,
                lifecycleState = Lifecycle.State.RESUMED,
                hasSavedStateOwner = true,
            ),
        ).isFalse()
        assertThat(
            canRecreateAllAppsQsbComposition(
                searchBarHidden = false,
                isAttached = true,
                lifecycleState = Lifecycle.State.DESTROYED,
                hasSavedStateOwner = true,
            ),
        ).isFalse()
    }

    @Test
    fun missingSavedStateOwner_waitsForFinalViewTree() {
        assertThat(
            canRecreateAllAppsQsbComposition(
                searchBarHidden = false,
                isAttached = true,
                lifecycleState = Lifecycle.State.RESUMED,
                hasSavedStateOwner = false,
            ),
        ).isFalse()
    }
}
