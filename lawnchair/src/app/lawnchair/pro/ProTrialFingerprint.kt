package app.lawnchair.pro

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/**
 * One-trial-per-device fingerprint: SHA-256 of the app-scoped Android ID and the package name.
 *
 * Android ID (SSAID) is scoped to this app's signing key and user, and survives reinstalls and data
 * clears, so a trial can't be restarted by clearing data. Only this hash is sent to the activation
 * service; the raw identifier never leaves the device.
 */
object ProTrialFingerprint {

    @SuppressLint("HardwareIds")
    fun compute(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        // Without an Android ID, fall back to the per-install ID (resettable, but still one per install).
        val source = androidId?.takeIf { it.isNotBlank() } ?: ProDeviceId.get(context)
        return hash(source, context.packageName)
    }

    fun hash(deviceIdentifier: String, packageName: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$deviceIdentifier:$packageName".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
