package com.onmyway.app.data.sync

import android.content.Context
import androidx.work.*
import com.onmyway.app.OnMyWayApplication
import com.onmyway.app.core.reminder.scheduleReminders
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val WORK_NAME = "pc_backup_on_change"
private const val PERIODIC_NAME = "pc_backup_periodic"

internal object PcBackupSync {
    private val attemptMutex = Mutex()
    fun schedule(context: Context) {
        if (BackupCredentials.load(context) == null) return
        val generation = PcSyncProgress(context.getSharedPreferences("pc_backup", 0)).changed()
        val request = OneTimeWorkRequestBuilder<PcBackupWorker>()
            .setInputData(workDataOf("generation" to generation))
            .setInitialDelay(2, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP drops changes arriving during a running worker (including its completion window).
        // A successor survives that window. Workers already covered by an earlier pass skip I/O.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    suspend fun synchronizeNow(context: Context, credential: BackupCredential): Boolean = attemptMutex.withLock {
        if (BackupCredentials.load(context) != credential ||
            context.getSharedPreferences("settings", 0).getString("server", "http://127.0.0.1:8000") != credential.server)
            throw SyncConflict("同步连接已变化，请使用当前数据服务重试")
        val progress = PcSyncProgress(context.getSharedPreferences("pc_backup", 0))
        val generation = progress.begin()
        try {
            val restored = PcSyncEngine.synchronize(context, credential)
            if (restored) scheduleReminders(context)
            val protocol = PcSyncEngine.taskProtocol(context, credential)
            if (BackupCredentials.load(context) == credential) progress.succeeded(generation, protocol, restored)
            restored
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (BackupCredentials.load(context) == credential) progress.failed(error)
            throw error
        }
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
