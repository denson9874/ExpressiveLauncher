package dev.launcher.expressive.feed;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import amirz.aidlbridge.IBridge;
import amirz.aidlbridge.IBridgeCallback;

/**
 * Minimal bridge between Expressive Launcher and Google's legacy Discover
 * overlay. The separate process is intentionally debuggable because Google refuses ordinary
 * third-party release clients; the exported entry point remains protected by a signature
 * permission and a second same-certificate Binder check.
 */
public final class ExpressiveFeedBridgeService extends Service {
    private static final String TAG = "ExpressiveFeed";
    private static final String OVERLAY_ACTION = "com.android.launcher3.WINDOW_OVERLAY";
    private static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";

    private final Set<UpstreamConnection> activeConnections = ConcurrentHashMap.newKeySet();

    @Override
    public IBinder onBind(Intent intent) {
        final CallerIdentity caller = CallerIdentity.from(intent != null ? intent.getData() : null);
        if (caller == null) {
            Log.w(TAG, "Rejected bridge bind without a valid app://package:uid identity");
            return null;
        }
        return new BridgeBinder(caller, intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        closeAllConnections();
        return false;
    }

    @Override
    public void onDestroy() {
        closeAllConnections();
        super.onDestroy();
    }

    private boolean isTrustedLauncher(CallerIdentity caller, int binderUid) {
        if (caller.uid != binderUid) {
            return false;
        }
        final PackageManager packageManager = getPackageManager();
        return FeedCallerPolicy.isAllowedCaller(
                packageManager.getPackagesForUid(binderUid),
                packageManager.checkSignatures(binderUid, Process.myUid()),
                caller.packageName);
    }

    private Intent createGoogleOverlayIntent(Intent launcherIntent) {
        final Uri launcherUri = launcherIntent.getData();
        final Uri bridgeUri = launcherUri.buildUpon()
                .encodedAuthority(getPackageName() + ":" + Process.myUid())
                .build();
        return launcherIntent.cloneFilter()
                .setAction(OVERLAY_ACTION)
                .setPackage(GOOGLE_APP)
                .setData(bridgeUri);
    }

    private void closeAllConnections() {
        for (UpstreamConnection connection : activeConnections.toArray(new UpstreamConnection[0])) {
            connection.close();
        }
    }

    private final class BridgeBinder extends IBridge.Stub {
        private final CallerIdentity caller;
        private final Intent googleOverlayIntent;

        BridgeBinder(CallerIdentity caller, Intent launcherIntent) {
            this.caller = caller;
            googleOverlayIntent = createGoogleOverlayIntent(launcherIntent);
        }

        @Override
        public void bindService(IBridgeCallback callback, int requestedFlags) {
            final int binderUid = Binder.getCallingUid();
            if (callback == null || !isTrustedLauncher(caller, binderUid)) {
                throw new SecurityException("Only the same-signed Expressive Launcher may connect");
            }

            final UpstreamConnection connection = new UpstreamConnection(
                    callback,
                    caller.packageName,
                    googleOverlayIntent);
            activeConnections.add(connection);
            if (!connection.connect(FeedCallerPolicy.sanitizeBindFlags(requestedFlags))) {
                connection.reportDisconnected();
                // connect() links to callback death before asking Android to bind. Always close a
                // failed attempt so that death recipient cannot retain an abandoned connection.
                connection.close();
            }
        }
    }

    private final class UpstreamConnection implements ServiceConnection, IBinder.DeathRecipient {
        private final IBridgeCallback callback;
        private final String launcherPackage;
        private final Intent intent;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean deathLinked;
        private volatile boolean bound;

        UpstreamConnection(IBridgeCallback callback, String launcherPackage, Intent intent) {
            this.callback = callback;
            this.launcherPackage = launcherPackage;
            this.intent = intent;
        }

        synchronized boolean connect(int flags) {
            try {
                callback.asBinder().linkToDeath(this, 0);
                deathLinked = true;
                if (closed.get()) {
                    return false;
                }
                bound = getApplicationContext().bindService(intent, this, flags);
                // A callback can die between linkToDeath() and bindService(). Balance a binding
                // created during that race instead of leaving Google connected to a dead client.
                if (closed.get() && bound) {
                    unbindSafely();
                    return false;
                }
                return bound;
            } catch (RemoteException | RuntimeException exception) {
                Log.e(TAG, "Unable to connect Google Discover for " + launcherPackage, exception);
                return false;
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed.get()) {
                return;
            }
            try {
                callback.onServiceConnected(
                        name,
                        new SignatureTransactProxy(
                                ExpressiveFeedBridgeService.this,
                                service,
                                launcherPackage));
            } catch (RemoteException exception) {
                close();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            reportDisconnected(name);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            reportDisconnected(name);
            close();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            reportDisconnected(name);
            close();
        }

        @Override
        public void binderDied() {
            close();
        }

        void reportDisconnected() {
            reportDisconnected(new ComponentName(GOOGLE_APP, "UnavailableOverlayService"));
        }

        void reportDisconnected(ComponentName name) {
            try {
                callback.onServiceDisconnected(name);
            } catch (RemoteException ignored) {
                // The launcher process is already gone.
            }
        }

        synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            activeConnections.remove(this);
            if (deathLinked) {
                callback.asBinder().unlinkToDeath(this, 0);
                deathLinked = false;
            }
            if (bound) {
                unbindSafely();
            }
        }

        private void unbindSafely() {
            try {
                getApplicationContext().unbindService(this);
            } catch (IllegalArgumentException ignored) {
                // Android already tore down the binding.
            }
            bound = false;
        }
    }

    private static final class CallerIdentity {
        final String packageName;
        final int uid;

        private CallerIdentity(String packageName, int uid) {
            this.packageName = packageName;
            this.uid = uid;
        }

        static CallerIdentity from(Uri uri) {
            if (uri == null || !"app".equals(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            final int uid = uri.getPort();
            if (uid <= 0) {
                return null;
            }
            return new CallerIdentity(uri.getHost(), uid);
        }
    }
}
