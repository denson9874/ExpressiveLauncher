package app.lawnchair.pro

import android.content.Context
import java.security.SecureRandom
import java.util.Locale

object ProDeviceId {

    private const val PREFS_NAME = "expressive_pro_license"
    private const val KEY_DEVICE_ID = "device_install_id"
    private const val CROCKFORD_CHARS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * Get or generate a persistent, unique installation device ID for this device.
     * Formatted as "DEV-XXXX-XXXX" using Crockford Base32.
     */
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing.trim().uppercase(Locale.ROOT)
        }

        val generated = generateRandomDeviceId()
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

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
