package com.onmyway.app

import android.Manifest
import android.content.Intent
import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
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
internal fun EmbeddedNavigationScreen(sessionId: String, mode: String, destination: Place, via: List<Place>, onStarted: () -> Unit, onExit: (Boolean) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val hostView = LocalView.current
    val exit by rememberUpdatedState(onExit)
    var confirmExit by remember { mutableStateOf(false) }
    val started by rememberUpdatedState(onStarted)
    val controller = remember(sessionId, mode, destination, via) {
        NavigationRuntime.obtain(context, sessionId, mode, destination, via)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.permissionResult()
    }
    var notificationAllowed by remember { mutableStateOf(notificationsAllowed(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAllowed = notificationsAllowed(context)
    }
    BackHandler { confirmExit = true }
    DisposableEffect(controller, owner) {
        controller.attach(context, { started() }, { confirmExit = true })
        val previousKeepScreenOn = hostView.keepScreenOn
        hostView.keepScreenOn = true
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.resume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.resume()
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        controller.startIfAllowed()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            hostView.keepScreenOn = previousKeepScreenOn
            controller.detach()
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationAllowed) {
            TextButton({ notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("允许导航通知，方便锁屏后返回导航")
            }
        }
        Box(Modifier.weight(1f)) {
            controller.Content { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        }
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text(if (controller.arrived) "确认到达终点？" else "结束本次导航？") },
        text = { Text("导航结束后方案会保存在行程记录；下次出发重新定位并规划，未办事项保留。") },
        confirmButton = { TextButton({ confirmExit = false; exit(controller.arrived) }) { Text(if (controller.arrived) "确认到达" else "结束导航") } },
        dismissButton = { TextButton({ confirmExit = false }) { Text("继续导航") } },
    )
}

internal class NavigationController(
    private val context: Context,
    val sessionId: String,
    private val mode: String,
    private val destination: Place,
    private val via: List<Place>,
) {
    val runId = java.util.UUID.randomUUID().toString()
    var serviceLease = 0L
        private set
    private var engineStartIssued = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startCommit: Job? = null
    private var originRequest: Job? = null
    private var originMeasuredAt = 0L
    private var onStarted: () -> Unit = {}
    private var onExit: () -> Unit = {}
    private var viewContext: Context? = null
    var phase by mutableStateOf(NavigationPhase.PREPARING)
        private set
    val needsService get() = phase.needsService
    private fun transition(event: NavigationEvent) { phase = navigationTransition(phase, event) }
    fun matches(id: String, travelMode: String, end: Place, stops: List<Place>) =
        sessionId == id && mode == travelMode && destination == end && via == stops
    private var disposed = false
    private var engine: AMapNavi? = null
    private var view by mutableStateOf<AMapNaviView?>(null)
    private var status by mutableStateOf("准备导航")
    private val ready get() = phase in setOf(NavigationPhase.READY, NavigationPhase.PAUSED)
    private val running get() = phase in setOf(NavigationPhase.NAVIGATING, NavigationPhase.REPLANNING)
    val arrived get() = phase == NavigationPhase.ARRIVED
    private val calculating get() = phase in setOf(NavigationPhase.CALCULATING, NavigationPhase.REPLANNING)
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
        if (calculating || phase == NavigationPhase.STARTING) serviceFailed("导航准备超时，请检查网络后重试")
    }
    private val listener = object : SimpleNaviListener() {
        override fun onInitNaviSuccess() { if (!disposed) { initialized = true; handler.post { if (!disposed) calculate() } } }
        override fun onInitNaviFailure() { if (!disposed) serviceFailed("导航初始化失败，请检查 Android Key") }
        override fun onStartNavi(type: Int) {
            if (disposed || phase != NavigationPhase.STARTING) return
            if (startCommit != null) return
            startCommit = scope.launch {
                try {
                    val database = TaskDatabase.get(context)
                    database.withTransaction {
                        val trip = database.trips().load()
                        check(trip != null && trip.sessionId == sessionId) { "导航方案已变化" }
                        val opened = trip.copy(started = true,
                            startedAt = trip.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
                        database.trips().save(opened)
                        if (!trip.started) database.trips().record(RouteHistory(sessionId = sessionId, planId = trip.planId,
                            state = NavigationState.NAVIGATION_OPENED.name, timestamp = opened.startedAt,
                            requestJson = trip.requestJson, resultJson = trip.resultJson))
                    }
                    if (!disposed && phase == NavigationPhase.STARTING) {
                        handler.removeCallbacks(timeout)
                        transition(NavigationEvent.SDK_STARTED)
                        status = "导航中 · 请留意实际道路"
                        onStarted()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { serviceFailed("导航状态保存失败，已停止导航，请重试") }
            }
        }
        @Deprecated("SDK compatibility callback")
        override fun onCalculateRouteSuccess(ids: IntArray?) { routeReady() }
        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) { routeReady() }
        @Deprecated("SDK compatibility callback")
        override fun onCalculateRouteFailure(code: Int) { routeFailed(code) }
        override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) { routeFailed(result?.errorCode ?: -1) }
        override fun onNaviInfoUpdate(info: NaviInfo?) {
            if (running && info != null) {
                status = "剩余 ${formatDuration(info.pathRetainTime)} · ${formatDistance(info.pathRetainDistance)}"
                turnIcon = info.iconBitmap
                if (!spokenInstruction) instruction = "前方 ${formatDistance(info.curStepRetainDistance)} · ${info.nextRoadName.orEmpty()}"
            }
        }
        override fun onGetNavigationText(text: String?) { if (!text.isNullOrBlank()) { instruction = text; spokenInstruction = true } }
        @Deprecated("SDK compatibility callback")
        override fun onGetNavigationText(type: Int, text: String?) { if (!text.isNullOrBlank()) { instruction = text; spokenInstruction = true } }
        override fun onManeuverInfoUpdate(type: Int, bitmap: android.graphics.Bitmap?) { turnIcon = bitmap }
        override fun onArriveDestination() {
            if (disposed || !running) return
            transition(NavigationEvent.ARRIVE)
            stopEngine()
            stopService()
            status = "已到达终点，请确认到达"
        }
        override fun onGpsSignalWeak(weak: Boolean) { if (weak && running) status = "定位信号较弱，请留意实际道路" }
        override fun onReCalculateRouteForYaw() {
            if (disposed || !running) return
            transition(NavigationEvent.REPLAN)
            status = "正在重新计算路线"
            handler.removeCallbacks(timeout); handler.postDelayed(timeout, 30_000)
        }
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
                            TextButton({ onExit() }) { Text("结束导航") }
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
                                Text("导航期间，高德 SDK 会使用位置、设备及网络信息，提供实时引导。结束导航后方案会保存在行程记录。")
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
                                    running -> pauseNavigation()
                                    ready -> startNavigation()
                                    else -> if (engine == null) initialize() else calculate()
                                }
                            }, enabled = !calculating && phase != NavigationPhase.STARTING, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(if (phase == NavigationPhase.STARTING) "正在启动…" else if (running) "暂停导航" else if (phase == NavigationPhase.PAUSED) "重新定位并继续" else if (ready) "开始导航" else "重新算路")
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
        if (!consent || !hasPermission) return
        if (engine != null) {
            if (initialized && phase == NavigationPhase.FAILED) calculate()
            return
        }
        try {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            engine = AMapNavi.getInstance(context.applicationContext).also {
                it.setUseInnerVoice(true)
                it.addAMapNaviListener(listener)
            }
            createView()
            if (initialized) calculate()
        } catch (_: Exception) { serviceFailed("导航初始化失败，请检查配置后返回重试") }
    }

    private fun calculate() {
        val navi = engine ?: return
        if (calculating) return
        if (needsService || disposed) return
        stopEngine()
        startRequested = true
        transition(NavigationEvent.CALCULATE)
        status = "正在获取当前位置"
        originRequest = scope.launch {
            val origin = try {
                val fix = readCurrentLocation(context)
                originMeasuredAt = fix.elapsedRealtimeNanos / 1_000_000L
                com.amap.api.maps.CoordinateConverter(context)
                    .from(com.amap.api.maps.CoordinateConverter.CoordType.GPS)
                    .coord(LatLng(fix.latitude, fix.longitude)).convert()
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                serviceFailed("定位超时，请开启精确位置并到信号较好的地方重试")
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                serviceFailed(e.message ?: "无法获取当前位置，请检查定位后重试")
                return@launch
            }
            if (disposed || phase != NavigationPhase.CALCULATING) return@launch
            originRequest = null
            status = "正在从当前位置计算路线"
            handler.removeCallbacks(timeout); handler.postDelayed(timeout, 30_000)
            fun Place.poi() = NaviPoi(name, LatLng(latitude, longitude), id.takeIf { it.startsWith("B") })
            val accepted = try {
                if (via.isEmpty()) {
                    val start = NaviLatLng(origin.latitude, origin.longitude)
                    val end = NaviLatLng(destination.latitude, destination.longitude)
                    if (mode == "walking") navi.calculateWalkRoute(start, end) else navi.calculateRideRoute(start, end)
                } else {
                    val start = NaviPoi("当前位置", origin, null)
                    if (mode == "walking") navi.calculateWalkRoute(start, via.map { it.poi() }, destination.poi(), TravelStrategy.SINGLE)
                    else navi.calculateRideRoute(start, via.map { it.poi() }, destination.poi(), TravelStrategy.SINGLE)
                }
            } catch (_: Exception) { false }
            if (!accepted) routeFailed(-1)
        }
    }

    private fun routeReady() {
        if (disposed || !calculating) return
        handler.removeCallbacks(timeout)
        transition(NavigationEvent.ROUTE_READY)
        if (startRequested && foreground) startNavigation(checkFreshness = false)
        if (ready) {
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
        if (disposed || !calculating) return
        val reason = when (code) {
            3 -> "起点无效，请重新定位后重试"
            6 -> "终点无效，请重新选择目的地"
            10 -> "起点附近没有可通行道路，请调整位置后重试"
            11 -> "没有找到通向终点的道路，请调整目的地"
            12, 21 -> "途经点无法通行，请调整途经点"
            13, 22 -> "导航授权校验失败，请检查 Android Key、包名和签名配置"
            else -> "算路失败，请检查网络后重试"
        }
        serviceFailed("$reason（$code）")
    }

    private fun startNavigation(checkFreshness: Boolean = true) {
        if (!ready || !foreground || disposed) return
        if (checkFreshness && requiresFreshNavigationRoute(phase,
                android.os.SystemClock.elapsedRealtime() - originMeasuredAt)) {
            calculate()
            return
        }
        if (!hasLocationPermission(context)) {
            serviceFailed("位置权限已关闭，请重新允许后重试")
            hasPermission = false
            return
        }
        startRequested = false
        transition(NavigationEvent.START)
        serviceLease++
        engineStartIssued = false
        startCommit = null
        status = "正在启动导航"
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 15_000)
        try {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, NavigationService::class.java).putExtra("run_id", runId).putExtra("lease", serviceLease))
        } catch (_: Exception) { serviceFailed("无法启动后台导航，请返回前台并检查定位权限后重试") }
    }

    fun serviceReady() {
        if (disposed || phase != NavigationPhase.STARTING || engineStartIssued) return
        engineStartIssued = true
        val accepted = runCatching { engine?.startNavi(NaviType.GPS) == true }.getOrDefault(false)
        if (!accepted) serviceFailed("导航启动失败，请重试")
        // Only onStartNavi confirms that navigation actually began.
    }
    fun serviceFailed(reason: String) {
        if (disposed) return
        handler.removeCallbacks(timeout)
        transition(NavigationEvent.FAIL)
        startRequested = false
        stopEngine()
        stopService()
        status = reason
    }
    private fun pauseNavigation() {
        transition(NavigationEvent.PAUSE)
        startRequested = false
        handler.removeCallbacks(timeout)
        stopEngine()
        stopService()
        status = "导航已暂停，继续时将从当前位置重新算路"
    }
    private fun stopEngine() {
        originRequest?.cancel(); originRequest = null
        startCommit?.cancel(); startCommit = null
        runCatching { engine?.stopNavi() }
        runCatching { engine?.stopGPS() }
    }
    private fun stopService() { serviceLease++; context.stopService(Intent(context, NavigationService::class.java)) }

    fun attach(activityContext: Context, started: () -> Unit, exit: () -> Unit) {
        viewContext = activityContext
        onStarted = started
        onExit = exit
        if (engine != null) createView()
        if (running) onStarted()
    }
    private fun createView() {
        val activityContext = viewContext ?: return
        if (view != null) return
        view = AMapNaviView(activityContext).also {
            it.viewOptions = it.viewOptions.apply { setLayoutVisible(false) }
            it.setAMapNaviViewListener(viewListener)
            it.onCreate(null)
            it.setOnMapLoadedListener { drawRoute() }
            if (foreground) it.onResume()
        }
    }
    fun resume() {
        foreground = true
        view?.onResume()
        if (needsService && !hasLocationPermission(context)) {
            hasPermission = false
            serviceFailed("位置权限已关闭，导航已停止，请允许定位后重试")
        }
        if (startRequested && ready) startNavigation()
    }
    fun pause() { foreground = false; view?.onPause() }
    fun detach() {
        foreground = false
        routeLine?.remove(); routeLine = null
        view?.onPause(); view?.onDestroy(); view = null
        viewContext = null
        onStarted = {}; onExit = {}
        if (disposed && (NavigationRuntime.current == null || NavigationRuntime.current === this)) AMapNavi.destroy()
    }
    fun destroy() {
        if (disposed) return
        disposed = true
        scope.cancel()
        transition(NavigationEvent.CLOSE)
        handler.removeCallbacksAndMessages(null)
        stopEngine()
        engine?.removeAMapNaviListener(listener)
        stopService()
        if (view == null) AMapNavi.destroy()
    }
}
