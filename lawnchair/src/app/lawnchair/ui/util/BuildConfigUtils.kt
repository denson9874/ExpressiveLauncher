package app.lawnchair.ui.util

import com.android.launcher3.BuildConfig

internal fun isPlayStoreChannel(channel: String): Boolean {
    return channel == "play" || channel == "expressive"
}

fun isPlayStoreFlavor(): Boolean = isPlayStoreChannel(BuildConfig.FLAVOR_channel)

/** Whether this channel declares and may open Android's all-files special-access screen. */
fun canRequestManageAllFilesAccess(): Boolean = BuildConfig.CAN_REQUEST_MANAGE_ALL_FILES_ACCESS

/** Whether this channel declares and may request broad photo/video library access. */
fun canRequestBroadVisualMediaAccess(): Boolean = BuildConfig.CAN_REQUEST_BROAD_VISUAL_MEDIA_ACCESS
