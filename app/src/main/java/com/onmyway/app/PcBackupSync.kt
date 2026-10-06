package com.onmyway.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import androidx.room.InvalidationTracker
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "onmyway_pc_backup_v1"
private const val WORK_NAME = "pc_backup_on_change"
private const val PERIODIC_NAME = "pc_backup_periodic"

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

internal object PcBackupSync {
    fun schedule(context: Context) {
        if (BackupCredentials.load(context) == null) return
        context.getSharedPreferences("pc_backup", 0).edit { putString("sync_state", "pending") }
        val request = OneTimeWorkRequestBuilder<PcBackupWorker>()
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun enable(context: Context, server: String, token: String) {
        BackupCredentials.save(context, server, token)
        (context.applicationContext as? OnMyWayApplication)?.startObserving()
        val periodic = PeriodicWorkRequestBuilder<PcBackupWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        schedule(context)
    }

    fun disable(context: Context) {
        BackupCredentials.clear(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
    }
}

internal class PcBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val credential = BackupCredentials.load(applicationContext) ?: return Result.success()
        val currentServer = applicationContext.getSharedPreferences("settings", 0)
            .getString("server", "http://127.0.0.1:8000")
        if (currentServer != credential.server) return Result.failure()
        return try {
            applicationContext.getSharedPreferences("pc_backup", 0).edit { putString("sync_state", "syncing") }
            val restored = PcSyncEngine.synchronize(applicationContext, credential)
            applicationContext.getSharedPreferences("pc_backup", 0).edit {
                putLong("last_success_at", System.currentTimeMillis())
                remove("last_error")
                putString("sync_state", "synced")
                if (restored) putBoolean("needs_reload", true)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            applicationContext.getSharedPreferences("pc_backup", 0).edit {
                putString("last_error", error.message?.take(160) ?: "同步失败")
                putString("sync_state", if (error is SyncConflict || error is ApiHttpException && error.status == 409) "conflict" else "pending")
            }
            if (error is SyncConflict || error is ApiHttpException && error.status in listOf(401, 403, 409, 413, 422))
                Result.failure() else Result.retry()
        }
    }
}

class OnMyWayApplication : Application() {
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        PcBackupSync.schedule(this)
    }
    private var databaseObserver: InvalidationTracker.Observer? = null

    override fun onCreate() {
        super.onCreate()
        startObserving()
    }

    fun startObserving() {
        if (databaseObserver != null || BackupCredentials.load(this) == null) return
        listOf("settings", "assistant_conversation", "navigation_context").forEach {
            getSharedPreferences(it, 0).registerOnSharedPreferenceChangeListener(preferenceListener)
        }
        databaseObserver = object : InvalidationTracker.Observer(
            "tasks", "saved_trip", "route_history", "suggestion_feedback", "agent_events",
            "assistant_messages", "assistant_draft", "planning_draft", "planning_draft_tasks", "assistant_clarification", "assistant_place_choices") {
            override fun onInvalidated(tables: Set<String>) { PcBackupSync.schedule(this@OnMyWayApplication) }
        }.also { TaskDatabase.get(this).invalidationTracker.addObserver(it) }
        PcBackupSync.schedule(this)
    }
}
