package app.lawnchair.ui.preferences

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.lawnchair.test.SmartspaceHostActivity
import app.lawnchair.ui.preferences.components.WallpaperAccessPermissionDialog
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class WallpaperAccessPermissionComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<SmartspaceHostActivity>()

    @Before
    fun requireExpressiveProduct() {
        assumeTrue("Expressive direct-distribution dialog", BuildConfig.IS_EXPRESSIVE_PRODUCT)
    }

    @Test
    fun directBuild_offersWallpaperAccessWithoutPlayDenialOrMediaRequests() {
        val context = composeRule.activity
        composeRule.setContent {
            MaterialTheme {
                WallpaperAccessPermissionDialog(
                    managedFilesChecked = false,
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.wallpaper_access_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(
                R.string.wallpaper_all_files_access_description,
                context.getString(R.string.derived_app_name),
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.open_permission_settings))
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithText(context.getString(R.string.manage_storage_access_denied_title))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.permission_label_read_photos_videos))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.permission_desc_wallpaper_multiple))
            .assertDoesNotExist()
    }

    @Test
    fun cancel_closesDialogWithoutRequestingAccess() {
        val context = composeRule.activity
        val showing = mutableStateOf(true)
        var dismissals = 0
        var permissionRequests = 0
        composeRule.setContent {
            MaterialTheme {
                if (showing.value) {
                    WallpaperAccessPermissionDialog(
                        managedFilesChecked = false,
                        onDismiss = {
                            dismissals++
                            showing.value = false
                        },
                        onPermissionRequest = { permissionRequests++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()

        composeRule.onNodeWithText(context.getString(R.string.wallpaper_access_title))
            .assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(dismissals).isEqualTo(1)
            assertThat(permissionRequests).isEqualTo(0)
        }
    }

    @Test
    fun alreadyGrantedAllFilesAccess_dismissesWithoutPhotoVideoApproval() {
        val context = composeRule.activity
        val showing = mutableStateOf(true)
        var dismissals = 0
        var permissionRequests = 0
        composeRule.setContent {
            MaterialTheme {
                if (showing.value) {
                    WallpaperAccessPermissionDialog(
                        managedFilesChecked = true,
                        onDismiss = {
                            dismissals++
                            showing.value = false
                        },
                        onPermissionRequest = { permissionRequests++ },
                    )
                }
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.wallpaper_access_title))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.permission_label_read_photos_videos))
            .assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(dismissals).isEqualTo(1)
            assertThat(permissionRequests).isEqualTo(0)
        }
    }
}
