package com.onmyway.app

import java.net.URLEncoder
import java.util.Locale

private fun coordinate(value: Double) = String.format(Locale.US, "%.6f", value)
private fun sameLocation(a: Place, b: Place) = coordinate(a.longitude) == coordinate(b.longitude) && coordinate(a.latitude) == coordinate(b.latitude)

/** Only remove redundant adjacent visits. A non-adjacent return visit is meaningful. */
fun navigationWaypoints(stops: List<Place>, destination: Place, origin: Place? = null): List<Place> {
    val result = mutableListOf<Place>()
    for (place in stops) {
        if (result.isEmpty() && origin != null && sameLocation(place, origin)) continue
        if (result.lastOrNull()?.let { sameLocation(it, place) } != true) result.add(place)
    }
    if (result.lastOrNull()?.let { sameLocation(it, destination) } == true) result.removeAt(result.lastIndex)
    return result
}

fun nativeRouteUri(mode: String, destination: Place, stops: List<Place> = emptyList(), origin: Place? = null): String {
    require(mode in listOf("walking", "cycling")) { "不支持的出行方式" }
    val waypoints = navigationWaypoints(stops, destination, origin)
    require(waypoints.size <= 6) { "单次行程最多支持 6 个办事站点" }
    fun name(value: String) = value.replace('|', '｜').replace('\n', ' ')
    val params = linkedMapOf("sourceApplication" to "OnMyWay", "dev" to "0", "t" to if (mode == "cycling") "3" else "2",
        "dlat" to coordinate(destination.latitude), "dlon" to coordinate(destination.longitude), "dname" to name(destination.name))
    if (origin != null) {
        params["slat"] = coordinate(origin.latitude); params["slon"] = coordinate(origin.longitude); params["sname"] = name(origin.name)
    }
    if (waypoints.isNotEmpty()) {
        params["vian"] = waypoints.size.toString()
        params["vialats"] = waypoints.joinToString("|") { coordinate(it.latitude) }
        params["vialons"] = waypoints.joinToString("|") { coordinate(it.longitude) }
        params["vianames"] = waypoints.joinToString("|") { name(it.name) }
    }
    return "amapuri://route/plan/?" + params.entries.joinToString("&") { (key, value) ->
        "$key=${URLEncoder.encode(value, "UTF-8")}" }
}
