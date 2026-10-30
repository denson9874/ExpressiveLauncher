/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.launcher3.util

import android.content.res.Resources
import android.graphics.Point
import android.view.ViewGroup
import com.android.launcher3.DeviceProfile
import com.android.launcher3.R
import kotlin.math.roundToInt

object DimensionUtils {
    /**
     * Height of the phone taskbar, the framework's navigation_bar_frame_height. That dimen is
     * private, so its id changes between Android releases, and the id aapt2 baked into
     * R.dimen.taskbar_phone_size from the compile SDK can point at another dimen on the device.
     * It is read by name instead, through [res] so the orientation-specific value applies.
     */
    @JvmStatic
    fun getTaskbarPhoneSize(res: Resources): Int =
        getFrameworkDimensionPixelSize(res, "navigation_bar_frame_height", 48f)

    /** The framework's rounded_corner_content_padding, read by name like [getTaskbarPhoneSize]. */
    @JvmStatic
    fun getTaskbarPhoneRoundedCornerContentMargin(res: Resources): Int =
        getFrameworkDimensionPixelSize(res, "rounded_corner_content_padding", 0f)

    private fun getFrameworkDimensionPixelSize(res: Resources, name: String, fallbackDp: Float) =
        res.getIdentifier(name, "dimen", "android").let { id ->
            if (id != 0) res.getDimensionPixelSize(id)
            else (fallbackDp * res.displayMetrics.density).roundToInt()
        }

    /**
     * Point where x is width, and y is height of taskbar based on provided [deviceProfile] x or y
     * could also be -1 to indicate there is no dimension specified
     */
    @JvmStatic
    fun getTaskbarPhoneDimensions(
        deviceProfile: DeviceProfile,
        res: Resources,
        isPhoneMode: Boolean,
        isGestureNav: Boolean,
    ): Point {
        val p = Point()
        // Taskbar for large screen
        if (!isPhoneMode) {
            p.x = ViewGroup.LayoutParams.MATCH_PARENT
            p.y = deviceProfile.taskbarProfile.height
            return p
        }

        // Taskbar on phone using gesture nav, it will always be stashed
        if (isGestureNav) {
            p.x = ViewGroup.LayoutParams.MATCH_PARENT
            p.y = res.getDimensionPixelSize(R.dimen.taskbar_stashed_size)
            return p
        }

        // Taskbar on phone, portrait
        if (!deviceProfile.deviceProperties.isLandscape) {
            p.x = ViewGroup.LayoutParams.MATCH_PARENT
            p.y = getTaskbarPhoneSize(res)
            return p
        }

        // Taskbar on phone, landscape
        p.x = getTaskbarPhoneSize(res)
        p.y = ViewGroup.LayoutParams.MATCH_PARENT
        return p
    }
}
