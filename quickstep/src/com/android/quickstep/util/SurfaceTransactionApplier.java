/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewRootImpl;

import androidx.annotation.Nullable;

import com.android.quickstep.RemoteAnimationTargets.ReleaseCheck;

import java.util.function.BooleanSupplier;

import app.lawnchair.compat.LawnchairQuickstepCompat;

/**
 * Helper class to apply surface transactions in sync with RenderThread similar to
 *   android.view.SyncRtSurfaceTransactionApplier
 * with some Launcher specific utility methods
 */
@TargetApi(Build.VERSION_CODES.R)
public class SurfaceTransactionApplier extends ReleaseCheck {

    private static final int MSG_UPDATE_SEQUENCE_NUMBER = 0;

    private final Handler mApplyHandler;

    private boolean mInitialized;
    private SurfaceControl mBarrierSurfaceControl;
    private ViewRootImpl mTargetViewRootImpl;

    private int mLastSequenceNumber = 0;

    /**
     * @param targetView The view in the surface that acts as synchronization anchor.
     */
    public SurfaceTransactionApplier(View targetView) {
        targetView.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                initialize(v);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                mInitialized = false;
                mTargetViewRootImpl = null;
                mBarrierSurfaceControl = null;
                setCanRelease(true);
            }
        });
        if (targetView.isAttachedToWindow()) {
            initialize(targetView);
        } else {
            mInitialized = false;
        }
        mApplyHandler = new Handler(Looper.getMainLooper(), this::onApplyMessage);
        setCanRelease(true);
    }

    private void initialize(View view) {
        mTargetViewRootImpl = view.getViewRootImpl();
        mBarrierSurfaceControl = null;
        if (mTargetViewRootImpl != null) {
            try {
                mBarrierSurfaceControl = mTargetViewRootImpl.getSurfaceControl();
            } catch (Throwable t) {
                // Hidden API may be blocked on unrooted installs; scheduleApply will fall back.
            }
        }
        mInitialized = mTargetViewRootImpl != null;
    }

    protected boolean onApplyMessage(Message msg) {
        if (msg.what == MSG_UPDATE_SEQUENCE_NUMBER) {
            setCanRelease(msg.arg1 == mLastSequenceNumber);
            return true;
        }
        return false;
    }

    /**
     * Schedules applying surface parameters on the next frame.
     *
     * @param params The surface parameters to apply. DO NOT MODIFY the list after passing into
     *               this method to avoid synchronization issues.
     */
    public void scheduleApply(SurfaceTransaction params) {
        scheduleApply(params, /* shouldApply= */ null);
    }

    /**
     * Schedules applying surface parameters on the next frame, gated by an optional
     * {@code shouldApply} check evaluated on RenderThread right before committing.
     */
    public void scheduleApply(SurfaceTransaction params, @Nullable BooleanSupplier shouldApply) {
        View view = mTargetViewRootImpl != null ? mTargetViewRootImpl.getView() : null;
        if (!mInitialized
                || mTargetViewRootImpl == null
                || view == null
                || !view.isAttachedToWindow()
                || view.getWindowVisibility() != View.VISIBLE
                || mBarrierSurfaceControl == null
                || !mBarrierSurfaceControl.isValid()) {
            if (shouldApply == null || shouldApply.getAsBoolean()) {
                params.getTransaction().apply();
            }
            if (!mInitialized || view == null || !view.isAttachedToWindow()) {
                setCanRelease(true);
            }
            return;
        }
        Transaction t = params.getTransaction();

        mLastSequenceNumber++;
        final int toApplySeqNo = mLastSequenceNumber;
        final ViewRootImpl targetVri = mTargetViewRootImpl;
        final SurfaceControl barrierSurface = mBarrierSurfaceControl;
        setCanRelease(false);
        try {
            targetVri.registerRtFrameCallback(frame -> {
                try {
                    if (shouldApply != null && !shouldApply.getAsBoolean()) {
                        return;
                    }
                    if (!barrierSurface.isValid()) {
                        t.apply();
                        return;
                    }
                    if (LawnchairQuickstepCompat.ATLEAST_S) {
                        targetVri.mergeWithNextTransaction(t, frame);
                    } else {
                        t.apply();
                    }
                } catch (Throwable ignored) {
                    if (shouldApply == null || shouldApply.getAsBoolean()) {
                        try {
                            t.apply();
                        } catch (Throwable ignoredApply) {
                            // Ignore fallback failure on teardown.
                        }
                    }
                } finally {
                    Message.obtain(mApplyHandler, MSG_UPDATE_SEQUENCE_NUMBER, toApplySeqNo, 0)
                            .sendToTarget();
                }
            });
        } catch (Throwable ignored) {
            if (shouldApply == null || shouldApply.getAsBoolean()) {
                t.apply();
            }
            setCanRelease(true);
            return;
        }

        // Make sure a frame gets scheduled.
        view.invalidate();
    }
}
