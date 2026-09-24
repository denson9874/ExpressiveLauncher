package app.lawnchair.pro

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object ProLicenseVerifier {

    /**
     * Master ECDSA P-256 (secp256r1) Public Key in X.509 SubjectPublicKeyInfo DER format (Base64).
     * The private key is held exclusively by the project owner and is NEVER committed or distributed.
     */
    const val PRO_PUBLIC_KEY_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEQ2IKJ1Abq8srwhR39mznSCNHhNbC5RUmM3n51GIrhMZPAOEQwMj8KZ9GVn8S2a9oQ8pJO8nBSfwRkNUbV0PdRA=="

    private val publicKey: PublicKey by lazy {
        val keyBytes = Base64.decode(PRO_PUBLIC_KEY_BASE64, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private const val MAGIC_0 = 'E'.code.toByte()
    private const val MAGIC_1 = 'P'.code.toByte()
    private const val CURRENT_VERSION: Byte = 1

    private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val DECODE_MAP = IntArray(128) { -1 }.apply {
        CROCKFORD_ALPHABET.forEachIndexed { index, c ->
            this[c.code] = index
        }
        // Crockford normalization for common typos
        this['O'.code] = 0
        this['o'.code] = 0
        this['I'.code] = 1
        this['i'.code] = 1
        this['L'.code] = 1
        this['l'.code] = 1
    }

    /**
     * Verify and parse an offline Pro license key.
     *
     * @param rawKey The license key string (e.g. "EXPR-PRO-XXXX-XXXX-...")
     * @return Result containing [ProLicenseDetails] on success, or an exception describing the failure.
     */
    fun verify(rawKey: String): Result<ProLicenseDetails> = runCatching {
        var clean = rawKey.trim().uppercase()
        if (clean.startsWith("EXPR-PRO-")) {
            clean = clean.removePrefix("EXPR-PRO-")
        } else if (clean.startsWith("EXPR-")) {
            clean = clean.removePrefix("EXPR-")
        }
        clean = clean.replace("-", "").replace(" ", "")

        val rawBytes = decodeCrockford(clean)
        require(rawBytes.size >= 17 + 1 + 64 + 2) { "Key is too short to be a valid Expressive Pro license" }

        val dataWithoutCrc = rawBytes.copyOfRange(0, rawBytes.size - 2)
        val expectedCrc = ((rawBytes[rawBytes.size - 2].toInt() and 0xFF) shl 8) or
            (rawBytes[rawBytes.size - 1].toInt() and 0xFF)
        val actualCrc = crc16Ccitt(dataWithoutCrc)
        require(actualCrc == expectedCrc) { "Checksum failed. The key contains a typo or was corrupted." }

        require(rawBytes[0] == MAGIC_0 && rawBytes[1] == MAGIC_1) { "Invalid license magic header" }
        require(rawBytes[2] == CURRENT_VERSION) { "Unsupported license schema version: ${rawBytes[2]}" }

        val typeId = rawBytes[3].toInt() and 0xFF
        val licenseType = LicenseType.fromId(typeId)

        val buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val issuedAt = buffer.int.toLong() and 0xFFFFFFFFL
        val expiresAt = buffer.int.toLong() and 0xFFFFFFFFL
        val features = buffer.int.toLong() and 0xFFFFFFFFL
        val recipLen = buffer.get().toInt() and 0xFF

        val payloadLen = 17 + recipLen
        require(dataWithoutCrc.size >= payloadLen + 1) { "Malformed license payload length" }

        val recipBytes = ByteArray(recipLen)
        buffer.get(recipBytes)
        val recipient = String(recipBytes, Charsets.UTF_8)

        val sigLen = buffer.get().toInt() and 0xFF
        require(dataWithoutCrc.size >= payloadLen + 1 + sigLen) { "Malformed license signature length" }
        val sigBytes = ByteArray(sigLen)
        buffer.get(sigBytes)

        val payloadBytes = rawBytes.copyOfRange(0, payloadLen)

        // Asymmetric cryptographic signature verification
        val ecdsa = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey)
            update(payloadBytes)
        }
        val isSignatureValid = ecdsa.verify(sigBytes)
        require(isSignatureValid) { "Invalid cryptographic signature. Key was not issued by the owner." }

        val details = ProLicenseDetails(
            type = licenseType,
            recipient = recipient,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            features = features,
            rawKeyCode = rawKey.trim(),
        )

        require(!details.isExpired) { "This license key expired on ${formatDate(expiresAt)}." }

        details
    }

    private fun decodeCrockford(s: String): ByteArray {
        var bitBuf = 0
        var bitsInBuf = 0
        val out = mutableListOf<Byte>()

        for (c in s) {
            val code = c.code
            if (code >= 128) continue
            val v = DECODE_MAP[code]
            if (v == -1) continue
            bitBuf = (bitBuf shl 5) or v
            bitsInBuf += 5
            while (bitsInBuf >= 8) {
                bitsInBuf -= 8
                out.add(((bitBuf shr bitsInBuf) and 0xFF).toByte())
            }
        }
        if (bitsInBuf > 0) {
            val paddingMask = (1 shl bitsInBuf) - 1
            require((bitBuf and paddingMask) == 0) { "Invalid padding bits in license key" }
        }
        return out.toByteArray()
    }

    fun crc16Ccitt(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            for (bit in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    private fun formatDate(timestampSeconds: Long): String {
        val dt = java.util.Date(timestampSeconds * 1000L)
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(dt)
    }
}
