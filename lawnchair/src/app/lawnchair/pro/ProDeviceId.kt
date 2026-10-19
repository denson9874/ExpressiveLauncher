package app.lawnchair.pro

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

object ProDeviceId {

    private const val PREFS_NAME = "expressive_pro_license"
    private const val KEY_DEVICE_ID = "device_install_id"
    private const val CROCKFORD_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Android ID that some old devices report for every unit; not an identifier. */
    private const val SHARED_EMULATOR_ANDROID_ID = "9774d56d682e549c"

    /**
     * Get or generate a persistent installation device ID for this device.
     * Formatted as "DEV-XXXX-XXXX" using Crockford Base32.
     *
     * A new ID is derived from the app-scoped Android ID, so reinstalling or clearing data gives the
     * same ID back and device-bound keys keep working. A factory reset still changes it; the activation
     * service then moves the license ([ProManager.activateOrMove]). An ID already saved is kept as is.
     */
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing.trim().uppercase(Locale.ROOT)
        }

        val generated = fromAndroidId(androidId(context), context.packageName) ?: generateRandomDeviceId()
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    /**
     * Device ID for [androidId], or null when there is no usable one. Salted differently from
     * [ProTrialFingerprint] so the two values can't be matched to each other.
     */
    fun fromAndroidId(androidId: String?, packageName: String): String? {
        if (androidId.isNullOrBlank() || androidId.equals(SHARED_EMULATOR_ANDROID_ID, ignoreCase = true)) {
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("expressive-device-id:$androidId:$packageName".toByteArray(Charsets.UTF_8))
        // The first 40 bits as eight Crockford characters.
        var bits = 0L
        for (i in 0 until 5) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
        val chars = CharArray(8) { i -> CROCKFORD_CHARS[((bits shr (35 - 5 * i)) and 0x1F).toInt()] }
        return "DEV-${String(chars, 0, 4)}-${String(chars, 4, 4)}"
    }

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    private fun generateRandomDeviceId(): String {
        val random = SecureRandom()
        val part1 = StringBuilder(4)
        val part2 = StringBuilder(4)
        for (i in 0 until 4) {
            part1.append(CROCKFORD_CHARS[random.nextInt(CROCKFORD_CHARS.length)])
            part2.append(CROCKFORD_CHARS[random.nextInt(CROCKFORD_CHARS.length)])
        }
        return "DEV-$part1-$part2"
    }
}
