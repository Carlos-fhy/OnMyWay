package com.onmyway.app.data.sync

import android.content.Context
import androidx.work.*
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.onmyway.app.data.remote.ApiHttpException
import kotlinx.coroutines.CancellationException

internal open class PcBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val credential = BackupCredentials.load(applicationContext) ?: return Result.success()
        val currentServer = applicationContext.getSharedPreferences("settings", 0)
            .getString("server", "http://127.0.0.1:8000")
        if (currentServer != credential.server) return Result.failure()
        val progress = PcSyncProgress(applicationContext.getSharedPreferences("pc_backup", 0))
        if (progress.covered(inputData.getLong("generation", -1))) return Result.success()
        return try {
            PcBackupSync.synchronizeNow(applicationContext, credential)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is SyncConflict || error is ApiHttpException && error.status in listOf(401, 403, 409, 413, 422))
                Result.failure() else Result.retry()
        }
    }
}
