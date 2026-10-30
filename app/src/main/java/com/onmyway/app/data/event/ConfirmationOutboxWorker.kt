package com.onmyway.app.data.event

import android.content.Context
import androidx.work.*
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.sync.BackupCredentials
import java.util.concurrent.TimeUnit

internal class ConfirmationOutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Without a configured PC connection receipts stay pending locally. The
        // credential observer schedules this worker again once it is enabled.
        val credential = BackupCredentials.load(applicationContext) ?: return Result.success()
        val sender = ConfirmationOutboxSender(
            TaskDatabase.get(applicationContext).confirmations(),
            HttpConfirmationTransport(credential),
        )
        return if (sender.flush()) Result.retry() else Result.success()
    }
}

internal object ConfirmationOutboxSync {
    private const val WORK_NAME = "confirmation_outbox"

    fun schedule(context: Context) {
        if (BackupCredentials.load(context) == null) return
        val request = OneTimeWorkRequestBuilder<ConfirmationOutboxWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
