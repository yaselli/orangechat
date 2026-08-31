/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts locally persisted credentials with a non-exportable Android Keystore key.
 *
 * [associatedData] binds a ciphertext to its storage field, so moving an encrypted value
 * from one preference/column to another makes authentication fail instead of silently
 * changing the meaning of the secret.
 */
object SecretCrypto {
    private const val KEY_ALIAS = "orangechat_local_secrets_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val GCM_TAG_LENGTH_BITS = 128

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    fun encrypt(plaintext: String?, associatedData: String): String? {
        if (plaintext == null || plaintext.isEmpty()) return plaintext

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val envelope = ByteArray(1 + cipher.iv.size + ciphertext.size).also { output ->
            output[0] = cipher.iv.size.toByte()
            cipher.iv.copyInto(output, destinationOffset = 1)
            ciphertext.copyInto(output, destinationOffset = 1 + cipher.iv.size)
        }
        return PREFIX + Base64.encodeToString(envelope, Base64.NO_WRAP)
    }

    fun decrypt(storedValue: String?, associatedData: String): String? {
        if (storedValue == null || !isEncrypted(storedValue)) return storedValue

        val envelope = Base64.decode(storedValue.removePrefix(PREFIX), Base64.NO_WRAP)
        require(envelope.isNotEmpty()) { "Encrypted secret envelope is empty" }
        val ivLength = envelope[0].toInt() and 0xff
        require(ivLength in 12 until envelope.size) { "Encrypted secret envelope has an invalid IV" }

        val iv = envelope.copyOfRange(1, 1 + ivLength)
        val ciphertext = envelope.copyOfRange(1 + ivLength, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        }
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return synchronized(this) {
            val refreshedKeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (refreshedKeyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .setRandomizedEncryptionRequired(true)
                            .build()
                    )
                }
                .generateKey()
        }
    }
}
