package com.onmyway.app.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.work.*
import com.onmyway.app.data.remote.Api
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "onmyway_pc_backup_v1"

internal data class BackupCredential(val server: String, val token: String)

/** The backup token is encrypted with a non-exportable Android Keystore key. */
internal object BackupCredentials {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    fun save(context: Context, server: String, token: String) {
        require(token.length >= 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences("pc_backup", 0).edit {
            putString("server", Api.validateBase(server))
            putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString("token", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    fun load(context: Context): BackupCredential? = runCatching {
        val prefs = context.getSharedPreferences("pc_backup", 0)
        val server = prefs.getString("server", null) ?: return null
        val iv = Base64.decode(prefs.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val encrypted = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        BackupCredential(server, String(cipher.doFinal(encrypted), Charsets.UTF_8))
    }.getOrNull()

    fun clear(context: Context) = context.getSharedPreferences("pc_backup", 0).edit().clear().apply()
}
