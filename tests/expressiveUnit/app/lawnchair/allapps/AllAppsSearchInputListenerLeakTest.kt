package app.lawnchair.allapps

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairApp
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.views.ContactSearchAccessibilityTest
import com.android.launcher3.DeviceProfile
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
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
 * L01: an in-process relaunch (dark mode, font, recreate()) keeps Home's window, so its
 * ViewTreeObserver outlives every destroyed Launcher. AllAppsSearchInput registered its global
 * layout listener on a child that was not attached yet (the listener merged into the window's
 * observer) and removed it on that child after it had already detached (a no-op). The window
 * kept one search bar per relaunch, each holding its whole destroyed Launcher. The QSB
 * pre-draw retries had the same register/remove asymmetry.
 *
 * A host window stays alive while the views attach and detach, as the kept window does.
 *
 * SDK 34: the search bars' preferences reach FeatureFlags, and Android 17's framework jar bundles
 * an older com.android.wm.shell.Flags that FeatureFlags can't load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    shadows = [ContactSearchAccessibilityTest.ShadowFontManager::class],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class AllAppsSearchInputListenerLeakTest {

    @Before
    fun setUp() {
        // firstCached() reads preferences through LawnchairApp.instance, which this plain
        // Application host never creates.
        val app = LawnchairApp()
        ReflectionHelpers.callInstanceMethod<Any>(
            app,
            "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, ApplicationProvider.getApplicationContext()),
        )
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", app)
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.setStaticField(LawnchairApp::class.java, "instance", null)
    }

    @Test
    fun drawerSearchBar_leavesNoListenerOnTheKeptWindow() {
        val window = keptWindow()

        val detached = attachAndDetach(window, preDrawField = "recreateQsbCompositionOnPreDraw") { launcher ->
            LayoutInflater.from(launcher).inflate(R.layout.search_container_all_apps, null, false)
        }

        assertThat(detached.leftOnWindow).isEmpty()
        assertThat(collected(detached.view)).isTrue()
    }

    @Test
    fun dockSearchBar_detachedBeforeItsFirstFrame_leavesNoListenerOnTheKeptWindow() {
        val window = keptWindow()

        val detached = attachAndDetach(window, preDrawField = "createCompositionAfterAttach") { launcher ->
            LayoutInflater.from(launcher).inflate(R.layout.search_container_hotseat, null, false)
        }

        assertThat(detached.leftOnWindow).isEmpty()
        assertThat(collected(detached.view)).isTrue()
    }

    /** The window that survives the relaunch: attached for the whole test. */
    private fun keptWindow(): FrameLayout {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible().get()
        val content = FrameLayout(activity)
        activity.setContentView(content)
        shadowOf(Looper.getMainLooper()).idle()
        check(content.isAttachedToWindow)
        return content
    }

    /**
     * Inflates a view for a Launcher that is then thrown away, shows it in [window] and removes it
     * again, as the destroyed instance's content leaves the kept window. Returns only weak
     * references, and the listeners the view left on the window's observer.
     */
    private fun attachAndDetach(
        window: FrameLayout,
        preDrawField: String,
        inflate: (LawnchairLauncher) -> View,
    ): Detached {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        ReflectionHelpers.setField(launcher, "mDeviceProfile", deviceProfile())
        val view = inflate(launcher)
        window.addView(view)
        check(view.isAttachedToWindow)
        window.removeView(view)
        check(!view.isAttachedToWindow)

        val observer = window.viewTreeObserver
        val preDraw = ReflectionHelpers.getField<Any>(view, preDrawField)
        val left = buildList {
            if (listeners(observer, "mOnGlobalLayoutListeners").any { it === view }) add("global layout: $view")
            if (listeners(observer, "mOnPreDrawListeners").any { it === preDraw }) add("pre-draw: $preDrawField")
        }
        return Detached(WeakReference(view), left)
    }

    /**
     * [view] holds its Launcher, so a view the window lets go of no longer pins that Launcher.
     * (The Launcher itself can't be checked here: Robolectric keeps every activity it builds.)
     */
    private class Detached(
        val view: WeakReference<View>,
        val leftOnWindow: List<String>,
    )

    /**
     * DeviceProfile's test constructor has no grid: the search bars only add a listener to it,
     * and the drawer's padding divides by its column count.
     */
    private fun deviceProfile(): DeviceProfile {
        val unsafe = ReflectionHelpers.getStaticField<Any>(Class.forName("sun.misc.Unsafe"), "theUnsafe")
        val grid = ReflectionHelpers.callInstanceMethod<Any>(
            unsafe,
            "allocateInstance",
            ReflectionHelpers.ClassParameter.from(Class::class.java, InvariantDeviceProfile::class.java),
        )
        ReflectionHelpers.setField(grid, "mChangeListeners", CopyOnWriteArrayList<Any>())
        return DeviceProfile().also {
            ReflectionHelpers.setField(it, "inv", grid)
            ReflectionHelpers.setField(it, "numShownAllAppsColumns", 4)
        }
    }

    private fun listeners(observer: ViewTreeObserver, field: String): List<Any> {
        val copyOnWrite = ReflectionHelpers.getField<Any>(observer, field) ?: return emptyList()
        return ReflectionHelpers.getField<List<Any>>(copyOnWrite, "mData").toList()
    }

    private fun collected(reference: WeakReference<*>): Boolean {
        repeat(20) {
            if (reference.get() == null) return true
            Runtime.getRuntime().gc()
            System.runFinalization()
            shadowOf(Looper.getMainLooper()).idle()
        }
        return reference.get() == null
    }
}
