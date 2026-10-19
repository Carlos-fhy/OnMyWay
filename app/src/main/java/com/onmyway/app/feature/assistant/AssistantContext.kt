package com.onmyway.app.feature.assistant

import org.json.JSONObject

/** Only current editable fields belong in model context, never local IDs or provider replies. */
internal fun assistantDraftContext(draft: JSONObject): String? = if (!assistantCardVersionSupported(draft)) null else when (draft.optString("action")) {
    "create_tasks" -> "当前未确认的待办草稿如下，尚未保存。按 item_id 和 version 逐项修改；" +
        "仅修改用户明确指定的字段，其余条目保留。planned_date 是计划日期，deadline 是硬截止时间，reminder_at 是到点提醒时间，三者独立。" +
        "修改不表示确认；信息不明确时追问，不创建替代草稿。\n${taskDraftRequestItems(draft)}"
    "create_task" -> {
        val fields = JSONObject().put("title", draft.optString("title"))
            .put("place_type", draft.optString("place_type"))
            .put("deadline", draft.optString("deadline"))
            .put("reminder_at", draft.optString("reminder_at"))
        "当前有未确认的待办草稿，任务尚未保存。以下 JSON 是当前字段，deadline 为空表示未设截止时间，reminder_at 为空表示未设提醒。" +
            "用户修改这条草稿时，只修改明确指定的字段，保留其他字段；信息不明确时追问，修改不代表确认保存。\n$fields"
    }
    "plan_route" -> "当前有未确认的路线草稿：终点${draft.optString("destination")}，出行方式${draft.optString("mode")}。这条路线尚未执行。"
    else -> null
}

/** Stored history is separate from model context. Keep a bounded, contiguous recent exchange. */
internal fun assistantContextLines(lines: List<ChatLine>, maxCharacters: Int = 6_000): List<ChatLine> {
    val selected = mutableListOf<ChatLine>()
    var remaining = maxCharacters
    var newerAt: Long? = null
    for (line in lines.asReversed()) {
        val newer = newerAt
        if (newer != null && (newer - line.createdAt > 30 * 60_000L || line.createdAt > newer)) break
        newerAt = line.createdAt
        // Old route events are available in the archive, not instructions for this request.
        if (line.routeHistoryId != null) continue
        val text = line.text.take(4_000)
        if (text.length > remaining || selected.size == 12) break
        selected.add(line.copy(text = text))
        remaining -= text.length
    }
    return selected.asReversed()
}

/** Keep the latest entry for each trip, preserving unrelated trips and all ordinary messages. */
internal fun latestRouteEntries(lines: List<ChatLine>): List<ChatLine> {
    val seen = mutableSetOf<String>()
    val lastRouteId = lines.lastOrNull { it.routeHistoryId != null }?.id
    return lines.asReversed().filter { line ->
        val route = line.routePlanId ?: line.routeSessionId ?: line.routeHistoryId
        route == null || ((line.routePlanId != null || !line.routeAbandoned || line.id == lastRouteId) && seen.add(route))
    }.asReversed()
}
