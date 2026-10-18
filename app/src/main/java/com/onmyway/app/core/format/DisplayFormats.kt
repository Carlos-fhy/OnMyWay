package com.onmyway.app.core.format

import androidx.room.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

fun displayTimestamp(value: Long): String = java.time.Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(inputTime)
val inputTime: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
fun parseTime(value: String): String? = if (value.isBlank()) null else LocalDateTime.parse(value.trim(), inputTime)
    .atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
fun displayTime(value: String): String = java.time.OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(inputTime)

fun formatDuration(seconds: Int): String {
    val totalMinutes = kotlin.math.ceil(seconds / 60.0).toInt().coerceAtLeast(0)
    return if (totalMinutes >= 60) {
        val hours = totalMinutes / 60
        val rem = totalMinutes % 60
        if (rem == 0) "${hours} 小时" else "${hours} 小时 ${rem} 分钟"
    } else {
        "$totalMinutes 分钟"
    }
}

fun formatDistance(meters: Int): String {
    val safeMeters = meters.coerceAtLeast(0)
    return if (safeMeters >= 1000) {
        val km = safeMeters / 1000.0
        String.format(java.util.Locale.CHINA, "%.1f 公里", km)
    } else {
        "$safeMeters 米"
    }
}
