package com.onmyway.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Keep the date and zone together so deadline grouping uses the same local clock. */
internal data class CurrentDate(val date: LocalDate, val zone: ZoneId)

internal fun currentDate(
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): CurrentDate = CurrentDate(now.atZone(zone).toLocalDate(), zone)

internal fun millisUntilNextLocalDate(now: Instant, zone: ZoneId): Long {
    // A local day can be 23 or 25 hours, and some zones have skipped entire dates.
    val nextDay = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    return Duration.between(now, nextDay).toMillis().coerceAtLeast(1L)
}

@Composable
internal fun rememberCurrentDate(): CurrentDate {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var current by remember { mutableStateOf(currentDate()) }
    var clockRevision by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                current = currentDate()
                clockRevision++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(lifecycle, clockRevision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = Instant.now()
                val zone = ZoneId.systemDefault()
                current = currentDate(now, zone)
                delay(millisUntilNextLocalDate(now, zone))
            }
        }
    }
    return current
}
