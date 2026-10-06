package com.onmyway.app

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
