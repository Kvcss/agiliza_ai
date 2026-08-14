package com.agilizaai.cotacao.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_portal_credentials", Context.MODE_PRIVATE)

    fun save(origin: String, email: String, password: String) {
        prefs.edit()
            .putString(KEY_ORIGIN, origin)
            .putString(KEY_EMAIL, encrypt(email))
            .putString(KEY_PASSWORD, encrypt(password))
            .apply()
    }

    fun email(): String = decryptOrEmpty(prefs.getString(KEY_EMAIL, null))

    fun password(): String = decryptOrEmpty(prefs.getString(KEY_PASSWORD, null))

    fun origin(): String = prefs.getString(KEY_ORIGIN, "").orEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptOrEmpty(payload: String?): String {
        if (payload.isNullOrBlank()) return ""
        return runCatching {
            val bytes = Base64.decode(payload, Base64.NO_WRAP)
            require(bytes.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES))
            )
            cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
        }.getOrElse {
            clear()
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "agiliza_portal_credentials_v1"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ORIGIN = "origin"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
