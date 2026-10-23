package app.lawnchair.util

import android.content.ComponentName
import android.os.UserHandle
import com.android.launcher3.util.ComponentKey

/**
 * Parses the "pkg/cls#user" string [ComponentKey.toString] writes, including the keys
 * [ComponentKey.fromString] cannot read.
 *
 * A [com.android.launcher3.shortcuts.ShortcutKey] stores the shortcut ID as the class, and an ID
 * may contain '#' (gesture shortcuts pinned before 5.0.1 embed "pkg/cls#user"), so the user is
 * split off at the last '#'. The class is kept verbatim: toString uses flattenToString, which never
 * abbreviates it, so a leading '.' is part of a shortcut ID and must not be expanded with the
 * package the way [ComponentName.unflattenFromString] does.
 *
 * Returns null when [value] is not in that form.
 */
fun parseComponentKeyLenient(value: String): ComponentKey? {
    val packageEnd = value.indexOf('/')
    val userStart = value.lastIndexOf('#')
    if (packageEnd < 0 || userStart <= packageEnd + 1) return null
    val userId = value.substring(userStart + 1).toIntOrNull() ?: return null
    return ComponentKey(
        ComponentName(value.substring(0, packageEnd), value.substring(packageEnd + 1, userStart)),
        UserHandle.of(userId),
    )
}
