package app.lawnchair.allapps.views

import android.graphics.drawable.Icon
import android.util.LruCache
import app.lawnchair.search.adapter.CALCULATOR
import app.lawnchair.search.adapter.HISTORY
import app.lawnchair.search.adapter.WEB_SUGGESTION
import com.android.launcher3.icons.BitmapInfo

/**
 * Loaded icons of search action and app shortcut rows, keyed by what the icon is rather than by the
 * result. The web-search, suggestion and shortcut rows are rebuilt on every keystroke with the same
 * icon; a cache hit binds it in the same frame instead of after a background load.
 */
internal val ACTION_ICON_CACHE = LruCache<String, BitmapInfo>(48)

/** Packages whose action rows always draw the same bitmap icon, whatever the query. */
private val CONSTANT_BITMAP_PACKAGES = setOf(WEB_SUGGESTION, HISTORY, CALCULATOR)

/**
 * Cache key for a search action icon, or null when the icon can differ between results with the
 * same key (contact photos, file previews, package icons that follow the icon pack). [theme]
 * identifies the launcher instance and night mode, so a recreated launcher re-tints its icons.
 */
internal fun searchActionIconKey(
    packageName: String,
    user: String,
    layoutType: String?,
    icon: Icon?,
    primaryIconFromTitle: Boolean,
    /** The row badges its icon with a package or component icon. */
    badged: Boolean,
    theme: String,
): String? {
    // These depend on the package icon, which follows the icon pack.
    if (icon == null || badged || primaryIconFromTitle) return null
    val base = "$packageName|$user|$layoutType|$theme"
    return when {
        icon.type == Icon.TYPE_RESOURCE -> "$base|res:${icon.resPackage}:${icon.resId}"
        packageName in CONSTANT_BITMAP_PACKAGES -> "$base|constant"
        else -> null
    }
}

/** Cache key for an app shortcut row's icon; a shortcut update changes [lastChanged]. */
internal fun searchShortcutIconKey(
    packageName: String,
    id: String,
    user: String,
    lastChanged: Long,
    theme: String,
): String = "shortcut|$packageName|$id|$user|$lastChanged|$theme"
