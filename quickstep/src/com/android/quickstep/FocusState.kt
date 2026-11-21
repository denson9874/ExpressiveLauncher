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

package com.android.quickstep

import android.app.ActivityManager
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import com.android.launcher3.Utilities
import com.android.launcher3.util.Executors
import com.android.wm.shell.shared.IFocusTransitionListener
import com.android.wm.shell.shared.IFocusTransitionListener.Stub
import com.android.wm.shell.shared.IShellTransitions

/** Class to track focus state of displays and windows */
class FocusState {

    var focusedDisplayId = DEFAULT_DISPLAY
        private set(value) {
            field = value
            listeners.toSet().forEach { it.onFocusedDisplayChanged(value) }
        }

    private var listeners = mutableSetOf<FocusChangeListener>()

    fun addListener(l: FocusChangeListener) = listeners.add(l)

    fun removeListener(l: FocusChangeListener) = listeners.remove(l)

    fun init(transitions: IShellTransitions?) {
        if (transitions == null) {
            focusedDisplayId = DEFAULT_DISPLAY
            return
        }
        try {
            transitions.setFocusTransitionListener(createListenerStub())
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed call setFocusTransitionListener", e)
        }
    }

    internal fun createListenerStub(): Stub =
        object : Stub() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (Utilities.ATLEAST_CINNAMON_BUN && code == TRANSACTION_ON_FOCUSED_TASK_CHANGED) {
                    // Android 17 added onFocusedTaskChanged(RunningTaskInfo, boolean, boolean).
                    data.enforceInterface(IFocusTransitionListener.DESCRIPTOR)
                    data.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR)
                    data.readBoolean()
                    data.readBoolean()
                    data.enforceNoDataAvail()
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }

            override fun onFocusedDisplayChanged(displayId: Int) {
                Executors.MAIN_EXECUTOR.execute { focusedDisplayId = displayId }
            }
        }

    interface FocusChangeListener {
        fun onFocusedDisplayChanged(displayId: Int)
    }

    override fun toString() = "{FocusState focusedDisplayId=$focusedDisplayId}"

    companion object {
        private const val TAG = "FocusState"

        // Android 17 IFocusTransitionListener method 2 (IBinder.FIRST_CALL_TRANSACTION + 1).
        internal const val TRANSACTION_ON_FOCUSED_TASK_CHANGED =
            IBinder.FIRST_CALL_TRANSACTION + 1
    }
}
