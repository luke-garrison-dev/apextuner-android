package com.apextuner.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface SecureKeyValueStore {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
    fun remove(key: String)
    fun clear()
}

@Singleton
class AndroidKeystoreSecureKeyValueStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecureKeyValueStore {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun putString(key: String, value: String) {
        require(key.isNotBlank()) { "Secure-store key must not be blank." }
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = listOf(cipher.iv, encrypted)
                .joinToString(SEPARATOR) { Base64.encodeToString(it, Base64.NO_WRAP) }
            check(preferences.edit().putString(key, payload).commit()) {
                "Unable to persist encrypted value."
            }
        }
    }

    override fun getString(key: String): String? {
        val payload = preferences.getString(key, null) ?: return null
        return synchronized(lock) {
            try {
                val parts = payload.split(SEPARATOR, limit = 2)
                if (parts.size != 2) return@synchronized null
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            check(preferences.edit().remove(key).commit()) { "Unable to remove encrypted value." }
        }
    }

    override fun clear() {
        synchronized(lock) {
            check(preferences.edit().clear().commit()) { "Unable to clear encrypted values." }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "secure_store"
        const val KEY_ALIAS = "apextuner.local.secure_store.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = "."
    }
}
