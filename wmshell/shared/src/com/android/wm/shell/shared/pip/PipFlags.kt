/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.shared.pip

import android.app.AppGlobals
import android.content.pm.PackageManager
import android.os.Build
import android.os.flagging.AconfigPackage
import android.util.Log
import android.window.DesktopExperienceFlags.ENABLE_DESKTOP_WINDOWING_PIP
import com.android.wm.shell.Flags

class PipFlags {
    companion object {
        private const val TAG = "PipFlags"

        /**
         * Returns true if PiP2 implementation should be used. Special note: if PiP on Desktop
         * Windowing is enabled, override the PiP2 gantry flag to be ON.
         *
         * On Android 17 the framework dropped ENABLE_DESKTOP_WINDOWING_PIP, so this follows the
         * system Shell's own enable_pip2 flag instead of the launcher's bundled copy.
         */
        @JvmStatic
        val isPip2ExperimentEnabled: Boolean by lazy {
            val isTv = AppGlobals.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK, 0)
            if (Build.VERSION.SDK_INT >= 37) {
                isSystemShellPip2Enabled() && !isTv
            } else {
                (Flags.enablePip2() || ENABLE_DESKTOP_WINDOWING_PIP.isTrue) && !isTv
            }
        }

        /** Reads the Shell's enable_pip2, which Android 17 turns on for non-TV devices. */
        private fun isSystemShellPip2Enabled(): Boolean =
            try {
                AconfigPackage.load("com.android.wm.shell").getBooleanFlagValue("enable_pip2", true)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read com.android.wm.shell.enable_pip2, assuming on", e)
                true
            }

        @JvmStatic
        val isPipUmoExperienceEnabled: Boolean by lazy {
            Flags.enablePipUmoExperience()
        }
    }
}
