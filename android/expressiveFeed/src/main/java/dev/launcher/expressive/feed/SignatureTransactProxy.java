package dev.launcher.expressive.feed;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/**
 * Relays overlay Binder transactions as the companion process while rejecting every caller that
 * is not both an Expressive Launcher package and signed by the same certificate.
 */
final class SignatureTransactProxy extends Binder {
    private final Context context;
    private final IBinder target;
    private final String claimedLauncherPackage;

    SignatureTransactProxy(Context context, IBinder target, String claimedLauncherPackage) {
        this.context = context.getApplicationContext();
        this.target = target;
        this.claimedLauncherPackage = claimedLauncherPackage;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        final int callingUid = Binder.getCallingUid();
        final PackageManager packageManager = context.getPackageManager();
        final boolean allowed = FeedCallerPolicy.isAllowedCaller(
                packageManager.getPackagesForUid(callingUid),
                packageManager.checkSignatures(callingUid, Process.myUid()),
                claimedLauncherPackage);
        if (!allowed) {
            throw new SecurityException("Caller is not an authorized Expressive Launcher");
        }

        // Ensure Google observes this deliberately debuggable bridge as the Binder caller rather
        // than the non-debuggable launcher whose private overlay request it would reject.
        final long identity = Binder.clearCallingIdentity();
        try {
            return target.transact(code, data, reply, flags);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
