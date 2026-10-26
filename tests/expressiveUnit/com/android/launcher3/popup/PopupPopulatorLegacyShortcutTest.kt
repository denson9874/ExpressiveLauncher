// ITEM_TYPE_SHORTCUT and LauncherAppState are deprecated, and are what this test covers.
@file:Suppress("DEPRECATION")

package com.android.launcher3.popup

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
import com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.ShortcutUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps
import org.robolectric.util.ReflectionHelpers

/**
 * R18: long-pressing a legacy shortcut (ITEM_TYPE_SHORTCUT) whose intent has no package and no
 * component, such as a Contacts direct-dial shortcut or one imported from another launcher,
 * crashed Home: the popup's background update built an ApplicationInfoWrapper with a null
 * package on the model thread. Such a shortcut has no app to query deep shortcuts for, so the
 * update now does nothing for it, while an app icon still queries its shortcuts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        PopupPopulatorLegacyShortcutTest.ShadowRecordingLauncherApps::class,
        PopupPopulatorLegacyShortcutTest.ShadowLauncherAppStateCompanion::class,
    ],
)
class PopupPopulatorLegacyShortcutTest {

    private lateinit var launcher: LawnchairLauncher

    @Before
    fun setUp() {
        ShadowRecordingLauncherApps.queries.clear()
        launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
    }

    @Test
    fun legacyShortcutWithoutPackageOrComponent_updatesWithoutCrashing() {
        val directDial = legacyShortcut(Intent(Intent.ACTION_CALL, Uri.parse("tel:5550100")))
        // Its long-press opens the popup, with no deep shortcut rows.
        assertThat(ShortcutUtil.supportsShortcuts(directDial)).isTrue()
        assertThat(ShortcutUtil.supportsDeepShortcuts(directDial)).isFalse()
        assertThat(directDial.targetComponent).isNull()
        assertThat(directDial.targetPackage).isNull()

        runUpdate(directDial)

        assertThat(ShadowRecordingLauncherApps.queries).isEmpty()
    }

    @Test
    fun legacyShortcutWithOnlyAPackage_updatesWithoutQuerying() {
        // A package but no activity: ShortcutRequest.withContainer(null) can't query anything.
        val wifi = legacyShortcut(Intent(Settings.ACTION_WIFI_SETTINGS).setPackage(SETTINGS))
        assertThat(wifi.targetComponent).isNull()
        assertThat(wifi.targetPackage).isEqualTo(SETTINGS)

        runUpdate(wifi)

        assertThat(ShadowRecordingLauncherApps.queries).isEmpty()
    }

    @Test
    fun appIcon_stillQueriesItsPublishedShortcuts() {
        val app = WorkspaceItemInfo().apply {
            itemType = ITEM_TYPE_APPLICATION
            container = CONTAINER_DESKTOP
            intent = AppInfo.makeLaunchIntent(MAPS)
            user = Process.myUserHandle()
        }

        runUpdate(app)

        assertThat(ShadowRecordingLauncherApps.queries)
            .containsExactly(MAPS to Process.myUserHandle())
    }

    private fun legacyShortcut(shortcutIntent: Intent) = WorkspaceItemInfo().apply {
        itemType = ITEM_TYPE_SHORTCUT
        container = CONTAINER_DESKTOP
        title = "Legacy shortcut"
        intent = shortcutIntent
        user = Process.myUserHandle()
    }

    /** Runs the popup's background update the way PopupContainerWithArrow posts it. */
    private fun runUpdate(item: WorkspaceItemInfo) {
        PopupPopulator.createUpdateRunnable(
            launcher,
            item,
            Handler(Looper.getMainLooper()),
            null,
            emptyList(),
        ).run()
    }

    /** LauncherApps that records each shortcut query's activity and user, and finds none. */
    @Implements(LauncherApps::class)
    class ShadowRecordingLauncherApps : ShadowLauncherApps() {
        @Implementation
        override fun getShortcuts(query: LauncherApps.ShortcutQuery, user: UserHandle): List<ShortcutInfo> {
            queries += ReflectionHelpers.getField<ComponentName?>(query, "mActivity") to user
            return emptyList()
        }

        companion object {
            val queries = mutableListOf<Pair<ComponentName?, UserHandle>>()
        }
    }

    // The real LauncherAppState builds the whole launcher graph, whose FeatureFlags can't load on
    // this SDK. The update only uses its IconCache to fill a deep shortcut row, and these popups
    // have none, so an empty instance is enough.
    @Implements(LauncherAppState.Companion::class)
    class ShadowLauncherAppStateCompanion {
        @Implementation
        fun getInstance(context: Context): LauncherAppState = appState

        private companion object {
            val appState by lazy { allocate(LauncherAppState::class.java) as LauncherAppState }

            fun allocate(type: Class<*>): Any {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
                    .get(null)
                return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)!!
            }
        }
    }

    companion object {
        private const val SETTINGS = "com.android.settings"
        private val MAPS = ComponentName("com.example.maps", "com.example.maps.MainActivity")
    }
}
