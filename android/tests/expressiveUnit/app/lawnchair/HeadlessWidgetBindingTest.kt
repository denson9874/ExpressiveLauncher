package app.lawnchair

import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import com.android.launcher3.LauncherPrefs
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppWidgetHost
import org.robolectric.shadows.ShadowAppWidgetManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [37],
    application = Application::class,
    shadows = [
        HeadlessWidgetBindingTest.RecordingWidgetHost::class,
        HeadlessWidgetBindingTest.RecordingWidgetManager::class,
    ],
)
class HeadlessWidgetBindingTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var manager: HeadlessWidgetsManager
    private lateinit var info: AppWidgetProviderInfo

    @Before
    fun setUp() {
        nextId = 10
        deletedIds.clear()
        allocatedIds.clear()
        boundWidgets.clear()
        allowBinding = false
        LauncherPrefs.getDevicePrefs(context).edit().clear().commit()
        info = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.google.android.googlequicksearchbox", "WeatherWidget")
            ReflectionHelpers.setField(
                this,
                "providerInfo",
                ActivityInfo().apply {
                    applicationInfo = ApplicationInfo().apply { uid = Process.myUid() }
                },
            )
        }
        manager = HeadlessWidgetsManager(context)
    }

    @After
    fun tearDown() {
        manager.close()
    }

    @Test
    fun invalidWidgetId_getsAFreshPersistedIdWithoutRestarting() {
        val widget = manager.getWidget(info, PREF_KEY)
        val firstIntent = requireNotNull(widget.getBindIntent())
        val invalidId = firstIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)

        // Simulate the invalid ID observed before the Pixel could approve its widget.
        boundWidgets.remove(invalidId)
        val retryIntent = requireNotNull(widget.getBindIntent())
        val retryId = retryIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)

        assertThat(retryId).isGreaterThan(invalidId)
        assertThat(deletedIds).contains(invalidId)
        assertThat(LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)).isEqualTo(retryId)
        assertThat(retryIntent.action).isEqualTo(AppWidgetManager.ACTION_APPWIDGET_BIND)
        assertThat(retryIntent.getParcelableExtra<ComponentName>(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER))
            .isEqualTo(info.provider)

        // The granted retry is recognized by this same Widget instance.
        boundWidgets[retryId] = info
        assertThat(widget.isBound).isTrue()
    }

    @Test
    fun existingBinding_keepsItsIdAndDoesNotOpenAnotherPermissionDialog() {
        val existingId = 7
        LauncherPrefs.getDevicePrefs(context).edit().putInt(PREF_KEY, existingId).commit()
        boundWidgets[existingId] = info

        val widget = manager.getWidget(info, PREF_KEY)

        assertThat(widget.isBound).isTrue()
        assertThat(widget.getBindIntent()).isNull()
        assertThat(allocatedIds).isEmpty()
        assertThat(deletedIds).isEmpty()
        assertThat(LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)).isEqualTo(existingId)
    }

    @Test
    fun permissionGrantedSinceFirstAttempt_bindsFreshIdWithoutAnotherDialog() {
        val widget = manager.getWidget(info, PREF_KEY)
        val staleId = LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)
        allowBinding = true

        assertThat(widget.getBindIntent()).isNull()
        assertThat(widget.isBound).isTrue()
        val replacementId = LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)
        assertThat(replacementId).isGreaterThan(staleId)
        assertThat(deletedIds).contains(staleId)
        assertThat(boundWidgets[replacementId]?.provider).isEqualTo(info.provider)
    }

    @Test
    fun bindingDeletedWhileProcessRemainsAlive_isRecoveredOnNextAttempt() {
        allowBinding = true
        val widget = manager.getWidget(info, PREF_KEY)
        val firstId = LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)
        assertThat(widget.isBound).isTrue()
        boundWidgets.remove(firstId)

        assertThat(widget.getBindIntent()).isNull()
        assertThat(widget.isBound).isTrue()
        assertThat(LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)).isGreaterThan(firstId)
        assertThat(deletedIds).contains(firstId)
    }

    @Test
    fun staleCachedId_adoptsTheValidPersistedBindingWithoutAllocatingOrDeleting() {
        val widget = manager.getWidget(info, PREF_KEY)
        val staleId = LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)
        val existingId = 17
        boundWidgets.remove(staleId)
        boundWidgets[existingId] = info
        LauncherPrefs.getDevicePrefs(context).edit().putInt(PREF_KEY, existingId).commit()
        val allocationsBeforeRecovery = allocatedIds.toList()
        val deletionsBeforeRecovery = deletedIds.toList()

        assertThat(widget.isBound).isFalse()
        assertThat(widget.getBindIntent()).isNull()

        assertThat(widget.isBound).isTrue()
        assertThat(LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)).isEqualTo(existingId)
        assertThat(boundWidgets[existingId]?.provider).isEqualTo(info.provider)
        assertThat(allocatedIds).containsExactlyElementsIn(allocationsBeforeRecovery).inOrder()
        assertThat(deletedIds).containsExactlyElementsIn(deletionsBeforeRecovery).inOrder()
    }

    @Test
    fun secondManagerBinding_isAdoptedByTheOlderWidgetInstance() {
        val olderWidget = manager.getWidget(info, PREF_KEY)
        val olderId = LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)
        val secondManager = HeadlessWidgetsManager(context)
        try {
            val newerWidget = secondManager.getWidget(info, PREF_KEY)
            allowBinding = true
            assertThat(newerWidget.getBindIntent()).isNull()
            val newerId = LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)
            assertThat(newerId).isGreaterThan(olderId)
            assertThat(olderWidget.isBound).isFalse()
            val allocationsBeforeRecovery = allocatedIds.toList()
            val deletionsBeforeRecovery = deletedIds.toList()

            assertThat(olderWidget.getBindIntent()).isNull()

            assertThat(olderWidget.isBound).isTrue()
            assertThat(newerWidget.isBound).isTrue()
            assertThat(LauncherPrefs.getDevicePrefs(context).getInt(PREF_KEY, -1)).isEqualTo(newerId)
            assertThat(boundWidgets.keys).containsExactly(newerId)
            assertThat(allocatedIds).containsExactlyElementsIn(allocationsBeforeRecovery).inOrder()
            assertThat(deletedIds).containsExactlyElementsIn(deletionsBeforeRecovery).inOrder()
        } finally {
            secondManager.close()
        }
    }

    @Implements(AppWidgetHost::class)
    class RecordingWidgetHost : ShadowAppWidgetHost() {
        @Implementation
        override fun allocateAppWidgetId(): Int = nextId.also {
            nextId++
            allocatedIds += it
        }

        @Implementation
        fun deleteAppWidgetId(appWidgetId: Int) {
            deletedIds += appWidgetId
            boundWidgets.remove(appWidgetId)
        }
    }

    @Implements(AppWidgetManager::class)
    class RecordingWidgetManager : ShadowAppWidgetManager() {
        @Implementation
        override fun getAppWidgetInfo(appWidgetId: Int): AppWidgetProviderInfo? = boundWidgets[appWidgetId]

        @Implementation
        fun bindAppWidgetIdIfAllowed(
            appWidgetId: Int,
            profile: UserHandle?,
            provider: ComponentName,
            options: Bundle?,
        ): Boolean {
            if (allowBinding) {
                boundWidgets[appWidgetId] = AppWidgetProviderInfo().apply { this.provider = provider }
            }
            return allowBinding
        }
    }

    companion object {
        private const val PREF_KEY = "smartspaceWidgetId"
        private var nextId = 10
        private var allowBinding = false
        private val allocatedIds = mutableListOf<Int>()
        private val deletedIds = mutableListOf<Int>()
        private val boundWidgets = mutableMapOf<Int, AppWidgetProviderInfo>()
    }
}
