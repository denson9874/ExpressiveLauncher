package com.android.launcher3.model

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller.SessionInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.lawnchair.LawnchairApp
import com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP
import com.android.launcher3.Workspace
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.WorkspaceData.MutableWorkspaceData
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.PackageManagerHelper
import com.android.launcher3.util.PackageUserKey
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Issue #15: Home crash-looped on Android 12 after Android restored its data. The first load after
 * a restore sends installers the first-screen broadcast with archiving extras, built from the
 * active-session map that loadWorkspaceImpl caches only on Android 15+ (archived apps don't exist
 * earlier). On Android 12-14 that map was null, FirstScreenBroadcastHelper threw, the load never
 * committed, and IS_FIRST_LOAD_AFTER_RESTORE stayed true, so every launch crashed the same way.
 *
 * SDK 34 stands in for Android 12-14: Utilities.ATLEAST_V is false on all of them. Android 17's
 * framework jar bundles an older com.android.launcher3.Flags without the archiving-extras flag, so
 * the Android 15+ side is covered through the decision and the helper instead of SDK 37.
 *
 * The broadcast runs on a LoaderTask built without its constructor, which needs the whole launcher
 * graph; sendFirstScreenActiveInstallsBroadcast only reads the fields set here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirstScreenBroadcastRestoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // collectWorkspaceScreens() reads preferences through LawnchairApp.instance, which this
        // plain Application host never creates.
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
    fun firstLoadAfterRestore_belowV_fallsBackToTheLegacyBroadcast() {
        val legacy = RecordingBroadcast()
        val task = loaderTask(legacy, isRestoreFromBackup = true, cachedSessions = null)

        sendFirstScreenActiveInstallsBroadcast(task)

        // The legacy broadcast carries the session map it was built with, so installers still
        // hear about first-screen installs.
        assertThat(legacy.sentItems).containsExactly(firstScreenApp)
    }

    @Test
    fun ordinaryLoad_usesTheLegacyBroadcast() {
        val legacy = RecordingBroadcast()
        val task = loaderTask(legacy, isRestoreFromBackup = false, cachedSessions = null)

        sendFirstScreenActiveInstallsBroadcast(task)

        assertThat(legacy.sentItems).containsExactly(firstScreenApp)
    }

    @Test
    fun failedBroadcast_doesNotAbortTheLoad() {
        // The broadcast is a courtesy to installers. If it throws, the load must still commit
        // (clearing IS_FIRST_LOAD_AFTER_RESTORE after a restore) instead of crashing Home.
        val task = loaderTask(
            ThrowingBroadcast(IllegalStateException("installer broadcast failed")),
            isRestoreFromBackup = false,
            cachedSessions = null,
        )

        sendFirstScreenActiveInstallsBroadcast(task)
    }

    @Test
    fun stoppedLoader_stillCancelsTheLoad() {
        val task = loaderTask(
            ThrowingBroadcast(CancellationException("Loader stopped")),
            isRestoreFromBackup = false,
            cachedSessions = null,
        )

        assertThrows(CancellationException::class.java) {
            sendFirstScreenActiveInstallsBroadcast(task)
        }
    }

    @Test
    fun archivingExtras_withTheCachedSessionMap_reportInstalledFirstScreenApps() {
        // What the Android 15+ restore path sends once loadWorkspaceImpl has cached the sessions.
        shadowOf(context.packageManager)
            .setInstallSourceInfo(RESTORED_PACKAGE, INSTALLER_PACKAGE, INSTALLER_PACKAGE)

        val models = FirstScreenBroadcastHelper.createModelsForFirstScreenBroadcast(
            PackageManagerHelper(context),
            listOf(firstScreenApp),
            HashMap<PackageUserKey, SessionInfo>(),
            emptyList(),
        )
        FirstScreenBroadcastHelper.sendBroadcastsForModels(context, models)

        val broadcast = shadowOf(context as Application).broadcastIntents
            .single { it.action == ACTION_FIRST_SCREEN_ACTIVE_INSTALLS }
        assertThat(broadcast.`package`).isEqualTo(INSTALLER_PACKAGE)
        assertThat(broadcast.getStringArrayListExtra("workspaceInstalledItems"))
            .containsExactly(RESTORED_PACKAGE)
    }

    @Test
    fun archivingExtras_needAndroid15AndTheCachedSessions() {
        // Android 15+: loadWorkspaceImpl cached the sessions, so the extras can be built.
        assertThat(shouldAttach(atLeastV = true, cachedSessions = HashMap())).isTrue()

        // Issue #15: Android 12-14 never caches the sessions.
        assertThat(shouldAttach(atLeastV = false, cachedSessions = null)).isFalse()
        // Archived apps don't exist below Android 15, even with a map.
        assertThat(shouldAttach(atLeastV = false, cachedSessions = HashMap())).isFalse()
        // Android 15+ with archiving support off never caches them either.
        assertThat(shouldAttach(atLeastV = true, cachedSessions = null)).isFalse()
    }

    @Test
    fun archivingExtras_keepTheUpstreamConditions() {
        assertThat(shouldAttach(isRestoreFromBackup = false)).isFalse()
        assertThat(shouldAttach(disableArchivingLauncherBroadcast = 1)).isFalse()
        assertThat(shouldAttach(archivingExtrasEnabled = false)).isFalse()
    }

    private fun shouldAttach(
        isRestoreFromBackup: Boolean = true,
        disableArchivingLauncherBroadcast: Int = 0,
        archivingExtrasEnabled: Boolean = true,
        atLeastV: Boolean = true,
        cachedSessions: Map<PackageUserKey, SessionInfo>? = HashMap(),
    ): Boolean = LoaderTask.shouldAttachArchivingExtras(
        isRestoreFromBackup,
        disableArchivingLauncherBroadcast,
        archivingExtrasEnabled,
        atLeastV,
        cachedSessions,
    )

    private val firstScreenApp: ItemInfo = WorkspaceItemInfo().apply {
        id = 1
        container = CONTAINER_DESKTOP
        screenId = Workspace.FIRST_SCREEN_ID
        intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(RESTORED_PACKAGE, "$RESTORED_PACKAGE.Main"))
    }

    private fun loaderTask(
        legacyBroadcast: FirstScreenBroadcast,
        isRestoreFromBackup: Boolean,
        cachedSessions: HashMap<PackageUserKey, SessionInfo>?,
    ): LoaderTask {
        val workspace = MutableWorkspaceData().apply { addItems(listOf(firstScreenApp), null) }
        val bgDataModel = allocate(BgDataModel::class.java)
        ReflectionHelpers.setField(bgDataModel, "itemsIdMap", workspace)

        return allocate(LoaderTask::class.java).also {
            ReflectionHelpers.setField(it, "mContext", context)
            ReflectionHelpers.setField(it, "mBgDataModel", bgDataModel)
            ReflectionHelpers.setField(it, "mPmHelper", PackageManagerHelper(context))
            ReflectionHelpers.setField(it, "mFirstScreenBroadcast", legacyBroadcast)
            ReflectionHelpers.setField(it, "mIsRestoreFromBackup", isRestoreFromBackup)
            ReflectionHelpers.setField(it, "mInstallingPkgsCached", cachedSessions)
        }
    }

    private fun sendFirstScreenActiveInstallsBroadcast(task: LoaderTask) {
        ReflectionHelpers.callInstanceMethod<Any>(task, "sendFirstScreenActiveInstallsBroadcast")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            .get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    /** The legacy path, recording what it was asked to send. */
    private class RecordingBroadcast : FirstScreenBroadcast(emptyMap()) {
        var sentItems: List<ItemInfo>? = null

        override fun sendBroadcasts(context: Context, firstScreenItems: List<ItemInfo>) {
            sentItems = firstScreenItems
        }
    }

    private class ThrowingBroadcast(private val failure: RuntimeException) :
        FirstScreenBroadcast(emptyMap()) {
        override fun sendBroadcasts(context: Context, firstScreenItems: List<ItemInfo>) {
            throw failure
        }
    }

    private companion object {
        const val RESTORED_PACKAGE = "com.example.restored"
        const val INSTALLER_PACKAGE = "com.android.vending"
        const val ACTION_FIRST_SCREEN_ACTIVE_INSTALLS =
            "com.android.launcher3.action.FIRST_SCREEN_ACTIVE_INSTALLS"
    }
}
