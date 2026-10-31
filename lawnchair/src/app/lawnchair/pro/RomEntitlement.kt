package app.lawnchair.pro

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Pro comes included when the launcher runs on ExpressiveOS. The check is the platform signing
 * certificate (the "android" package), not a system property or the system-app flag: root users
 * can set props with resetprop (and integrity modules delete ro.evolution.*), and any APK can be
 * made a system app with a module. A GitHub or Play update over the bundled copy keeps Pro.
 */
object RomEntitlement {

    /** SHA-256 of ExpressiveOS release platform certificates (vendor/evolution-priv/keys/platform.x509.pem). */
    val EXPRESSIVE_OS_PLATFORM_CERTS = setOf(
        "be94c373419142830cb0956d1293081ba99696423dbe0b7c97bda4f698e51f48",
    )

    /** Platform certificate digests to report instead of the real ones; unit tests only. */
    @androidx.annotation.VisibleForTesting
    @Volatile
    var platformCertDigestsForTests: Set<String>? = null

    fun isIncluded(context: Context): Boolean = platformCertDigests(context).any { it in EXPRESSIVE_OS_PLATFORM_CERTS }

    private fun platformCertDigests(context: Context): Set<String> {
        platformCertDigestsForTests?.let { return it }
        val signingInfo = try {
            context.packageManager
                .getPackageInfo("android", PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } ?: return emptySet()
        val certs = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        } ?: return emptySet()
        return certs.mapTo(mutableSetOf()) { sha256Hex(it.toByteArray()) }
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
