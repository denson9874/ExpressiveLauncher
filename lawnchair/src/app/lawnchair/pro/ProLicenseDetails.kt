package app.lawnchair.pro

enum class LicenseType(val id: Int, val displayName: String) {
    TESTER(1, "Tester Edition"),
    GIVEAWAY(2, "Giveaway VIP"),
    VIP(3, "Lifetime Backer"),
    DEV(4, "Internal Dev");

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
}
