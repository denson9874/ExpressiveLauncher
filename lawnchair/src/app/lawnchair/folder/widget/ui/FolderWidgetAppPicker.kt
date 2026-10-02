package app.lawnchair.folder.widget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.util.appsState
import app.lawnchair.util.navigationBarsOrDisplayCutoutPadding
import com.android.launcher3.R
import com.android.launcher3.util.ComponentKey

@Composable
fun FolderWidgetAppPicker(
    initialTitle: String,
    initialSelectedKeys: List<ComponentKey>,
    onSave: (title: String, selectedKeys: List<ComponentKey>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedKeys = remember {
        mutableStateListOf<ComponentKey>().apply { addAll(initialSelectedKeys) }
    }
    val apps by appsState()

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .navigationBarsOrDisplayCutoutPadding()
            .imePadding()
            .padding(top = 16.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.folder_widget_label),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(android.R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onSave(title, selectedKeys.toList()) },
                ) {
                    Text(text = stringResource(R.string.folder_widget_save))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            label = { Text(text = stringResource(R.string.folder_hint_text)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text(text = stringResource(R.string.all_apps_search_market_message)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Rounded.Clear,
                            contentDescription = null,
                        )
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                items(filteredApps, key = { it.key.toString() }) { app ->
                    val isChecked = selectedKeys.contains(app.key)
                    AppItem(
                        app = app,
                        onClick = {
                            if (isChecked) {
                                selectedKeys.remove(app.key)
                            } else {
                                selectedKeys.add(app.key)
                            }
                        },
                        endWidget = {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = null,
                            )
                        },
                    )
                }
            }
        }
    }
}
