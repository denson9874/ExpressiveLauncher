package app.lawnchair.pro

enum class LicenseType(val id: Int, val displayName: String) {
    TESTER(1, "Tester Edition"),
    GIVEAWAY(2, "Giveaway VIP"),
    VIP(3, "Lifetime Backer"),
    DEV(4, "Internal Dev"),
    CAKEY(5, "Cakey Edition"),
    TRIAL(6, "Pro Trial");

    companion object {
        fun fromId(id: Int): LicenseType = values().firstOrNull { it.id == id } ?: TESTER
    }
}

data class ProLicenseDetails(
    val type: LicenseType,
    val recipient: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val features: Long,
    val rawKeyCode: String,
) {
    val isLifetime: Boolean get() = expiresAt == 0L
    val isExpired: Boolean get() = isExpiredAt(System.currentTimeMillis() / 1000L)

    fun isExpiredAt(nowSeconds: Long): Boolean = expiresAt > 0L && nowSeconds > expiresAt

    val isTrial: Boolean get() = type == LicenseType.TRIAL

    /** Whole days left before a time-limited license ends (rounded up), or null for lifetime keys. */
    fun daysLeftAt(nowSeconds: Long): Int? =
        if (isLifetime) null else maxOf(0L, (expiresAt - nowSeconds + 86_399L) / 86_400L).toInt()

    val isDeviceBound: Boolean get() = recipient.startsWith("device:", ignoreCase = true)
    val boundDeviceId: String? get() = if (isDeviceBound) recipient.substringAfter("device:").trim() else null

    val isAccountBound: Boolean get() = recipient.startsWith("account:", ignoreCase = true)
    val boundAccountEmail: String? get() = if (isAccountBound) recipient.substringAfter("account:").trim() else null

    val isCakey: Boolean get() = type == LicenseType.CAKEY || recipient.contains("Cakey", ignoreCase = true)
}
