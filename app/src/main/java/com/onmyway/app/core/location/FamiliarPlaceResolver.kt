package com.onmyway.app.core.location

import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.placeTypes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

internal data class FamiliarPlaceCandidate(val alias: String, val place: Place)

internal data class FamiliarPlaceMatch(
    val alias: String,
    val place: Place,
    val distanceMeters: Double,
)

/** The resolver never silently borrows a nearby shop for a different kind of errand. */
internal sealed interface FamiliarPlaceLookup {
    data class Matched(val match: FamiliarPlaceMatch) : FamiliarPlaceLookup
    data object NotApplicable : FamiliarPlaceLookup
    data object NoCandidate : FamiliarPlaceLookup
    data object NoOrigin : FamiliarPlaceLookup
    data class OutOfRange(val nearest: FamiliarPlaceMatch) : FamiliarPlaceLookup
}

/**
 * Deterministic, client-only matching for saved familiar places.
 * Purpose tags win; keyword matching is only a compatibility fallback for untagged places.
 */
internal object FamiliarPlaceResolver {
    const val MAX_AUTO_FILL_DISTANCE_METERS = 3_000.0

    private val keywordFallbacks = mapOf(
        "PARCEL" to listOf("快递", "驿站", "菜鸟", "丰巢", "包裹", "自提", "收发室", "邮局"),
        "TAKEOUT" to listOf("外卖", "取餐", "餐柜", "食堂"),
        "PRINT_SHOP" to listOf("打印", "文印", "复印", "图文", "快印"),
        "PHARMACY" to listOf("药房", "药店", "医药", "买药", "诊所"),
        "SUPERMARKET" to listOf("超市", "便利店", "生鲜", "菜场", "菜市场", "小卖部"),
    )

    fun candidates(
        type: String,
        places: Map<String, Place>,
        purposes: Map<String, Set<String>>,
    ): List<FamiliarPlaceCandidate> {
        if (type !in placeTypes) return emptyList()
        val usable = places.filter { (alias, place) ->
            alias.isNotBlank() && place.id.isNotBlank() && place.name.isNotBlank() &&
                place.longitude.isFinite() && place.latitude.isFinite() &&
                place.longitude in -180.0..180.0 && place.latitude in -90.0..90.0
        }
        val tagged = usable.filter { (alias, _) -> type in purposes[alias].orEmpty() }
        if (tagged.isNotEmpty()) return tagged.map { FamiliarPlaceCandidate(it.key, it.value) }
            .sortedBy { it.alias }
        val keywords = keywordFallbacks[type].orEmpty()
        if (keywords.isEmpty()) return emptyList()
        return usable.filter { (alias, place) ->
            purposes[alias].isNullOrEmpty() &&
                keywords.any { keyword ->
                    normalized(alias).contains(normalized(keyword)) ||
                        normalized(place.name).contains(normalized(keyword))
                }
        }.map { FamiliarPlaceCandidate(it.key, it.value) }.sortedBy { it.alias }
    }

    fun hasCandidates(
        type: String,
        places: Map<String, Place>,
        purposes: Map<String, Set<String>>,
    ): Boolean = candidates(type, places, purposes).isNotEmpty()

    fun resolve(
        type: String,
        origin: Place?,
        places: Map<String, Place>,
        purposes: Map<String, Set<String>>,
    ): FamiliarPlaceLookup {
        if (type !in placeTypes) return FamiliarPlaceLookup.NotApplicable
        val candidates = candidates(type, places, purposes)
        if (candidates.isEmpty()) return FamiliarPlaceLookup.NoCandidate
        if (origin == null || !origin.longitude.isFinite() || !origin.latitude.isFinite() ||
            origin.longitude !in -180.0..180.0 || origin.latitude !in -90.0..90.0) {
            return FamiliarPlaceLookup.NoOrigin
        }
        val nearest = candidates.map { candidate ->
            FamiliarPlaceMatch(
                candidate.alias,
                candidate.place,
                distanceMeters(origin.latitude, origin.longitude,
                    candidate.place.latitude, candidate.place.longitude),
            )
        }.sortedWith(compareBy<FamiliarPlaceMatch> { it.distanceMeters }.thenBy { it.alias })
            .first()
        return if (nearest.distanceMeters <= MAX_AUTO_FILL_DISTANCE_METERS)
            FamiliarPlaceLookup.Matched(nearest)
        else FamiliarPlaceLookup.OutOfRange(nearest)
    }

    /** Same equirectangular approximation as the planner, so client hints agree with route math. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val midLat = (lat1 + lat2) / 2.0 * PI / 180.0
        val x = (lon2 - lon1) * 111_320.0 * cos(midLat)
        val y = (lat2 - lat1) * 110_540.0
        return hypot(x, y)
    }

    private fun normalized(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }
}
