/*
 * Copyright 2026, Expressive Launcher
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

package app.lawnchair.ui.preferences.destinations

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.android.launcher3.R

@Composable
fun WidgetPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    PreferenceLayout(
        label = stringResource(id = R.string.widget_settings_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        val roundedWidgetsAdapter = prefs2.roundedWidgets.getAdapter()
        PreferenceGroup(heading = stringResource(id = R.string.style)) {
            SwitchPreference(
                adapter = roundedWidgetsAdapter,
                label = stringResource(id = R.string.force_rounded_widgets),
            )
            ExpandAndShrink(visible = roundedWidgetsAdapter.state.value) {
                SliderPreference(
                    label = stringResource(id = R.string.widget_corner_radius_label),
                    adapter = prefs2.widgetCornerRadius.getAdapter(),
                    step = 2,
                    valueRange = 0..40,
                    showUnit = "dp",
                )
            }
        }

        PreferenceGroup(heading = stringResource(id = R.string.layout)) {
            SliderPreference(
                label = stringResource(id = R.string.widget_padding_label),
                adapter = prefs2.widgetPaddingFactor.getAdapter(),
                step = 0.05f,
                valueRange = 0F..2F,
                showAsPercentage = true,
            )
            SwitchPreference(
                adapter = prefs2.allowWidgetOverlap.getAdapter(),
                label = stringResource(id = R.string.allow_widget_overlap),
            )
            SwitchPreference(
                adapter = prefs2.widgetUnlimitedSize.getAdapter(),
                label = stringResource(id = R.string.widget_unlimited_size_label),
                description = stringResource(id = R.string.widget_unlimited_size_description),
            )
            SwitchPreference(
                adapter = prefs2.forceWidgetResize.getAdapter(),
                label = stringResource(id = R.string.force_widget_resize_label),
                description = stringResource(id = R.string.force_widget_resize_description),
            )
        }
    }
}
