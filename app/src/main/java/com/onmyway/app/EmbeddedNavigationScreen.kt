package com.onmyway.app

import android.Manifest
import android.content.Intent
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AmapPageType
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.TravelStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.navi.model.NaviPoi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun EmbeddedNavigationScreen(mode: String, destination: Place, via: List<Place>, onExit: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val hostView = LocalView.current
    val exit by rememberUpdatedState(onExit)
    var confirmExit by remember { mutableStateOf(false) }
    val controller = remember(mode, destination, via) { NavigationController(context, mode, destination, via) { confirmExit = true } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.permissionResult()
    }
    BackHandler { confirmExit = true }
    DisposableEffect(controller, owner) {
        val previousKeepScreenOn = hostView.keepScreenOn
        hostView.keepScreenOn = true
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.resume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.resume()
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                Lifecycle.Event.ON_STOP -> controller.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        controller.startIfAllowed()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            hostView.keepScreenOn = previousKeepScreenOn
            controller.destroy()
        }
    }
    controller.Content { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("退出导航？") },
        text = { Text("停止导航引导，行程和待办会保留。") },
        confirmButton = { TextButton({ confirmExit = false; exit() }) { Text("退出导航") } },
        dismissButton = { TextButton({ confirmExit = false }) { Text("继续导航") } },
    )
}

private class NavigationController(
    private val context: Context,
    private val mode: String,
    private val destination: Place,
    private val via: List<Place>,
    private val onExit: () -> Unit,
) {
    private var disposed = false
    private var engine: AMapNavi? = null
    private var view by mutableStateOf<AMapNaviView?>(null)
    private var status by mutableStateOf("准备导航")
    private var ready by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var calculating by mutableStateOf(false)
    private var consent by mutableStateOf(false)
    private var hasPermission by mutableStateOf(false)
    private var initialized = false
    private var startRequested = true
    private var instruction by mutableStateOf("正在准备路线")
    private var turnIcon by mutableStateOf<android.graphics.Bitmap?>(null)
    private var spokenInstruction = false
    private var routeLine: Polyline? = null
    private var foreground = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeout = Runnable {
        if (calculating) { calculating = false; status = "算路超时，请检查网络后重试" }
    }
    private val listener = object : SimpleNaviListener() {
        override fun onInitNaviSuccess() { initialized = true; if (view != null) calculate() }
        override fun onInitNaviFailure() { calculating = false; status = "导航初始化失败，请检查 Android Key" }
        @Deprecated("SDK compatibility callback")
        override fun onCalculateRouteSuccess(ids: IntArray?) { routeReady() }
        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) { routeReady() }
        @Deprecated("SDK compatibility callback")
        override fun onCalculateRouteFailure(code: Int) { routeFailed(code) }
        override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) { routeFailed(result?.errorCode ?: -1) }
        override fun onNaviInfoUpdate(info: NaviInfo?) {
            if (running && info != null) {
                status = "剩余 ${(info.pathRetainTime + 59) / 60} 分钟 · ${info.pathRetainDistance} 米"
                turnIcon = info.iconBitmap
                if (!spokenInstruction) instruction = "前方 ${info.curStepRetainDistance} 米 · ${info.nextRoadName.orEmpty()}"
            }
        }
        override fun onGetNavigationText(text: String?) { if (!text.isNullOrBlank()) { instruction = text; spokenInstruction = true } }
        @Deprecated("SDK compatibility callback")
        override fun onGetNavigationText(type: Int, text: String?) { if (!text.isNullOrBlank()) { instruction = text; spokenInstruction = true } }
        override fun onManeuverInfoUpdate(type: Int, bitmap: android.graphics.Bitmap?) { turnIcon = bitmap }
        override fun onArriveDestination() {
            engine?.stopNavi(); running = false; ready = false
            status = "已到达终点，请返回行程确认完成"
        }
        override fun onGpsSignalWeak(weak: Boolean) { if (weak && running) status = "定位信号较弱，请留意实际道路" }
        override fun onReCalculateRouteForYaw() { status = "正在重新计算路线" }
    }
    private val viewListener = object : AMapNaviViewListener {
        override fun onNaviCancel() { onExit() }
        override fun onNaviBackClick(): Boolean { onExit(); return true }
        override fun onAMapNaviViewExit() { onExit() }
        override fun onNaviViewLoaded() { if (ready && !running) view?.displayOverview() }
        override fun onNaviSetting() {}
        override fun onNaviMapMode(mode: Int) {}
        override fun onNaviTurnClick() {}
        override fun onNextRoadClick() {}
        override fun onScanViewButtonClick() {}
        override fun onLockMap(locked: Boolean) {}
        override fun onMapTypeChanged(type: Int) {}
        override fun onNaviViewShowMode(mode: Int) {}
        override fun onStopSpeaking() {}
        override fun onViewTypeChanged(type: AmapPageType?) {}
        override fun onStrategyChanged(strategy: Int) {}
        override fun onBroadcastModeChanged(mode: Int) {}
        override fun onDayAndNightModeChanged(mode: Int) {}
        override fun onScaleAutoChanged(enabled: Boolean) {}
        override fun onListenToVoiceDuringCallChanged(enabled: Boolean) {}
        override fun onControlMusicVolumeModeChanged(mode: Int) {}
        override fun onEagleChanged(enabled: Boolean) {}
        override fun onNaviRouteHighlightChange(path: Long, type: Int) {}
    }

    fun startIfAllowed() {
        consent = context.getSharedPreferences("embedded_navigation", Context.MODE_PRIVATE).getBoolean("consent", false)
        hasPermission = hasLocationPermission(context)
        if (consent && hasPermission && BuildConfig.AMAP_CONFIGURED) initialize()
    }

    @Composable
    fun Content(requestPermission: () -> Unit) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ onExit() }) { Text("返回行程") }
                            Text(if (mode == "walking") "步行导航" else "自行车导航", Modifier.padding(16.dp))
                        }
                        Text(status, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        if (running) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                turnIcon?.takeUnless { it.isRecycled }?.let { Image(it.asImageBitmap(), "转向提示", Modifier.size(40.dp)) }
                                Text(instruction, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (!BuildConfig.AMAP_CONFIGURED) {
                            Text("请配置 local.properties 中的 amap.android.key 后重新安装。", Modifier.padding(20.dp))
                        } else if (!consent) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("导航期间，高德 SDK 会使用位置、设备及网络信息，提供实时引导。离开此页面会停止导航，行程继续保留。")
                                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, "https://lbs.amap.com/pages/privacy/".toUri())) }) { Text("高德隐私政策") }
                                Button({
                                    context.getSharedPreferences("embedded_navigation", Context.MODE_PRIVATE).edit().putBoolean("consent", true).apply()
                                    consent = true
                                    requestNavigation(requestPermission)
                                }) { Text("同意并继续") }
                            }
                        } else if (!hasPermission) {
                            Button({ requestNavigation(requestPermission) }, Modifier.padding(20.dp)) { Text("允许使用位置") }
                        } else {
                            val currentView = view
                            if (currentView != null) AndroidView(factory = { currentView }, modifier = Modifier.weight(1f).fillMaxWidth())
                            else Spacer(Modifier.weight(1f))
                            if (calculating) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Button({
                                when {
                                    running -> { startRequested = false; engine?.stopNavi(); running = false; status = "导航已暂停" }
                                    ready -> {
                                        running = engine?.startNavi(NaviType.GPS) == true
                                        status = if (running) "导航中 · 请留意实际道路" else "导航启动失败，请重试"
                                    }
                                    else -> if (engine == null) initialize() else calculate()
                                }
                            }, enabled = !calculating, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(if (running) "暂停导航" else if (ready) "开始导航" else "重新算路")
                            }
                        }
                    }
                }
    }

    fun permissionResult() {
        hasPermission = hasLocationPermission(context)
        if (hasPermission) initialize()
    }

    private fun requestNavigation(requestPermission: () -> Unit) {
        if (hasLocationPermission(context)) { hasPermission = true; initialize() }
        else requestPermission()
    }

    private fun initialize() {
        if (engine != null || !consent || !hasPermission) return
        try {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            engine = AMapNavi.getInstance(context.applicationContext).also {
                it.setUseInnerVoice(true)
                it.addAMapNaviListener(listener)
            }
            view = AMapNaviView(context).also {
                it.viewOptions = it.viewOptions.apply { setLayoutVisible(false) }
                it.setAMapNaviViewListener(viewListener)
                it.onCreate(null)
                it.setOnMapLoadedListener { drawRoute() }
                if (foreground) it.onResume()
            }
            if (initialized) calculate()
        } catch (_: Exception) { status = "导航初始化失败，请检查配置后返回重试" }
    }

    private fun calculate() {
        val navi = engine ?: return
        if (calculating) return
        navi.stopNavi(); running = false; ready = false; calculating = true
        status = "正在从当前位置计算路线"
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 30_000)
        fun Place.poi() = NaviPoi(name, LatLng(latitude, longitude), id.takeIf { it.startsWith("B") })
        val accepted = try {
            if (via.isEmpty()) {
                val end = NaviLatLng(destination.latitude, destination.longitude)
                if (mode == "walking") navi.calculateWalkRoute(end) else navi.calculateRideRoute(end)
            } else {
                if (mode == "walking") navi.calculateWalkRoute(null, via.map { it.poi() }, destination.poi(), TravelStrategy.SINGLE)
                else navi.calculateRideRoute(null, via.map { it.poi() }, destination.poi(), TravelStrategy.SINGLE)
            }
        } catch (_: Exception) { false }
        if (!accepted) routeFailed(-1)
    }

    private fun routeReady() {
        if (disposed) return
        handler.removeCallbacks(timeout)
        calculating = false; ready = true
        if (startRequested && foreground) {
            startRequested = false
            running = engine?.startNavi(NaviType.GPS) == true
            status = if (running) "导航中 · 请留意实际道路" else "导航启动失败，请重试"
        }
        if (!running) {
            status = "路线已就绪" + if (via.isEmpty()) "" else " · 途经 ${via.size} 站"
            view?.displayOverview()
        }
        drawRoute()
    }

    private fun drawRoute() {
        if (disposed) return
        val points = engine?.naviPath?.coordList?.map { LatLng(it.latitude, it.longitude) }.orEmpty()
        if (points.size < 2) return
        routeLine?.remove()
        routeLine = view?.map?.addPolyline(PolylineOptions().addAll(points)
            .width(6f * context.resources.displayMetrics.density).color(android.graphics.Color.rgb(36, 107, 80)))
    }

    private fun routeFailed(code: Int) {
        if (disposed) return
        handler.removeCallbacks(timeout)
        calculating = false; ready = false
        status = "算路失败（$code），请检查网络和导航权限" + if (via.isEmpty()) "" else "，包括途经点权限"
    }

    fun resume() { foreground = true; view?.onResume() }
    fun pause() { view?.onPause() }
    fun stop() {
        foreground = false
        startRequested = false
        if (running) { engine?.stopNavi(); running = false; status = "导航已暂停" }
    }
    fun destroy() {
        disposed = true
        handler.removeCallbacksAndMessages(null)
        engine?.stopNavi(); engine?.removeAMapNaviListener(listener)
        view?.onPause(); view?.onDestroy()
        if (engine != null) AMapNavi.destroy()
    }
}
