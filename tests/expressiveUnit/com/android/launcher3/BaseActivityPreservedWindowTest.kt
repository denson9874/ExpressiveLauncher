package com.android.launcher3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.qsb.findQsbWindowContentChild
import com.android.launcher3.util.ActivityLifecycleCallbacksAdapter
import com.android.launcher3.views.BaseDragLayer
import com.google.common.truth.Truth.assertThat
import java.lang.ref.WeakReference
import java.time.Duration
import kotlin.coroutines.ContinuationInterceptor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * D01 + L03: Android relaunches Home in-process for a dark-mode, font or other configuration
 * change, and keeps its window for the new instance. The kept DecorView still names the destroyed
 * instance as its ViewTreeLifecycleOwner while the new instance's onCreate attaches its content.
 * Launcher's compose-on-attach search bar then created the window Recomposer against that
 * DESTROYED lifecycle: it never started, so nothing recomposed (write-only sheets) and the dock
 * search bar's focus ripple threw "MotionDurationScale scale factor requested before recomposer
 * loop start" on D-pad focus.
 *
 * Robolectric's relaunch builds a fresh window, so [PreservedWindow] reproduces the kept one: it
 * attaches the new instance's decor before onCreate, still owned by the destroyed instance, as
 * ActivityThread does. A minimal [BaseActivity] supplies Launcher's own owner wiring.
 *
 * SDK 34: BaseActivity.onCreate reaches FeatureFlags, and Android 17's framework jar bundles an
 * older com.android.wm.shell.Flags that FeatureFlags can't load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BaseActivityPreservedWindowTest {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private val preservedWindow = PreservedWindow()

    @Before
    fun setUp() {
        // Compose's main-thread dispatcher outlives each test, but Robolectric empties the main
        // looper between tests. A frame the dispatcher scheduled just before that reset never
        // arrives, and the dispatcher would wait for it forever instead of scheduling another.
        val main = AndroidUiDispatcher.Main[ContinuationInterceptor] as AndroidUiDispatcher
        ReflectionHelpers.setField(main, "scheduledFrameDispatch", false)
        ReflectionHelpers.setField(main, "scheduledTrampolineDispatch", false)
        ComposeHostActivity.value.intValue = 0
        app.registerActivityLifecycleCallbacks(preservedWindow)
        // ActivityScenario resolves the activity through the package manager.
        shadowOf(app.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = ComposeHostActivity::class.java.name
                packageName = app.packageName
            },
        )
    }

    @After
    fun tearDown() {
        idle()
        app.unregisterActivityLifecycleCallbacks(preservedWindow)
    }

    @Test
    fun recreate_keptWindow_windowRecomposerRunsAndRecomposes() {
        ActivityScenario.launch(ComposeHostActivity::class.java).use { scenario ->
            scenario.recreate()
            idle()
            assertThat(preservedWindow.kept).isEqualTo(1)

            scenario.onActivity { assertComposeIsLive(it) }
        }
    }

    @Test
    fun nightModeToggles_keptWindow_windowRecomposerRunsAndRecomposes() {
        val controller = Robolectric.buildActivity(ComposeHostActivity::class.java).setup()

        // The device repro: night yes, night no, twice.
        repeat(2) {
            controller.configurationChange(uiMode(controller.get(), Configuration.UI_MODE_NIGHT_YES))
            idle()
            controller.configurationChange(uiMode(controller.get(), Configuration.UI_MODE_NIGHT_NO))
            idle()
        }

        assertThat(preservedWindow.kept).isEqualTo(4)
        assertComposeIsLive(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun saveInstanceState_thenReturnToHome_lifecycleIsResumedAndComposeStaysLive() {
        val controller = Robolectric.buildActivity(ComposeHostActivity::class.java).setup()
        controller.configurationChange(uiMode(controller.get(), Configuration.UI_MODE_NIGHT_YES))
        idle()

        // L03: Settings in front of Home stops it and saves its state; then Home returns.
        controller.pause().stop().saveInstanceState(Bundle())
        assertThat(controller.get().lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        controller.restart().start().resume().postResume()
        idle()

        assertThat(controller.get().lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
        assertComposeIsLive(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun relaunches_destroyedInstanceCanBeCollected() {
        val controller = Robolectric.buildActivity(ComposeHostActivity::class.java).setup()
        val first = WeakReference<Activity>(controller.get())

        controller.configurationChange(uiMode(controller.get(), Configuration.UI_MODE_NIGHT_YES))
        idle()
        controller.configurationChange(uiMode(controller.get(), Configuration.UI_MODE_NIGHT_NO))
        idle()

        assertThat(preservedWindow.kept).isEqualTo(2)
        assertThat(collected(first)).isTrue()
        controller.pause().stop().destroy()
    }

    private fun assertComposeIsLive(activity: ComposeHostActivity) {
        val composeView = activity.composeView
        val window = activity.window!!
        // The decor names the live instance, not the destroyed one.
        assertThat(window.decorView.findViewTreeLifecycleOwner()).isSameInstanceAs(activity)

        val recomposer = findQsbWindowContentChild(composeView).compositionContext as Recomposer
        assertThat(recomposer.currentState.value).isNotEqualTo(Recomposer.State.Inactive)
        assertThat(recomposer.currentState.value).isNotEqualTo(Recomposer.State.ShutDown)
        // The focus ripple reads this; it threw before the recomposer loop started.
        assertThat(recomposer.effectCoroutineContext[MotionDurationScale]!!.scaleFactor).isEqualTo(1f)

        // A state write reaches the screen: the composition is not frozen at its first frame.
        assertThat(activity.composedValue).isEqualTo(ComposeHostActivity.value.intValue)
        ComposeHostActivity.value.intValue++
        Snapshot.sendApplyNotifications()
        idle()
        assertThat(activity.composedValue).isEqualTo(ComposeHostActivity.value.intValue)
    }

    private fun uiMode(context: Context, night: Int) = Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun collected(reference: WeakReference<*>): Boolean {
        repeat(20) {
            if (reference.get() == null) return true
            Runtime.getRuntime().gc()
            System.runFinalization()
            idle()
        }
        return reference.get() == null
    }

    /**
     * Reproduces ActivityThread's kept window for a relaunched instance: the new instance's decor
     * is attached before its onCreate and still carries the destroyed instance's view-tree
     * owners. Application callbacks run before the activity's own pre-create callbacks.
     */
    private class PreservedWindow : ActivityLifecycleCallbacksAdapter {
        private var destroyed: Activity? = null
        var kept = 0

        override fun onActivityDestroyed(activity: Activity) {
            if (activity is ComposeHostActivity) destroyed = activity
        }

        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            val previous = destroyed ?: return
            destroyed = null
            val window = activity.window
            val decor = window.decorView
            decor.setViewTreeLifecycleOwner(previous as ComposeHostActivity)
            decor.setViewTreeSavedStateRegistryOwner(previous)
            window.attributes.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION
            activity.windowManager.addView(decor, window.attributes)
            ReflectionHelpers.setField(activity, "mDecor", decor)
            ReflectionHelpers.setField(activity, "mWindowAdded", true)
            // A kept window has been traversed: its decor is attached, so content attaches as
            // soon as onCreate adds it.
            val viewRoot = checkNotNull(decor.parent) { "kept decor has no ViewRootImpl" }
            ReflectionHelpers.callInstanceMethod<Any>(viewRoot, "doTraversal")
            check(decor.isAttachedToWindow) { "kept decor is not attached" }
            kept++
        }
    }
}

/** The smallest [BaseActivity]: Launcher's owner wiring and one compose-on-attach view. */
class ComposeHostActivity : BaseActivity() {

    lateinit var composeView: ComposeView
    var composedValue = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = ComposeView(this).apply {
            // As Launcher's drawer search bar (AllAppsSearchInput.qsbShell) did.
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val current = value.intValue
                SideEffect { composedValue = current }
            }
        }
        // Launcher.onCreate sets its root the same way.
        setContentView(FrameLayout(this).apply { addView(composeView) })
    }

    override fun getDragLayer(): BaseDragLayer<*>? = null

    companion object {
        val value = mutableIntStateOf(0)
    }
}
