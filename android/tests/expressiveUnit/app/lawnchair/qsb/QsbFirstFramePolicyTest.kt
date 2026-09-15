package app.lawnchair.qsb

import android.app.Application
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class QsbFirstFramePolicyTest {

    private class TestOwner : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        private val registry = LifecycleRegistry(this)

        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        override val lifecycle: Lifecycle
            get() = registry

        override val savedStateRegistry: SavedStateRegistry
            get() = controller.savedStateRegistry

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    @Test
    fun cleanInstallDefaults_enableGoogleBackedDockSearch() {
        val resources = ApplicationProvider.getApplicationContext<Application>().resources

        assertThat(resources.getBoolean(R.bool.config_default_show_hotseat)).isTrue()
        assertThat(resources.getString(R.string.config_default_hotseat_mode)).isEqualTo("lawnchair")
        assertThat(resources.getString(R.string.config_default_qsb_search_provider_id))
            .isEqualTo("google")
    }

    @Test
    fun unmeasuredComposeChild_requiresAttachedMeasurePass() {
        assertThat(requiresAttachedQsbRemeasure(measuredWidth = 0, measuredHeight = 120)).isTrue()
        assertThat(requiresAttachedQsbRemeasure(measuredWidth = 800, measuredHeight = 0)).isTrue()
        assertThat(requiresAttachedQsbRemeasure(measuredWidth = 800, measuredHeight = 120)).isFalse()
    }

    @Test
    fun composition_isCreatedOnlyFromALiveAttachedViewTree() {
        assertThat(canCreateAttachedQsbComposition(true, Lifecycle.State.CREATED)).isTrue()
        assertThat(canCreateAttachedQsbComposition(true, Lifecycle.State.RESUMED)).isTrue()
        assertThat(canCreateAttachedQsbComposition(false, Lifecycle.State.RESUMED)).isFalse()
        assertThat(canCreateAttachedQsbComposition(true, Lifecycle.State.DESTROYED)).isFalse()
        assertThat(canCreateAttachedQsbComposition(true, null)).isFalse()
    }

    @Test
    fun measurement_waitsForBothLifecycleAndSavedStateOwners() {
        assertThat(
            canMeasureAttachedQsbComposition(
                isAttached = true,
                lifecycleState = Lifecycle.State.CREATED,
                hasSavedStateOwner = true,
            ),
        ).isTrue()
        assertThat(
            canMeasureAttachedQsbComposition(
                isAttached = true,
                lifecycleState = null,
                hasSavedStateOwner = true,
            ),
        ).isFalse()
        assertThat(
            canMeasureAttachedQsbComposition(
                isAttached = true,
                lifecycleState = Lifecycle.State.RESUMED,
                hasSavedStateOwner = false,
            ),
        ).isFalse()
        assertThat(
            canMeasureAttachedQsbComposition(
                isAttached = false,
                lifecycleState = Lifecycle.State.RESUMED,
                hasSavedStateOwner = true,
            ),
        ).isFalse()
        assertThat(
            canMeasureAttachedQsbComposition(
                isAttached = true,
                lifecycleState = Lifecycle.State.DESTROYED,
                hasSavedStateOwner = true,
            ),
        ).isFalse()
    }

    @Test
    fun previewInnerOwner_isPropagatedToComposeWindowContentChild() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val androidContent = FrameLayout(context).apply { id = android.R.id.content }
        val windowContentChild = FrameLayout(context)
        val previewRoot = FrameLayout(context)
        val composeHost = FrameLayout(context)
        val owner = TestOwner()

        androidContent.addView(windowContentChild)
        windowContentChild.addView(previewRoot)
        previewRoot.addView(composeHost)
        previewRoot.setViewTreeLifecycleOwner(owner)
        previewRoot.setViewTreeSavedStateRegistryOwner(owner)

        assertThat(composeHost.findViewTreeLifecycleOwner()).isSameInstanceAs(owner)
        assertThat(windowContentChild.findViewTreeLifecycleOwner()).isNull()
        assertThat(prepareQsbWindowOwners(composeHost)).isTrue()
        assertThat(findQsbWindowContentChild(composeHost)).isSameInstanceAs(windowContentChild)
        assertThat(windowContentChild.findViewTreeLifecycleOwner()).isSameInstanceAs(owner)
        assertThat(windowContentChild.findViewTreeSavedStateRegistryOwner()).isSameInstanceAs(owner)
    }

    @Test
    fun liveWindowOwner_isNotReplacedByNestedPreviewOwner() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val androidContent = FrameLayout(context).apply { id = android.R.id.content }
        val windowContentChild = FrameLayout(context)
        val previewRoot = FrameLayout(context)
        val composeHost = FrameLayout(context)
        val windowOwner = TestOwner()
        val previewOwner = TestOwner()

        androidContent.addView(windowContentChild)
        windowContentChild.addView(previewRoot)
        previewRoot.addView(composeHost)
        windowContentChild.setViewTreeLifecycleOwner(windowOwner)
        windowContentChild.setViewTreeSavedStateRegistryOwner(windowOwner)
        previewRoot.setViewTreeLifecycleOwner(previewOwner)
        previewRoot.setViewTreeSavedStateRegistryOwner(previewOwner)

        assertThat(prepareQsbWindowOwners(composeHost)).isTrue()
        assertThat(windowContentChild.findViewTreeLifecycleOwner()).isSameInstanceAs(windowOwner)
        assertThat(windowContentChild.findViewTreeSavedStateRegistryOwner()).isSameInstanceAs(windowOwner)
    }

    @Test
    fun destroyedWindowOwner_isReplacedByLiveNestedOwner() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val androidContent = FrameLayout(context).apply { id = android.R.id.content }
        val windowContentChild = FrameLayout(context)
        val previewRoot = FrameLayout(context)
        val composeHost = FrameLayout(context)
        val destroyedWindowOwner = TestOwner().also { it.destroy() }
        val previewOwner = TestOwner()

        androidContent.addView(windowContentChild)
        windowContentChild.addView(previewRoot)
        previewRoot.addView(composeHost)
        windowContentChild.setViewTreeLifecycleOwner(destroyedWindowOwner)
        windowContentChild.setViewTreeSavedStateRegistryOwner(destroyedWindowOwner)
        previewRoot.setViewTreeLifecycleOwner(previewOwner)
        previewRoot.setViewTreeSavedStateRegistryOwner(previewOwner)

        assertThat(prepareQsbWindowOwners(composeHost)).isTrue()
        assertThat(windowContentChild.findViewTreeLifecycleOwner()).isSameInstanceAs(previewOwner)
        assertThat(windowContentChild.findViewTreeSavedStateRegistryOwner()).isSameInstanceAs(previewOwner)
    }
}
