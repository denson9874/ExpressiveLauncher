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

package com.android.quickstep.views

import android.app.WindowConfiguration
import com.android.launcher3.Flags.enableOverviewBackgroundWallpaperBlur
import com.android.launcher3.Utilities
import com.android.quickstep.RemoteTargetGluer.RemoteTargetHandle

/** Applies blur either behind launcher surface or live tile app. */
class BlurUtils(private val recentsView: RecentsView<*, *>) {

    private fun getLiveTileRemoteTargetHandles(): List<RemoteTargetHandle>? {
        val handles = recentsView.remoteTargetHandles
        if (handles == null || recentsView.recentsAnimationController == null) {
            return null
        }
        return handles.filter { handle ->
            val apps = handle.transformParams.targetSet?.apps
            apps == null ||
                apps.isEmpty() ||
                apps.any { it.windowConfiguration?.activityType != WindowConfiguration.ACTIVITY_TYPE_HOME }
        }
    }

    private fun Iterable<RemoteTargetHandle>.setDrawBelowRecents(drawBelowRecents: Boolean) {
        forEach { it.taskViewSimulator.drawsBelowRecents = drawBelowRecents }
    }

    /**
     * Controls if live tile should be above or below Recents layer, and update the base layer to
     * apply blur to in BaseDepthController.
     */
    fun setDrawLiveTileBelowRecents(drawBelowRecents: Boolean) {
        if (drawBelowRecents) {
            getLiveTileRemoteTargetHandles()?.setDrawBelowRecents(true)
        } else {
            // Clear drawsBelowRecents even if recentsAnimationController has already been nulled
            // (e.g. during onRecentsAnimationComplete).
            recentsView.remoteTargetHandles?.asIterable()?.setDrawBelowRecents(false)
        }
        updateBlurLayer()
    }

    /**
     * Set surface in [remoteTargetHandles] to be above Recents layer, and update the base layer to
     * apply blur to in BaseDepthController.
     */
    fun setDrawAboveRecents(remoteTargetHandles: Array<RemoteTargetHandle>) {
        remoteTargetHandles.asIterable().setDrawBelowRecents(false)
        updateBlurLayer(drawingAboveRecents = true)
    }

    private fun updateBlurLayer(drawingAboveRecents: Boolean = false) {
        if (!enableOverviewBackgroundWallpaperBlur()) return
        // Blurs behind lowest live tile surface that's below recents or Launcher if there
        // are none. Filter each target individually so home targets and already-released leashes
        // never capture the blur layer.
        val nonHomeValidTargets =
            getLiveTileRemoteTargetHandles()
                ?.asSequence()
                ?.filter { it.taskViewSimulator.drawsBelowRecents }
                ?.flatMap { it.transformParams.targetSet?.apps?.asIterable() ?: emptyList() }
                ?.filter {
                    it.windowConfiguration?.activityType != WindowConfiguration.ACTIVITY_TYPE_HOME
                }
                ?.filter { it.leash != null && it.leash.isValid }
        val targetLeash =
            if (Utilities.ATLEAST_S_V2) {
                nonHomeValidTargets?.maxByOrNull { it.leash.layerId }?.leash
            } else {
                nonHomeValidTargets?.maxByOrNull { it.prefixOrderIndex }?.leash
            }
        recentsView.depthController?.setBaseSurfaceOverride(
            targetLeash,
            /* applyOnDraw= */ drawingAboveRecents,
        )
    }
}
