package com.google.android.libraries.launcherclient;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import app.lawnchair.FeedBridge;

public class BaseClientService implements ServiceConnection {
    private boolean mConnected;
    private final Context mContext;
    private final int mFlags;
    private ServiceConnection mConnection;

    BaseClientService(Context context, int flags) {
        mContext = context;
        mFlags = flags;
    }

    public final boolean connect() {
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
            } catch (Throwable e) {
                Log.e("LauncherClient", "Unable to connect to overlay service", e);
                mConnection = null;
            }
        }
        return mConnected;
    }

    public final void disconnect() {
        if (mConnected && mConnection != null) {
            try {
                mContext.unbindService(mConnection);
            } catch (IllegalArgumentException e) {
                // Package replacement can tear down the binding before its broadcast is handled.
                Log.w("LauncherClient", "Overlay service was already disconnected", e);
            }
            mConnected = false;
            mConnection = null;
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
    }
}
