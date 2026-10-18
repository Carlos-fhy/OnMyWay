package com.onmyway.app.feature.navigation

import com.onmyway.app.core.model.Place
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class NavigationRouteTest {
    private val start = Place("start", "起点", 120.0, 30.0)
    private val shop = Place("shop", "药店 A & B | 南门", 120.1, 30.1)
    private val end = Place("end", "目的地", 120.2, 30.2)
    private fun params(uri: String) = URI(uri).rawQuery.split("&").associate {
        val parts = it.split("=", limit = 2)
        parts[0] to URLDecoder.decode(parts[1], "UTF-8")
    }

    @Test fun entireTripKeepsDestinationAndOrderedWaypoints() {
        val p = params(nativeRouteUri("walking", end, listOf(shop), start))
        assertEquals("目的地", p["dname"])
        assertEquals("1", p["vian"])
        assertEquals("药店 A & B ｜ 南门", p["vianames"])
        assertEquals("30.100000", p["vialats"])
        assertEquals("120.100000", p["vialons"])
        assertEquals("2", p["t"])
        assertEquals("30.000000", p["slat"])
    }
    @Test fun repeatedAdjacentStopsAndTerminalStopsAreRemoved() {
        assertEquals(listOf(shop), navigationWaypoints(listOf(start, shop, shop, end), end, start))
        assertEquals(listOf(end, shop), navigationWaypoints(listOf(end, shop, end), end))
    }
    @Test fun resumeUsesCurrentLocationAndCanGoStraightToDestination() {
        val p = params(nativeRouteUri("cycling", end))
        assertFalse(p.containsKey("slat"))
        assertFalse(p.containsKey("vian"))
        assertEquals("3", p["t"])
    }
    @Test fun sixStopsPreserveOrderAndExcessIsNeverSilentlyTruncated() {
        val places = (1..6).map { Place("$it", "站$it", 120.0 + it / 100.0, 30.0 + it / 100.0) }
        val p = params(nativeRouteUri("cycling", end, places))
        assertEquals("6", p["vian"])
        assertEquals(places.map { it.name }, p["vianames"]!!.split("|"))
        assertEquals(6, p["vialats"]!!.split("|").size)
        assertThrows(IllegalArgumentException::class.java) { nativeRouteUri("walking", end, places + shop) }
    }
}
