package com.onmyway.app.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** A route origin must be recent enough for a moving user and precise enough to price a detour. */
internal const val FRESH_FIX_MAX_AGE_MILLIS = 60_000L
internal const val QUICK_CACHED_FIX_MAX_AGE_MILLIS = 10 * 60_000L
internal const val CACHED_FIX_MAX_AGE_MILLIS = 24 * 60 * 60 * 1_000L
private const val FRESH_FIX_MAX_AGE_NANOS = FRESH_FIX_MAX_AGE_MILLIS * 1_000_000L
private const val QUICK_CACHED_FIX_MAX_AGE_NANOS = QUICK_CACHED_FIX_MAX_AGE_MILLIS * 1_000_000L
private const val CACHED_FIX_MAX_AGE_NANOS = CACHED_FIX_MAX_AGE_MILLIS * 1_000_000L
private const val MAX_FIX_ACCURACY_METERS = 100f
private const val LOCATION_TIMEOUT_MILLIS = 20_000L

internal fun usablePlanningFix(
    ageNanos: Long,
    accuracyMeters: Float?,
    maxAgeNanos: Long = FRESH_FIX_MAX_AGE_NANOS,
    maxAccuracyMeters: Float = MAX_FIX_ACCURACY_METERS,
): Boolean = ageNanos in 0..maxAgeNanos && accuracyMeters != null &&
    accuracyMeters.isFinite() && accuracyMeters in 0f..maxAccuracyMeters

/**
 * 手机室内拿不到新定位时，系统仍可能保留当天更早的位置。
 * 这类位置只作为兜底，界面会明确提示它的时间，避免把过期位置当成实时结果。
 */
internal fun usableCachedFix(
    ageNanos: Long,
    accuracyMeters: Float?,
    maxAgeNanos: Long = CACHED_FIX_MAX_AGE_NANOS,
    maxAccuracyMeters: Float = MAX_FIX_ACCURACY_METERS,
): Boolean = usablePlanningFix(ageNanos, accuracyMeters, maxAgeNanos, maxAccuracyMeters)

internal fun readableLocationAge(ageMillis: Long): String {
    val safe = ageMillis.coerceAtLeast(0)
    return when {
        safe < 60_000L -> "不到 1 分钟"
        safe < 3_600_000L -> "${safe / 60_000L} 分钟"
        safe < 86_400_000L -> "${safe / 3_600_000L} 小时"
        else -> "${safe / 86_400_000L} 天"
    }
}

/** 位置结果和它的年龄一起返回，调用方才能区分实时定位和缓存兜底。 */
data class LocationSnapshot(val location: Location, val ageMillis: Long) {
    val usedCachedFix: Boolean get() = ageMillis > FRESH_FIX_MAX_AGE_MILLIS
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun fixAgeNanos(location: Location): Long =
    SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos

private fun usable(location: Location, cached: Boolean): Boolean {
    val accuracy = location.accuracy.takeIf { location.hasAccuracy() }
    return if (cached) usableCachedFix(fixAgeNanos(location), accuracy)
    else usablePlanningFix(fixAgeNanos(location), accuracy)
}

private fun snapshot(location: Location) =
    LocationSnapshot(location, fixAgeNanos(location).coerceAtLeast(0) / 1_000_000L)

private fun betterCachedFix(candidate: Location, current: Location): Boolean {
    val candidateAge = fixAgeNanos(candidate)
    val currentAge = fixAgeNanos(current)
    return candidateAge < currentAge ||
        (candidateAge == currentAge && candidate.accuracy < current.accuracy)
}

private sealed interface FixResult {
    class Fresh(val location: Location) : FixResult
    class Cached(val location: Location) : FixResult
    object None : FixResult
}

/**
 * One foreground request; listeners are removed on success, timeout or cancellation.
 * 先看系统缓存的最近位置，再向网络、融合和 GPS 同时请求新位置。
 * [allowCached] 只应在线路起点这类允许明确提示后使用缓存位置的场景打开；
 * 导航开始需要真实当前位置时传 false。
 */
suspend fun readCurrentLocation(context: Context, allowCached: Boolean = true): LocationSnapshot {
    check(hasLocationPermission(context)) { "请允许位置权限，或在系统应用设置中开启后重试。" }
    val manager = context.getSystemService(LocationManager::class.java)
    check(LocationManagerCompat.isLocationEnabled(manager)) { "请先打开手机的定位服务。" }
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val providers = buildList {
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        if (fine) add(LocationManager.GPS_PROVIDER)
    }.filter { manager.isProviderEnabled(it) }
    check(providers.isNotEmpty()) { "当前没有可用的定位方式，请开启精确位置或手动选择起点。" }

    val cached = AtomicReference<Location?>(null)
    fun consider(location: Location?) {
        if (location == null || !usable(location, cached = true)) return
        while (true) {
            val current = cached.get()
            if (current == null) {
                if (cached.compareAndSet(null, location)) return
            } else {
                if (!betterCachedFix(location, current) || cached.compareAndSet(current, location)) return
            }
        }
    }

    // 系统缓存通常能立即返回；如果它刚刚更新过，就不必再等 20 秒。
    providers.forEach { provider ->
        consider(runCatching { manager.getLastKnownLocation(provider) }.getOrNull())
    }
    cached.get()?.takeIf { usable(it, cached = false) }?.let { return snapshot(it) }
    // 几分钟内的位置足以开始规划。先返回它，避免为了等 GPS 让用户站 20 秒。
    if (allowCached) {
        cached.get()?.takeIf { candidate ->
            usableCachedFix(
                fixAgeNanos(candidate),
                candidate.accuracy.takeIf { candidate.hasAccuracy() },
                QUICK_CACHED_FIX_MAX_AGE_NANOS,
            )
        }?.let { return snapshot(it) }
    }

    val signals = mutableListOf<CancellationSignal>()
    val result = try {
        withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                var remaining = providers.size
                continuation.invokeOnCancellation { signals.forEach { it.cancel() } }
                providers.forEach { provider ->
                    if (!continuation.isActive) return@forEach
                    val signal = CancellationSignal().also { signals.add(it) }
                    try {
                        LocationManagerCompat.getCurrentLocation(
                            manager,
                            provider,
                            signal,
                            ContextCompat.getMainExecutor(context),
                        ) { location ->
                            if (!continuation.isActive) return@getCurrentLocation
                            consider(location)
                            when {
                                location != null && usable(location, cached = false) ->
                                    continuation.resume(FixResult.Fresh(location))
                                allowCached && location != null && usableCachedFix(
                                    fixAgeNanos(location),
                                    location.accuracy.takeIf { location.hasAccuracy() },
                                    QUICK_CACHED_FIX_MAX_AGE_NANOS,
                                ) -> continuation.resume(FixResult.Cached(location))
                                else -> {
                                    remaining--
                                    if (remaining == 0) continuation.resume(FixResult.None)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        remaining--
                        if (remaining == 0 && continuation.isActive) continuation.resume(FixResult.None)
                    }
                }
            }
        }
    } finally { signals.forEach { it.cancel() } }

    val fresh = (result as? FixResult.Fresh)?.location
    if (fresh != null) return snapshot(fresh)
    val quick = (result as? FixResult.Cached)?.location
    if (allowCached && quick != null) return snapshot(quick)
    val fallback = cached.get()
    if (allowCached && fallback != null) return snapshot(fallback)
    throw IllegalStateException(
        if (fallback != null) "暂时没有新的定位，请到信号较好的地方重试，或手动选择起点。"
        else "暂时无法获取位置，请到信号较好的地方重试，或手动选择起点。"
    )
}
