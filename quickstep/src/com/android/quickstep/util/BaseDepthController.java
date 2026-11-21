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
 * limitations under the License.
 */
package com.android.quickstep.util;

import static android.os.Trace.TRACE_TAG_APP;

import static com.android.launcher3.Flags.enableOverviewBackgroundWallpaperBlur;
import static com.android.launcher3.Flags.enableScalingRevealHomeAnimation;

import android.app.WallpaperManager;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Binder;
import android.os.IBinder;
import android.os.Trace;
import android.util.FloatProperty;
import android.util.Log;
import android.view.CrossWindowBlurListeners;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.app.animation.Interpolators;
import com.android.launcher3.Flags;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.statemanager.StateManager;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.MultiPropertyFactory;
import com.android.launcher3.util.MultiPropertyFactory.MultiProperty;
import com.android.launcher3.views.ScrimView;
import com.android.systemui.shared.system.BlurUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.lawnchair.LawnchairApp;
import app.lawnchair.compat.LawnchairQuickstepCompat;

/**
 * Utility class for applying depth effect
 */
public class BaseDepthController {
    public static final float DEPTH_0_PERCENT = 0f;
    public static final float DEPTH_60_PERCENT = 0.6f;
    public static final float DEPTH_70_PERCENT = 0.7f;

    private static final FloatProperty<BaseDepthController> DEPTH =
            new FloatProperty<BaseDepthController>("depth") {
                @Override
                public void setValue(BaseDepthController depthController, float depth) {
                    depthController.setDepth(depth);
                }

                @Override
                public Float get(BaseDepthController depthController) {
                    return depthController.mDepth;
                }
            };

    private static final int DEPTH_INDEX_STATE_TRANSITION = 0;
    private static final int DEPTH_INDEX_WIDGET = 1;
    private static final int DEPTH_INDEX_COUNT = 2;

    // b/291401432
    private static final String TAG = "BaseDepthController";

    protected final QuickstepLauncher mLauncher;
    /** Property to set the depth for state transition. */
    public final MultiProperty stateDepth;
    /** Property to set the depth for widget picker. */
    public final MultiProperty widgetDepth;

    /**
     * Blur radius when completely zoomed out, in pixels.
     */
    protected final int mMaxBlurRadius;
    protected final WallpaperManager mWallpaperManager;
    protected boolean mCrossWindowBlursEnabled;

    /**
     * Ratio from 0 to 1, where 0 is fully zoomed out, and 1 is zoomed in.
     *
     * @see android.service.wallpaper.WallpaperService.Engine#onZoomChanged(float)
     */
    private float mDepth;

    protected SurfaceControl mBaseSurface;

    protected SurfaceControl mBaseSurfaceOverride;

    // Hints that there is potentially content behind Launcher and that we shouldn't optimize by
    // marking the launcher surface as opaque.  Only used in certain Launcher states.
    private boolean mHasContentBehindLauncher;

    /** Pause blur but allow transparent, can be used when launch something behind the Launcher. */
    protected boolean mPauseBlurs;

    /**
     * Last blur value, in pixels, that was applied.
     */
    protected int mCurrentBlur;
    /**
     * If we requested early wake-up offsets to SurfaceFlinger.
     */
    protected boolean mInEarlyWakeUp;

    protected boolean mWaitingOnSurfaceValidity;

    @Nullable
    protected SurfaceTransactionApplier mSurfaceTransactionApplier;

    private SurfaceControl mBlurSurface = null;
    private SurfaceControl mPendingBlurSurfaceRelease = null;
    private final List<SurfaceControl> mDeferredBlurSurfaceReleases = new ArrayList<>();
    private final AtomicInteger mImmediateApplyGeneration = new AtomicInteger();

    /** Whether a blur {@link RenderEffect} may currently be set on the depth blur targets. */
    private boolean mWorkspaceEffectApplied;
    /** Last workspace blur effect, reused while the radius stays the same. */
    private RenderEffect mWorkspaceBlurEffect;
    private int mWorkspaceBlurEffectRadius;
    private boolean mRendererHintsUnsupported;
    /**
     * Isolated Android 16+ early-wakeup helper so API 31-35 ART never resolves
     * {@code android.gui.EarlyWakeupInfo} on {@link BaseDepthController}.
     */
    @Nullable
    private final EarlyWakeupController mEarlyWakeupController;

    private interface EarlyWakeupController {
        boolean setEarlyWakeup(@NonNull SurfaceControl.Transaction transaction, boolean start);
    }

    private static final class BaklavaEarlyWakeup implements EarlyWakeupController {
        private final android.gui.EarlyWakeupInfo mInfo = new android.gui.EarlyWakeupInfo();

        BaklavaEarlyWakeup() {
            mInfo.token = new Binder();
            mInfo.trace = BaseDepthController.class.getName();
        }

        @Override
        public boolean setEarlyWakeup(
                @NonNull SurfaceControl.Transaction transaction, boolean start) {
            if (start) {
                transaction.setEarlyWakeupStart(mInfo);
            } else {
                transaction.setEarlyWakeupEnd(mInfo);
            }
            return true;
        }
    }

    public BaseDepthController(QuickstepLauncher activity) {
        mLauncher = activity;
        if (Flags.allAppsBlur() || enableOverviewBackgroundWallpaperBlur()) {
            if (Utilities.ATLEAST_S) {
                mCrossWindowBlursEnabled =
                        CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
            } else {
                mCrossWindowBlursEnabled = false;
            }
            mMaxBlurRadius = activity.getResources().getDimensionPixelSize(
                    R.dimen.max_depth_blur_radius_enhanced);
        } else {
            mMaxBlurRadius = activity.getResources().getInteger(R.integer.max_depth_blur_radius);
        }
        mWallpaperManager = activity.getSystemService(WallpaperManager.class);

        MultiPropertyFactory<BaseDepthController> depthProperty =
                new MultiPropertyFactory<>(this, DEPTH, DEPTH_INDEX_COUNT, Float::max);
        stateDepth = depthProperty.get(DEPTH_INDEX_STATE_TRANSITION);
        widgetDepth = depthProperty.get(DEPTH_INDEX_WIDGET);
        mEarlyWakeupController = createEarlyWakeupController();
    }

    @Nullable
    private static EarlyWakeupController createEarlyWakeupController() {
        if (!Utilities.ATLEAST_BAKLAVA || !LawnchairApp.isRecentsEnabled()) {
            return null;
        }
        try {
            return new BaklavaEarlyWakeup();
        } catch (Throwable t) {
            return null;
        }
    }

    public void setSurfaceTransactionApplier(
            @Nullable SurfaceTransactionApplier surfaceTransactionApplier) {
        mSurfaceTransactionApplier = surfaceTransactionApplier;
    }

    /**
     * Returns if cross window blurs are enabled. In other words, whether launcher should use blurs
     * style UI or fallback style UI.
     */
    public boolean isCrossWindowBlursEnabled() {
        return mCrossWindowBlursEnabled;
    }

    protected void setCrossWindowBlursEnabled(boolean isEnabled) {
        if (mCrossWindowBlursEnabled == isEnabled) {
            return;
        }
        mCrossWindowBlursEnabled = isEnabled;
        mLauncher.updateBlurStyle();
        applyDepthAndBlur();
    }

    public void setHasContentBehindLauncher(boolean hasContentBehindLauncher) {
        mHasContentBehindLauncher = hasContentBehindLauncher;
    }

    public void pauseBlursOnWindows(boolean pause) {
        if (mPauseBlurs == pause) {
            return;
        }
        mPauseBlurs = pause;
        applyDepthAndBlur();
    }

    protected void onInvalidSurface() { }

    protected void applyDepthAndBlur() {
        applyDepthAndBlur(null, /* applyImmediately */ false, /* skipSimilarBlur */ true);
    }

    /**
     * Applies depth and blur to the launcher.
     *
     * @param transaction      optional {@link SurfaceTransaction} to apply the blur to.
     * @param applyImmediately whether to apply the blur immediately or defer to the next frame.
     */
    protected void applyDepthAndBlur(@Nullable SurfaceTransaction transaction,
            boolean applyImmediately, boolean skipSimilarBlur) {
        boolean scheduledDeferred = false;
        boolean dispatched = false;
        try {
            int outcome = applyDepthAndBlurInternal(
                    transaction, applyImmediately, skipSimilarBlur);
            dispatched = outcome != DISPATCH_NONE;
            scheduledDeferred = outcome == DISPATCH_DEFERRED;
        } finally {
            if (!dispatched && transaction != null) {
                applyImmediateTransaction(transaction.getTransaction());
            }
            if (scheduledDeferred) {
                deferPendingBlurSurfaceRelease();
            } else {
                releasePendingBlurSurface();
            }
        }
    }

    private static final int DISPATCH_NONE = 0;
    private static final int DISPATCH_IMMEDIATE = 1;
    private static final int DISPATCH_DEFERRED = 2;

    private int applyDepthAndBlurInternal(@Nullable SurfaceTransaction transaction,
            boolean applyImmediately, boolean skipSimilarBlur) {
        float depth = mDepth;
        View rootView = mLauncher.getRootView();
        IBinder windowToken = rootView != null ? rootView.getWindowToken() : null;
        if (windowToken != null && Utilities.ATLEAST_R && mWallpaperManager != null) {
            try {
                if (enableScalingRevealHomeAnimation()) {
                    mWallpaperManager.setWallpaperZoomOut(windowToken, depth);
                } else {
                    // The API's full zoom-out is three times larger than the zoom-out we apply
                    // to the icons. To keep the two consistent throughout the animation while
                    // keeping Launcher's concept of full depth unchanged, we divide by 3 here.
                    mWallpaperManager.setWallpaperZoomOut(windowToken, depth / 3);
                }
            } catch (IllegalArgumentException ignored) {
                // Window token can become invalid during activity teardown.
            }
        }

        ScrimView scrimView = mLauncher.getScrimView();
        boolean hasOpaqueBg = scrimView != null && scrimView.isFullyOpaque();
        boolean isSurfaceOpaque = !mHasContentBehindLauncher && hasOpaqueBg && !mPauseBlurs;

        float blurAmount;
        if (enableScalingRevealHomeAnimation()) {
            blurAmount = mapDepthToBlur(depth);
        } else {
            blurAmount = depth;
        }

        int previousBlur = mCurrentBlur;
        int newBlur = BlurUtils.supportsBlursOnWindows() && mCrossWindowBlursEnabled
                && !hasOpaqueBg && !mPauseBlurs
                ? (int) (blurAmount * mMaxBlurRadius) : 0;
        int delta = Math.abs(newBlur - previousBlur);
        boolean skipUpdate = skipSimilarBlur && delta < Utilities.dpToPx(1) && newBlur != 0
                && previousBlur != 0 && blurAmount != 1f;

        // Always keep mCurrentBlur and workspace RenderEffects in sync with depth, even when the
        // SurfaceControl is temporarily unavailable. Otherwise setDepth(0) can early-return later
        // (same depth) and leave workspace/hotseat stuck blurred until the activity restarts.
        if (!skipUpdate) {
            mCurrentBlur = newBlur;
            blurWorkspaceDepthTargets();
        }

        if (!BlurUtils.supportsBlursOnWindows()) {
            return applyUnconsumedTransaction(transaction, applyImmediately);
        }
        if (mBaseSurface == null) {
            return applyUnconsumedTransaction(transaction, applyImmediately);
        }
        if (!mBaseSurface.isValid()) {
            Log.d(TAG, "mSurface is not valid");
            mWaitingOnSurfaceValidity = true;
            onInvalidSurface();
            return applyUnconsumedTransaction(transaction, applyImmediately);
        }
        mWaitingOnSurfaceValidity = false;

        SurfaceControl blurSurface =
                enableOverviewBackgroundWallpaperBlur()
                        && mBlurSurface != null && mBlurSurface.isValid()
                        ? mBlurSurface
                        : mBaseSurface;

        if (skipUpdate) {
            return applyUnconsumedTransaction(transaction, applyImmediately);
        }
        if (blurSurface == null || !blurSurface.isValid()) {
            return applyUnconsumedTransaction(transaction, applyImmediately);
        }

        SurfaceTransaction finalTransaction =
                transaction != null ? transaction : new SurfaceTransaction();
        return applyBlurTransaction(
                finalTransaction, blurSurface, isSurfaceOpaque, blurAmount, applyImmediately);
    }

    private int applyUnconsumedTransaction(
            @Nullable SurfaceTransaction transaction, boolean applyImmediately) {
        if (transaction == null) {
            return DISPATCH_NONE;
        }
        return dispatchSurfaceTransaction(transaction, applyImmediately);
    }

    private int applyBlurTransaction(
            @NonNull SurfaceTransaction finalTransaction,
            @NonNull SurfaceControl blurSurface,
            boolean isSurfaceOpaque,
            float blurAmount,
            boolean applyImmediately) {
        finalTransaction.forSurface(blurSurface)
                .setBackgroundBlurRadius(mCurrentBlur)
                .setOpaque(isSurfaceOpaque);

        boolean wantsEarlyWakeUp = blurAmount > 0 && blurAmount < 1;
        if (wantsEarlyWakeUp && !mInEarlyWakeUp) {
            setEarlyWakeup(finalTransaction.getTransaction(), true);
        } else if (!wantsEarlyWakeUp && mInEarlyWakeUp) {
            setEarlyWakeup(finalTransaction.getTransaction(), false);
        }

        return dispatchSurfaceTransaction(finalTransaction, applyImmediately);
    }

    private int dispatchSurfaceTransaction(
            @NonNull SurfaceTransaction surfaceTransaction, boolean applyImmediately) {
        if (!applyImmediately
                && mSurfaceTransactionApplier != null
                && LawnchairQuickstepCompat.ATLEAST_S) {
            final int scheduledGen = mImmediateApplyGeneration.get();
            mSurfaceTransactionApplier.scheduleApply(
                    surfaceTransaction, () -> mImmediateApplyGeneration.get() == scheduledGen);
            return DISPATCH_DEFERRED;
        }
        applyImmediateTransaction(surfaceTransaction.getTransaction());
        return DISPATCH_IMMEDIATE;
    }

    /**
     * Applies a synchronous transaction while invalidating any older in-flight RenderThread
     * callbacks from this controller and carrying forward removals of deferred blur surfaces so
     * an async callback can never resurrect a removed layer or strand early-wakeup state.
     */
    private void applyImmediateTransaction(@NonNull SurfaceControl.Transaction transaction) {
        mImmediateApplyGeneration.incrementAndGet();
        try (SurfaceControl.Transaction t = transaction) {
            for (int i = 0; i < mDeferredBlurSurfaceReleases.size(); i++) {
                SurfaceControl deferred = mDeferredBlurSurfaceReleases.get(i);
                if (deferred.isValid()) {
                    t.remove(deferred);
                }
            }
            t.apply();
        }
    }

    /**
     * Sets the early wakeup state.
     *
     * @param inEarlyWakeUp whether SurfaceFlinger's early wakeup timing should be active.
     */
    public void setEarlyWakeup(boolean inEarlyWakeUp) {
        if (mInEarlyWakeUp == inEarlyWakeUp) {
            return;
        }
        if (!LawnchairApp.isRecentsEnabled()) {
            return;
        }
        SurfaceControl.Transaction transaction = createTransaction();
        setEarlyWakeup(transaction, inEarlyWakeUp);
        applyImmediateTransaction(transaction);
    }

    /**
     * Sets the early wakeup state.
     *
     * @param transaction transaction to apply to.
     * @param start whether to start or end the early wakeup.
     */
    protected void setEarlyWakeup(@NonNull SurfaceControl.Transaction transaction, boolean start) {
        if (mInEarlyWakeUp == start) {
            return;
        }
        if (!LawnchairApp.isRecentsEnabled()) {
            return;
        }
        Log.d(TAG, "setEarlyWakeup: " + start);
        if (start) {
            notifyRendererForBlurStart();
            if (mEarlyWakeupController != null) {
                try {
                    if (mEarlyWakeupController.setEarlyWakeup(transaction, true)) {
                        mInEarlyWakeUp = true;
                    }
                } catch (Throwable ignored) {
                    // Hidden SurfaceFlinger hint unavailable; keep mInEarlyWakeUp false so we
                    // never send an unmatched setEarlyWakeupEnd later.
                }
            }
        } else {
            mInEarlyWakeUp = false;
            if (mEarlyWakeupController != null) {
                try {
                    mEarlyWakeupController.setEarlyWakeup(transaction, false);
                } catch (Throwable ignored) {
                    // Hidden SurfaceFlinger hint unavailable.
                }
            }
        }
    }

    private void notifyRendererForBlurStart() {
        if (mRendererHintsUnsupported) {
            return;
        }
        View rootView = mLauncher.getRootView();
        ViewRootImpl viewRootImpl = rootView != null ? rootView.getViewRootImpl() : null;
        if (viewRootImpl == null) {
            return;
        }
        try {
            if (Utilities.ATLEAST_BAKLAVA) {
                try {
                    Trace.instantForTrack(TRACE_TAG_APP, TAG, "notifyRendererForGpuLoadUp");
                } catch (Throwable ignored) {
                    // Trace.instantForTrack is a hidden API; ignore if restricted.
                }
                viewRootImpl.notifyRendererForGpuLoadUp("applyBlur");
                viewRootImpl.notifyRendererOfExpensiveFrame("applyBlur");
            } else if (Utilities.ATLEAST_U) {
                viewRootImpl.notifyRendererOfExpensiveFrame();
            }
        } catch (Throwable ignored) {
            mRendererHintsUnsupported = true;
        }
    }

    /** @return {@code true} if the workspace should be blurred. */
    @VisibleForTesting
    public boolean blurWorkspaceDepthTargets() {
        // RenderEffect / createBlurEffect require API 31+.
        if (!Utilities.ATLEAST_S) {
            return false;
        }
        StateManager<LauncherState, Launcher> stateManager = mLauncher.getStateManager();
        LauncherState settledState = stateManager.getState();

        // Stuck-blur bug: AllAppsState.shouldBlurWorkspace(NORMAL) is true during close, and a
        // skipped/interrupted apply can leave RenderEffect on workspace/hotseat icons after home
        // is settled. Always clear once we are on NORMAL with no active transition, or depth is 0.
        if (!Flags.allAppsBlur()
                || mDepth <= 0f
                || mCurrentBlur <= 0
                || (settledState == LauncherState.NORMAL && !stateManager.isInTransition())) {
            clearWorkspaceRenderEffectsIfApplied();
            return false;
        }

        LauncherState targetState = stateManager.getTargetState() != null
                ? stateManager.getTargetState() : settledState;
        // Only blur workspace if the current state wants to blur based on the target state.
        if (!stateManager.getCurrentStableState().shouldBlurWorkspace(targetState)) {
            clearWorkspaceRenderEffectsIfApplied();
            return false;
        }

        if (mWorkspaceBlurEffect == null || mWorkspaceBlurEffectRadius != mCurrentBlur) {
            mWorkspaceBlurEffect = RenderEffect.createBlurEffect(
                    mCurrentBlur, mCurrentBlur, Shader.TileMode.DECAL);
            mWorkspaceBlurEffectRadius = mCurrentBlur;
        }
        RenderEffect blurEffect = mWorkspaceBlurEffect;
        // Setting the same effect again is a no-op for the view, so this stays cheap per frame
        // and still re-applies it if something else cleared it.
        mLauncher.getDepthBlurTargets().forEach(target -> target.setRenderEffect(blurEffect));
        mWorkspaceEffectApplied = true;
        return true;
    }

    /**
     * Per-frame path: only clears and invalidates the targets if we set a blur on them, so
     * depth changes with no workspace blur (e.g. app open/close) don't redraw workspace/hotseat.
     */
    private void clearWorkspaceRenderEffectsIfApplied() {
        if (mWorkspaceEffectApplied) {
            clearWorkspaceRenderEffects();
        }
    }

    /**
     * Clears {@link RenderEffect} from workspace/hotseat. Public so callers can drop a stuck
     * icon blur without going through SurfaceControl apply.
     */
    public void clearWorkspaceRenderEffects() {
        if (!Utilities.ATLEAST_S) {
            return;
        }
        mWorkspaceEffectApplied = false;
        mLauncher.getDepthBlurTargets().forEach(target -> {
            target.setRenderEffect(null);
            target.invalidate();
        });
    }

    private void setDepth(float depth) {
        depth = Utilities.boundToRange(depth, 0, 1);
        // Depth of the Launcher state we are in or transitioning to.
        float targetStateDepth = mLauncher.getStateManager().getState().getDepth(mLauncher);

        float depthF;
        if (depth == targetStateDepth) {
            // Always apply the target state depth.
            depthF = depth;
        } else {
            // Round out the depth to dedupe frequent, non-perceptable updates
            int depthI = (int) (depth * 256);
            depthF = depthI / 256f;
        }
        if (Float.compare(mDepth, depthF) == 0) {
            // Depth is unchanged, but a previous apply may have returned early (invalid surface)
            // leaving blur stuck. Force a re-apply when clearing.
            if (depthF == 0f && mCurrentBlur != 0) {
                applyDepthAndBlur();
            }
            return;
        }
        mDepth = depthF;
        applyDepthAndBlur();
    }

    /**
     * Sets the lowest surface that should not be blurred.
     * <p>
     * Blur is applied to below {@link #mBaseSurfaceOverride}. When set to {@code null}, blur is
     * applied to below {@link #mBaseSurface}.
     * </p>
     */
    public void setBaseSurfaceOverride(@Nullable SurfaceControl baseSurfaceOverride,
            boolean applyOnDraw) {
        SurfaceControl validOverride =
                baseSurfaceOverride != null && baseSurfaceOverride.isValid()
                        ? baseSurfaceOverride
                        : null;
        if (mBaseSurfaceOverride != validOverride) {
            boolean applyImmediately = mBaseSurfaceOverride != null && validOverride == null
                    && !applyOnDraw;
            mBaseSurfaceOverride = validOverride;
            Log.d(TAG, "setBaseSurfaceOverride: applying blur behind leash " + validOverride);
            SurfaceTransaction transaction = setupBlurSurface();
            applyDepthAndBlur(transaction, applyImmediately, /* skipSimilarBlur */ false);
        }
    }

    private @Nullable SurfaceTransaction setupBlurSurface() {
        SurfaceTransaction transaction = null;
        if (mBaseSurface != null && mBaseSurface.isValid()
                && mBaseSurfaceOverride != null && mBaseSurfaceOverride.isValid()) {
            transaction = new SurfaceTransaction();
            transaction.forSurface(mBaseSurface)
                    .setBackgroundBlurRadius(0)
                    .setOpaque(false);
            if (mBlurSurface == null || !mBlurSurface.isValid()) {
                if (mBlurSurface != null) {
                    mBlurSurface.release();
                }
                mBlurSurface = new SurfaceControl.Builder()
                        .setName("Overview Blur")
                        .setHidden(false)
                        .build();
                Log.d(TAG,
                        "setupBlurSurface: creating Overview Blur surface " + mBlurSurface);
                transaction.forSurface(mBlurSurface).reparent(mBaseSurface);
                Log.d(TAG,
                        "setupBlurSurface: reparenting " + mBlurSurface + " to " + mBaseSurface);
            }
            transaction.forSurface(mBlurSurface).setRelativeLayer(mBaseSurfaceOverride, -1);
            Log.d(TAG, "setupBlurSurface: relayering to leash " + mBaseSurfaceOverride);
        } else {
            if (mBaseSurfaceOverride != null && !mBaseSurfaceOverride.isValid()) {
                mBaseSurfaceOverride = null;
            }
            if (mBlurSurface != null) {
                Log.d(TAG, "setupBlurSurface: removing blur surface " + mBlurSurface);
                SurfaceControl blurToRemove = mBlurSurface;
                mBlurSurface = null;
                if (blurToRemove.isValid()) {
                    transaction = new SurfaceTransaction();
                    transaction.forSurface(blurToRemove).setRemove();
                }
                releasePendingBlurSurface();
                mPendingBlurSurfaceRelease = blurToRemove;
            }
        }
        return transaction;
    }

    protected void releaseBlurSurface() {
        mImmediateApplyGeneration.incrementAndGet();
        SurfaceControl blurToRemove = mBlurSurface;
        mBlurSurface = null;
        if (blurToRemove != null || !mDeferredBlurSurfaceReleases.isEmpty()) {
            try (SurfaceControl.Transaction transaction = createTransaction()) {
                boolean hasRemovals = false;
                if (blurToRemove != null && blurToRemove.isValid()) {
                    transaction.remove(blurToRemove);
                    hasRemovals = true;
                }
                for (int i = 0; i < mDeferredBlurSurfaceReleases.size(); i++) {
                    SurfaceControl deferred = mDeferredBlurSurfaceReleases.get(i);
                    if (deferred.isValid()) {
                        transaction.remove(deferred);
                        hasRemovals = true;
                    }
                }
                if (hasRemovals) {
                    transaction.apply();
                }
            } finally {
                if (blurToRemove != null) {
                    blurToRemove.release();
                }
                for (int i = mDeferredBlurSurfaceReleases.size() - 1; i >= 0; i--) {
                    SurfaceControl deferred = mDeferredBlurSurfaceReleases.remove(i);
                    deferred.release();
                }
            }
        }
        releasePendingBlurSurface();
    }

    private void deferPendingBlurSurfaceRelease() {
        SurfaceControl blurToRelease = mPendingBlurSurfaceRelease;
        if (blurToRelease == null) {
            return;
        }
        mPendingBlurSurfaceRelease = null;
        if (mSurfaceTransactionApplier == null) {
            blurToRelease.release();
            return;
        }
        mDeferredBlurSurfaceReleases.add(blurToRelease);
        mSurfaceTransactionApplier.addOnSafeToReleaseCallback(() -> {
            if (mDeferredBlurSurfaceReleases.remove(blurToRelease)) {
                blurToRelease.release();
            }
        });
    }

    private void releasePendingBlurSurface() {
        if (mPendingBlurSurfaceRelease != null) {
            mPendingBlurSurfaceRelease.release();
            mPendingBlurSurfaceRelease = null;
        }
    }

    /**
     * Sets the specified app target surface to apply the blur to.
     */
    protected void setBaseSurface(SurfaceControl baseSurface) {
        if (mBaseSurface != baseSurface || mWaitingOnSurfaceValidity) {
            mBaseSurface = baseSurface;
            Log.d(TAG, "setSurface:\n\tmWaitingOnSurfaceValidity: " + mWaitingOnSurfaceValidity
                    + "\n\tmBaseSurface: " + mBaseSurface);
            SurfaceTransaction transaction = null;
            if (enableOverviewBackgroundWallpaperBlur()) {
                transaction = setupBlurSurface();
            }
            boolean applyImmediately = baseSurface == null || !baseSurface.isValid();
            applyDepthAndBlur(transaction, applyImmediately,
                    /* skipSimilarBlur */ false);
        }
    }

    /**
     * Maps depth values to blur amounts as a percentage of the max blur.
     * The blur percentage grows linearly with depth, and maxes out at 30% depth.
     */
    private static float mapDepthToBlur(float depth) {
        return Interpolators.clampToProgress(depth, 0, 0.3f);
    }

    private SurfaceControl.Transaction createTransaction() {
        return new SurfaceControl.Transaction();
    }
}
