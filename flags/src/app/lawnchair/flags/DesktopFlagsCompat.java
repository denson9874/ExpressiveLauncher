package app.lawnchair.flags;

import android.window.DesktopExperienceFlags;
import android.window.DesktopModeFlags;

/**
 * Reads {@link DesktopExperienceFlags} and {@link DesktopModeFlags} by name.
 *
 * <p>Android 17 removed enum constants that the Android 16 sources still read. Those flags
 * launched, so the framework dropped them. A direct field read links against the device's
 * framework and throws {@link NoSuchFieldError} there, so the launcher reads them by name and
 * falls back to the launched value. On Android 16 the lookup finds the same constant as before.
 */
public final class DesktopFlagsCompat {

    private DesktopFlagsCompat() {}

    /** {@code DesktopExperienceFlags.<name>.isTrue()}, or {@code whenRemoved} if it is gone. */
    public static boolean experience(String name, boolean whenRemoved) {
        try {
            return DesktopExperienceFlags.valueOf(name).isTrue();
        } catch (IllegalArgumentException e) {
            return whenRemoved;
        }
    }

    /** {@code DesktopModeFlags.<name>.isTrue()}, or {@code whenRemoved} if it is gone. */
    public static boolean mode(String name, boolean whenRemoved) {
        try {
            return DesktopModeFlags.valueOf(name).isTrue();
        } catch (IllegalArgumentException e) {
            return whenRemoved;
        }
    }
}
