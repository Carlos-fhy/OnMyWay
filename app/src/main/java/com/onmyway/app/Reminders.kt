package com.onmyway.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.*
import java.util.concurrent.TimeUnit

const val REMINDER_INTERVAL_MS = 72L * 60 * 60 * 1000

/** A durable in-app reminder and priority increment are committed together. */
suspend fun processReminders(db: TaskDatabase, now: Long): List<Errand> = db.withTransaction {
    db.tasks().dueForReminder(now - REMINDER_INTERVAL_MS).map { task ->
        task.copy(priority = (task.priority + 1).coerceAtMost(3), updatedAt = now,
            lastRemindedAt = now, reminderCount = task.reminderCount + 1, reminderPending = true)
            .also { db.tasks().save(it) }
    }
}

fun scheduleReminders(context: Context) {
    val manager = WorkManager.getInstance(context)
    manager.enqueueUniquePeriodicWork("task-aging", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS).build())
    manager.enqueueUniqueWork("task-aging-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ReminderWorker>().build())
}

fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val changed = try { processReminders(TaskDatabase.get(applicationContext), System.currentTimeMillis()) }
        catch (_: java.io.IOException) { return Result.retry() }
        catch (_: android.database.sqlite.SQLiteException) { return Result.retry() }
        if (changed.isNotEmpty() && notificationsAllowed(applicationContext)) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("task-reminders", "待办提醒", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(applicationContext, "task-reminders")
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("还有 ${changed.size} 件事待办")
                .setContentText("已生成三天提醒，优先级提升一级（最高为重要）。")
                .setContentIntent(intent).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            try { manager.notify(3001, notification) } catch (_: SecurityException) { /* In-app reminders remain available. */ }
        }
        return Result.success()
    }
}
