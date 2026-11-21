package com.google.android.libraries.launcherclient;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import amirz.aidlbridge.IBridge;
import amirz.aidlbridge.IBridgeCallback;

public class LauncherClientBridge extends IBridgeCallback.Stub implements ServiceConnection {
    private static final String TAG = "LauncherClientBridge";
    private static final String INTERFACE_DESCRIPTOR = "amirz.aidlbridge.IBridge";

    private final BaseClientService mClientService;
    private final int mFlags;
    private ComponentName mConnectionName;

    public LauncherClientBridge(BaseClientService launcherClientService, int flags) {
        mClientService = launcherClientService;
        mFlags = flags;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (service == null) {
            MAIN_EXECUTOR.execute(() -> {
                ComponentName target = mConnectionName != null ? mConnectionName : name;
                mConnectionName = null;
                mClientService.onNullBinding(target);
            });
            return;
        }
        try {
            if (INTERFACE_DESCRIPTOR.equals(service.getInterfaceDescriptor())) {
                IBridge bridge = IBridge.Stub.asInterface(service);
                if (bridge == null) {
                    MAIN_EXECUTOR.execute(() -> mClientService.onNullBinding(name));
                    return;
                }
                try {
                    bridge.bindService(this, mFlags);
                } catch (RemoteException | SecurityException e) {
                    Log.w(TAG, "Failed to bind overlay through bridge", e);
                    MAIN_EXECUTOR.execute(() -> mClientService.onBindingDied(name));
                }
            } else {
                MAIN_EXECUTOR.execute(() -> {
                    mConnectionName = name;
                    mClientService.onServiceConnected(name, service);
                });
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to inspect bridge binder interface", e);
            MAIN_EXECUTOR.execute(() -> mClientService.onBindingDied(name));
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        MAIN_EXECUTOR.execute(() -> {
            ComponentName target = mConnectionName != null ? mConnectionName : name;
            mConnectionName = null;
            mClientService.onServiceDisconnected(target);
        });
    }

    @Override
    public void onBindingDied(ComponentName name) {
        MAIN_EXECUTOR.execute(() -> {
            ComponentName target = mConnectionName != null ? mConnectionName : name;
            mConnectionName = null;
            mClientService.onBindingDied(target);
        });
    }

    @Override
    public void onNullBinding(ComponentName name) {
        MAIN_EXECUTOR.execute(() -> {
            ComponentName target = mConnectionName != null ? mConnectionName : name;
            mConnectionName = null;
            mClientService.onNullBinding(target);
        });
    }
}
