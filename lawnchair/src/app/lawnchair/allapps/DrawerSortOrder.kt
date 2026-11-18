package app.lawnchair.allapps

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import com.android.launcher3.R
import com.android.launcher3.model.data.AppInfo
import java.util.concurrent.TimeUnit

/** The order of apps in the app drawer. */
enum class DrawerSortOrder(val prefValue: String, @StringRes val labelRes: Int) {
    ALPHABETICAL("alphabetical", R.string.drawer_sort_alphabetical),
    INSTALL_DATE("install_date", R.string.drawer_sort_install_date),
    MOST_USED("most_used", R.string.drawer_sort_most_used),
    ;

    companion object {
        fun fromString(value: String?): DrawerSortOrder = entries.firstOrNull { it.prefValue == value } ?: ALPHABETICAL
    }
}

/**
 * Apps with a higher rank (newer install, more use) first; apps with the same rank, or none, keep
 * [byName] order. Ranks are looked up by package name.
 */
fun drawerComparator(order: DrawerSortOrder, byName: Comparator<AppInfo>, ranks: Map<String, Long>): Comparator<AppInfo> = if (order == DrawerSortOrder.ALPHABETICAL) {
    byName
} else {
    compareByDescending<AppInfo> { ranks[it.componentName?.packageName] ?: 0L }.then(byName)
}

/**
 * Install times and foreground use per package, loaded once per sort rather than per comparison.
 * Install times are read once per package per launcher process; usage is reread after a minute.
 */
class DrawerSortRanks(private val context: Context) {
    private val installTimes = HashMap<String, Long>()
    private var usage: Map<String, Long> = emptyMap()
    private var usageReadAt: Long? = null

    fun forOrder(order: DrawerSortOrder, apps: Array<AppInfo>): Map<String, Long> = when (order) {
        DrawerSortOrder.ALPHABETICAL -> emptyMap()
        DrawerSortOrder.INSTALL_DATE -> installTimes(apps)
        DrawerSortOrder.MOST_USED -> usage()
    }

    private fun installTimes(apps: Array<AppInfo>): Map<String, Long> {
        apps.forEach { app ->
            val pkg = app.componentName?.packageName ?: return@forEach
            if (pkg !in installTimes) {
                installTimes[pkg] = try {
                    context.packageManager.getPackageInfo(pkg, 0).firstInstallTime
                } catch (e: PackageManager.NameNotFoundException) {
                    0L // Installed only in another profile.
                }
            }
        }
        return installTimes
    }

    /** Foreground time over the last 30 days; empty without usage access, so the order stays alphabetical. */
    private fun usage(): Map<String, Long> {
        val now = SystemClock.elapsedRealtime()
        val readAt = usageReadAt
        if (readAt != null && now - readAt < USAGE_TTL_MS) return usage
        usageReadAt = now
        usage = try {
            val manager = context.getSystemService(UsageStatsManager::class.java)
            val end = System.currentTimeMillis()
            manager?.queryAndAggregateUsageStats(end - USAGE_WINDOW_MS, end)
                ?.filterKeys { it != context.packageName } // Time on Home would rank our own icons first.
                ?.mapValues { it.value.totalTimeInForeground }
                .orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "No usage access; sorting the drawer alphabetically", e)
            emptyMap()
        }
        return usage
    }

    private companion object {
        const val TAG = "DrawerSortRanks"
        val USAGE_WINDOW_MS = TimeUnit.DAYS.toMillis(30)
        val USAGE_TTL_MS = TimeUnit.MINUTES.toMillis(1)
    }
}
