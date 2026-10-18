package com.onmyway.app.feature.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class ChatLine(
    val role: String,
    val text: String,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val routeHistoryId: String? = null,
    val routeSessionId: String? = null,
    val routeAbandoned: Boolean = false,
    val routePlanId: String? = null,
)

internal fun chatDateLabel(timestamp: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (timestamp <= 0) return "较早对话"
    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    return when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> day.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }
}
