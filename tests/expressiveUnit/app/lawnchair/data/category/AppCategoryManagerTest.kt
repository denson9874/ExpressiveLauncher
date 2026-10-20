package app.lawnchair.data.category

import android.app.Application
import android.content.pm.ApplicationInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AppCategoryManagerTest {

    @Test
    fun category_mapsFromApplicationInfoConstants() {
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_GAME, "com.example.game"))
            .isEqualTo(AppCategory.GAMES)
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_AUDIO, "com.example.music"))
            .isEqualTo(AppCategory.MEDIA)
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_VIDEO, "com.example.video"))
            .isEqualTo(AppCategory.MEDIA)
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_SOCIAL, "com.example.chat"))
            .isEqualTo(AppCategory.SOCIAL)
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_NEWS, "com.example.news"))
            .isEqualTo(AppCategory.SOCIAL)
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_PRODUCTIVITY, "com.example.docs"))
            .isEqualTo(AppCategory.WORK)
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_MAPS, "com.example.maps"))
            .isEqualTo(AppCategory.UTILITIES)
    }

    @Test
    fun category_infersFromPackageNameWhenUndefined() {
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_UNDEFINED, "com.spotify.music"))
            .isEqualTo(AppCategory.MEDIA)
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_UNDEFINED, "org.telegram.messenger"))
            .isEqualTo(AppCategory.SOCIAL)
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_UNDEFINED, "com.Slack"))
            .isEqualTo(AppCategory.WORK)
        assertThat(AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_UNDEFINED, "com.android.calculator2"))
            .isEqualTo(AppCategory.UTILITIES)
    }

    @Test
    fun getCategory_returnsInstantFallbackWithoutCrashing() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val manager = AppCategoryManager(app)
        val category = manager.getCategory("com.spotify.music")
        assertThat(category).isEqualTo(AppCategory.MEDIA)
    }

    @Test
    fun setCustomCategory_overridesResolvedCategory() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val manager = AppCategoryManager(app)
        manager.setCustomCategory("com.example.app", AppCategory.GAMES)
        assertThat(manager.getCategory("com.example.app")).isEqualTo(AppCategory.GAMES)
        manager.setCustomCategory("com.example.app", null)
    }
}
