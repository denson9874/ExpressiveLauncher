package app.lawnchair.allapps

import android.app.Application
import android.content.ComponentName
import com.android.launcher3.model.data.AppInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DrawerSortOrderTest {

    private val byName = compareBy<AppInfo> { it.title.toString() }

    private fun app(name: String) = AppInfo().apply {
        title = name
        componentName = ComponentName("pkg.$name", "pkg.$name.Main")
    }

    private val apps = listOf(app("Camera"), app("Alarm"), app("Browser"), app("Docs"))

    private fun sorted(order: DrawerSortOrder, ranks: Map<String, Long>) = apps.sortedWith(drawerComparator(order, byName, ranks)).map { it.title.toString() }

    @Test
    fun alphabeticalIgnoresRanks() {
        assertThat(sorted(DrawerSortOrder.ALPHABETICAL, mapOf("pkg.Docs" to 99L)))
            .containsExactly("Alarm", "Browser", "Camera", "Docs").inOrder()
    }

    @Test
    fun higherRankFirst_tiesAndUnrankedAppsStayAlphabetical() {
        val ranks = mapOf("pkg.Docs" to 30L, "pkg.Camera" to 10L, "pkg.Browser" to 10L)
        assertThat(sorted(DrawerSortOrder.MOST_USED, ranks))
            .containsExactly("Docs", "Browser", "Camera", "Alarm").inOrder()
    }

    @Test
    fun noRanks_forExampleWithoutUsageAccess_keepsAlphabeticalOrder() {
        assertThat(sorted(DrawerSortOrder.MOST_USED, emptyMap()))
            .containsExactly("Alarm", "Browser", "Camera", "Docs").inOrder()
    }

    @Test
    fun unknownSavedValueFallsBackToAlphabetical() {
        assertThat(DrawerSortOrder.fromString("bogus")).isEqualTo(DrawerSortOrder.ALPHABETICAL)
        assertThat(DrawerSortOrder.fromString(null)).isEqualTo(DrawerSortOrder.ALPHABETICAL)
        DrawerSortOrder.entries.forEach { assertThat(DrawerSortOrder.fromString(it.prefValue)).isEqualTo(it) }
    }
}
