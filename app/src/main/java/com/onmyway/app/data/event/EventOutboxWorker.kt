package com.onmyway.app.data.event

import android.content.Context
import androidx.work.*
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.sync.BackupCredentials
import java.util.concurrent.TimeUnit

internal class EventOutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Without a configured PC connection events stay pending locally. The
        // credential observer schedules this worker again once it is enabled.
        val credential = BackupCredentials.load(applicationContext) ?: return Result.success()
        val sender = EventOutboxSender(
            TaskDatabase.get(applicationContext).businessEvents(),
            HttpEventTransport(credential),
        )
        return if (sender.flush()) Result.retry() else Result.success()
    }
}

internal object EventOutboxSync {
    private const val WORK_NAME = "business_event_outbox"

    fun schedule(context: Context) {
        if (BackupCredentials.load(context) == null) return
        val request = OneTimeWorkRequestBuilder<EventOutboxWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE keeps events enqueued during a running pass from
        // being dropped by KEEP; the next pass sees them.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
