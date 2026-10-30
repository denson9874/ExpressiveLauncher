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

package com.android.launcher3.dagger

import android.content.res.Configuration
import android.graphics.Rect
import android.view.IDisplayWindowListener
import android.view.IWindowManager
import com.android.app.displaylib.DisplayRepository
import com.android.app.displaylib.DisplaysWithDecorationsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * displaylib's DisplaysWithDecorationsRepositoryImpl for Android 17 (API 37), where
 * IDisplayWindowListener gained onDisplayAnimationsDisabledChanged. The displaylib listener, built
 * against Android 16, lacks it, so the system's call on a display being added threw
 * AbstractMethodError on a binder thread and took the launcher down.
 */
class CinnamonBunDisplaysWithDecorationsRepository(
    private val windowManager: IWindowManager,
    bgApplicationScope: CoroutineScope,
    displayRepository: DisplayRepository,
) : DisplaysWithDecorationsRepository {

    private val decorationEvents: Flow<Event> = callbackFlow {
        val callback =
            object : IDisplayWindowListener.Stub() {
                override fun onDisplayAddSystemDecorations(displayId: Int) {
                    trySend(Event.Add(displayId))
                }

                override fun onDisplayRemoveSystemDecorations(displayId: Int) {
                    trySend(Event.Remove(displayId))
                }

                override fun onDesktopModeEligibleChanged(displayId: Int) {}

                override fun onDisplayAdded(displayId: Int) {}

                override fun onDisplayConfigurationChanged(
                    displayId: Int,
                    newConfig: Configuration?,
                ) {}

                override fun onDisplayRemoved(displayId: Int) {}

                override fun onFixedRotationStarted(displayId: Int, newRotation: Int) {}

                override fun onFixedRotationFinished(displayId: Int) {}

                override fun onKeepClearAreasChanged(
                    displayId: Int,
                    restricted: MutableList<Rect>?,
                    unrestricted: MutableList<Rect>?,
                ) {}

                // Android 17 only, so no override: the Android 16 prebuilt lacks it. proguard.pro
                // keeps it, since R8 sees no caller.
                fun onDisplayAnimationsDisabledChanged(displayId: Int, disabled: Boolean) {}
            }
        windowManager.registerDisplayWindowListener(callback)
        awaitClose { windowManager.unregisterDisplayWindowListener(callback) }
    }

    private val initialDisplayIdsWithDecorations: Set<Int> =
        displayRepository.displayIds.value
            .filter { windowManager.shouldShowSystemDecors(it) }
            .toSet()

    override val displayIdsWithSystemDecorations: StateFlow<Set<Int>> =
        merge(decorationEvents, displayRepository.displayRemovalEvent.map { Event.Remove(it) })
            .scan(initialDisplayIdsWithDecorations) { displayIds: Set<Int>, event: Event ->
                when (event) {
                    is Event.Add -> displayIds + event.displayId
                    is Event.Remove -> displayIds - event.displayId
                }
            }
            .distinctUntilChanged()
            .stateIn(
                scope = bgApplicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = initialDisplayIdsWithDecorations,
            )

    private sealed class Event(val displayId: Int) {
        class Add(displayId: Int) : Event(displayId)

        class Remove(displayId: Int) : Event(displayId)
    }
}
