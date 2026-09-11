package com.android.launcher3.allapps

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.AttributeSet
import app.lawnchair.LawnchairLauncher
import app.lawnchair.allapps.views.ContactSearchAccessibilityTest
import com.android.launcher3.Flags
import com.android.launcher3.logging.StatsLogManager
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.ApiWrapper
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.Executors
import com.android.launcher3.util.UserIconInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowUserManager
import org.robolectric.shadows.ShadowViewGroup
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        PrivateSpaceOpenActionTest.ShadowContainer::class,
        PrivateSpaceOpenActionTest.ShadowPrivateDecorations::class,
        PrivateSpaceOpenActionTest.ShadowProfiles::class,
        PrivateSpaceOpenActionTest.ShadowQuietMode::class,
        PrivateSpaceOpenActionTest.ShadowAnimationFlags::class,
        PrivateSpaceOpenActionTest.ShadowRecycler::class,
        ContactSearchAccessibilityTest.ShadowPreferenceManager::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateSpaceOpenActionTest {

    @Test
    fun lockedProfile_requestsSystemUnlockWithoutOpeningSettingsOrRevealingApps() {
        val fixture = fixture(UserProfileManager.STATE_DISABLED)

        assertThat(fixture.manager.openPrivateSpace()).isTrue()
        fixture.drainUnlockRequests()

        assertThat(fixture.system.requests).containsExactly(false to PRIVATE_USER)
        assertThat(fixture.manager.currentState).isEqualTo(UserProfileManager.STATE_DISABLED)
        assertThat(fixture.container.openCalls).isEqualTo(0)
        assertThat(shadowOf(fixture.launcher).nextStartedActivity).isNull()
    }

    @Test
    fun staleUnlockedCache_usesActualLockedProfileAndWaitsForSystemCallback() {
        val fixture = fixture(UserProfileManager.STATE_ENABLED)

        assertThat(fixture.manager.openPrivateSpace()).isTrue()
        fixture.drainUnlockRequests()

        assertThat(fixture.system.requests).containsExactly(false to PRIVATE_USER)
        assertThat(fixture.manager.currentState).isEqualTo(UserProfileManager.STATE_DISABLED)
        assertThat(fixture.container.openCalls).isEqualTo(0)
    }

    @Test
    fun unknownColdStartState_establishesLockedStateWithoutOptimisticallyOpening() {
        val fixture = fixture(UserProfileManager.STATE_UNKNOWN)

        assertThat(fixture.manager.openPrivateSpace()).isTrue()
        fixture.drainUnlockRequests()

        assertThat(fixture.manager.currentState).isEqualTo(UserProfileManager.STATE_DISABLED)
        assertThat(fixture.system.requests).containsExactly(false to PRIVATE_USER)
        assertThat(fixture.container.openCalls).isEqualTo(0)
    }

    @Test
    fun acceptedUnlockRequest_stillWaitsForProfileCallbackBeforeOpening() {
        val fixture = fixture(UserProfileManager.STATE_DISABLED)
        fixture.system.requestAccepted = true

        fixture.manager.openPrivateSpace()
        fixture.drainUnlockRequests()

        assertThat(fixture.system.requests).containsExactly(false to PRIVATE_USER)
        assertThat(fixture.manager.currentState).isEqualTo(UserProfileManager.STATE_DISABLED)
        assertThat(fixture.container.openCalls).isEqualTo(0)
    }

    @Test
    fun alreadyUnlockedProfile_opensContainerWithoutASecondUnlockRequest() {
        val fixture = fixture(UserProfileManager.STATE_ENABLED)
        fixture.system.quietMode = false

        assertThat(fixture.manager.openPrivateSpace()).isTrue()
        fixture.drainUnlockRequests()

        assertThat(fixture.container.openCalls).isEqualTo(1)
        assertThat(fixture.system.requests).isEmpty()
        assertThat(shadowOf(fixture.launcher).nextStartedActivity).isNull()
    }

    @Test
    fun unlockedAndroidWithStaleLockedModel_refreshesBeforeOpening() {
        val fixture = fixture(UserProfileManager.STATE_DISABLED)
        fixture.system.quietMode = false

        assertThat(fixture.manager.openPrivateSpace()).isTrue()
        fixture.drainUnlockRequests()

        assertThat(fixture.decorations.reloadCalls).isEqualTo(1)
        assertThat(fixture.system.requests).isEmpty()
        assertThat(fixture.container.openCalls).isEqualTo(0)
        assertThat(fixture.manager.currentState).isEqualTo(UserProfileManager.STATE_DISABLED)
    }

    @Test
    fun unlockedAndroidWithUnknownModel_refreshesAndWaitsForActualApps() {
        val fixture = fixture(UserProfileManager.STATE_UNKNOWN)
        fixture.system.quietMode = false

        assertThat(fixture.manager.openPrivateSpace()).isTrue()
        fixture.drainUnlockRequests()

        assertThat(fixture.decorations.reloadCalls).isEqualTo(1)
        assertThat(fixture.system.requests).isEmpty()
        assertThat(fixture.container.openCalls).isEqualTo(0)
        assertThat(fixture.manager.currentState).isEqualTo(UserProfileManager.STATE_DISABLED)
    }

    @Test
    fun noPrivateProfile_returnsUnhandledForTheNeutralSetupFallback() {
        val fixture = fixture(UserProfileManager.STATE_UNKNOWN)
        fixture.profiles.includePrivate = false

        assertThat(fixture.manager.openPrivateSpace()).isFalse()
        fixture.drainUnlockRequests()

        assertThat(fixture.system.requests).isEmpty()
        assertThat(fixture.container.openCalls).isEqualTo(0)
    }

    @Test
    fun actualUnlockCompletion_exitsSearchAndOpensContainerWithAnimationsDisabled() {
        val fixture = fixture(UserProfileManager.STATE_DISABLED)
        fixture.manager.openPrivateSpace()
        fixture.drainUnlockRequests()
        assertThat(fixture.container.openCalls).isEqualTo(0)
        assertThat(Flags.privateSpaceAnimation()).isFalse()

        // The real reset must observe the model's quiet-mode flag becoming clear before it
        // enters postUnlock; a submitted authentication request alone never does this.
        ReflectionHelpers.setField(fixture.container.modelStore, "mModelFlags", 0)
        fixture.manager.reset()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(fixture.manager.currentState).isEqualTo(UserProfileManager.STATE_ENABLED)
        assertThat(fixture.container.openCalls).isEqualTo(1)
        assertThat(fixture.container.searching).isFalse()
        assertThat(shadowOf(fixture.launcher).nextStartedActivity).isNull()
    }

    private fun fixture(state: Int): Fixture {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        shadowOf(Executors.MODEL_EXECUTOR.looper).pause()
        shadowOf(Executors.UI_HELPER_EXECUTOR.looper).pause()
        val apps = ActivityAllAppsContainerView<LawnchairLauncher>(launcher)
        val container = Shadow.extract<ShadowContainer>(apps)
        container.modelStore = AllAppsStore(launcher)
        ReflectionHelpers.setField(
            container.modelStore,
            "mModelFlags",
            com.android.launcher3.model.data.AppsListData.FLAG_PRIVATE_PROFILE_QUIET_MODE_ENABLED,
        )
        val userManager = launcher.getSystemService(UserManager::class.java)
        val tracker = ReflectionHelpers.callConstructor(DaggerSingletonTracker::class.java)
        val userCache = UserCache(launcher, tracker, ApiWrapper(launcher))
        val manager = PrivateProfileManager(userManager, apps, NoopStatsLogManager(launcher), userCache)
        manager.setCurrentState(state)
        val decorations = Shadow.extract<ShadowPrivateDecorations>(manager)
        decorations.recycler = AllAppsRecyclerView(launcher)
        return Fixture(
            launcher,
            manager,
            container,
            Shadow.extract(userCache),
            Shadow.extract(userManager),
            decorations,
        )
    }

    private data class Fixture(
        val launcher: LawnchairLauncher,
        val manager: PrivateProfileManager,
        val container: ShadowContainer,
        val profiles: ShadowProfiles,
        val system: ShadowQuietMode,
        val decorations: ShadowPrivateDecorations,
    ) {
        fun drainUnlockRequests() {
            shadowOf(Executors.UI_HELPER_EXECUTOR.looper).idle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class NoopStatsLogManager(context: Context) : StatsLogManager(context)

    // Keep openPrivateSpace, profile selection, quiet-mode dispatch and the post-unlock callback
    // real. These shadows isolate the Android service and the expensive full launcher/animation host.
    @Implements(ActivityAllAppsContainerView::class)
    class ShadowContainer : ShadowViewGroup() {
        @RealObject private lateinit var realContainer: ActivityAllAppsContainerView<*>
        var searching = true
        var openCalls = 0
        lateinit var modelStore: AllAppsStore<LawnchairLauncher>

        @Implementation
        fun __constructor__(context: Context) {
            ReflectionHelpers.setField(realContainer, "mContext", context)
            ReflectionHelpers.setField(realContainer, "mActivityContext", context)
        }

        @Implementation
        fun __constructor__(context: Context, attrs: AttributeSet?) = __constructor__(context)

        @Implementation
        fun __constructor__(context: Context, attrs: AttributeSet?, defStyleAttr: Int) =
            __constructor__(context)

        @Implementation
        fun getAppsStore(): AllAppsStore<LawnchairLauncher> = modelStore

        @Implementation
        fun updateHeaderScroll(scrolledOffset: Int) = Unit

        @Implementation
        fun isSearching(): Boolean = searching

        @Implementation
        fun resetAndScrollToPrivateSpaceHeader() {
            openCalls++
            searching = false
        }
    }

    @Implements(PrivateProfileManager::class)
    class ShadowPrivateDecorations {
        var reloadCalls = 0
        lateinit var recycler: AllAppsRecyclerView

        @Implementation
        fun initializeInBackgroundThread(context: Context) = Unit

        @Implementation
        fun expandPrivateSpace() = Unit

        @Implementation
        fun addPrivateSpaceDecorator() = Unit

        @Implementation
        fun getMainRecyclerView(): AllAppsRecyclerView = recycler

        @Implementation
        fun reloadPrivateSpaceApps() {
            reloadCalls++
        }
    }

    @Implements(AllAppsRecyclerView::class)
    class ShadowRecycler : ShadowViewGroup() {
        @RealObject private lateinit var realRecycler: AllAppsRecyclerView

        @Implementation
        override fun __constructor__(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) {
            // View and AllAppsRecyclerView have this same constructor signature. Initialize the
            // Android View once, but omit AllApps' unrelated invariant-profile/model lookup.
            if (realRecycler.context == null) {
                super.__constructor__(context, attrs, defStyleAttr, defStyleRes)
            }
        }
    }

    @Implements(UserCache::class)
    class ShadowProfiles {
        var includePrivate = true

        @Implementation
        fun __constructor__(context: Context, tracker: DaggerSingletonTracker, api: ApiWrapper) = Unit

        @Implementation
        fun getUserProfiles(): List<UserHandle> = if (includePrivate) {
            listOf(Process.myUserHandle(), PRIVATE_USER)
        } else {
            listOf(Process.myUserHandle())
        }

        @Implementation
        fun getUserInfo(user: UserHandle): UserIconInfo = UserIconInfo(
            user,
            if (user == PRIVATE_USER) UserIconInfo.TYPE_PRIVATE else UserIconInfo.TYPE_MAIN,
        )
    }

    @Implements(UserManager::class)
    class ShadowQuietMode : ShadowUserManager() {
        var quietMode = true
        var requestAccepted = false
        val requests = mutableListOf<Pair<Boolean, UserHandle>>()

        @Implementation
        override fun isQuietModeEnabled(userHandle: UserHandle): Boolean = quietMode

        @Implementation
        override fun requestQuietModeEnabled(enableQuietMode: Boolean, userHandle: UserHandle): Boolean {
            requests += enableQuietMode to userHandle
            return requestAccepted
        }
    }

    @Implements(Flags::class)
    class ShadowAnimationFlags {
        companion object {
            @JvmStatic
            @Implementation
            fun privateSpaceAnimation(): Boolean = false
        }
    }

    companion object {
        private val PRIVATE_USER = UserHandle(11)
    }
}
