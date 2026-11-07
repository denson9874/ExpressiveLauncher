/*
 * Copyright (C) 2026 Expressive Launcher
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
package com.android.quickstep.util;

import static com.android.launcher3.testing.shared.ResourceUtils.STATUS_BAR_HEIGHT;

import android.content.Context;
import android.view.Display;
import android.view.DisplayCutout;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.policy.SystemBarUtils;
import com.android.launcher3.Utilities;
import com.android.launcher3.testing.shared.ResourceUtils;

/**
 * The status bar height, also on Android 12.0. {@link SystemBarUtils} only exists from Android 12L
 * (API 32): calling it on API 31 throws {@code NoClassDefFoundError} and crashed the launcher on
 * every start when it was the Recents provider through QuickSwitch (GitHub issue #60).
 */
public final class StatusBarHeightCompat {

    private StatusBarHeightCompat() { }

    /** Same value as {@link SystemBarUtils#getStatusBarHeight(Context)} on every supported API. */
    public static int get(Context context) {
        if (Utilities.ATLEAST_S_V2) {
            return SystemBarUtils.getStatusBarHeight(context);
        }
        return beforeSystemBarUtils(context);
    }

    /**
     * SystemBarUtils' own rule, from public APIs: the status bar dimen plus any waterfall inset, never
     * less than the display cutout's safe top inset.
     */
    @VisibleForTesting
    static int beforeSystemBarUtils(Context context) {
        int statusBarHeight = ResourceUtils.getDimenByName(
                STATUS_BAR_HEIGHT, context.getResources(), 0 /* defaultValue */);
        DisplayCutout cutout = cutoutOf(context);
        if (cutout == null) {
            return statusBarHeight;
        }
        return Math.max(cutout.getSafeInsetTop(),
                statusBarHeight + cutout.getWaterfallInsets().top);
    }

    @Nullable
    private static DisplayCutout cutoutOf(Context context) {
        try {
            Display display = context.getDisplay();
            return display == null ? null : display.getCutout();
        } catch (UnsupportedOperationException e) {
            // Not a visual context: no display, so no cutout to account for.
            return null;
        }
    }
}
