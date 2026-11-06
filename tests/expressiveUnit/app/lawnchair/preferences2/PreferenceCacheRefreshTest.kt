package app.lawnchair.preferences2

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * A live icon-size change must reach the grid reload that its onSet starts. The reload reads
 * [firstCached], whose cache is filled by an asynchronous DataStore collector, so the reload has
 * to refresh that cache itself instead of hoping the collector ran first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PreferenceCacheRefreshTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()
    private lateinit var prefs2: PreferenceManager2

    @Before
    fun setUp() {
        prefs2 = PreferenceManager2.getInstance(context)
        writeHomeIconSize(1f)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(homeIconSize()).isEqualTo(1f)
    }

    @Test
    fun cachedValue_lagsAWrite_untilRefreshed() {
        writeHomeIconSize(0.9f)
        // The collector has not run yet: this is the stale value a reload used to read.
        assertThat(homeIconSize()).isEqualTo(1f)

        prefs2.refreshCachedPreferences()

        assertThat(homeIconSize()).isEqualTo(0.9f)
    }

    @Test
    fun recreate_refreshesTheCacheBeforeReloading() {
        var refreshes = 0

        ReloadHelper(context) { refreshes++ }.recreate()

        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun defaultReloadHelper_refreshesTheSharedPreferenceCache() {
        writeHomeIconSize(0.9f)

        ReloadHelper(context).recreate()

        assertThat(homeIconSize()).isEqualTo(0.9f)
    }

    private fun homeIconSize(): Float = prefs2.homeIconSizeFactor.firstCached(prefs2)

    private fun writeHomeIconSize(value: Float) {
        runBlocking {
            prefs2.preferencesDataStore.edit {
                it[floatPreferencesKey("home_icon_size_factor")] = value
            }
        }
    }
}
