package app.lawnchair.allapps.views

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import app.lawnchair.LawnchairLauncher
import app.lawnchair.search.adapter.SETTINGS
import app.lawnchair.search.adapter.SearchActionCompat
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory
import com.android.app.search.LayoutType
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import com.android.launcher3.allapps.ActivityAllAppsContainerView
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.logging.StatsLogManager
import com.android.launcher3.pm.UserCache
import com.android.launcher3.util.Executors
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
import org.robolectric.shadows.ShadowViewGroup
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        ContactSearchAccessibilityTest.ShadowFontManager::class,
        ContactSearchAccessibilityTest.ShadowPreferenceManager::class,
        ContactSearchAccessibilityTest.ShadowThemeProvider::class,
        PrivateSpaceRecoveryRowTest.ShadowContainer::class,
        PrivateSpaceRecoveryRowTest.ShadowPrivateManager::class,
    ],
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateSpaceRecoveryRowTest {

    @Test
    fun rowTap_opensPrivateSpaceWithoutLaunchingSettings() {
        val fixture = fixture()
        fixture.row.bind(fixture.recovery, emptyList())

        assertThat(fixture.row.performClick()).isTrue()

        assertThat(fixture.manager.openCalls).isEqualTo(1)
        assertThat(shadowOf(fixture.launcher).nextStartedActivity).isNull()
    }

    @Test
    fun iconTap_opensPrivateSpaceWithoutLaunchingSettings() {
        val fixture = fixture()
        fixture.row.bind(fixture.recovery, emptyList())

        assertThat(fixture.row.findViewById<View>(R.id.icon).performClick()).isTrue()

        assertThat(fixture.manager.openCalls).isEqualTo(1)
        assertThat(shadowOf(fixture.launcher).nextStartedActivity).isNull()
    }

    @Test
    fun keyboardQuickLaunch_usesTheSamePrivateSpaceAction() {
        val fixture = fixture()
        fixture.row.bind(fixture.recovery, emptyList())

        assertThat(fixture.row.launch()).isTrue()

        assertThat(fixture.manager.openCalls).isEqualTo(1)
        assertThat(shadowOf(fixture.launcher).nextStartedActivity).isNull()
    }

    @Test
    fun noConfiguredProfile_preservesAndroidSetupFallback() {
        val fixture = fixture()
        fixture.manager.handled = false
        fixture.row.bind(fixture.recovery, emptyList())

        fixture.row.performClick()

        assertThat(fixture.manager.openCalls).isEqualTo(1)
        assertThat(shadowOf(fixture.launcher).nextStartedActivity.action)
            .isEqualTo(fixture.settingsIntent.action)
    }

    @Test
    fun absentManager_preservesAndroidSetupFallback() {
        val fixture = fixture()
        fixture.container.manager = null
        fixture.row.bind(fixture.recovery, emptyList())

        fixture.row.findViewById<View>(R.id.icon).performClick()

        assertThat(fixture.manager.openCalls).isEqualTo(0)
        assertThat(shadowOf(fixture.launcher).nextStartedActivity.action)
            .isEqualTo(fixture.settingsIntent.action)
    }

    @Test
    fun recycledRow_restoresNormalSettingsAndThenPrivateSpaceActions() {
        val fixture = fixture()
        fixture.row.bind(fixture.recovery, emptyList())
        val ordinarySettings = SearchTargetCompat.Builder(
            SearchTargetCompat.RESULT_TYPE_SETTING_TILE,
            LayoutType.ICON_SLICE,
            "ordinary_settings",
        )
            .setPackageName(SETTINGS)
            .setUserHandle(Process.myUserHandle())
            .setExtras(Bundle())
            .setSearchAction(
                SearchActionCompat.Builder("ordinary_settings", "Display settings")
                    .setIntent(fixture.settingsIntent)
                    .build(),
            )
            .build()
        fixture.row.bind(ordinarySettings, emptyList())

        fixture.row.performClick()
        assertThat(shadowOf(fixture.launcher).nextStartedActivity.action)
            .isEqualTo(fixture.settingsIntent.action)
        fixture.row.findViewById<View>(R.id.icon).performClick()
        assertThat(fixture.manager.openCalls).isEqualTo(0)
        // Ordinary icon handling may launch the ordinary destination; consume only that result.
        shadowOf(fixture.launcher).nextStartedActivity

        fixture.row.bind(fixture.recovery, emptyList())
        fixture.row.findViewById<View>(R.id.icon).performClick()
        fixture.row.launch()
        assertThat(fixture.manager.openCalls).isEqualTo(2)
        assertThat(shadowOf(fixture.launcher).nextStartedActivity).isNull()
    }

    private fun fixture(): Fixture {
        val launcher = Robolectric.buildActivity(LawnchairLauncher::class.java).get()
        ReflectionHelpers.setField(launcher, "mDeviceProfile", DeviceProfile())
        shadowOf(Executors.MODEL_EXECUTOR.looper).pause()
        val apps = ActivityAllAppsContainerView<LawnchairLauncher>(launcher)
        ReflectionHelpers.setField(launcher, "mAppsView", apps)
        val container = Shadow.extract<ShadowContainer>(apps)
        val manager = PrivateProfileManager(null, apps, null, null)
        container.manager = manager
        val context = ContextThemeWrapper(launcher, R.style.LauncherTheme)
        val row = LayoutInflater.from(context).inflate(
            R.layout.search_result_small_icon_row,
            FrameLayout(context),
            false,
        ) as SearchResultIconRow
        val settingsIntent = Intent("dev.expressive.test.PRIVATE_SPACE_SETUP")
        shadowOf(launcher.packageManager).addResolveInfoForIntent(
            settingsIntent,
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    name = "TestSettingsActivity"
                    packageName = launcher.packageName
                    applicationInfo = ApplicationInfo().apply { packageName = launcher.packageName }
                    exported = true
                }
            },
        )
        return Fixture(
            launcher,
            row,
            container,
            Shadow.extract(manager),
            settingsIntent,
            SearchTargetFactory(context).createPrivateSpaceRecoveryTarget(settingsIntent),
        )
    }

    private data class Fixture(
        val launcher: LawnchairLauncher,
        val row: SearchResultIconRow,
        val container: ShadowContainer,
        val manager: ShadowPrivateManager,
        val settingsIntent: Intent,
        val recovery: SearchTargetCompat,
    )

    // Keep the actual inflated row, icon binding and click dispatch. Only the unrelated full
    // launcher container and the manager boundary are replaced in this renderer regression.
    @Implements(ActivityAllAppsContainerView::class)
    class ShadowContainer : ShadowViewGroup() {
        @RealObject private lateinit var realContainer: ActivityAllAppsContainerView<*>
        var manager: PrivateProfileManager? = null

        @Implementation
        fun __constructor__(context: Context) {
            ReflectionHelpers.setField(realContainer, "mContext", context)
        }

        @Implementation
        fun __constructor__(context: Context, attrs: AttributeSet?) = __constructor__(context)

        @Implementation
        fun __constructor__(context: Context, attrs: AttributeSet?, defStyleAttr: Int) =
            __constructor__(context)

        @Implementation
        fun getPrivateProfileManager(): PrivateProfileManager? = manager
    }

    @Implements(PrivateProfileManager::class)
    class ShadowPrivateManager {
        var handled = true
        var openCalls = 0

        @Implementation
        fun __constructor__(
            userManager: UserManager?,
            allApps: ActivityAllAppsContainerView<*>,
            statsLogManager: StatsLogManager?,
            userCache: UserCache?,
        ) = Unit

        @Implementation
        fun openPrivateSpace(): Boolean {
            openCalls++
            return handled
        }
    }
}
