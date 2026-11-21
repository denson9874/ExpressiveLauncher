/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.shared.recents.model

import android.app.WindowConfiguration
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.WindowInsetsController.Appearance
import android.window.TaskSnapshot
import app.lawnchair.compat.CinnamonBunCompat
import app.lawnchair.compat.LawnchairQuickstepCompat

/** Data for a single thumbnail. */
data class ThumbnailData(
    val thumbnail: Bitmap? = null,
    var orientation: Int = Configuration.ORIENTATION_UNDEFINED,
    @JvmField var rotation: Int = WindowConfiguration.ROTATION_UNDEFINED,
    @JvmField var insets: Rect = Rect(),
    @JvmField var letterboxInsets: Rect = Rect(),
    @JvmField var reducedResolution: Boolean = false,
    @JvmField var isRealSnapshot: Boolean = true,
    var isTranslucent: Boolean = false,
    @JvmField var windowingMode: Int = WindowConfiguration.WINDOWING_MODE_UNDEFINED,
    @JvmField @Appearance var appearance: Int = 0,
    @JvmField var scale: Float = 1f,
    var snapshotId: Long = 0,
) {
    fun recycleBitmap() {
        thumbnail?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
    }

    companion object {
        private fun makeThumbnail(snapshot: TaskSnapshot): Bitmap? {
            var thumbnail: Bitmap? = null
            try {
                if (LawnchairQuickstepCompat.ATLEAST_CINNAMON_BUN) {
                    // Android 17 hides the hardware buffer behind wrapToBitmap().
                    thumbnail = CinnamonBunCompat.wrapSnapshotToBitmap(snapshot)
                } else {
                    snapshot.hardwareBuffer?.use { buffer ->
                        thumbnail = Bitmap.wrapHardwareBuffer(buffer, snapshot.colorSpace)
                    }
                }
            } catch (ex: IllegalArgumentException) {
                // TODO(b/157562905): Workaround for a crash when we get a snapshot without this
                // state
                val bufferInfo = if (LawnchairQuickstepCompat.ATLEAST_CINNAMON_BUN) {
                    "snapshotId=${snapshot.id}"
                } else {
                    "${snapshot.hardwareBuffer}"
                }
                Log.e(
                    "ThumbnailData",
                    "Unexpected snapshot without USAGE_GPU_SAMPLED_IMAGE: $bufferInfo",
                    ex,
                )
            }

            if (thumbnail != null) {
                return thumbnail
            }
            val width = snapshot.taskSize.x
            val height = snapshot.taskSize.y
            if (width <= 0 || height <= 0) {
                Log.w("ThumbnailData", "Ignoring snapshot with invalid taskSize=${width}x$height")
                return null
            }
            return Bitmap.createBitmap(width, height, ARGB_8888).apply {
                eraseColor(Color.BLACK)
            }
        }

        @JvmStatic
        fun wrap(
            taskIds: IntArray?,
            snapshots: Array<TaskSnapshot?>?,
        ): HashMap<Int, ThumbnailData> {
            return hashMapOf<Int, ThumbnailData>().apply {
                if (taskIds != null && snapshots != null && taskIds.size == snapshots.size) {
                    repeat(snapshots.size) {
                        snapshots[it]?.let { snapshot -> put(taskIds[it], fromSnapshot(snapshot)) }
                    }
                }
            }
        }

        @JvmStatic
        fun fromSnapshot(snapshot: TaskSnapshot): ThumbnailData {
            val thumbnail = makeThumbnail(snapshot)
            val taskWidth = snapshot.taskSize.x
            val scale = if (thumbnail != null && taskWidth > 0) {
                thumbnail.width.toFloat() / taskWidth
            } else {
                1f
            }
            return ThumbnailData(
                thumbnail = thumbnail,
                insets = Rect(snapshot.contentInsets),
                letterboxInsets = Rect(snapshot.letterboxInsets),
                orientation = snapshot.orientation,
                rotation = snapshot.rotation,
                reducedResolution = snapshot.isLowResolution,
                // TODO(b/149579527): Pass task size instead of computing scale.
                // Assume width and height were scaled the same; compute scale only for width
                scale = scale,
                isRealSnapshot = snapshot.isRealSnapshot,
                isTranslucent = snapshot.isTranslucent,
                windowingMode = snapshot.windowingMode,
                appearance = snapshot.appearance,
                snapshotId = snapshot.id,
            )
        }
    }
}
