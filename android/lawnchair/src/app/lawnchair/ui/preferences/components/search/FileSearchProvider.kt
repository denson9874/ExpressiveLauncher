package app.lawnchair.ui.preferences.components.search

import android.Manifest
import android.app.Application
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lawnchair.preferences.PreferenceAdapter
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.components.PermissionDialog
import app.lawnchair.ui.preferences.components.controls.MainSwitchPreference
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.controls.TwoTargetSwitchPreference
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.ui.util.canRequestBroadVisualMediaAccess
import app.lawnchair.ui.util.canRequestManageAllFilesAccess
import app.lawnchair.util.FileAccessManager
import app.lawnchair.util.FileAccessState
import app.lawnchair.util.openAppPermissionSettings
import app.lawnchair.util.requestManageAllFilesAccessPermission
import com.android.launcher3.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

class FileSearchProviderViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val fileAccessManager: FileAccessManager = FileAccessManager.getInstance(application)

    val hasAnyPermissions = fileAccessManager.hasAnyPermission
    val visualMediaAccessState = fileAccessManager.visualMediaAccessState
    val audioAccessState = fileAccessManager.audioAccessState
    val allFilesAccessState = fileAccessManager.allFilesAccessState
    val selectedTreeUri = fileAccessManager.selectedTreeUri
    val selectedTreeLabel = fileAccessManager.selectedTreeLabel

    fun refreshAccessStates() = fileAccessManager.refresh()

    fun grantSelectedTreeAccess(uri: Uri) = fileAccessManager.grantSelectedTreeAccess(uri)
}

internal fun applyFolderGrantToSearchPreferences(
    grantPersisted: Boolean,
    enableSelectedFolderSearch: () -> Unit,
    enableMainFilesSearch: () -> Unit,
): Boolean {
    if (!grantPersisted) return false
    enableSelectedFolderSearch()
    enableMainFilesSearch()
    return true
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FileSearchProvider(
    modifier: Modifier = Modifier,
    viewModel: FileSearchProviderViewModel = viewModel(),
) {
    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()
    val appName = stringResource(id = R.string.derived_app_name)

    val mainAdapter = prefs.searchResultFilesToggle.getAdapter()
    val hasAnyPermissions by viewModel.hasAnyPermissions.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshAccessStates()
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccessStates()
        onPauseOrDispose {}
    }

    MainSwitchPreference(
        checked = mainAdapter.state.value,
        onCheckedChange = mainAdapter::onChange,
        label = stringResource(R.string.search_pref_result_files_title),
        enabled = hasAnyPermissions,
        modifier = modifier,
    )
    PreferenceGroup(
        heading = stringResource(R.string.search_pref_files_search_on),
    ) {
        val allFilesAccessState by viewModel.allFilesAccessState.collectAsStateWithLifecycle()
        val allFilesAccessAdapter = prefs.searchResultAllFiles.getAdapter()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (canRequestManageAllFilesAccess()) {
                ManageExternalStorageSetting(
                    accessState = allFilesAccessState,
                    adapter = allFilesAccessAdapter,
                    onPermissionRequest = viewModel::refreshAccessStates,
                )
            } else {
                val selectedTreeUri by viewModel.selectedTreeUri.collectAsStateWithLifecycle()
                val selectedTreeLabel by viewModel.selectedTreeLabel.collectAsStateWithLifecycle()
                SelectedFolderAccessSetting(
                    accessState = allFilesAccessState,
                    adapter = allFilesAccessAdapter,
                    mainFilesAdapter = mainAdapter,
                    selectedTreeUri = selectedTreeUri,
                    selectedTreeLabel = selectedTreeLabel,
                    onFolderSelect = viewModel::grantSelectedTreeAccess,
                    onPermissionRequest = viewModel::refreshAccessStates,
                )
            }
        } else {
            GenericAccessSetting(
                adapter = allFilesAccessAdapter,
                requiredPermission = Manifest.permission.READ_EXTERNAL_STORAGE,
                switchEnabled = { allFilesAccessState != FileAccessState.Denied },
                label = stringResource(R.string.search_pref_result_all_files_title),
                permissionTitle = stringResource(R.string.permissions_external_storage),
                permissionDescription = stringResource(R.string.permissions_external_storage_description, appName),
                onPermissionResult = { viewModel.refreshAccessStates() },
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val audioAccessState by viewModel.audioAccessState.collectAsStateWithLifecycle()

            if (canRequestBroadVisualMediaAccess()) {
                val visualMediaAccessState by viewModel.visualMediaAccessState.collectAsStateWithLifecycle()
                VisualMediaSetting(
                    accessState = visualMediaAccessState,
                    adapter = prefs.searchResultVisualMedia.getAdapter(),
                    onPermissionRequest = viewModel::refreshAccessStates,
                    alwaysEnabled = allFilesAccessAdapter.state.value && allFilesAccessState == FileAccessState.Full,
                )
            }
            GenericAccessSetting(
                adapter = prefs.searchResultAudio.getAdapter(),
                requiredPermission = android.Manifest.permission.READ_MEDIA_AUDIO,
                switchEnabled = { audioAccessState != FileAccessState.Denied },
                label = stringResource(R.string.search_pref_result_audio_media_title),
                permissionTitle = stringResource(R.string.permissions_music_audio),
                permissionDescription = stringResource(R.string.permissions_music_audio_description, appName),
                onPermissionResult = { viewModel.refreshAccessStates() },
                alwaysEnabled = allFilesAccessAdapter.state.value && allFilesAccessState == FileAccessState.Full,
            )
        }
    }

    ExpandAndShrink(hasAnyPermissions) {
        PreferenceGroup {
            SliderPreference(
                label = stringResource(id = R.string.max_file_result_count_title),
                adapter = prefs2.maxFileResultCount.getAdapter(),
                step = 1,
                valueRange = 3..10,
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
@Composable
private fun ManageExternalStorageSetting(
    accessState: FileAccessState,
    adapter: PreferenceAdapter<Boolean>,
    onPermissionRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPermissionDialog by remember { mutableStateOf(false) }

    if (accessState == FileAccessState.Full) {
        SwitchPreference(
            adapter = adapter,
            label = stringResource(R.string.search_pref_result_all_files_title),
            modifier = modifier,
        )
    } else {
        TwoTargetSwitchPreference(
            label = stringResource(R.string.search_pref_result_all_files_title),
            description = stringResource(R.string.permissions_needed),
            checked = false,
            onCheckedChange = {
                if (accessState == FileAccessState.Denied) {
                    showPermissionDialog = true
                } else {
                    adapter.onChange(it)
                }
            },
            onClick = {
                if (accessState == FileAccessState.Denied) {
                    showPermissionDialog = true
                }
            },
            switchEnabled = accessState != FileAccessState.Denied,
            modifier = modifier,
        )
    }

    if (showPermissionDialog) {
        FileAccessPermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onPermissionRequest = onPermissionRequest,
        )
    }
}

/**
 * Play-safe general file access. Android grants only the directory the user explicitly chooses,
 * and [FileAccessManager] keeps that grant across process restarts.
 */
@Composable
private fun SelectedFolderAccessSetting(
    accessState: FileAccessState,
    adapter: PreferenceAdapter<Boolean>,
    mainFilesAdapter: PreferenceAdapter<Boolean>,
    selectedTreeUri: Uri?,
    selectedTreeLabel: String?,
    onFolderSelect: (Uri) -> Boolean,
    onPermissionRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasFolderAccess = accessState == FileAccessState.Partial && selectedTreeUri != null
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (
                applyFolderGrantToSearchPreferences(
                    grantPersisted = onFolderSelect(uri),
                    enableSelectedFolderSearch = { adapter.onChange(true) },
                    enableMainFilesSearch = { mainFilesAdapter.onChange(true) },
                )
            ) {
                onPermissionRequest()
            } else {
                Toast.makeText(
                    context,
                    R.string.search_pref_result_selected_folder_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val openFolderPicker = { folderPicker.launch(selectedTreeUri) }

    TwoTargetSwitchPreference(
        label = stringResource(R.string.search_pref_result_selected_folder_title),
        description = if (hasFolderAccess) {
            stringResource(
                R.string.search_pref_result_selected_folder_active,
                selectedTreeLabel ?: stringResource(R.string.search_pref_result_selected_folder_title),
            )
        } else {
            stringResource(R.string.search_pref_result_selected_folder_description)
        },
        checked = hasFolderAccess && adapter.state.value,
        onCheckedChange = { checked ->
            if (hasFolderAccess) adapter.onChange(checked) else openFolderPicker()
        },
        onClick = openFolderPicker,
        switchEnabled = hasFolderAccess,
        modifier = modifier,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VisualMediaSetting(
    accessState: FileAccessState,
    adapter: PreferenceAdapter<Boolean>,
    onPermissionRequest: () -> Unit,
    alwaysEnabled: Boolean = false,
) {
    val context = LocalContext.current
    val permissionState = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ),
    ) {
        onPermissionRequest()
    }

    var showPermissionDialog by remember { mutableStateOf(false) }
    var showPartialAccessDialog by remember { mutableStateOf(false) }
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }

    if (alwaysEnabled || accessState == FileAccessState.Full) {
        SwitchPreference(
            checked = alwaysEnabled || adapter.state.value,
            onCheckedChange = adapter::onChange,
            label = stringResource(R.string.search_pref_result_visual_media_title),
            enabled = !alwaysEnabled,
        )
    } else {
        TwoTargetSwitchPreference(
            label = stringResource(R.string.search_pref_result_visual_media_title),
            description = stringResource(R.string.permissions_needed),
            checked = (adapter.state.value && accessState != FileAccessState.Denied),
            onCheckedChange = {
                if (accessState == FileAccessState.Denied) {
                    showPermissionDialog = true
                } else {
                    adapter.onChange(it)
                }
            },
            onClick = {
                if (accessState == FileAccessState.Denied) {
                    showPermissionDialog = true
                } else if (accessState == FileAccessState.Partial) {
                    showPartialAccessDialog = true
                }
            },
            switchEnabled = accessState != FileAccessState.Denied,
        )
    }

    if (showPermissionDialog) {
        PermissionDialog(
            title = stringResource(R.string.permissions_photos_videos),
            text = stringResource(R.string.permissions_photos_videos_description, stringResource(id = R.string.derived_app_name)),
            isPermanentlyDenied = hasRequestedPermission &&
                !permissionState.allPermissionsGranted &&
                !permissionState.shouldShowRationale,
            onConfirm = {
                hasRequestedPermission = true
                permissionState.launchMultiplePermissionRequest()
            },
            onDismiss = { showPermissionDialog = false },
            onGoToSettings = { context.openAppPermissionSettings() },
        )
    }

    if (showPartialAccessDialog) {
        AlertDialog(
            onDismissRequest = { showPartialAccessDialog = false },
            title = { Text(stringResource(R.string.permissions_photos_videos_full)) },
            text = { Text(stringResource(R.string.permissions_photos_videos_full_description, stringResource(id = R.string.derived_app_name))) },
            confirmButton = {
                Column {
                    Button(
                        onClick = {
                            context.openAppPermissionSettings()
                            showPartialAccessDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.permissions_photos_videos_grant_full)) }

                    TextButton(
                        onClick = {
                            permissionState.launchMultiplePermissionRequest()
                            showPartialAccessDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(R.string.permissions_photos_videos_manage_selected)) }

                    TextButton(
                        onClick = { showPartialAccessDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(android.R.string.cancel)) }
                }
            },
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun GenericAccessSetting(
    adapter: PreferenceAdapter<Boolean>,
    requiredPermission: String,
    switchEnabled: (PermissionState) -> Boolean,
    label: String,
    permissionTitle: String,
    permissionDescription: String,
    onPermissionResult: () -> Unit,
    alwaysEnabled: Boolean = false,
) {
    var showPermissionDialog by remember { mutableStateOf(false) }
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }
    val permission = rememberPermissionState(requiredPermission) {
        onPermissionResult()
    }

    val context = LocalContext.current

    if (!alwaysEnabled && !switchEnabled(permission)) {
        TwoTargetSwitchPreference(
            label = label,
            description = stringResource(R.string.permissions_needed),
            switchEnabled = false,
            checked = false,
            onCheckedChange = {},
            onClick = {
                showPermissionDialog = true
            },
        )
    } else {
        SwitchPreference(
            label = label,
            checked = alwaysEnabled || adapter.state.value,
            onCheckedChange = adapter::onChange,
            enabled = !alwaysEnabled,
        )
    }

    if (showPermissionDialog) {
        PermissionDialog(
            title = permissionTitle,
            text = permissionDescription,
            isPermanentlyDenied = hasRequestedPermission &&
                !permission.status.isGranted &&
                !permission.status.shouldShowRationale,
            onConfirm = {
                hasRequestedPermission = true
                permission.launchPermissionRequest()
            },
            onDismiss = { showPermissionDialog = false },
            onGoToSettings = { context.openAppPermissionSettings() },
        )
    }
}

/**
 * A dialog that requests file access permission.
 *
 * On Android R and above, this requests manage all files access for channels whose manifest
 * declares it. Play-distributed channels use [SelectedFolderAccessSetting] instead.
 *
 * @param onDismiss Called when the dialog is dismissed.
 * @param modifier The modifier to be applied to the dialog.
 * @param rationale The rationale to show to the user.
 * @param onPermissionRequest Called when the permission is requested.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FileAccessPermissionDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    rationale: String = stringResource(R.string.permissions_manage_storage_description, stringResource(id = R.string.derived_app_name)),
    onPermissionRequest: () -> Unit = {},
) {
    val context = LocalContext.current

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        PermissionDialog(
            title = stringResource(R.string.permissions_manage_storage),
            modifier = modifier,
            text = rationale,
            isPermanentlyDenied = true,
            onConfirm = { },
            onDismiss = onDismiss,
            onGoToSettings = {
                onPermissionRequest()
                context.requestManageAllFilesAccessPermission()
            },
        )
    } else {
        val permission = rememberPermissionState(Manifest.permission.READ_EXTERNAL_STORAGE)

        PermissionDialog(
            title = stringResource(R.string.permissions_external_storage),
            modifier = modifier,
            text = rationale,
            isPermanentlyDenied = permission.status.shouldShowRationale,
            onConfirm = {
                onPermissionRequest()
                permission.launchPermissionRequest()
            },
            onDismiss = onDismiss,
            onGoToSettings = { context.openAppPermissionSettings() },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TwoTargetSwitchPreferencePreview() {
    LawnchairTheme {
        Column {
            var checked1 by remember { mutableStateOf(false) }
            TwoTargetSwitchPreference(
                checked = checked1,
                onCheckedChange = { checked1 = it },
                label = "Search files",
                description = "Simple switch",
            )
            var checked2 by remember { mutableStateOf(true) }
            TwoTargetSwitchPreference(
                checked = checked2,
                onCheckedChange = { checked2 = it },
                label = "Search files with onClick",
                description = "Has a divider and separate click target",
                onClick = { /* Handle click */ },
            )
        }
    }
}
