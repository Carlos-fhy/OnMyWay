package com.onmyway.app.feature.assistant

import com.onmyway.app.core.format.formatDistance
import com.onmyway.app.core.location.FamiliarPlaceLookup
import com.onmyway.app.core.location.FamiliarPlaceMatch
import com.onmyway.app.core.location.FamiliarPlaceResolver
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.placeTypes
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class PlaceFillRequest(val itemId: String, val placeType: String)

internal data class PlaceFillSummary(
    val filled: List<FamiliarPlaceMatch> = emptyList(),
    val missingCount: Int = 0,
    val locationUnavailable: Boolean = false,
    val outOfRange: Boolean = false,
) {
    val filledCount: Int get() = filled.size
}

/** Only missing outdoor items of a known shop type are candidates for a deterministic fill. */
internal fun placeFillRequests(proposal: JSONObject): List<PlaceFillRequest> {
    if (proposal.optString("action") != "create_tasks") return emptyList()
    val items = proposal.optJSONArray("tasks") ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("item_id")
        val type = item.optString("place_type")
        if (id.isBlank() || type !in placeTypes) return@mapNotNull null
        if (item.has("place") && !item.isNull("place")) return@mapNotNull null
        PlaceFillRequest(id, type)
    }
}

/**
 * Fills only the exact requests supplied by the caller and never overwrites an existing place.
 * Any malformed data degrades to an empty summary instead of discarding an otherwise valid draft.
 */
internal fun applyFamiliarPlaceFills(
    proposal: JSONObject,
    requests: List<PlaceFillRequest>,
    origin: Place?,
    places: Map<String, Place>,
    purposes: Map<String, Set<String>>,
): PlaceFillSummary {
    if (requests.isEmpty()) return PlaceFillSummary()
    return try {
        val items = proposal.optJSONArray("tasks") ?: return PlaceFillSummary()
        val byId = (0 until items.length()).mapNotNull(items::optJSONObject)
            .associateBy { it.optString("item_id") }
        val filled = mutableListOf<FamiliarPlaceMatch>()
        var missing = 0
        var locationUnavailable = false
        var outOfRange = false
        requests.forEach { request ->
            val item = byId[request.itemId]
            if (item == null || item.has("place") && !item.isNull("place")) {
                missing++
                return@forEach
            }
            when (val lookup = FamiliarPlaceResolver.resolve(
                request.placeType, origin, places, purposes,
            )) {
                is FamiliarPlaceLookup.Matched -> {
                    item.put("place", lookup.match.place.json())
                    filled += lookup.match
                }
                FamiliarPlaceLookup.NoOrigin -> {
                    locationUnavailable = true
                    missing++
                }
                is FamiliarPlaceLookup.OutOfRange -> {
                    outOfRange = true
                    missing++
                }
                FamiliarPlaceLookup.NoCandidate, FamiliarPlaceLookup.NotApplicable -> missing++
            }
        }
        PlaceFillSummary(filled, missing, locationUnavailable, outOfRange)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PlaceFillSummary()
    }
}

/** Resolves the alias whose saved place has the same identity, for display only. */
internal fun familiarAliasFor(place: Place?, places: Map<String, Place>): String? =
    place?.let { target -> places.entries.firstOrNull { it.value.id == target.id }?.key }

/** Client-authored copy; it never claims that the model chose a shop. */
internal fun placeFillNote(summary: PlaceFillSummary): String {
    val parts = mutableListOf<String>()
    val nearest = summary.filled.minByOrNull { it.distanceMeters }
    if (nearest != null) {
        val distance = formatDistance(nearest.distanceMeters.roundToInt())
        parts += if (summary.filledCount == 1)
            "已按常用地点填入「${nearest.place.name}」（直线约 $distance），请核对后再保存。"
        else "已按常用地点填入 ${summary.filledCount} 项，最近直线约 $distance；请核对。"
    }
    when {
        summary.locationUnavailable -> parts += "未获取当前位置，地点未自动填入；可打开修改选择。"
        summary.outOfRange && summary.filledCount == 0 ->
            parts += "3 公里内没有匹配的常用地点，地点未自动填入；可打开修改选择。"
        summary.outOfRange -> parts += "另有 ${summary.missingCount} 项在 3 公里内没有匹配的常用地点，未自动填入。"
        summary.missingCount > 0 -> parts += "还有 ${summary.missingCount} 项缺办理地点。"
    }
    return parts.joinToString(" ")
}
