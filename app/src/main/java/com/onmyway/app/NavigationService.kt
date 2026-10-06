package com.onmyway.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Only live, user-started navigation is retained. Never restarts a route after process death. */
class NavigationService : Service() {
    private var run: NavigationController? = null
    private var lease = -1L
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = NavigationRuntime.current
        if (current == null || current.runId != intent?.getStringExtra("run_id") || !current.needsService || current.serviceLease != intent.getLongExtra("lease", -1)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        run = current
        lease = current.serviceLease
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("navigation", "进行中的导航", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "navigation")
                .setSmallIcon(R.drawable.ic_launcher).setContentTitle("OnMyWay 导航进行中")
                .setContentText("点击返回导航，可在导航页暂停或结束")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(4001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(4001, notification)
            current.serviceReady()
        } catch (_: Exception) {
            current.serviceFailed("无法启动后台导航，请检查定位权限后重试")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        run?.takeIf { it.needsService && it.serviceLease == lease }?.serviceFailed("后台导航已被系统停止，请返回导航页重试")
        run = null
        super.onDestroy()
    }
}

/** Process-owned engine; Activity/map views attach and detach without ending the trip. */
internal object NavigationRuntime {
    var current: NavigationController? by mutableStateOf(null)
        private set

    fun obtain(context: Context, sessionId: String, mode: String, destination: Place, via: List<Place>): NavigationController {
        current?.takeIf { it.matches(sessionId, mode, destination, via) }?.let { return it }
        current?.destroy()
        return NavigationController(context.applicationContext, sessionId, mode, destination, via).also { current = it }
    }
    fun owns(sessionId: String) = current?.sessionId == sessionId
    fun finish(sessionId: String) {
        current?.takeIf { it.sessionId == sessionId }?.let {
            it.destroy()
            current = null
        }
    }
}
