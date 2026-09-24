package app.lawnchair.pro

enum class LicenseType(val id: Int, val displayName: String) {
    TESTER(1, "Tester Edition"),
    GIVEAWAY(2, "Giveaway VIP"),
    VIP(3, "Lifetime Backer"),
    DEV(4, "Internal Dev"),
    CAKEY(5, "Cakey Edition");

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
    val isExpired: Boolean get() = expiresAt > 0L && (System.currentTimeMillis() / 1000L) > expiresAt

    val isDeviceBound: Boolean get() = recipient.startsWith("device:", ignoreCase = true)
    val boundDeviceId: String? get() = if (isDeviceBound) recipient.substringAfter("device:").trim() else null

    val isAccountBound: Boolean get() = recipient.startsWith("account:", ignoreCase = true)
    val boundAccountEmail: String? get() = if (isAccountBound) recipient.substringAfter("account:").trim() else null

    val isCakey: Boolean get() = type == LicenseType.CAKEY || recipient.contains("Cakey", ignoreCase = true)
}
