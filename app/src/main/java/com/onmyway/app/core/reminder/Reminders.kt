package com.onmyway.app.core.reminder

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
import com.onmyway.app.MainActivity
import com.onmyway.app.R
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import java.util.concurrent.TimeUnit

const val REMINDER_INTERVAL_MS = 72L * 60 * 60 * 1000

/** An appointment takes precedence over aging; urgency comes from dates, not a priority score. */
suspend fun processReminders(db: TaskDatabase, now: Long): List<Errand> = db.withTransaction {
    db.tasks().dueForReminder(now, now - REMINDER_INTERVAL_MS).map { task ->
        task.copy(updatedAt = now, lastRemindedAt = now, reminderCount = task.reminderCount + 1,
            reminderPending = true, reminderAt = 0)
            .also { db.tasks().save(it) }
    }
}

private suspend fun changeTaskReminder(db: TaskDatabase, expected: Errand, at: Long?, now: Long): Errand = db.withTransaction {
    val current = checkNotNull(db.tasks().get(expected.id)) { "待办已删除，请刷新后重试" }
    check(current.status == "pending" && expected.status == "pending") { "待办状态已变化，请刷新后重试" }
    check(current.createdAt == expected.createdAt && current.lastRemindedAt == expected.lastRemindedAt &&
        current.reminderCount == expected.reminderCount && current.reminderPending == expected.reminderPending &&
        current.reminderAt == expected.reminderAt) { "这条提醒已更新，请重新打开任务菜单" }
    if (at == null) check(current.reminderPending) { "本次提醒已经处理" }
    if (at != null && at == current.reminderAt && !current.reminderPending) return@withTransaction current
    current.copy(reminderAt = at ?: current.reminderAt, reminderPending = false, updatedAt = now)
        .also { db.tasks().save(it) }
}

internal suspend fun snoozeTaskReminder(db: TaskDatabase, expected: Errand, at: Long, now: Long): Errand {
    require(at > now) { "请选择未来的提醒时间" }
    return changeTaskReminder(db, expected, at, now)
}

internal suspend fun dismissTaskReminder(db: TaskDatabase, expected: Errand, now: Long): Errand =
    changeTaskReminder(db, expected, null, now)

/** Publish under the same database lock as reminder actions; never alert for an older generation. */
internal suspend fun publishCurrentReminders(db: TaskDatabase, candidates: List<Errand>,
    publish: (List<Errand>, Boolean) -> Unit) = db.withTransaction {
    val pending = db.tasks().unreadReminders()
    val alert = pending.any { task -> candidates.any {
        it.id == task.id && it.lastRemindedAt == task.lastRemindedAt && it.reminderCount == task.reminderCount
    } }
    publish(pending, alert)
}

internal fun scheduleReminderNotificationRefresh(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork("task-reminder-notification", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf("refresh_only" to true)).build())
}

/** The wake-up only queues processing; replacing it never cancels the database transaction. */
internal fun scheduleTaskReminder(context: Context, task: Errand, replace: Boolean = true) {
    val manager = WorkManager.getInstance(context)
    val tag = "task-reminder-${task.id}"
    if (replace) manager.cancelAllWorkByTag(tag)
    if (task.status != "pending" || task.reminderAt <= 0) {
        return
    }
    // A processor reading an older appointment must never replace a newer user timer.
    manager.enqueueUniqueWork("$tag-${task.reminderAt}", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<ReminderWakeWorker>()
            .addTag(tag)
            .setInitialDelay((task.reminderAt - System.currentTimeMillis()).coerceIn(0, TimeUnit.DAYS.toMillis(365)), TimeUnit.MILLISECONDS).build())
}

fun scheduleReminders(context: Context) {
    val manager = WorkManager.getInstance(context)
    manager.enqueueUniquePeriodicWork("task-aging", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS).build())
    manager.enqueueUniqueWork("task-aging-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ReminderWorker>().build())
}

open class ReminderWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork("task-aging-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>().build())
        return Result.success()
    }
}

fun notificationsAllowed(context: Context): Boolean =
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

open class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val database = TaskDatabase.get(applicationContext)
        val refreshOnly = inputData.getBoolean("refresh_only", false)
        return try {
            val changed = if (refreshOnly) emptyList() else processReminders(database, System.currentTimeMillis())
            publishCurrentReminders(database, changed) { pending, alert ->
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                if (pending.isEmpty()) manager.cancel(3001)
                else if (notificationsAllowed(applicationContext) && (alert || manager.activeNotifications.any { it.id == 3001 })) {
                    manager.createNotificationChannel(NotificationChannel("task-reminders", "待办提醒", NotificationManager.IMPORTANCE_DEFAULT))
                    val intent = PendingIntent.getActivity(applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val notification = NotificationCompat.Builder(applicationContext, "task-reminders")
                        .setSmallIcon(R.drawable.ic_launcher).setContentTitle("还有 ${pending.size} 件事待办")
                        .setContentText("打开待办可逐项处理、稍后提醒或忽略本次提醒。")
                        .setOnlyAlertOnce(!alert)
                        .setContentIntent(intent).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                    try { manager.notify(3001, notification) } catch (_: SecurityException) { /* In-app reminders remain available. */ }
                }
            }
            if (!refreshOnly) for (task in database.tasks().scheduledReminders())
                scheduleTaskReminder(applicationContext, task, replace = false)
            Result.success()
        }
        catch (_: java.io.IOException) { return Result.retry() }
        catch (_: android.database.sqlite.SQLiteException) { return Result.retry() }
    }
}
