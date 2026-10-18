package com.onmyway.app

import android.content.Context
import androidx.work.WorkerParameters
import com.onmyway.app.core.reminder.ReminderWakeWorker as CurrentReminderWakeWorker
import com.onmyway.app.core.reminder.ReminderWorker as CurrentReminderWorker
import com.onmyway.app.data.sync.PcBackupWorker as CurrentPcBackupWorker

/** Compatibility constructors for WorkSpecs queued by versions before package organization. */
class ReminderWorker(context: Context, params: WorkerParameters) : CurrentReminderWorker(context, params)
class ReminderWakeWorker(context: Context, params: WorkerParameters) : CurrentReminderWakeWorker(context, params)
internal class PcBackupWorker(context: Context, params: WorkerParameters) : CurrentPcBackupWorker(context, params)
