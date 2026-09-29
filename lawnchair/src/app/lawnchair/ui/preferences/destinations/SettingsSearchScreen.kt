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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceSearchScaffold
import app.lawnchair.ui.preferences.components.layout.LocalSettingsSearchTarget
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.navigation.PreferenceRoute
import com.android.launcher3.R

@Composable
fun SettingsSearchScreen(
    onNavigate: (PreferenceRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val allItems = remember(context) { SettingsSearchIndex.items(context) }
    val filteredItems = remember(query, allItems) { SettingsSearch.filter(allItems, query) }
    val isSearching = query.isNotBlank()
    val searchTarget = LocalSettingsSearchTarget.current
    val open = { item: SearchablePreferenceItem ->
        // The screen we are about to open scrolls to the row with this title and pulses it.
        searchTarget?.request(item.title)
        onNavigate(item.destination)
    }

    PreferenceSearchScaffold(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(id = R.string.settings_search_placeholder)) },
        modifier = modifier,
    ) { paddingValues ->
        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(id = R.string.settings_search_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isSearching) {
                    // Ranked best match first, so results are not split under page headings.
                    item(key = "results") {
                        PreferenceGroup {
                            filteredItems.forEach { item -> SearchResultRow(item, open) }
                        }
                    }
                } else {
                    filteredItems.groupBy { it.category }.forEach { (category, items) ->
                        item(key = category) {
                            PreferenceGroup(heading = category) {
                                items.forEach { item -> SearchResultRow(item, open) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    item: SearchablePreferenceItem,
    onOpen: (SearchablePreferenceItem) -> Unit,
) {
    PreferenceTemplate(
        title = { Text(text = item.title) },
        description = item.description?.let { { Text(text = it) } },
        endWidget = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = item.category,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
        onClick = { onOpen(item) },
    )
}
