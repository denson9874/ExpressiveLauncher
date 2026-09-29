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

import android.content.Context
import androidx.annotation.StringRes
import app.lawnchair.ui.preferences.components.search.SearchProviderId
import app.lawnchair.ui.preferences.navigation.About
import app.lawnchair.ui.preferences.navigation.AppDrawer
import app.lawnchair.ui.preferences.navigation.AppDrawerFolder
import app.lawnchair.ui.preferences.navigation.AppDrawerHiddenApps
import app.lawnchair.ui.preferences.navigation.BackupAndRestore
import app.lawnchair.ui.preferences.navigation.CreateBackup
import app.lawnchair.ui.preferences.navigation.DismissedPredictionApps
import app.lawnchair.ui.preferences.navigation.Dock
import app.lawnchair.ui.preferences.navigation.DockSearchProvider
import app.lawnchair.ui.preferences.navigation.Folders
import app.lawnchair.ui.preferences.navigation.General
import app.lawnchair.ui.preferences.navigation.GeneralIconPack
import app.lawnchair.ui.preferences.navigation.GeneralIconShape
import app.lawnchair.ui.preferences.navigation.Gestures
import app.lawnchair.ui.preferences.navigation.HomeScreen
import app.lawnchair.ui.preferences.navigation.HomeScreenGrid
import app.lawnchair.ui.preferences.navigation.HomeScreenPopupEditor
import app.lawnchair.ui.preferences.navigation.Predictions
import app.lawnchair.ui.preferences.navigation.PreferenceRoute
import app.lawnchair.ui.preferences.navigation.Search
import app.lawnchair.ui.preferences.navigation.SearchProviderPreference
import app.lawnchair.ui.preferences.navigation.Smartspace
import app.lawnchair.ui.preferences.navigation.WidgetPreferencesRoute
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import java.text.Normalizer
import java.util.Locale

/** One row of the settings search results. */
data class SearchablePreferenceItem(
    val title: String,
    val description: String? = null,
    val category: String,
    val destination: PreferenceRoute,
    val keywords: List<String> = emptyList(),
) {
    internal val searchable: SearchableText by lazy(LazyThreadSafetyMode.NONE) {
        SearchableText(
            title = SettingsSearch.normalize(title),
            description = SettingsSearch.normalize(description.orEmpty()),
            category = SettingsSearch.normalize(category),
            keywords = SettingsSearch.normalize(keywords.joinToString(" ")),
        )
    }
}

internal class SearchableText(
    val title: String,
    val description: String,
    val category: String,
    val keywords: String,
)

/** Which product's settings a search covers: a few rows exist only in some builds. */
data class SettingsSearchProduct(
    val standardHomeOnly: Boolean,
    val expressive: Boolean,
) {
    companion object {
        val Expressive = SettingsSearchProduct(standardHomeOnly = true, expressive = true)
        val Lawnchair = SettingsSearchProduct(standardHomeOnly = false, expressive = false)

        fun current() = SettingsSearchProduct(
            standardHomeOnly = BuildConfig.STANDARD_HOME_ONLY,
            expressive = BuildConfig.IS_EXPRESSIVE_PRODUCT,
        )
    }
}

/** The settings screens people can land on, with the label the dashboard shows for each. */
enum class SettingsSearchPage(@StringRes val label: Int, val route: PreferenceRoute) {
    ICONS_AND_APPEARANCE(R.string.general_label, General),
    HOME_SCREEN(R.string.home_screen_label, HomeScreen),
    WIDGETS(R.string.widget_settings_label, WidgetPreferencesRoute),
    AT_A_GLANCE(R.string.smartspace_widget, Smartspace),
    APP_DRAWER(R.string.app_drawer_label, AppDrawer),
    SUGGESTIONS(R.string.suggestion_pref_screen_title, Predictions),
    SEARCH(R.string.search_bar_label, Search()),
    DOCK(R.string.dock_label, Dock),
    FOLDERS(R.string.folders_label, Folders),
    GESTURES(R.string.gestures_label, Gestures),
    BACKUP_AND_RESTORE(R.string.backup_and_restore_label, BackupAndRestore),
    ABOUT(R.string.about_label, About),
}

/** Where an entry exists; rows that a product never draws must not be searchable there. */
enum class SettingsSearchAvailability(private val appliesTo: (SettingsSearchProduct) -> Boolean) {
    EVERYWHERE({ true }),
    NOT_STANDARD_HOME({ !it.standardHomeOnly }),
    EXPRESSIVE_ONLY({ it.expressive }),
    UPSTREAM_ONLY({ !it.expressive }),
    ;

    fun isAvailableIn(product: SettingsSearchProduct) = appliesTo(product)
}

/**
 * A searchable setting. [title] and [description] are the same string resources the settings page
 * uses, so the search text cannot drift from the screen and follows the user's language.
 * [keywords] are extra English aliases that are matched but never shown.
 */
data class SettingsSearchEntry(
    @StringRes val title: Int,
    val page: SettingsSearchPage,
    @StringRes val description: Int? = null,
    val destination: PreferenceRoute = page.route,
    val keywords: List<String> = emptyList(),
    val availability: SettingsSearchAvailability = SettingsSearchAvailability.EVERYWHERE,
)

object SettingsSearchIndex {

    fun entries(product: SettingsSearchProduct): List<SettingsSearchEntry> =
        allEntries.filter { it.availability.isAvailableIn(product) }

    fun items(
        context: Context,
        product: SettingsSearchProduct = SettingsSearchProduct.current(),
    ): List<SearchablePreferenceItem> = entries(product).map { entry ->
        SearchablePreferenceItem(
            title = context.getString(entry.title),
            description = entry.description?.let(context::getString),
            category = context.getString(entry.page.label),
            destination = entry.destination,
            keywords = entry.keywords,
        )
    }

    private val allEntries: List<SettingsSearchEntry> = buildList {
        page(SettingsSearchPage.ICONS_AND_APPEARANCE) {
            entry(R.string.home_screen_rotation_label, R.string.home_screen_rotation_description, keywords = "rotate, auto-rotate, landscape, orientation")
            entry(R.string.live_information_label, R.string.live_information_description, availability = SettingsSearchAvailability.NOT_STANDARD_HOME, keywords = "announcements, feature flags, remote")
            entry(R.string.font_label, keywords = "typeface, text, fonts")
            entry(R.string.icon_style_label, destination = GeneralIconPack, keywords = "icon pack, icons, themed")
            entry(R.string.transparent_background_icons_label, R.string.transparent_background_icons_description)
            entry(R.string.icon_shape_label, destination = GeneralIconShape(ShapeRoute.APP_SHAPE), keywords = "squircle, circle, rounded, mask, app icons")
            entry(R.string.auto_adaptive_icons_label, R.string.auto_adaptive_icons_description, keywords = "adaptive icons, wrap")
            entry(R.string.shadow_bg_icons_label, keywords = "drop shadow")
            entry(R.string.background_lightness_label, keywords = "icon background, brightness")
            entry(R.string.theme_label, keywords = "dark mode, light mode, night, system theme, dark theme")
            entry(R.string.accent_color, keywords = "palette, monet, tint, wallpaper colors")
            entry(R.string.color_style_label, keywords = "monet, palette, tonal, vibrant")
            entry(R.string.notification_dots, keywords = "badge, badges, notifications, app badges")
            entry(R.string.show_notification_count, keywords = "badge count, dot number")
            entry(R.string.themed_icon_title, destination = GeneralIconPack, keywords = "monochrome, material you, home screen icons")
            entry(R.string.force_monochrome_label, R.string.force_monochrome_description, destination = GeneralIconPack, keywords = "themed icons, recolor")
        }

        page(SettingsSearchPage.HOME_SCREEN) {
            entry(R.string.auto_add_shortcuts_label, keywords = "new apps, install, auto add, shortcuts")
            entry(R.string.infinite_scrolling_label, R.string.infinite_scrolling_description, keywords = "loop, wrap, endless, continuous")
            entry(R.string.return_to_default_page, R.string.return_to_default_page_description, keywords = "first page, default page, home button, exit app, stay on page")
            entry(R.string.show_suggested_apps_in_dock, R.string.show_suggested_apps_in_dock_description, keywords = "hotseat, predictions, suggested apps, empty spots, empty slots")
            entry(R.string.add_app_drawer_icon, R.string.add_app_drawer_icon_description, keywords = "all apps button, drawer shortcut")
            entry(R.string.google_search_bar_label, R.string.google_search_bar_description, availability = SettingsSearchAvailability.EXPRESSIVE_ONLY, keywords = "qsb, ai mode, lens, widget, dock search, pixel search")
            entry(R.string.remove_all_views_from_home_screen, keywords = "reset, empty, wipe, remove all, layout")
            entry(R.string.expressive_feed_show, availability = SettingsSearchAvailability.EXPRESSIVE_ONLY, keywords = "feed, minus one, swipe right, swipe left, google feed, setup, replace discover support")
            entry(R.string.expressive_feed_show_selected, availability = SettingsSearchAvailability.EXPRESSIVE_ONLY, keywords = "feed, other feeds, swipe right, swipe left")
            entry(R.string.minus_one_enable, availability = SettingsSearchAvailability.UPSTREAM_ONLY, keywords = "feed, discover, google feed")
            entry(R.string.home_screen_text_color, keywords = "label color, text colour")
            entry(R.string.app_closing_animation, keywords = "overlay, closing app, transition")
            entry(R.string.wallpaper_scrolling_label, keywords = "parallax, wallpaper scrolling, swipe")
            entry(R.string.wallpaper_depth_effect_label, R.string.wallpaper_depth_effect_description, keywords = "depth, zoom, parallax")
            entry(R.string.show_sys_ui_scrim, keywords = "scrim, status bar shadow, gradient")
            entry(R.string.home_screen_grid, destination = HomeScreenGrid, keywords = "columns, rows, size, layout, dock icons, grid size")
            entry(R.string.horizontal_padding_label, keywords = "margins, spacing")
            entry(R.string.vertical_padding_label, keywords = "margins, spacing")
            entry(R.string.home_screen_lock, R.string.home_screen_lock_description, keywords = "lock layout, freeze, prevent moves")
            entry(R.string.edit_menu_items, destination = HomeScreenPopupEditor, keywords = "popup, long press menu, shortcuts, customize menu")
            entry(R.string.show_status_bar, keywords = "hide status bar, fullscreen, immersive")
            entry(R.string.solid_status_bar_background_label, R.string.solid_status_bar_background_description, keywords = "black bar, status bar background, opaque")
            entry(R.string.dark_status_bar_label, R.string.dark_status_bar_description, keywords = "light status bar, icon contrast, clock color")
            entry(R.string.status_bar_clock_label, R.string.status_bar_clock_description, availability = SettingsSearchAvailability.NOT_STANDARD_HOME, keywords = "hide clock")
            entry(R.string.icon_sizes, keywords = "scale, size of icons, home icons")
            entry(R.string.show_labels, keywords = "names, icon text, home labels")
            entry(R.string.label_size, keywords = "text size, font size, home labels")
        }

        page(SettingsSearchPage.WIDGETS) {
            entry(R.string.force_rounded_widgets, keywords = "round, corners, curves")
            entry(R.string.widget_corner_radius_label, keywords = "radius, rounded, curves")
            entry(R.string.widget_padding_label, keywords = "margins, spacing, space between")
            entry(R.string.allow_widget_overlap, keywords = "stacking, overlap")
            entry(R.string.widget_unlimited_size_label, R.string.widget_unlimited_size_description, keywords = "unlimited, resize, bounds")
            entry(R.string.force_widget_resize_label, R.string.force_widget_resize_description, keywords = "resize, freedom")
        }

        page(SettingsSearchPage.AT_A_GLANCE) {
            entry(R.string.smartspace_widget_toggle_label, keywords = "smartspace, widget, date, weather")
            entry(R.string.smartspace_mode_label, keywords = "smartspacer, source")
            entry(R.string.smartspace_weather, keywords = "temperature, forecast")
            entry(R.string.smartspace_battery_status, keywords = "charging, power")
            entry(R.string.smartspace_torch, keywords = "torch, flashlight")
            entry(R.string.smartspace_now_playing, keywords = "music, song, media")
            entry(R.string.smartspace_onboarding, keywords = "setup, tips")
            entry(R.string.smartspace_date, keywords = "day, calendar")
            entry(R.string.smartspace_time, keywords = "clock")
            entry(R.string.smartspace_time_format, keywords = "12-hour, 24-hour, clock")
            entry(R.string.smartspace_calendar, keywords = "date format, calendar type")
            entry(R.string.maximum_number_of_targets, keywords = "cards, limit")
            entry(R.string.open_smartspacer_settings, keywords = "smartspacer")
        }

        page(SettingsSearchPage.APP_DRAWER) {
            entry(R.string.hidden_apps_label, destination = AppDrawerHiddenApps, keywords = "hide apps, privacy, secret")
            entry(R.string.show_suggested_apps_in_drawer, R.string.show_suggested_apps_in_drawer_description, keywords = "predictions, suggested apps, recent apps")
            entry(R.string.suggestion_pref_screen_title, destination = Predictions, keywords = "predictions, suggestions provider")
            entry(R.string.app_drawer_folder, destination = AppDrawerFolder, keywords = "folders, categories, tabs, organize")
            entry(R.string.background_opacity, keywords = "transparency, blur")
            entry(R.string.work_profile_tab_container_background_label, keywords = "tabs, work profile")
            entry(R.string.pref_all_apps_search_bar_background, keywords = "search bar, backdrop")
            entry(R.string.app_drawer_columns, keywords = "grid, columns")
            entry(R.string.row_height_label, keywords = "grid, rows, spacing")
            entry(R.string.app_drawer_indent_label, keywords = "margins, spacing")
            entry(R.string.top_padding_label, keywords = "margins, spacing")
            entry(R.string.icon_sizes, keywords = "scale, size of icons, drawer icons")
            entry(R.string.show_labels, keywords = "names, icon text, drawer labels")
            entry(R.string.label_size, keywords = "text size, font size, drawer labels")
            entry(R.string.twoline_label, keywords = "two lines, wrap, long names")
            entry(R.string.pref_all_apps_remember_position_title, R.string.pref_all_apps_remember_position_description, keywords = "scroll position, last position")
            entry(R.string.pref_all_apps_show_scrollbar_title, keywords = "fast scroll, scroller")
            entry(R.string.app_drawer_haptic_feedback_label, keywords = "vibration, haptics")
        }

        page(SettingsSearchPage.SUGGESTIONS) {
            entry(R.string.global_predictions_label, keywords = "predictions, suggested apps, turn off suggestions")
            entry(R.string.prediction_mode_label, keywords = "predictions, provider, android")
            entry(R.string.prediction_weighted_usage_stats_label, R.string.prediction_weighted_usage_stats_description, keywords = "usage access, statistics")
            entry(R.string.prioritize_recently_installed_apps, R.string.prioritize_recently_installed_apps_description, keywords = "new apps, updated apps")
            entry(R.string.dismissed_prediction_apps_label, destination = DismissedPredictionApps, keywords = "dismissed, hide suggestion, never suggest")
        }

        page(SettingsSearchPage.SEARCH) {
            // Dock search bar tab
            entry(R.string.search_provider, destination = DockSearchProvider, keywords = "dock search, engine, google, duckduckgo, bing, qsb")
            entry(R.string.hotseat_mode_label, keywords = "dock search, qsb, search widget")
            entry(R.string.apply_accent_color_label, keywords = "dock search, qsb, tint")
            entry(R.string.corner_radius_label, keywords = "dock search, qsb, rounded")
            entry(R.string.qsb_hotseat_background_transparency, keywords = "dock search, qsb, transparency")
            entry(R.string.qsb_hotseat_stroke_width, keywords = "dock search, qsb, outline, border")
            // App drawer search tab
            drawerSearch(R.string.show_app_search_bar, keywords = "search bar, hide search")
            drawerSearch(R.string.pref_search_auto_show_keyboard, keywords = "keyboard, focus")
            drawerSearch(R.string.allapps_match_qsb_style_label, R.string.allapps_match_qsb_style_description, keywords = "dock search, qsb")
            drawerSearch(R.string.search_pref_result_apps_and_shortcuts_title, keywords = "apps, shortcuts, results")
            drawerSearch(R.string.search_pref_result_shortcuts_title, keywords = "app shortcuts, results")
            drawerSearch(R.string.search_pref_result_people_title, R.string.search_pref_result_contacts_description, keywords = "contacts, address book, phone, results")
            drawerSearch(R.string.search_pref_result_tips_title, keywords = "tips, results")
            drawerSearch(R.string.search_pref_result_settings_title, keywords = "system settings, results")
            drawerSearch(R.string.app_search_algorithm, keywords = "matching, fuzzy")
            drawerSearch(R.string.fuzzy_search_title, R.string.fuzzy_search_desc, keywords = "matching, typos")
            drawerSearch(R.string.search_pref_result_web_title, keywords = "web search, suggestions, autocomplete, online")
            drawerSearch(R.string.allapps_web_suggestion_provider_label, keywords = "web search, engine, google, duckduckgo, bing")
            drawerSearch(R.string.search_pref_result_files_title, R.string.search_pref_result_files_description, keywords = "documents, downloads, media, storage, results")
            drawerSearch(R.string.search_pref_result_history_title, keywords = "recent searches, results")
            drawerSearch(R.string.clear_history, keywords = "delete searches, privacy, recent searches")
            drawerSearch(R.string.all_apps_search_result_calculator, keywords = "math, results")
            // Options inside each result type's own screen
            providerOption(R.string.max_apps_result_count_title, SearchProviderId.APPS, keywords = "how many results, limit")
            providerOption(R.string.max_recent_result_count_title, SearchProviderId.HISTORY, keywords = "how many results, limit, recent searches")
            providerOption(R.string.max_settings_entry_result_count_title, SearchProviderId.SETTINGS, keywords = "how many results, limit, system settings")
            providerOption(R.string.max_suggestion_result_count_title, SearchProviderId.WEB, keywords = "how many results, limit, web suggestions")
            providerOption(R.string.max_web_suggestion_delay, SearchProviderId.WEB, keywords = "delay, wait, web suggestions, typing")
            providerOption(R.string.max_people_result_count_title, SearchProviderId.CONTACTS, keywords = "how many results, limit, contacts")
            providerOption(R.string.max_file_result_count_title, SearchProviderId.FILES, keywords = "how many results, limit, documents")
            providerOption(R.string.search_pref_result_all_files_title, SearchProviderId.FILES, keywords = "storage, permission, manage all files")
            providerOption(R.string.search_pref_result_selected_folder_title, SearchProviderId.FILES, keywords = "folder, storage, choose folder, documents")
            providerOption(R.string.search_pref_result_visual_media_title, SearchProviderId.FILES, keywords = "gallery, pictures, photos, images, videos, media")
            drawerSearch(R.string.show_hidden_apps_in_search_results, keywords = "hidden apps, privacy")
        }

        page(SettingsSearchPage.DOCK) {
            entry(R.string.dock_label, R.string.home_screen_dock_link_description, keywords = "hotseat, favorites")
            entry(R.string.show_hotseat_title, keywords = "hide dock, hotseat")
            entry(R.string.hotseat_background, keywords = "backdrop, pill")
            entry(R.string.hotseat_bg_corner_radius, keywords = "rounded")
            entry(R.string.hotseat_bg_alpha, keywords = "transparency")
            entry(R.string.hotseat_bg_horizontal_inset_left, keywords = "margin, inset")
            entry(R.string.hotseat_bg_horizontal_inset_right, keywords = "margin, inset")
            entry(R.string.hotseat_bg_vertical_inset_top, keywords = "margin, inset")
            entry(R.string.hotseat_bg_vertical_inset_bottom, keywords = "margin, inset")
            entry(R.string.dock_icons, keywords = "icon count, hotseat count, columns")
            entry(R.string.dock_rows, keywords = "hotseat rows")
            entry(R.string.dock_pages, keywords = "hotseat pages, swipe")
            entry(R.string.hotseat_bottom_space_label, keywords = "margin, spacing")
            entry(R.string.page_indicator_height, keywords = "dots, pager")
        }

        page(SettingsSearchPage.FOLDERS) {
            entry(R.string.folder_shape_match_icon_label, R.string.folder_shape_match_icon_description, keywords = "folder shape, icon shape, same shape")
            entry(R.string.folder_shape_label, destination = GeneralIconShape(ShapeRoute.FOLDER_SHAPE), keywords = "folder preview, squircle, circle")
            entry(R.string.folder_preview_bg_opacity_label, keywords = "transparency")
            entry(R.string.folder_bg_opacity_label, keywords = "transparency")
            entry(R.string.max_folder_columns, keywords = "grid, width")
            entry(R.string.max_folder_rows, keywords = "grid, height")
        }

        page(SettingsSearchPage.GESTURES) {
            entry(R.string.gesture_double_tap, keywords = "sleep, lock screen, screen off, dt2s")
            entry(R.string.gesture_swipe_up, keywords = "app drawer, all apps, overview")
            entry(R.string.gesture_swipe_down, keywords = "notifications, shade, quick settings")
            entry(R.string.gesture_two_finger_swipe_up, keywords = "app drawer")
            entry(R.string.gesture_two_finger_swipe_down, keywords = "notifications, shade")
            entry(R.string.gesture_home_tap, keywords = "home tap, press home")
            entry(R.string.gesture_back_tap, keywords = "back tap")
            entry(R.string.sleep_mode_label, keywords = "lock screen, screen off, accessibility, double tap")
        }

        page(SettingsSearchPage.BACKUP_AND_RESTORE) {
            entry(R.string.create_backup, destination = CreateBackup, keywords = "export, save settings, layout")
            entry(R.string.restore_backup, keywords = "import, load, layout")
            entry(R.string.restore_nova_backup, keywords = "nova launcher, import, migrate, convert")
        }

        page(SettingsSearchPage.ABOUT) {
            entry(R.string.expressive_update_channel_label, R.string.expressive_update_channel_description, availability = SettingsSearchAvailability.EXPRESSIVE_ONLY, keywords = "qa, stable, release channel, updates, github, notifications")
            entry(R.string.privacy_policy, availability = SettingsSearchAvailability.EXPRESSIVE_ONLY, keywords = "privacy, data, legal")
            entry(R.string.acknowledgements, keywords = "licenses, open source, credits, libraries")
        }
    }

    private fun MutableList<SettingsSearchEntry>.page(
        page: SettingsSearchPage,
        block: PageBuilder.() -> Unit,
    ) {
        addAll(PageBuilder(page).apply(block).entries)
    }

    private class PageBuilder(private val page: SettingsSearchPage) {
        val entries = mutableListOf<SettingsSearchEntry>()

        fun entry(
            @StringRes title: Int,
            @StringRes description: Int? = null,
            destination: PreferenceRoute = page.route,
            availability: SettingsSearchAvailability = SettingsSearchAvailability.EVERYWHERE,
            keywords: String = "",
        ) {
            entries += SettingsSearchEntry(
                title = title,
                page = page,
                description = description,
                destination = destination,
                keywords = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                availability = availability,
            )
        }

        fun providerOption(
            @StringRes title: Int,
            provider: SearchProviderId,
            keywords: String = "",
        ) = entry(
            title = title,
            destination = SearchProviderPreference(provider),
            keywords = "app list search, $keywords",
        )

        fun drawerSearch(
            @StringRes title: Int,
            @StringRes description: Int? = null,
            keywords: String = "",
        ) = entry(
            title = title,
            description = description,
            destination = Search(SearchRoute.DRAWER_SEARCH),
            keywords = "app list search, $keywords",
        )
    }
}

object SettingsSearch {

    /** Lower-cases and strips accents so "dock", "DOCK" and "dóck" all match. */
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .lowercase(Locale.ROOT)

    /**
     * Keeps items where every word of [query] appears in the title, description, page or keywords.
     * Best first: the exact title, titles that start with the query, titles with a word that
     * starts with it, other title matches, keyword matches, then the rest. Ties keep index order.
     */
    fun filter(items: List<SearchablePreferenceItem>, query: String): List<SearchablePreferenceItem> {
        val tokens = normalize(query).split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return items
        return items
            .mapNotNull { item -> rank(item.searchable, tokens)?.let { item to it } }
            .sortedBy { it.second }
            .map { it.first }
    }

    private fun rank(text: SearchableText, tokens: List<String>): Int? {
        if (tokens.all { it in text.title }) {
            return when {
                text.title == tokens.joinToString(" ") -> RANK_TITLE_EXACT
                text.title.startsWith(tokens.first()) -> RANK_TITLE_STARTS
                text.title.split(' ').any { it.startsWith(tokens.first()) } -> RANK_TITLE_WORD
                else -> RANK_TITLE
            }
        }
        if (tokens.all { it in text.title || it in text.keywords }) return RANK_KEYWORD
        if (tokens.all { it in text.title || it in text.description || it in text.category || it in text.keywords }) {
            return RANK_ANYWHERE
        }
        return null
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")
    private const val RANK_TITLE_EXACT = 0
    private const val RANK_TITLE_STARTS = 1
    private const val RANK_TITLE_WORD = 2
    private const val RANK_TITLE = 3
    private const val RANK_KEYWORD = 4
    private const val RANK_ANYWHERE = 5
}
