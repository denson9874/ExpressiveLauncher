/*
 * Copyright 2022, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.about

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceDivider
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupHeading
import app.lawnchair.ui.preferences.components.layout.PreferenceGroupItem
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.ui.preferences.navigation.AboutLicenses
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun About(
    modifier: Modifier = Modifier,
    viewModel: AboutViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val applicationIcon = remember(context) {
        context.packageManager.getApplicationIcon(context.applicationInfo)
    }
    val applicationIconPainter = rememberDrawablePainter(applicationIcon)
    val dailyContent = rememberDailyAboutContent()

    // Keying this short interaction to the local day prevents a tap sequence from mixing two
    // different daily snark packs when the screen remains open across midnight.
    val easterEggTapCountSaver = remember(dailyContent.dayKey) {
        dailyScopedIntSaver(dailyContent.dayKey) {
            it in 0 until ABOUT_EASTER_EGG_TAP_COUNT
        }
    }
    val celebrationVisibilitySaver = remember(dailyContent.dayKey) {
        dailyScopedBooleanSaver(dailyContent.dayKey)
    }
    var easterEggTapCount by rememberSaveable(
        dailyContent.dayKey,
        stateSaver = easterEggTapCountSaver,
    ) { mutableIntStateOf(0) }
    var showThankYouCelebration by rememberSaveable(
        dailyContent.dayKey,
        stateSaver = celebrationVisibilitySaver,
    ) {
        mutableStateOf(false)
    }
    val onAboutHeroTap = {
        val tapResult = nextAboutEasterEggTap(
            currentTapCount = easterEggTapCount,
            dailyMessageResIds = dailyContent.snarkPack.messageResIds,
        )
        easterEggTapCount = tapResult.nextTapCount
        Toast.makeText(context, tapResult.messageResId, Toast.LENGTH_SHORT).show()
        if (tapResult.showCelebration) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            showThankYouCelebration = true
        }
    }

    val sheetState = rememberModalBottomSheetState(true)
    var openBottomSheet by remember { mutableStateOf(false) }
    var promptRequestedByNotification by remember {
        mutableStateOf(
            (context as? Activity)?.intent?.getBooleanExtra(
                EXTRA_OPEN_EXPRESSIVE_UPDATE_PROMPT,
                false,
            ) == true,
        )
    }
    val scope = rememberCoroutineScope()
    val expressiveUpdateConfig = remember { installedExpressiveUpdateConfig() }

    val prefs: PreferenceManager = PreferenceManager.getInstance(context)

    LaunchedEffect(promptRequestedByNotification, uiState.updateState) {
        if (promptRequestedByNotification && uiState.updateState is UpdateState.Available) {
            ExpressiveUpdateNotifications.cancel(context)
            (context as? Activity)?.intent?.removeExtra(EXTRA_OPEN_EXPRESSIVE_UPDATE_PROMPT)
            promptRequestedByNotification = false
            openBottomSheet = true
            sheetState.show()
        }
    }

    if (openBottomSheet) {
        val updateState = uiState.updateState
        if (updateState is UpdateState.Available) {
            ChangesDialog(
                changelogState = updateState.changelogState,
                releaseNotes = updateState.releaseNotes,
                updateName = updateState.name,
                onDismiss = {
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        openBottomSheet = false
                    }
                },
                onDownload = {
                    if (updateState.releaseNotes != null) {
                        viewModel.downloadAndInstallUpdate()
                    } else {
                        viewModel.downloadUpdate()
                    }
                },
                snoozeOptions = if (updateState.releaseNotes != null) {
                    ExpressiveUpdateSnoozeOption.entries
                } else {
                    emptyList()
                },
                onSnooze = { option ->
                    viewModel.snoozeUpdate(updateState, option)
                },
                sheetState = sheetState,
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PreferenceLayoutLazyColumn(
            label = stringResource(id = R.string.about_label),
            modifier = Modifier.fillMaxSize(),
            backArrowVisible = !LocalIsExpandedScreen.current,
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = stringResource(R.string.about_easter_egg_tap_label),
                            onClick = onAboutHeroTap,
                        )
                        .testTag(ABOUT_EASTER_EGG_TARGET_TAG)
                        .padding(top = 8.dp, bottom = 8.dp),
                ) {
                    Image(
                        // The Expressive flavor supplies a layer-list app icon. painterResource
                        // crashes on that XML type, while DrawablePainter correctly supports it.
                        painter = applicationIconPainter,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(id = R.string.derived_app_name),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (prefs.hideVersionInfo.get()) {
                            prefs.pseudonymVersion.get() + " (pseudonym)"
                        } else {
                            BuildConfig.VERSION_DISPLAY_NAME
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.combinedClickable(
                            onClick = onAboutHeroTap,
                            onLongClick = {
                                val destination = if (BuildConfig.IS_EXPRESSIVE_PRODUCT) {
                                    AboutDestinations.GITHUB_PROFILE_URL
                                } else {
                                    "https://github.com/LawnchairLauncher/lawnchair/commit/${BuildConfig.COMMIT_HASH}"
                                }
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        destination.toUri(),
                                    ),
                                )
                            },
                        ),
                    )
                }
            }
            item {
                DailySparkCard(
                    content = dailyContent,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
            item {
                if (expressiveUpdateConfig != null) {
                    ExpressiveUpdateNotificationControl(config = expressiveUpdateConfig)
                }
                UpdateSection(
                    updateState = uiState.updateState,
                    onInstall = {
                        viewModel.installUpdate(it)
                    },
                    onForceInstall = {
                        viewModel.installUpdate(it, forceInstall = true)
                    },
                    onViewChanges = {
                        openBottomSheet = true
                        scope.launch {
                            sheetState.show()
                        }
                    },
                    onDismissMajorUpdate = {
                        viewModel.resetToDownloaded(it)
                    },
                )
            }
            item {
                Spacer(modifier = Modifier.requiredHeight(16.dp))
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    uiState.topLinks.forEach { link ->
                        LawnchairLink(
                            iconResId = link.iconResId,
                            label = stringResource(id = link.labelResId),
                            modifier = Modifier.weight(weight = 1f),
                            url = link.url,
                        )
                    }
                }
            }
            preferenceGroupItems(
                items = uiState.coreTeam,
                isFirstChild = false,
                heading = { stringResource(id = R.string.product) },
                key = { _, it -> it.name },
            ) { _, it ->
                ContributorRow(
                    member = it,
                )
            }
            if (uiState.supportAndPr.isNotEmpty()) {
                preferenceGroupItems(
                    items = uiState.supportAndPr,
                    isFirstChild = false,
                    heading = { stringResource(id = R.string.support_and_pr) },
                    key = { _, it -> it.name },
                ) { _, it ->
                    ContributorRow(
                        member = it,
                    )
                }
            }
            if (uiState.bottomLinks.isNotEmpty()) {
                preferenceGroupItems(
                    items = uiState.bottomLinks,
                    isFirstChild = false,
                    heading = { stringResource(id = R.string.community) },
                    key = { _, it -> it.labelResId },
                ) { _, it ->
                    HorizontalLawnchairLink(
                        iconResId = it.iconResId,
                        label = stringResource(id = it.labelResId),
                        url = it.url,
                    )
                }
            }
            item {
                PreferenceGroupHeading(
                    stringResource(R.string.legal),
                )
            }
            item {
                PreferenceGroupItem(
                    cutTop = false,
                    cutBottom = true,
                ) {
                    NavigationActionPreference(
                        label = stringResource(id = R.string.acknowledgements),
                        destination = AboutLicenses,
                    )
                }
            }
            item {
                Spacer(Modifier.height(3.dp))
            }
            if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) {
                item {
                    PreferenceGroupItem(
                        cutTop = true,
                        cutBottom = false,
                    ) {
                        ClickablePreference(
                            label = stringResource(id = R.string.privacy_policy),
                            onClick = {
                                val webpage = BuildConfig.PRIVACY_POLICY_URL.toUri()
                                val intent = Intent(Intent.ACTION_VIEW, webpage)
                                if (intent.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(intent)
                                }
                            },
                        )
                    }
                }
            }
        }

        AboutThankYouCelebration(
            visible = showThankYouCelebration,
            onDismiss = { showThankYouCelebration = false },
        )
    }
}
