package app.lawnchair.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

fun Context.openAppPermissionSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    val uri: Uri = Uri.fromParts("package", packageName, null)
    intent.data = uri

    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

    if (intent.resolveActivity(packageManager) != null) {
        startActivity(intent)
    } else {
        Log.e("Permissions", "No activity found to handle application details settings intent")
    }
}

@RequiresApi(Build.VERSION_CODES.R)
fun Context.requestManageAllFilesAccessPermission() {
    for (intent in manageAllFilesAccessIntents(packageName)) {
        if (intent.resolveActivity(packageManager) == null) continue
        try {
            startActivity(intent)
            return
        } catch (exception: ActivityNotFoundException) {
            Log.w("Permissions", "Storage settings activity disappeared before launch", exception)
        } catch (exception: SecurityException) {
            Log.w("Permissions", "Storage settings activity rejected the request", exception)
        }
    }

    Log.e("Permissions", "No activity found to handle all-files access settings")
}

@RequiresApi(Build.VERSION_CODES.R)
internal fun manageAllFilesAccessIntents(packageName: String): List<Intent> {
    val commonFlags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_NO_HISTORY or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

    return listOf(
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", packageName, null),
        ).addFlags(commonFlags),
        // Some OEM builds omit the app-specific page. The global list is a safe fallback for
        // sideload channels that actually declare MANAGE_EXTERNAL_STORAGE.
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(commonFlags),
    )
}
