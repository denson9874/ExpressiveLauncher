/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.shared.bubbles

import android.content.Context
import android.content.res.Resources
import android.view.View
import android.view.WindowManagerPolicyConstants

/** Simplifies accessing context fields. */
object ContextUtils {

    // The framework id is private and changes between releases, so it is looked up by name.
    private val navBarInteractionModeResId: Int by lazy {
        Resources.getSystem().getIdentifier("config_navBarInteractionMode", "integer", "android")
    }

    /** Gets navigation mode. */
    @JvmStatic
    val Context.navigationMode: Int
        get() =
            if (navBarInteractionModeResId != 0) {
                resources.getInteger(navBarInteractionModeResId)
            } else {
                WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON
            }

    /** Returns whether the navigation mode is gestures. */
    @JvmStatic
    val Context.isGestureNavigationMode: Boolean
        get() = navigationMode == WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL

    /** Returns whether layout direction is rtl. */
    @JvmStatic
    val Context.isRtl: Boolean
        get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
}
