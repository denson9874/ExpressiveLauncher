package app.lawnchair.gestures

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.os.Process
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.window.OnBackAnimationCallback
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.gestures.OpenAppGestureFailureTest.ShadowRefusingLauncherApps
import app.lawnchair.gestures.handlers.GestureHandler
import app.lawnchair.gestures.handlers.NoOpGestureHandler
import app.lawnchair.gestures.handlers.OpenAppGestureHandler
import app.lawnchair.gestures.handlers.OpenAppTarget
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherState
import com.android.launcher3.R
import com.android.launcher3.dragndrop.DragLayer
import com.android.launcher3.dragndrop.LauncherDragController
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.util.BackPressHandler
import com.android.launcher3.util.ComponentKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * L04: Settings > Gestures > Back button (Pro) never ran its action. NORMAL hooked the action
 * into LauncherState.onBackPressed(Launcher), which nothing calls: every Back on Home (the
 * predictive-back callback, the 3-button Back key, Launcher#onBackPressed) ends in
 * LauncherState.onBackInvoked, which did nothing in NORMAL. Back on Home now runs the action,
 * once per press, after whatever is open on Home has had its turn to take Back, and only once
 * Home has settled (not while All Apps is still closing into it).
 *
 * The action is an "Open app" handler, so a run shows up as one recorded launch.
 *
 * SDK 34: the drag layer's focus outline reads com.android.launcher3.Flags, and Android 17's
 * framework jar bundles an older copy that hides ours. Below Android 14 Back reaches the same
 * NORMAL.onBackInvoked through Launcher#onBackPressed's older branch; that is checked on a
 * device, as the Robolectric images cached for this suite are SDK 34 and 37 only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    shadows = [ShadowRefusingLauncherApps::class],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeBackGestureTest {

    private lateinit var launcher: LawnchairLauncher
    private lateinit var dragLayer: DragLayer

    @Before
    fun setUp() {
        ShadowRefusingLauncherApps.failure = null
        ShadowRefusingLauncherApps.launches.clear()
        // The drag controller reads preferences through LawnchairApp.instance, which this plain
        // Application host never creates.
        val app = LawnchairApp()
        ReflectionHelpers.callInstanceMethod<Any>(
            app,
            "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, ApplicationProvider.getApplicationContext()),
        )
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", app)

        // Home at rest, with what Back asks before the state: the drag controller, and the drag
        // layer that holds folders, popups and sheets.
        launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        ReflectionHelpers.setField(
            launcher,
            "mStateManager",
            StateManager<LauncherState, Launcher>(launcher, LauncherState.NORMAL),
        )
        ReflectionHelpers.setField(launcher, "mDragController", LauncherDragController(launcher))
        dragLayer = DragLayer(ContextThemeWrapper(launcher, R.style.LauncherTheme), null)
        ReflectionHelpers.setField(launcher, "mDragLayer", dragLayer)
    }

    @After
    fun tearDown() {
        idle()
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", null)
    }

    @Test
    fun backOnHome_runsTheBackButtonAction() {
        setBackAction(OpenAppGestureHandler(launcher, OpenAppTarget.App(MAPS)))

        dispatchBack()

        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS)
        assertThat(launcher.stateManager.state).isEqualTo(LauncherState.NORMAL)
    }

    @Suppress("DEPRECATION")
    @Test
    fun backKeyOnHome_runsTheBackButtonActionOncePerPress() {
        setBackAction(OpenAppGestureHandler(launcher, OpenAppTarget.App(MAPS)))

        launcher.onBackPressed()
        idle()
        launcher.onBackPressed()
        idle()
        // The Back key as Home's input consumer hands it over while Home is the recents
        // activity: straight to dispatchKeyEvent, without the window's back callback.
        assertThat(launcher.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))).isTrue()
        idle()

        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS, MAPS, MAPS)
    }

    @Test
    fun backOnHome_runsOnlyTheChosenAction() {
        setBackAction(NoOpGestureHandler(launcher))

        dispatchBack()

        assertThat(ShadowRefusingLauncherApps.launches).isEmpty()
        assertThat(launcher.stateManager.state).isEqualTo(LauncherState.NORMAL)

        // Choosing an action makes the same Back run it.
        setBackAction(OpenAppGestureHandler(launcher, OpenAppTarget.App(MAPS)))

        dispatchBack()

        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS)
    }

    @Test
    fun backWhileHomeIsSettling_runsNothingUntilHomeHasSettled() {
        setBackAction(OpenAppGestureHandler(launcher, OpenAppTarget.App(MAPS)))
        val stateManager = launcher.stateManager

        // All Apps is closing: StateManager already reports NORMAL, but All Apps is still the
        // stable state until the animation ends. A second quick Back belongs to that close.
        ReflectionHelpers.setField(stateManager, "mCurrentStableState", LauncherState.ALL_APPS)
        assertThat(stateManager.state).isEqualTo(LauncherState.NORMAL)

        dispatchBack()

        assertThat(ShadowRefusingLauncherApps.launches).isEmpty()

        // All Apps finished closing: Back is Home's.
        ReflectionHelpers.setField(stateManager, "mCurrentStableState", LauncherState.NORMAL)

        dispatchBack()

        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS)

        // All Apps is about to open (the transition is set, its animation not started yet).
        val transition = ReflectionHelpers.getField<Any>(stateManager, "mConfig")
        ReflectionHelpers.setField(transition, "targetState", LauncherState.ALL_APPS)

        dispatchBack()

        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS)
    }

    @Test
    fun backOnHome_closesAnOpenFloatingViewBeforeTheAction() {
        setBackAction(OpenAppGestureHandler(launcher, OpenAppTarget.App(MAPS)))
        val folder = OpenFloatingView(dragLayer.context)
        dragLayer.addView(folder)

        dispatchBack()

        assertThat(folder.closes).isEqualTo(1)
        assertThat(folder.isOpen).isFalse()
        assertThat(ShadowRefusingLauncherApps.launches).isEmpty()

        // With nothing open any more, the next Back is Home's again.
        dispatchBack()

        assertThat(folder.closes).isEqualTo(1)
        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS)
    }

    @Test
    fun backOnHome_letsARegisteredBackHandlerGoFirst() {
        setBackAction(OpenAppGestureHandler(launcher, OpenAppTarget.App(MAPS)))
        var handled = 0
        // As split selection's handler does: it takes Back while a split is being picked.
        var picking = true
        val handler = object : BackPressHandler {
            override fun canHandleBack() = picking

            override fun onBackInvoked() {
                handled++
                picking = false
            }
        }
        ReflectionHelpers.callInstanceMethod<Any>(
            launcher,
            "addBackAnimationCallback",
            ReflectionHelpers.ClassParameter.from(BackPressHandler::class.java, handler),
        )

        dispatchBack()

        assertThat(handled).isEqualTo(1)
        assertThat(ShadowRefusingLauncherApps.launches).isEmpty()

        // Once the handler declines, Back is Home's again.
        dispatchBack()

        assertThat(handled).isEqualTo(1)
        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS)
    }

    /**
     * Back as Android 14+ delivers it: the predictive-back callback Home registers and the
     * 3-button Back key both take Launcher#getOnBackAnimationCallback and invoke it.
     */
    private fun dispatchBack() {
        ReflectionHelpers.callInstanceMethod<OnBackAnimationCallback>(launcher, "getOnBackAnimationCallback")
            .onBackInvoked()
        idle()
    }

    /**
     * Gives GestureController [handler] as the Back button's action, as it maps it from the
     * preference. DataStore reads that preference on another thread, so the test hands over the
     * mapped handler instead of waiting for the read.
     */
    private fun setBackAction(handler: GestureHandler) {
        ReflectionHelpers.setField(launcher.gestureController, "backPressHandler", MutableStateFlow(handler))
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A folder, popup or sheet as Back sees it: open on Home until something closes it. */
    private class OpenFloatingView(context: Context) : AbstractFloatingView(context, null) {
        var closes = 0

        init {
            mIsOpen = true
        }

        // AbstractFloatingView#close clears mIsOpen right after this, as a real folder's close does.
        override fun handleClose(animate: Boolean) {
            closes++
        }

        override fun isOfType(type: Int): Boolean = (type and AbstractFloatingView.TYPE_FOLDER) != 0

        override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean = false
    }

    companion object {
        private val MAPS = ComponentKey(
            ComponentName("com.example.maps", "com.example.maps.MainActivity"),
            Process.myUserHandle(),
        )
    }
}
