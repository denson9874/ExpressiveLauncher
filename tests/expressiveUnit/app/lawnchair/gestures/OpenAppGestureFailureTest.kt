package app.lawnchair.gestures

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.views.ContactSearchAccessibilityTest
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.gestures.handlers.OpenAppTarget
import app.lawnchair.gestures.type.GestureType
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.BubbleTextView
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.util.ComponentKey
import com.google.common.truth.Truth.assertThat
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/**
 * R15: an "Open app" gesture or action shortcut whose app was uninstalled or disabled, or whose
 * profile is paused, made LauncherApps throw from a coroutine with no handler, which crashed
 * Home. The launch now shows a toast, keeps the stored gesture, and any other gesture failure
 * is logged instead of reaching the thread's default handler (which kills the app).
 *
 * The toast names the app by its installed label (or its package name), never by the name in
 * the config: the exported START_ACTION trampoline takes that config from any app, so another
 * app could otherwise make Home show text of its choosing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        OpenAppGestureFailureTest.ShadowRefusingLauncherApps::class,
        // BubbleTextView's font setup reaches FeatureFlags, which can't load on this SDK.
        ContactSearchAccessibilityTest.ShadowFontManager::class,
    ],
)
class OpenAppGestureFailureTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val uncaught = mutableListOf<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var launcher: LawnchairLauncher

    @Before
    fun setUp() {
        ShadowRefusingLauncherApps.failure = null
        ShadowRefusingLauncherApps.launches.clear()
        ShadowToast.reset()
        // In the app this is the default handler, which reports the crash and kills Home.
        val thread = Thread.currentThread()
        previousHandler = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught += error }
        launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        // Maps and the work Mail app are installed for their users; GONE is not installed at all.
        install(MAPS, MAPS_LABEL)
        install(WORK_MAIL, WORK_MAIL_LABEL)
    }

    @After
    fun tearDown() {
        Thread.currentThread().uncaughtExceptionHandler = previousHandler
        ShadowRefusingLauncherApps.failure = null
    }

    @Test
    fun refusedLaunch_showsUnavailableToastInsteadOfCrashing() {
        val refusals = listOf(
            // Uninstalled, disabled, or no longer has a launcher activity.
            MAPS to SecurityException("Attempt to launch activity without category Intent.CATEGORY_LAUNCHER"),
            MAPS to ActivityNotFoundException("No Activity found"),
            // Paused or locked work profile.
            WORK_MAIL to IllegalStateException("User is locked or not running"),
        )
        for ((key, failure) in refusals) {
            ShadowToast.reset()
            ShadowRefusingLauncherApps.failure = failure

            runBlocking { openApp(key).createHandler(launcher).onTrigger(launcher) }

            assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
            assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(unavailableText(labelOf(key)))
            assertThat(ShadowToast.getTextOfLatestToast()).doesNotContain(SUPPLIED_NAME)
        }
        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS, MAPS, WORK_MAIL).inOrder()
        assertThat(uncaught).isEmpty()
    }

    @Test
    fun unavailableToast_saysTheAppIsUnavailableAndTheGestureCanBeReassigned() {
        val text = unavailableText(MAPS_LABEL)

        assertThat(text).startsWith("$MAPS_LABEL isn't available.")
        assertThat(text).contains("another action for this gesture")
    }

    @Test
    fun refusedLaunch_fromAnotherAppsIntent_namesTheInstalledAppNotTheSuppliedName() {
        ShadowRefusingLauncherApps.failure = SecurityException("Attempt to launch activity without category Intent.CATEGORY_LAUNCHER")
        // What another app can put in the trampoline's EXTRA_HANDLER: a real component of an
        // installed app, with any name, including another installed app's.
        for (supplied in listOf(SUPPLIED_NAME, "", WORK_MAIL_LABEL)) {
            ShadowToast.reset()
            val extra = GestureHandlerConfig.toString(GestureHandlerConfig.OpenApp(supplied, OpenAppTarget.App(MAPS)))
            val config = GestureHandlerConfig.fromString(extra)
            assertThat(config).isEqualTo(GestureHandlerConfig.OpenApp(supplied, OpenAppTarget.App(MAPS)))

            runBlocking { config.createHandler(launcher).onTrigger(launcher) }

            assertThat(ShadowToast.shownToastCount()).isEqualTo(1)
            assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(unavailableText(MAPS_LABEL))
            if (supplied.isNotEmpty()) assertThat(ShadowToast.getTextOfLatestToast()).doesNotContain(supplied)
        }
        assertThat(uncaught).isEmpty()
    }

    @Test
    fun refusedLaunch_ofAnAppThatCantBeFound_namesThePackageNotTheSuppliedName() {
        // Uninstalled (or never installed for this user): there is no label to resolve.
        ShadowRefusingLauncherApps.failure = SecurityException("uninstalled")
        runBlocking { openApp(GONE).createHandler(launcher).onTrigger(launcher) }

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(unavailableText(GONE.componentName.packageName))
        assertThat(ShadowToast.getTextOfLatestToast()).doesNotContain(SUPPLIED_NAME)

        // Installed but with a blank label: still the package name, never the config's name.
        ShadowToast.reset()
        install(MAPS, "  ")
        runBlocking { openApp(MAPS).createHandler(launcher).onTrigger(launcher) }

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(unavailableText(MAPS.componentName.packageName))
        assertThat(ShadowToast.getTextOfLatestToast()).doesNotContain(SUPPLIED_NAME)
    }

    @Test
    fun refusedLaunch_inAnotherProfile_usesThatProfilesLabel() {
        // The same package in the personal profile has another name; the work profile's is used.
        install(ComponentKey(WORK_MAIL.componentName, Process.myUserHandle()), "Personal Mail")
        ShadowRefusingLauncherApps.failure = IllegalStateException("User is locked or not running")

        runBlocking { openApp(WORK_MAIL).createHandler(launcher).onTrigger(launcher) }

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(unavailableText(WORK_MAIL_LABEL))
    }

    @Test
    fun successfulLaunch_showsNoToast() {
        runBlocking { openApp(MAPS).createHandler(launcher).onTrigger(launcher) }

        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS)
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)
    }

    @Test
    fun refusedLaunch_keepsTheStoredGestures() {
        val prefs = PreferenceManager2.getInstance(launcher)
        val config = openApp(MAPS)
        prefs.doubleTapGestureHandler.setBlocking(config)
        runBlocking { prefs.setGestureForApp(ICON, GestureType.SWIPE_UP, config) }
        drainPreferenceReactions()
        ShadowRefusingLauncherApps.failure = SecurityException("uninstalled")

        // Home double tap (runs the stored config) and an "Open app" action shortcut.
        val stored = prefs.doubleTapGestureHandler.firstBlocking()
        runBlocking { stored.createHandler(launcher).onTrigger(launcher) }
        GestureController(launcher).handle(config)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS, MAPS)
        assertThat(prefs.doubleTapGestureHandler.firstBlocking()).isEqualTo(config)
        assertThat(runBlocking { prefs.getGestureForApp(ICON, GestureType.SWIPE_UP).first() }).isEqualTo(config)
        assertThat(uncaught).isEmpty()
    }

    @Test
    fun gestureController_failingActionDoesNotCrashHome() {
        val controller = GestureController(launcher)

        ShadowRefusingLauncherApps.failure = SecurityException("uninstalled")
        controller.handle(openApp(MAPS))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(unavailableText(MAPS_LABEL))

        // A failure the handler does not expect still stays inside the gesture.
        ShadowRefusingLauncherApps.failure = UnsupportedOperationException("unexpected")
        controller.handle(openApp(MAPS))
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS, MAPS)
        assertThat(uncaught).isEmpty()
    }

    @Test
    fun iconSwipe_failingActionDoesNotCrashHome() {
        ReflectionHelpers.setField(launcher, "mDeviceProfile", DeviceProfile())
        val listener = IconGestureListener(BubbleTextView(launcher), PreferenceManager2.getInstance(launcher), ICON)

        ShadowRefusingLauncherApps.failure = SecurityException("disabled")
        listener.triggerGesture(GestureType.SWIPE_UP, openApp(MAPS))
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(unavailableText(MAPS_LABEL))

        ShadowRefusingLauncherApps.failure = UnsupportedOperationException("unexpected")
        listener.triggerGesture(GestureType.SWIPE_UP, openApp(MAPS))
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(ShadowRefusingLauncherApps.launches).containsExactly(MAPS, MAPS)
        assertThat(uncaught).isEmpty()
    }

    /**
     * PreferenceManager2 answers the first preference write after it starts by reloading the
     * grid, the icon theme and Home itself, which this bare Robolectric host can't do. Run those
     * reactions before a gesture fires, so [uncaught] holds only what the gesture throws, and
     * check that nothing besides them was set aside.
     */
    private fun drainPreferenceReactions() {
        shadowOf(Looper.getMainLooper()).idle()
        val unrelated = uncaught.filterNot { error ->
            error.stackTrace.any { it.className.startsWith(PreferenceManager2::class.java.name) }
        }
        assertThat(unrelated).isEmpty()
        uncaught.clear()
    }

    /** A config whose stored name is not the app's label, as any trampoline caller can send. */
    private fun openApp(key: ComponentKey) = GestureHandlerConfig.OpenApp(SUPPLIED_NAME, OpenAppTarget.App(key))

    private fun unavailableText(name: String) = context.getString(R.string.gesture_handler_open_app_unavailable, name)

    private fun labelOf(key: ComponentKey) = if (key == WORK_MAIL) WORK_MAIL_LABEL else MAPS_LABEL

    /** Registers [key]'s package for its user with [label], as LauncherApps then reports it. */
    private fun install(key: ComponentKey, label: String) {
        val info = ApplicationInfo().apply {
            packageName = key.componentName.packageName
            nonLocalizedLabel = label
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED
        }
        Shadow.extract<ShadowRefusingLauncherApps>(launcher.getSystemService(LauncherApps::class.java))
            .addApplicationInfo(key.user, key.componentName.packageName, info)
    }

    /** LauncherApps that records each main-activity launch and refuses it with [failure]. */
    @Implements(LauncherApps::class)
    class ShadowRefusingLauncherApps : ShadowLauncherApps() {
        @Implementation
        fun startMainActivity(component: ComponentName, user: UserHandle, sourceBounds: Rect?, opts: Bundle?) {
            launches += ComponentKey(component, user)
            failure?.let { throw it }
        }

        companion object {
            var failure: RuntimeException? = null
            val launches = mutableListOf<ComponentKey>()
        }
    }

    companion object {
        private const val MAPS_LABEL = "Maps"
        private const val WORK_MAIL_LABEL = "Work Mail"

        /** Caller-chosen text that must never reach Home's toast. */
        private const val SUPPLIED_NAME = "Your phone is locked. Call 555-0100 to unlock Home"
        private val MAPS = ComponentKey(
            ComponentName("com.example.maps", "com.example.maps.MainActivity"),
            Process.myUserHandle(),
        )
        private val WORK_MAIL = ComponentKey(
            ComponentName("com.example.mail", "com.example.mail.Inbox"),
            UserHandle.of(10),
        )
        private val GONE = ComponentKey(
            ComponentName("com.example.gone", "com.example.gone.MainActivity"),
            Process.myUserHandle(),
        )
        private val ICON = ComponentKey(
            ComponentName("com.example.notes", "com.example.notes.MainActivity"),
            Process.myUserHandle(),
        )
    }
}
