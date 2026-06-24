package com.relaypony.android.transfer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.relaypony.crypto.AgeProvider
import com.relaypony.crypto.Identity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists this device's age identity across launches so that peers' pins of it stay valid. The
 * age secret key is encrypted at rest with an AES-256-GCM key generated in (and non-exportable
 * from) the Android Keystore; only the ciphertext lives in app-private SharedPreferences.
 */
class KeystoreIdentityStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("relaypony_identity", Context.MODE_PRIVATE)

    /** Return the stored identity, or generate, persist, and return a new one on first run. */
    fun loadOrCreate(provider: AgeProvider): Identity {
        prefs.getString(KEY_CT, null)?.let { stored ->
            runCatching { return provider.identityFromString(decrypt(stored)) }
        }
        val identity = provider.generateIdentity()
        prefs.edit().putString(KEY_CT, encrypt(provider.identityToString(identity))).apply()
        return identity
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(":", limit = 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "relaypony_identity_key"
        private const val KEY_CT = "identity_ct"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
