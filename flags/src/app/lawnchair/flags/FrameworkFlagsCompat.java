package app.lawnchair.flags;

import android.os.Build;
import android.os.flagging.AconfigPackage;
import android.util.Log;

/**
 * Reads framework aconfig flags that the Android 16 sources read through generated
 * {@code Flags} classes.
 *
 * <p>Android 17 moved those generated classes off the boot classpath (to
 * {@code com.android.internal.hidden_from_bootclasspath}), so calling, for example,
 * {@code android.view.inputmethod.Flags} there throws {@link NoClassDefFoundError}. On Android 17
 * the launcher reads the flag by name instead; on Android 16 it keeps calling the generated class.
 */
public final class FrameworkFlagsCompat {

    private static final String TAG = "FrameworkFlagsCompat";

    private static volatile Boolean sImeSwitcherRevamp;
    private static volatile Boolean sEnableMovingContentIntoPrivateSpace;

    private FrameworkFlagsCompat() {}

    /** {@code android.view.inputmethod.Flags.imeSwitcherRevamp()}. */
    public static boolean imeSwitcherRevamp() {
        Boolean value = sImeSwitcherRevamp;
        if (value == null) {
            value = Build.VERSION.SDK_INT >= 37
                    ? read("android.view.inputmethod", "ime_switcher_revamp", true)
                    : android.view.inputmethod.Flags.imeSwitcherRevamp();
            sImeSwitcherRevamp = value;
        }
        return value;
    }

    /**
     * {@code android.multiuser.Flags.enableMovingContentIntoPrivateSpace()} on Android 16, false
     * when the flag class is missing (some Android 16 builds, and every Android 17 build). Cached,
     * because a missing class is looked up again, and throws again, on every call.
     */
    public static boolean enableMovingContentIntoPrivateSpace() {
        Boolean value = sEnableMovingContentIntoPrivateSpace;
        if (value == null) {
            value = false;
            if (Build.VERSION.SDK_INT < 37) {
                try {
                    value = android.multiuser.Flags.enableMovingContentIntoPrivateSpace();
                } catch (LinkageError e) {
                    Log.w(TAG, "android.multiuser.Flags is missing, assuming false", e);
                }
            }
            sEnableMovingContentIntoPrivateSpace = value;
        }
        return value;
    }

    /** The value of {@code flag} in aconfig package {@code pkg}, or {@code fallback}. */
    public static boolean read(String pkg, String flag, boolean fallback) {
        try {
            return AconfigPackage.load(pkg).getBooleanFlagValue(flag, fallback);
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "Could not read " + pkg + "." + flag + ", assuming " + fallback, e);
            return fallback;
        }
    }
}
