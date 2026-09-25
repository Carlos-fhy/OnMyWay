package com.onmyway.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.maps.model.BitmapDescriptorFactory
import org.json.JSONObject

internal fun routePoints(encoded: String): List<Pair<Double, Double>> = encoded.split(';').mapNotNull { point ->
    val parts = point.split(',')
    if (parts.size != 2) null else {
        val lon = parts[0].toDoubleOrNull(); val lat = parts[1].toDoubleOrNull()
        if (lon != null && lat != null && lon.isFinite() && lat.isFinite() && lon in -180.0..180.0 && lat in -90.0..90.0) lat to lon else null
    }
}

@Composable
internal fun RouteMapPreview(result: JSONObject) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("map_preview", Context.MODE_PRIVATE) }
    var consent by remember { mutableStateOf(prefs.getBoolean("consent", false)) }
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column {
                if (!BuildConfig.AMAP_CONFIGURED) {
                    Text("地图预览暂未配置，仍可使用高德导航。", Modifier.padding(16.dp))
                } else if (!consent) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("高德地图 SDK 将联网加载底图，并按其隐私政策处理设备及网络信息。这里仅显示已规划路线，不开启持续定位。")
                        TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, "https://lbs.amap.com/pages/privacy/".toUri())) }) { Text("阅读高德隐私政策") }
                        Button({ prefs.edit().putBoolean("consent", true).apply(); consent = true }) { Text("同意并显示地图") }
                    }
                } else {
                    MapSurface(result, Modifier.height(280.dp).fillMaxWidth())
                    val legs = result.getJSONArray("legs")
                    val missing = (0 until legs.length()).any {
                        val leg = legs.getJSONObject(it)
                        leg.optInt("meters") > 0 && routePoints(leg.optString("polyline")).size < 2
                    }
                    if (missing) Text("部分路段未返回路线，请重新规划。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
}

@Composable
private fun MapSurface(result: JSONObject, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var loaded by remember(context) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val mapView = remember(context) {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        TextureMapView(context).apply {
            onCreate(null)
            map.setOnMapLoadedListener { loaded = true }
        }
    }
    val markers = remember(mapView) { mutableListOf<com.amap.api.maps.model.Marker>() }
    val lines = remember(mapView) { mutableListOf<com.amap.api.maps.model.Polyline>() }
    DisposableEffect(mapView, owner) {
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_RESUME -> mapView.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            else -> Unit
        } }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mapView.map.setOnMapLoadedListener(null)
            mapView.onPause(); mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier.onSizeChanged { size = it })
    LaunchedEffect(mapView, result, loaded, size) {
        if (!loaded || size.width == 0 || size.height == 0) return@LaunchedEffect
        val map = mapView.map
        // Remove only this preview's overlays; SDK clear() can race queued additions.
        markers.forEach { it.remove() }; markers.clear()
        lines.forEach { it.remove() }; lines.clear()
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.isMyLocationEnabled = false
        val density = context.resources.displayMetrics.density
        val bounds = LatLngBounds.Builder()
        val locations = mutableListOf<Place>()
        locations += Place.from(result.getJSONObject("origin"))
        val stops = result.getJSONArray("stops")
        for (i in 0 until stops.length()) locations += Place.from(stops.getJSONObject(i).getJSONObject("place"))
        locations += Place.from(result.getJSONObject("destination"))
        locations.forEachIndexed { index, place ->
            val point = LatLng(place.latitude, place.longitude)
            bounds.include(point)
            val label = when (index) { 0 -> "起"; locations.lastIndex -> "终"; else -> "$index" }
            val color = if (index == locations.lastIndex) Color.rgb(200, 108, 29) else Color.rgb(30, 90, 68)
            map.addMarker(MarkerOptions().position(point).anchor(.5f, .5f)
                .icon(BitmapDescriptorFactory.fromBitmap(routeMarker(label, color, density))).title(when(index) {
                0 -> "起点 · ${place.name}"
                locations.lastIndex -> "终点 · ${place.name}"
                else -> "$index · ${place.name}"
            }))?.let(markers::add)
        }
        val legs = result.getJSONArray("legs")
        for (i in 0 until legs.length()) {
            val points = routePoints(legs.getJSONObject(i).optString("polyline")).map { LatLng(it.first, it.second) }
            points.forEach(bounds::include)
            if (points.size >= 2) {
                map.addPolyline(PolylineOptions().addAll(points).width(8f * density).color(Color.WHITE).zIndex(1f))?.let(lines::add)
                map.addPolyline(PolylineOptions().addAll(points).width(5f * density).color(Color.rgb(30, 90, 68)).zIndex(2f))?.let(lines::add)
            }
        }
        val built = bounds.build()
        val paddingPx = (40 * density).toInt().coerceAtMost(minOf(size.width, size.height) / 4).coerceAtLeast(10)
        if (built.southwest.latitude == built.northeast.latitude && built.southwest.longitude == built.northeast.longitude) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(built.southwest, 15f))
        } else {
            runCatching {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(built, size.width, size.height, paddingPx))
            }.onFailure {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(built.southwest, 14f))
            }
        }
    }
}

private fun routeMarker(label: String, color: Int, density: Float): Bitmap {
    val size = (32 * density).toInt().coerceAtLeast(32)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    paint.color = Color.WHITE
    canvas.drawCircle(center, center, center, paint)
    paint.color = color
    canvas.drawCircle(center, center, center - 2 * density, paint)
    paint.color = Color.WHITE
    paint.textSize = 14 * density
    paint.isFakeBoldText = true
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(label, center, center - (paint.ascent() + paint.descent()) / 2, paint)
    return bitmap
}
