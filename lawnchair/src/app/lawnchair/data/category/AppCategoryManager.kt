package app.lawnchair.data.category

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import app.lawnchair.util.MainThreadInitializedObject
import com.android.launcher3.R
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.Executors
import java.util.concurrent.ConcurrentHashMap

enum class AppCategory(val id: Int, val labelRes: Int) {
    ALL(0, R.string.category_tab_all),
    GAMES(1, R.string.category_tab_games),
    MEDIA(2, R.string.category_tab_media),
    SOCIAL(3, R.string.category_tab_social),
    WORK(4, R.string.category_tab_work),
    UTILITIES(5, R.string.category_tab_utilities);

    companion object {
        fun fromId(id: Int): AppCategory = values().firstOrNull { it.id == id } ?: ALL

        fun fromApplicationInfo(category: Int, packageName: String): AppCategory {
            return when (category) {
                ApplicationInfo.CATEGORY_GAME -> GAMES
                ApplicationInfo.CATEGORY_AUDIO,
                ApplicationInfo.CATEGORY_VIDEO,
                ApplicationInfo.CATEGORY_IMAGE -> MEDIA
                ApplicationInfo.CATEGORY_SOCIAL,
                ApplicationInfo.CATEGORY_NEWS -> SOCIAL
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> WORK
                ApplicationInfo.CATEGORY_MAPS -> UTILITIES
                else -> inferFromPackageName(packageName)
            }
        }

        fun fromPackageName(pkg: String): AppCategory = inferFromPackageName(pkg)

        private fun inferFromPackageName(pkg: String): AppCategory {
            val lower = pkg.lowercase()
            return when {
                lower.contains("game") -> GAMES
                lower.contains("music") || lower.contains("video") || lower.contains("player") ||
                    lower.contains("camera") || lower.contains("gallery") || lower.contains("photo") ||
                    lower.contains("spotify") || lower.contains("netflix") || lower.contains("youtube") -> MEDIA
                lower.contains("chat") || lower.contains("talk") || lower.contains("messenger") ||
                    lower.contains("discord") || lower.contains("telegram") || lower.contains("reddit") ||
                    lower.contains("twitter") || lower.contains("social") || lower.contains("insta") -> SOCIAL
                lower.contains("office") || lower.contains("docs") || lower.contains("sheet") ||
                    lower.contains("note") || lower.contains("mail") || lower.contains("calendar") ||
                    lower.contains("task") || lower.contains("slack") || lower.contains("work") -> WORK
                else -> UTILITIES
            }
        }
    }
}

class AppCategoryManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val packageCategoryCache = ConcurrentHashMap<String, AppCategory>()

    fun indexPackagesAsync(packages: Collection<String>) {
        if (packages.isEmpty()) return
        Executors.MODEL_EXECUTOR.execute {
            val pm = context.packageManager
            for (pkg in packages) {
                if (packageCategoryCache.containsKey(pkg)) continue
                val custom = prefs.getInt("override_$pkg", -1)
                if (custom != -1) {
                    packageCategoryCache[pkg] = AppCategory.fromId(custom)
                    continue
                }
                val category = try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    AppCategory.fromApplicationInfo(appInfo.category, pkg)
                } catch (_: Exception) {
                    AppCategory.fromApplicationInfo(ApplicationInfo.CATEGORY_UNDEFINED, pkg)
                }
                packageCategoryCache[pkg] = category
            }
        }
    }

    fun indexAppsAsync(apps: Array<AppInfo>?) {
        if (apps == null || apps.isEmpty()) return
        val packages = apps.mapNotNull { it.targetComponent?.packageName ?: it.componentName?.packageName }
        indexPackagesAsync(packages)
    }

    fun getCategory(packageName: String): AppCategory {
        val custom = prefs.getInt("override_$packageName", -1)
        if (custom != -1) {
            return AppCategory.fromId(custom)
        }

        val cached = packageCategoryCache[packageName]
        if (cached != null) {
            return cached
        }

        val fallback = AppCategory.fromPackageName(packageName)
        packageCategoryCache[packageName] = fallback

        Executors.MODEL_EXECUTOR.execute {
            try {
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                val resolved = AppCategory.fromApplicationInfo(appInfo.category, packageName)
                packageCategoryCache[packageName] = resolved
            } catch (_: Exception) {
                // Keep fallback
            }
        }

        return fallback
    }

    fun setCustomCategory(packageName: String, category: AppCategory?) {
        if (category == null || category == AppCategory.ALL) {
            prefs.edit().remove("override_$packageName").apply()
        } else {
            prefs.edit().putInt("override_$packageName", category.id).apply()
        }
        packageCategoryCache.remove(packageName)
    }

    fun filterApps(apps: List<AppInfo>, category: AppCategory): List<AppInfo> {
        if (category == AppCategory.ALL) return apps
        return apps.filter { app ->
            val pkg = app.targetComponent?.packageName ?: app.componentName?.packageName.orEmpty()
            getCategory(pkg) == category
        }
    }

    companion object {
        private const val PREFS_NAME = "expressive_app_categories"

        @JvmField
        val INSTANCE = MainThreadInitializedObject(::AppCategoryManager)
    }
}
