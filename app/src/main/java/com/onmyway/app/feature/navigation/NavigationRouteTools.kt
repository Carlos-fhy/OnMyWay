package com.onmyway.app.feature.navigation

import com.onmyway.app.core.model.Place
import org.json.JSONObject

/** Boundary for location and deterministic map calculations; neither operation confirms a route. */
internal interface NavigationRouteTools {
    suspend fun origin(): Place
    suspend fun search(server: String, keyword: String, city: String): List<Place>
    suspend fun plan(server: String, body: JSONObject): JSONObject
}
