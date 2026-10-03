package app.lawnchair.folder.widget.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.lawnchair.data.folderwidget.FolderWidgetStyleRepository
import app.lawnchair.folder.widget.FolderWidgetAppsAdapter
import app.lawnchair.folder.widget.FolderWidgetDefaults
import app.lawnchair.folder.widget.FolderWidgetPanel
import app.lawnchair.folder.widget.FolderWidgetStyle
import app.lawnchair.folder.widget.FolderWidgetStyleEditor
import app.lawnchair.folder.widget.FolderWidgetView
import app.lawnchair.folder.widget.resolve
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.pro.ProManager
import app.lawnchair.pro.proManager
import app.lawnchair.ui.preferences.components.colorpreference.pickers.CustomColorPicker
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.Chip
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.pro.ProBadge
import app.lawnchair.ui.preferences.pro.ProGate
import app.lawnchair.ui.preferences.pro.RedeemProDialog
import app.lawnchair.util.navigationBarsOrDisplayCutoutPadding
import app.lawnchair.util.resolveFolderBackgroundColor
import com.android.launcher3.Launcher
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FolderWidgetSettingsSheet(
    launcher: Launcher,
    widget: FolderWidgetView,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val proManager = proManager()
    val isPro by proManager.isPro.collectAsState()
    val editor = remember(isPro) { FolderWidgetStyleEditor(isPro) }
    val repository = remember { FolderWidgetStyleRepository.INSTANCE.get(context) }
    val currentStyle by repository.observe(widget.mInfo.id).collectAsState(initial = FolderWidgetStyle())

    val prefs2 = preferenceManager2()
    val defaultOpacity by prefs2.folderBackgroundOpacity.get().collectAsState(initial = 1f)
    val defaultBgColor = remember(context) { resolveFolderBackgroundColor(context) }
    val defaultRadius = remember(context) {
        runCatching {
            context.resources.getDimension(android.R.dimen.system_app_widget_background_radius)
        }.getOrDefault(0f)
    }
    val defaults = remember(defaultBgColor, defaultOpacity, defaultRadius) {
        FolderWidgetDefaults(
            backgroundColor = defaultBgColor,
            backgroundOpacity = defaultOpacity,
            cornerRadiusPx = defaultRadius,
        )
    }

    fun saveStyle(newStyle: FolderWidgetStyle) {
        coroutineScope.launch {
            repository.save(widget.mInfo.id, newStyle)
        }
    }

    var showColorDialog by remember { mutableStateOf(false) }

    val density = LocalDensity.current.density
    val widgetWidthPx = widget.width.takeIf { it > 0 } ?: (widget.mInfo.spanX * 80 * density).roundToInt()
    val widgetHeightPx = widget.height.takeIf { it > 0 } ?: (widget.mInfo.spanY * 80 * density).roundToInt()
    val shorterSidePx = min(widgetWidthPx, widgetHeightPx)
    val maxRadiusPx = (shorterSidePx / 2f).coerceAtLeast(1f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .navigationBarsOrDisplayCutoutPadding()
            .imePadding()
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.folder_widget_customize),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        saveStyle(editor.reset())
                    },
                ) {
                    Text(text = stringResource(R.string.folder_widget_reset))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onDismiss) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        }

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Live Preview Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val widgetWidthDp = (widgetWidthPx / density).dp
                val widgetHeightDp = (widgetHeightPx / density).dp

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val scale = minOf(
                        maxWidth / widgetWidthDp,
                        maxHeight / widgetHeightDp,
                        1f,
                    )
                    val scaledWidth = widgetWidthDp * scale
                    val scaledHeight = widgetHeightDp * scale

                    var previewPanel by remember { mutableStateOf<FolderWidgetPanel?>(null) }
                    val previewAdapter = remember(widget.mInfo) {
                        FolderWidgetAppsAdapter(
                            launcher,
                            widget.mInfo,
                            { previewPanel?.gridSpec ?: widget.panel.gridSpec },
                            { false },
                        )
                    }

                    Box(
                        modifier = Modifier.size(scaledWidth, scaledHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        AndroidView(
                            modifier = Modifier
                                .requiredSize(widgetWidthDp, widgetHeightDp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                                },
                            factory = { ctx ->
                                FolderWidgetPanel(ctx).also {
                                    it.layoutParams = ViewGroup.LayoutParams(widgetWidthPx, widgetHeightPx)
                                    previewPanel = it
                                }
                            },
                            update = { panel ->
                                previewPanel = panel
                                val resolved = currentStyle.resolve(isPro, defaults)
                                panel.bind(
                                    title = widget.mInfo.title ?: "",
                                    adapter = previewAdapter,
                                    style = resolved,
                                    metrics = widget.metrics(widget.currentSpanX()),
                                )
                                previewAdapter.notifyDataSetChanged()
                            },
                        )
                    }
                }
            }

            // Free settings: Layout
            PreferenceGroup(
                heading = stringResource(R.string.general_label),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                // Columns Chip Row
                PreferenceTemplate(
                    title = {
                        Text(text = stringResource(R.string.folder_widget_columns))
                    },
                    description = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Chip(
                                label = stringResource(R.string.folder_widget_columns_auto),
                                selected = currentStyle.columns == null,
                                onClick = {
                                    saveStyle(editor.withColumns(currentStyle, null))
                                },
                            )
                            for (cols in 2..6) {
                                Chip(
                                    label = cols.toString(),
                                    selected = currentStyle.columns == cols,
                                    onClick = {
                                        saveStyle(editor.withColumns(currentStyle, cols))
                                    },
                                )
                            }
                        }
                    },
                )

                // Show app names
                SwitchPreference(
                    checked = currentStyle.showLabels,
                    onCheckedChange = { checked ->
                        saveStyle(editor.withShowLabels(currentStyle, checked))
                    },
                    label = stringResource(R.string.folder_widget_show_names),
                )

                // Show header
                SwitchPreference(
                    checked = currentStyle.showHeader,
                    onCheckedChange = { checked ->
                        saveStyle(editor.withShowHeader(currentStyle, checked))
                    },
                    label = stringResource(R.string.folder_widget_show_header),
                )

                // Show name below
                SwitchPreference(
                    checked = currentStyle.showNameBelow,
                    onCheckedChange = { checked ->
                        saveStyle(editor.withShowNameBelow(currentStyle, checked))
                    },
                    label = stringResource(R.string.folder_widget_show_name_below),
                )
            }

            // Pro settings: Style
            PreferenceGroup(
                heading = stringResource(R.string.style),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                // Background color
                ProGate(
                    lockedContent = {
                        LockedPreferenceRow(title = stringResource(R.string.folder_widget_background_color))
                    },
                ) {
                    val currentColor = currentStyle.backgroundColor ?: defaultBgColor
                    PreferenceTemplate(
                        title = { Text(text = stringResource(R.string.folder_widget_background_color)) },
                        description = {
                            Text(
                                text = if (currentStyle.backgroundColor != null) {
                                    String.format("#%06X", 0xFFFFFF and currentStyle.backgroundColor!!)
                                } else {
                                    stringResource(R.string.folder_widget_default)
                                },
                            )
                        },
                        endWidget = {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(currentColor))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            )
                        },
                        onClick = { showColorDialog = true },
                    )
                }

                // Background opacity
                ProGate(
                    lockedContent = {
                        LockedPreferenceRow(title = stringResource(R.string.folder_widget_background_opacity))
                    },
                ) {
                    val opacity = currentStyle.backgroundOpacity ?: defaults.backgroundOpacity
                    StyleSliderRow(
                        label = stringResource(R.string.folder_widget_background_opacity),
                        valueText = stringResource(R.string.n_percent, (opacity * 100).roundToInt()),
                        value = opacity,
                        valueRange = 0f..1f,
                        onValueChange = { newOpacity ->
                            saveStyle(editor.withBackgroundOpacity(currentStyle, newOpacity))
                        },
                    )
                }

                // Corner radius
                ProGate(
                    lockedContent = {
                        LockedPreferenceRow(title = stringResource(R.string.folder_widget_corner_radius))
                    },
                ) {
                    val currentRadius = currentStyle.cornerRadiusPx ?: defaults.cornerRadiusPx
                    val radiusInDp = (currentRadius / density).roundToInt()
                    StyleSliderRow(
                        label = stringResource(R.string.folder_widget_corner_radius),
                        valueText = "$radiusInDp dp",
                        value = currentRadius.coerceIn(0f, maxRadiusPx),
                        valueRange = 0f..maxRadiusPx,
                        onValueChange = { newRadius ->
                            saveStyle(editor.withCornerRadius(currentStyle, newRadius))
                        },
                    )
                }

                // Icon size
                ProGate(
                    lockedContent = {
                        LockedPreferenceRow(title = stringResource(R.string.folder_widget_icon_size))
                    },
                ) {
                    val currentScale = currentStyle.iconScale ?: 1f
                    StyleSliderRow(
                        label = stringResource(R.string.folder_widget_icon_size),
                        valueText = stringResource(R.string.n_percent, (currentScale * 100).roundToInt()),
                        value = currentScale,
                        valueRange = FolderWidgetStyle.MIN_ICON_SCALE..FolderWidgetStyle.MAX_ICON_SCALE,
                        steps = 8,
                        onValueChange = { newScale ->
                            saveStyle(editor.withIconScale(currentStyle, newScale))
                        },
                    )
                }
            }
        }
    }

    // Background color selection dialog
    if (showColorDialog) {
        var pickerColor by remember {
            mutableIntStateOf(currentStyle.backgroundColor ?: defaultBgColor)
        }
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text(text = stringResource(R.string.folder_widget_background_color)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    CustomColorPicker(
                        selectedColor = pickerColor,
                        onSelect = { pickerColor = it },
                    )
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            saveStyle(editor.withBackgroundColor(currentStyle, null))
                            showColorDialog = false
                        },
                    ) {
                        Text(text = stringResource(R.string.folder_widget_default))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { showColorDialog = false }) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        saveStyle(editor.withBackgroundColor(currentStyle, pickerColor))
                        showColorDialog = false
                    },
                ) {
                    Text(text = stringResource(R.string.action_apply))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StyleSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    PreferenceTemplate(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = label)
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        description = {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LockedPreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
) {
    var showRedeemDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mMSDLPlayerWrapper = MSDLPlayerWrapper.INSTANCE.get(context)

    PreferenceTemplate(
        modifier = modifier.clickable {
            mMSDLPlayerWrapper.playToken(MSDLToken.TAP_LOW_EMPHASIS)
            showRedeemDialog = true
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                ProBadge()
            }
        },
        description = {
            Text(
                text = stringResource(R.string.expressive_pro_locked_customization),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        },
        endWidget = {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        },
    )

    if (showRedeemDialog) {
        RedeemProDialog(onDismiss = { showRedeemDialog = false })
    }
}
