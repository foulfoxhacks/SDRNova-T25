package com.sdrnova.t25.radio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sdrnova.t25.BuildConfig
import com.sdrnova.t25.radioreference.RrAuth
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The RadioReference login, kept on the phone only. The password is encrypted with a key held in the Android
 * Keystore, and only stored at all when the user asks to be remembered.
 */
class RadioReferenceAccount(context: Context) {
    private val prefs = context.getSharedPreferences("radioreference", Context.MODE_PRIVATE)

    /** An app key baked in at build time with -Prr.appKey=..., so users don't need their own. */
    val bundledAppKey: String = BuildConfig.RR_APP_KEY

    data class Saved(val username: String, val password: String, val appKey: String, val remember: Boolean)

    fun load(): Saved = Saved(
        username = prefs.getString("username", "").orEmpty(),
        password = prefs.getString("password", null)?.let { runCatching { decrypt(it) }.getOrNull() }.orEmpty(),
        appKey = prefs.getString("appKey", "").orEmpty(),
        remember = prefs.getBoolean("remember", false),
    )

    fun save(saved: Saved) {
        prefs.edit()
            .putString("username", saved.username)
            .putString("appKey", saved.appKey)
            .putBoolean("remember", saved.remember)
            .putString("password", if (saved.remember && saved.password.isNotEmpty()) encrypt(saved.password) else null)
            .apply()
    }

    fun auth(saved: Saved) = RrAuth(saved.username.trim(), saved.password, bundledAppKey.ifEmpty { saved.appKey.trim() })

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val ALIAS = "sdrnova-radioreference"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
