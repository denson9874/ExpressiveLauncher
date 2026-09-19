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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceSearchScaffold
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.navigation.About
import app.lawnchair.ui.preferences.navigation.AppDrawer
import app.lawnchair.ui.preferences.navigation.AppDrawerFolder
import app.lawnchair.ui.preferences.navigation.AppDrawerHiddenApps
import app.lawnchair.ui.preferences.navigation.BackupAndRestore
import app.lawnchair.ui.preferences.navigation.CreateBackup
import app.lawnchair.ui.preferences.navigation.Dock
import app.lawnchair.ui.preferences.navigation.Folders
import app.lawnchair.ui.preferences.navigation.General
import app.lawnchair.ui.preferences.navigation.GeneralIconPack
import app.lawnchair.ui.preferences.navigation.GeneralIconShape
import app.lawnchair.ui.preferences.navigation.Gestures
import app.lawnchair.ui.preferences.navigation.HomeScreen
import app.lawnchair.ui.preferences.navigation.HomeScreenGrid
import app.lawnchair.ui.preferences.navigation.PreferenceRoute
import app.lawnchair.ui.preferences.navigation.Quickstep
import app.lawnchair.ui.preferences.navigation.Search
import app.lawnchair.ui.preferences.navigation.Smartspace
import app.lawnchair.ui.preferences.navigation.WidgetPreferencesRoute
import com.android.launcher3.R

data class SearchablePreferenceItem(
    val title: String,
    val description: String? = null,
    val category: String,
    val destination: PreferenceRoute,
    val keywords: List<String> = emptyList(),
)

@Composable
fun SettingsSearchScreen(
    onNavigate: (PreferenceRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val allItems = rememberSearchIndex()

    val filteredItems by remember(query, allItems) {
        derivedStateOf {
            val trimmed = query.trim().lowercase()
            if (trimmed.isEmpty()) {
                allItems
            } else {
                val tokens = trimmed.split(" ").filter { it.isNotEmpty() }
                allItems.filter { item ->
                    tokens.all { token ->
                        item.title.lowercase().contains(token) ||
                            item.description?.lowercase()?.contains(token) == true ||
                            item.category.lowercase().contains(token) ||
                            item.keywords.any { it.lowercase().contains(token) }
                    }
                }
            }
        }
    }

    PreferenceSearchScaffold(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(id = R.string.search_bar_label)) },
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
                    text = stringResource(id = R.string.no_search_results),
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
                val grouped = filteredItems.groupBy { it.category }
                grouped.forEach { (category, items) ->
                    item(key = category) {
                        PreferenceGroup(heading = category) {
                            items.forEach { item ->
                                PreferenceTemplate(
                                    title = { Text(text = item.title) },
                                    description = item.description?.let { { Text(text = it) } },
                                    endWidget = {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                        ) {
                                            Text(
                                                text = category,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            )
                                        }
                                    },
                                    onClick = { onNavigate(item.destination) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSearchIndex(): List<SearchablePreferenceItem> {
    val generalLabel = stringResource(id = R.string.general_label)
    val homeScreenLabel = stringResource(id = R.string.home_screen_label)
    val widgetsLabel = stringResource(id = R.string.widget_settings_label)
    val dockLabel = stringResource(id = R.string.dock_label)
    val appDrawerLabel = stringResource(id = R.string.app_drawer_label)
    val searchLabel = stringResource(id = R.string.search_bar_label)
    val foldersLabel = stringResource(id = R.string.folders_label)
    val gesturesLabel = stringResource(id = R.string.gestures_label)
    val smartspaceLabel = stringResource(id = R.string.smartspace_widget)
    val quickstepLabel = stringResource(id = R.string.quickstep_label)
    val backupRestoreLabel = stringResource(id = R.string.backup_and_restore_label)
    val aboutLabel = stringResource(id = R.string.about_label)

    return remember(
        generalLabel,
        homeScreenLabel,
        widgetsLabel,
        dockLabel,
        appDrawerLabel,
        searchLabel,
        foldersLabel,
        gesturesLabel,
        smartspaceLabel,
        quickstepLabel,
        backupRestoreLabel,
        aboutLabel,
    ) {
        listOf(
            // General
            SearchablePreferenceItem("Dark theme", "System theme, dark theme, light theme", generalLabel, General, listOf("theme", "mode", "night", "dark")),
            SearchablePreferenceItem("Accent color", "Customize launcher accent color palette", generalLabel, General, listOf("color", "palette", "monet", "tint")),
            SearchablePreferenceItem("Font style", "Change launcher typography and fonts", generalLabel, General, listOf("font", "text", "typeface")),
            SearchablePreferenceItem("Icon pack", "Choose custom icon pack for installed apps", generalLabel, GeneralIconPack, listOf("icons", "pack", "theme")),
            SearchablePreferenceItem("Icon shape", "Change adaptive icon shape mask", generalLabel, GeneralIconShape(), listOf("shape", "circle", "squircle", "rounded")),
            SearchablePreferenceItem("Notification dots", "Show notification badges on app icons", generalLabel, General, listOf("dots", "badge", "notifications")),
            SearchablePreferenceItem("Add app icons to Home screen", "Automatically add shortcuts for newly installed apps", generalLabel, HomeScreen, listOf("auto add", "new apps", "shortcuts")),

            // Home Screen
            SearchablePreferenceItem("Home screen grid", "Customize rows and columns on home screen", homeScreenLabel, HomeScreenGrid, listOf("grid", "rows", "columns", "density")),
            SearchablePreferenceItem("Home icon size", "Change icon size on home screen", homeScreenLabel, HomeScreen, listOf("size", "scale", "dimensions")),
            SearchablePreferenceItem("Show app labels", "Toggle icon text labels on home screen", homeScreenLabel, HomeScreen, listOf("labels", "names", "text")),
            SearchablePreferenceItem("Home label size", "Customize text label size on home screen", homeScreenLabel, HomeScreen, listOf("font size", "text size", "scale")),
            SearchablePreferenceItem("Double tap to sleep", "Double tap empty space to turn screen off", homeScreenLabel, HomeScreen, listOf("sleep", "lock", "dt2s", "double tap")),
            SearchablePreferenceItem("Lock home screen", "Prevent changes to the home screen layout", homeScreenLabel, HomeScreen, listOf("lock", "frozen", "prevent moves")),
            SearchablePreferenceItem("Wallpaper scrolling", "Scroll wallpaper when swiping between pages", homeScreenLabel, HomeScreen, listOf("wallpaper", "scroll", "parallax")),
            SearchablePreferenceItem("Wallpaper depth effect", "Cinematic wallpaper depth parallax effect", homeScreenLabel, HomeScreen, listOf("depth", "cinematic", "3d")),
            SearchablePreferenceItem("Show system UI scrim", "Darken top status bar scrim", homeScreenLabel, HomeScreen, listOf("scrim", "shadow", "status bar")),
            SearchablePreferenceItem("Infinite scrolling", "Loop continuously through home screens", homeScreenLabel, HomeScreen, listOf("infinite", "loop", "continuous")),
            SearchablePreferenceItem("Minus-one feed", "Show Google Feed to the left of home screen", homeScreenLabel, HomeScreen, listOf("feed", "discover", "minus one")),

            // Widgets
            SearchablePreferenceItem("Widget settings", "Configure widget padding, corner radius, and layout", widgetsLabel, WidgetPreferencesRoute, listOf("widgets", "resizing")),
            SearchablePreferenceItem("Widget corner radius", "Adjust how rounded widget corners are", widgetsLabel, WidgetPreferencesRoute, listOf("radius", "rounded", "curves", "corners")),
            SearchablePreferenceItem("Widget padding", "Adjust outer margin and padding around widgets", widgetsLabel, WidgetPreferencesRoute, listOf("padding", "margins", "spacing", "space between")),
            SearchablePreferenceItem("Force rounded widgets", "Enforce rounded corners on all widgets", widgetsLabel, WidgetPreferencesRoute, listOf("round", "corners", "curves")),
            SearchablePreferenceItem("Allow widget overlap", "Allow widgets to overlap other views on home screen", widgetsLabel, WidgetPreferencesRoute, listOf("overlap", "stacking")),
            SearchablePreferenceItem("Unlimited widget size", "Allow widgets to be resized beyond grid limits", widgetsLabel, WidgetPreferencesRoute, listOf("unlimited", "resize", "bounds")),
            SearchablePreferenceItem("Force widget resize", "Enable resizing for all installed widgets", widgetsLabel, WidgetPreferencesRoute, listOf("force", "resize", "freedom")),

            // Dock
            SearchablePreferenceItem("Dock settings", "Configure the hotseat dock at bottom of screen", dockLabel, Dock, listOf("dock", "hotseat")),
            SearchablePreferenceItem("Dock search bar", "Show search bar in the dock", dockLabel, Dock, listOf("search bar", "qsb", "dock search")),
            SearchablePreferenceItem("Dock background opacity", "Customize dock background transparency", dockLabel, Dock, listOf("opacity", "transparent", "background")),
            SearchablePreferenceItem("Dock icon count", "Number of app icons in the dock", dockLabel, Dock, listOf("columns", "icons", "hotseat count")),

            // App Drawer
            SearchablePreferenceItem("App drawer grid", "Customize columns and rows in app drawer", appDrawerLabel, AppDrawer, listOf("drawer grid", "columns", "app list")),
            SearchablePreferenceItem("Drawer icon size", "Customize app icon size in drawer", appDrawerLabel, AppDrawer, listOf("size", "scale", "icons")),
            SearchablePreferenceItem("Drawer label size", "Customize label text size in drawer", appDrawerLabel, AppDrawer, listOf("font size", "text size", "labels")),
            SearchablePreferenceItem("Hidden apps", "Hide selected apps from the app drawer", appDrawerLabel, AppDrawerHiddenApps, listOf("hide", "privacy", "secret")),
            SearchablePreferenceItem("Search in drawer", "Search apps, contacts, and web in app drawer", appDrawerLabel, Search(), listOf("search", "drawer search")),
            SearchablePreferenceItem("App drawer background opacity", "Adjust transparency of app drawer background", appDrawerLabel, AppDrawer, listOf("opacity", "transparent")),
            SearchablePreferenceItem("App drawer folders", "Organize apps into tabs or folders in drawer", appDrawerLabel, AppDrawerFolder, listOf("folders", "categories", "tabs")),

            // Search
            SearchablePreferenceItem("Search provider", "Google, DuckDuckGo, Bing, or custom provider", searchLabel, Search(), listOf("engine", "google", "duckduckgo", "bing")),
            SearchablePreferenceItem("Web search suggestions", "Show search engine suggestions while typing", searchLabel, Search(), listOf("suggestions", "auto complete", "web")),
            SearchablePreferenceItem("Contact search", "Search device contacts from search bar", searchLabel, Search(), listOf("contacts", "people", "address book")),
            SearchablePreferenceItem("Files search", "Search local documents and files", searchLabel, Search(), listOf("files", "documents", "downloads")),

            // Folders
            SearchablePreferenceItem("Folder preview shape", "Customize folder icon preview shape", foldersLabel, Folders, listOf("folder shape", "preview")),
            SearchablePreferenceItem("Folder columns", "Number of columns inside open folders", foldersLabel, Folders, listOf("columns", "grid", "folder width")),
            SearchablePreferenceItem("Folder rows", "Number of rows inside open folders", foldersLabel, Folders, listOf("rows", "grid", "folder height")),
            SearchablePreferenceItem("Folder background color", "Customize folder window background", foldersLabel, Folders, listOf("color", "tint", "background")),

            // Gestures
            SearchablePreferenceItem("Gestures", "Swipe down for notifications, swipe up, double tap", gesturesLabel, Gestures, listOf("gestures", "actions")),
            SearchablePreferenceItem("Swipe down gesture", "Action for swiping down on home screen", gesturesLabel, Gestures, listOf("swipe down", "notifications", "shade")),
            SearchablePreferenceItem("Swipe up gesture", "Action for swiping up on home screen", gesturesLabel, Gestures, listOf("swipe up", "app drawer", "overview")),
            SearchablePreferenceItem("Pinch to overview", "Pinch gesture action on home screen", gesturesLabel, Gestures, listOf("pinch", "zoom", "overview")),

            // Smartspace
            SearchablePreferenceItem("At a Glance / Smartspace", "Calendar events, weather, battery, and date", smartspaceLabel, Smartspace, listOf("at a glance", "smartspace", "widget")),
            SearchablePreferenceItem("Weather in Smartspace", "Show current temperature and forecast", smartspaceLabel, Smartspace, listOf("weather", "temperature", "forecast")),
            SearchablePreferenceItem("Smartspace date display", "Show date and time on home screen", smartspaceLabel, Smartspace, listOf("date", "time", "clock")),

            // Quickstep
            SearchablePreferenceItem("Quickstep / Recents", "Overview gestures, screenshot, clear all, lens", quickstepLabel, Quickstep, listOf("quickstep", "recents", "overview", "quickswitch")),

            // Backup & Restore
            SearchablePreferenceItem("Create backup", "Export Expressive Launcher settings and layout", backupRestoreLabel, CreateBackup, listOf("export", "save", "backup")),
            SearchablePreferenceItem("Restore backup", "Import Expressive Launcher backup file", backupRestoreLabel, BackupAndRestore, listOf("import", "load", "restore")),
            SearchablePreferenceItem("Import Nova backup", "Import layout and shortcuts from Nova Launcher backup", backupRestoreLabel, BackupAndRestore, listOf("nova", "import", "convert")),

            // About
            SearchablePreferenceItem("About Expressive Launcher", "Version, build info, changelog, and licenses", aboutLabel, About, listOf("version", "build", "about")),
            SearchablePreferenceItem("Release notes", "View what's new in this update", aboutLabel, About, listOf("changelog", "what's new", "update")),
            SearchablePreferenceItem("Update channel", "Check for updates and release notifications", aboutLabel, About, listOf("updates", "github", "release channel")),
        )
    }
}
