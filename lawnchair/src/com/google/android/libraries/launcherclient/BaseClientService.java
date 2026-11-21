package com.google.android.libraries.launcherclient;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import app.lawnchair.FeedBridge;

public class BaseClientService implements ServiceConnection {
    private static final String TAG = "LauncherClient";
    static final long INITIAL_RECONNECT_DELAY_MS = 1_000L;
    static final long MAX_RECONNECT_DELAY_MS = 60_000L;
    static final int MAX_RECONNECT_ATTEMPTS = 6;

    private boolean mConnected;
    private boolean mExplicitlyDisconnected;
    private final Context mContext;
    private final int mFlags;
    private ServiceConnection mConnection;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private int mReconnectAttempts = 0;
    private boolean mReconnectScheduled = false;
    private final Runnable mReconnectRunnable = () -> {
        mReconnectScheduled = false;
        if (shouldAutoReconnect() && !connect()) {
            scheduleReconnect();
        }
    };

    BaseClientService(Context context, int flags) {
        mContext = context;
        mFlags = flags;
    }

    public final boolean connect() {
        mExplicitlyDisconnected = false;
        cancelScheduledReconnect();
        if (!mConnected) {
            try {
                FeedBridge.ConnectionInfo connectionInfo =
                        FeedBridge.Companion.getInstance(mContext).resolveConnection();
                if (connectionInfo == null) {
                    return false;
                }

                // Resolve the provider on every connect. The old immutable connection choice kept
                // targeting Google after a compatible bridge was installed while Launcher lived.
                ServiceConnection connection = connectionInfo.getUseBridge()
                        ? new LauncherClientBridge(this, mFlags)
                        : this;
                Intent intent = FeedBridge.createOverlayIntent(
                        mContext, connectionInfo.getPackageName());
                mConnected = mContext.bindService(intent, connection, mFlags);
                mConnection = mConnected ? connection : null;
            } catch (SecurityException e) {
                Log.e(TAG, "Permission denied while connecting to overlay service", e);
                mConnected = false;
                mConnection = null;
            } catch (Throwable e) {
                Log.e(TAG, "Unable to connect to overlay service", e);
                mConnected = false;
                mConnection = null;
            }
        }
        return mConnected;
    }

    public final void disconnect() {
        mExplicitlyDisconnected = true;
        resetReconnectBackoff();
        unbindActiveConnection();
    }

    private void unbindActiveConnection() {
        if (mConnected && mConnection != null) {
            try {
                mContext.unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                // Package replacement can tear down the binding before its broadcast is handled.
                Log.w(TAG, "Overlay service was already disconnected", e);
            }
        }
        mConnected = false;
        mConnection = null;
    }

    protected boolean shouldAutoReconnect() {
        return !mExplicitlyDisconnected && !mConnected;
    }

    protected final void resetReconnectBackoff() {
        mReconnectAttempts = 0;
        cancelScheduledReconnect();
    }

    protected final void cancelScheduledReconnect() {
        if (mReconnectScheduled) {
            mMainHandler.removeCallbacks(mReconnectRunnable);
            mReconnectScheduled = false;
        }
    }

    protected final void scheduleReconnect() {
        if (mReconnectScheduled || !shouldAutoReconnect()) {
            return;
        }
        if (mReconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Overlay reconnect backoff attempts exhausted (" + mReconnectAttempts + ")");
            return;
        }
        long delayMs = computeReconnectDelayMs(mReconnectAttempts);
        mReconnectAttempts++;
        mReconnectScheduled = true;
        mMainHandler.postDelayed(mReconnectRunnable, delayMs);
    }

    public static long computeReconnectDelayMs(int attempt) {
        int clampedAttempt = Math.max(0, Math.min(attempt, 16));
        long delay = INITIAL_RECONNECT_DELAY_MS << clampedAttempt;
        return Math.min(delay, MAX_RECONNECT_DELAY_MS);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        resetReconnectBackoff();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
    }

    @Override
    public void onBindingDied(ComponentName name) {
        Log.w(TAG, "Overlay service binding died: " + name);
        handleDeadBinding(name);
    }

    @Override
    public void onNullBinding(ComponentName name) {
        Log.w(TAG, "Overlay service returned null binding: " + name);
        handleDeadBinding(name);
    }

    private void handleDeadBinding(ComponentName name) {
        unbindActiveConnection();
        onServiceDisconnected(name);
        scheduleReconnect();
    }
}
