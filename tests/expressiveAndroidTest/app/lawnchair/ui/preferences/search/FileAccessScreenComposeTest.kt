package app.lawnchair.ui.preferences.search

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.components.search.SearchProviderId
import app.lawnchair.ui.preferences.navigation.SearchProviderPreference
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileAccessScreenComposeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun expressiveManifest_omitsRestrictedStoragePermissions() {
        assumeTrue("Expressive-only storage contract", BuildConfig.IS_EXPRESSIVE_PRODUCT)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )

        assertThat(BuildConfig.CAN_REQUEST_MANAGE_ALL_FILES_ACCESS).isFalse()
        assertThat(BuildConfig.CAN_REQUEST_BROAD_VISUAL_MEDIA_ACCESS).isFalse()
        assertThat(packageInfo.requestedPermissions.orEmpty().asList()).containsNoneOf(
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    }

    @Test
    fun expressiveFilesScreen_usesSelectedFolderInsteadOfRestrictedAccess() {
        assumeTrue("Expressive-only storage contract", BuildConfig.IS_EXPRESSIVE_PRODUCT)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val intent = PreferenceActivity.createIntent(
            context,
            SearchProviderPreference(SearchProviderId.FILES),
        )
        ActivityScenario.launch<PreferenceActivity>(intent).use {
            composeRule.onNodeWithText(
                context.getString(R.string.search_pref_result_selected_folder_title),
            ).assertIsDisplayed()
            assertThat(
                composeRule.onAllNodesWithText(
                    context.getString(R.string.search_pref_result_all_files_title),
                ).fetchSemanticsNodes(),
            ).isEmpty()
            assertThat(
                composeRule.onAllNodesWithText(
                    context.getString(R.string.search_pref_result_visual_media_title),
                ).fetchSemanticsNodes(),
            ).isEmpty()
        }
    }
}
