package com.onmyway.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** One foreground request; listeners are removed on success, timeout or cancellation. */
suspend fun readCurrentLocation(context: Context): Location {
    check(hasLocationPermission(context)) { "请允许位置权限，或在系统应用设置中开启后重试。" }
    val manager = context.getSystemService(LocationManager::class.java)
    check(LocationManagerCompat.isLocationEnabled(manager)) { "请先打开手机的定位服务。" }
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
        (fine || it != LocationManager.GPS_PROVIDER) && manager.isProviderEnabled(it)
    }
    check(providers.isNotEmpty()) { "当前没有可用的定位方式，请开启精确位置或手动选择起点。" }
    val signals = mutableListOf<CancellationSignal>()
    try {
        return withTimeout(20_000) {
            suspendCancellableCoroutine { continuation ->
                var remaining = providers.size
                continuation.invokeOnCancellation { signals.forEach { it.cancel() } }
                providers.forEach { provider ->
                    if (continuation.isActive) {
                        val signal = CancellationSignal().also { signals.add(it) }
                        LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                            if (continuation.isActive) {
                                remaining--
                                if (location != null) continuation.resume(location)
                                else if (remaining == 0) continuation.resumeWith(Result.failure(IllegalStateException("暂时无法获取位置，请到信号较好的地方重试。")))
                            }
                        }
                    }
                }
            }
        }
    } finally { signals.forEach { it.cancel() } }
}
