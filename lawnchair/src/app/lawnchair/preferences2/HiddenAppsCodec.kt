package app.lawnchair.preferences2

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.lawnchair.util.kotlinxJson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts hidden component identifiers at rest with a non-exportable app-scoped Keystore key. */
internal class HiddenAppsCodec(context: Context) {

    private val keyAlias = "${context.packageName}.hidden_apps.v1"
    private val secretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::loadOrCreateKey)

    fun encrypt(values: Set<String>): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val plaintext = kotlinxJson.encodeToString(values).encodeToByteArray()
        val ciphertext = cipher.doFinal(plaintext)
        return listOf(
            FORMAT_VERSION,
            cipher.iv.toBase64(),
            ciphertext.toBase64(),
        ).joinToString(SEPARATOR)
    }

    fun decrypt(value: String): Set<String> {
        if (value.isBlank()) return emptySet()
        return runCatching {
            val parts = value.split(SEPARATOR, limit = 3)
            require(parts.size == 3 && parts[0] == FORMAT_VERSION)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, parts[1].fromBase64()),
            )
            kotlinxJson.decodeFromString<Set<String>>(
                cipher.doFinal(parts[2].fromBase64()).decodeToString(),
            )
        }.getOrDefault(emptySet())
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.NO_PADDING)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP or Base64.NO_PADDING)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = "v1"
        const val SEPARATOR = ":"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
