package com.google.android.libraries.launcherclient;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import app.lawnchair.FeedBridge;
import java.lang.ref.WeakReference;

public class LauncherClient {
    private static final String TAG = "LauncherClient";
    private static int apiVersion = -1;

    public static float clampScrollProgress(float progress) {
        if (Float.isNaN(progress) || progress <= 0f) {
            return 0f;
        }
        return Math.min(1f, progress);
    }

    private ILauncherOverlay mOverlay;
    public final IScrollCallback mScrollCallback;

    public final BaseClientService mBaseService;
    public final LauncherClientService mLauncherService;

    public final BroadcastReceiver feedPackageListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String changedPackage = intent.getData() != null
                    ? intent.getData().getSchemeSpecificPart()
                    : null;
            if (FeedBridge.Companion.getInstance(context)
                    .shouldReconnectForPackage(changedPackage)) {
                reconnect();
            }
        }
    };

    private int mActivityState = 0;
    int mServiceState = 0;
    public int mFlags;

    public LayoutParams mLayoutParams;
    public OverlayCallback mOverlayCallback;
    public final Activity mActivity;

    public boolean mDestroyed = false;
    private Bundle mLayoutBundle;

    public static class OverlayCallback extends ILauncherOverlayCallback.Stub implements Callback {
        public LauncherClient mClient;
        private final Handler mUIHandler = new Handler(Looper.getMainLooper(), this);
        public Window mWindow;
        private boolean mWindowHidden = false;
        public WindowManager mWindowManager;
        int mWindowShift;

        public void destroy() {
            mClient = null;
            mWindow = null;
            mWindowManager = null;
            mUIHandler.removeCallbacksAndMessages(null);
        }

        @Override
        public final void overlayScrollChanged(float f) {
            float clamped = clampScrollProgress(f);
            mUIHandler.removeMessages(2);
            Message.obtain(mUIHandler, 2, clamped).sendToTarget();
            if (clamped > 0f && mWindowHidden) {
                mWindowHidden = false;
            }
        }

        @Override
        public final void overlayStatusChanged(int i) {
            Message.obtain(mUIHandler, 4, i, 0).sendToTarget();
        }

        @Override
        public boolean handleMessage(@NonNull Message message) {
            if (mClient == null) {
                return true;
            }

            switch (message.what) {
                case 2:
                    if ((mClient.mServiceState & 1) != 0) {
                        float floatValue = clampScrollProgress((float) message.obj);
                        mClient.mScrollCallback.onOverlayScrollChanged(floatValue);
                    }
                    return true;
                case 3:
                    if (mWindow == null || mWindowManager == null) {
                        return true;
                    }
                    WindowManager.LayoutParams attributes = mWindow.getAttributes();
                    if ((Boolean) message.obj) {
                        attributes.x = mWindowShift;
                        attributes.flags |= 512;
                    } else {
                        attributes.x = 0;
                        attributes.flags &= -513;
                    }
                    mWindowManager.updateViewLayout(mWindow.getDecorView(), attributes);
                    return true;
                case 4:
                    mClient.setServiceState(message.arg1);
                    if (mClient.mScrollCallback instanceof ISerializableScrollCallback) {
                        ((ISerializableScrollCallback) mClient.mScrollCallback).setPersistentFlags(message.arg1);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    public LauncherClient(Activity activity, IScrollCallback scrollCallback, StaticInteger flags) {
        mActivity = activity;
        mScrollCallback = scrollCallback;
        mBaseService = new BaseClientService(activity, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        mFlags = flags.mData;

        mLauncherService = LauncherClientService.getInstance(activity);
        mLauncherService.mClient = new WeakReference<>(this);
        mOverlay = mLauncherService.mOverlay;

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addDataScheme("package");
        // A feed companion can be installed, upgraded, or removed while Launcher remains alive.
        // Listen for each transition and let FeedBridge filter to relevant provider packages.
        ContextCompat.registerReceiver(mActivity, feedPackageListener, intentFilter,
                ContextCompat.RECEIVER_EXPORTED);

        if (apiVersion <= 0) {
            loadApiVersion(activity);
        }

        connect();
        if (mActivity.getWindow() != null &&
                mActivity.getWindow().peekDecorView() != null &&
                mActivity.getWindow().peekDecorView().isAttachedToWindow()) {
            onAttachedToWindow();
        }
    }

    public void setEnableFeed(boolean enable) {
        if (enable) {
            mFlags |= 1;
        } else {
            mFlags &= ~1;
        }
    }

    public final void onAttachedToWindow() {
        if (!mDestroyed) {
            setLayoutParams(mActivity.getWindow().getAttributes());
        }
    }

    public final void onDetachedFromWindow() {
        if (!mDestroyed) {
            setLayoutParams(null);
        }
    }

    public final void onResume() {
        if (!mDestroyed) {
            mActivityState |= 2;
            if (mOverlay == null) {
                mLauncherService.setStopped(false);
                connect();
            }
            if (mOverlay != null && mLayoutParams != null) {
                try {
                    if (apiVersion < 4) {
                        mOverlay.onResume();
                    } else {
                        mOverlay.setActivityState(mActivityState);
                    }
                } catch (RemoteException e) {
                    handleRemoteException("onResume", e);
                }
            }
        }
    }

    public final void onPause() {
        if (!mDestroyed) {
            mActivityState &= -3;
            if (mOverlay != null && mLayoutParams != null) {
                try {
                    if (apiVersion < 4) {
                        mOverlay.onPause();
                    } else {
                        mOverlay.setActivityState(mActivityState);
                    }
                } catch (RemoteException e) {
                    handleRemoteException("onPause", e);
                }
            }
        }
    }

    public final void onStart() {
        if (!mDestroyed) {
            mLauncherService.setStopped(false);
            connect();
            mActivityState |= 1;
            if (mOverlay != null && mLayoutParams != null) {
                try {
                    mOverlay.setActivityState(mActivityState);
                } catch (RemoteException e) {
                    handleRemoteException("onStart", e);
                }
            }
        }
    }

    public final void onStop() {
        if (!mDestroyed) {
            mLauncherService.setStopped(true);
            mBaseService.disconnect();
            mActivityState &= -2;
            if (!(mOverlay == null || mLayoutParams == null)) {
                try {
                    mOverlay.setActivityState(mActivityState);
                } catch (RemoteException e) {
                    handleRemoteException("onStop", e);
                }
            }
        }
    }

    public void onDestroy() {
        mDestroyed = true;
        try {
            mActivity.unregisterReceiver(feedPackageListener);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Feed package receiver was already unregistered", e);
        }
        if (mOverlayCallback != null) {
            mOverlayCallback.destroy();
            mOverlayCallback = null;
        }
        if (mLauncherService.mClient != null && mLauncherService.mClient.get() == this) {
            mLauncherService.mClient = null;
        }
        mBaseService.disconnect();
    }

    private void connect() {
        if (!mDestroyed && (!mLauncherService.connect() || !mBaseService.connect())) {
            mActivity.runOnUiThread(() -> setServiceState(0));
        }
    }

    public void reconnect() {
        mBaseService.disconnect();
        mLauncherService.disconnect();
        mLauncherService.clearOverlay();
        LauncherClient.loadApiVersion(mActivity);
        if ((mActivityState & 2) != 0) {
            connect();
        }
    }

    public final void setLayoutParams(LayoutParams layoutParams) {
        if (mLayoutParams != layoutParams) {
            mLayoutParams = layoutParams;
            if (mLayoutParams != null) {
                exchangeConfig();
            } else if (mOverlay != null) {
                try {
                    mOverlay.windowDetached(mActivity.isChangingConfigurations());
                } catch (RemoteException e) {
                    Log.w(TAG, "Overlay IPC failed during windowDetached", e);
                }
                mOverlay = null;
            }
        }
    }

    public final void exchangeConfig() {
        if (mOverlay != null) {
            try {
                if (mOverlayCallback == null) {
                    mOverlayCallback = new OverlayCallback();
                }
                OverlayCallback overlayCallback = mOverlayCallback;
                overlayCallback.mClient = this;
                overlayCallback.mWindowManager = mActivity.getWindowManager();
                Point point = new Point();
                overlayCallback.mWindowManager.getDefaultDisplay().getRealSize(point);
                overlayCallback.mWindowShift = -Math.max(point.x, point.y);
                overlayCallback.mWindow = mActivity.getWindow();
                if (apiVersion < 3) {
                    mOverlay.windowAttached(mLayoutParams, mOverlayCallback, mFlags);
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable("layout_params", mLayoutParams);
                    bundle.putParcelable("configuration", mActivity.getResources().getConfiguration());
                    bundle.putInt("client_options", mFlags);
                    if (mLayoutBundle != null) {
                        bundle.putAll(mLayoutBundle);
                    }
                    mOverlay.windowAttached2(bundle, mOverlayCallback);
                }
                if (apiVersion >= 4) {
                    mOverlay.setActivityState(mActivityState);
                } else if ((mActivityState & 2) != 0) {
                    mOverlay.onResume();
                } else {
                    mOverlay.onPause();
                }
            } catch (RemoteException e) {
                handleRemoteException("exchangeConfig", e);
            }
        }
    }

    private boolean isConnected() {
        return mOverlay != null;
    }

    public final void startScroll() {
        if (isConnected()) {
            try {
                mOverlay.startScroll();
            } catch (RemoteException e) {
                handleRemoteException("startScroll", e);
            }
        }
    }

    public final void endScroll() {
        if (isConnected()) {
            try {
                mOverlay.endScroll();
            } catch (RemoteException e) {
                handleRemoteException("endScroll", e);
            }
        }
    }

    public final void setScroll(float f) {
        if (isConnected()) {
            try {
                mOverlay.onScroll(clampScrollProgress(f));
            } catch (RemoteException e) {
                handleRemoteException("setScroll", e);
            }
        }
    }

    private int verifyAndGetAnimationFlags(int duration) {
        if ((duration <= 0) || (duration > 2047)) {
            throw new IllegalArgumentException("Invalid duration");
        }
        return 0x1 | duration << 2;
    }

    public final void hideOverlay(boolean feedRunning) {
        if (mOverlay != null) {
            try {
                mOverlay.closeOverlay(feedRunning ? 1 : 0);
            } catch (RemoteException e) {
                handleRemoteException("hideOverlay(boolean)", e);
            }
        }
    }

    public final void hideOverlay(int duration) {
        if (mOverlay != null) {
            try {
                mOverlay.closeOverlay(verifyAndGetAnimationFlags(duration));
            } catch (RemoteException e) {
                handleRemoteException("hideOverlay(int)", e);
            }
        }
    }

    // Only used for accessibility
    public final void showOverlay(boolean feedRunning) {
        if (mOverlay != null) {
            try {
                mOverlay.openOverlay(feedRunning ? 1 : 0);
            } catch (RemoteException e) {
                handleRemoteException("showOverlay", e);
            }
        }
    }

    private void handleRemoteException(String action, RemoteException e) {
        Log.w(TAG, "Overlay IPC failed during " + action, e);
        if (e instanceof DeadObjectException) {
            setOverlay(null);
            mLauncherService.clearOverlay();
        }
    }

    public final boolean startSearch(byte[] bArr, Bundle bundle) {
        if (apiVersion >= 6 && mOverlay != null) {
            try {
                return mOverlay.startSearch(bArr, bundle);
            } catch (Throwable e) {
                Log.e("DrawerOverlayClient", "Error starting session for search", e);
            }
        }
        return false;
    }

    public final void redraw(Bundle layoutBundle) {
        mLayoutBundle = layoutBundle;
        if (mLayoutParams != null && apiVersion >= 7) {
            exchangeConfig();
        }
    }

    public final void redraw() {
        if (mLayoutParams != null && apiVersion >= 7) {
            exchangeConfig();
        }
    }

    final void setOverlay(ILauncherOverlay overlay) {
        mOverlay = overlay;
        if (mOverlay == null) {
            setServiceState(0);
        } else if (mLayoutParams != null) {
            exchangeConfig();
        }
    }

    void setServiceState(int serviceState) {
        if (mServiceState != serviceState) {
            mServiceState = serviceState;
            mScrollCallback.onServiceStateChanged((serviceState & 1) != 0);
        }
    }

    private static void loadApiVersion(Context context) {
        ResolveInfo resolveService = context.getPackageManager().resolveService(
                FeedBridge.createOverlayIntent(context, FeedBridge.GOOGLE_APP_PACKAGE),
                PackageManager.GET_META_DATA);
        apiVersion = resolveService == null || resolveService.serviceInfo.metaData == null ?
                1 :
                resolveService.serviceInfo.metaData.getInt("service.api.version", 1);
    }
}
