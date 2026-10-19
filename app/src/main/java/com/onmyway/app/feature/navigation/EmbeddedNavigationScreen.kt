package com.onmyway.app.feature.navigation

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.withTransaction
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
import com.onmyway.app.BuildConfig
import com.onmyway.app.R
import com.onmyway.app.core.format.formatDistance
import com.onmyway.app.core.format.formatDuration
import com.onmyway.app.core.location.hasLocationPermission
import com.onmyway.app.core.location.readCurrentLocation
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.reminder.notificationsAllowed
import com.onmyway.app.core.ui.AppConfirmDialog
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.RouteHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@Composable
internal fun EmbeddedNavigationScreen(
    sessionId: String,
    mode: String,
    destination: Place,
    via: List<Place>,
    onStarted: () -> Unit,
    onArrived: () -> Unit = {},
    onFailed: (String) -> Unit = {},
    onAdjustRoute: () -> Unit = {},
    autoStart: Boolean = true,
    onExit: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val hostView = LocalView.current
    val exit by rememberUpdatedState(onExit)
    var confirmExit by remember { mutableStateOf(false) }
    val started by rememberUpdatedState(onStarted)
    val arrived by rememberUpdatedState(onArrived)
    val failed by rememberUpdatedState(onFailed)
    val controller = remember(sessionId, mode, destination, via) {
        NavigationRuntime.obtain(
            context, sessionId, mode, destination, via,
            requireExplicitStart = !autoStart,
        )
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
        controller.attach(context,
            started = { started() },
            arrived = { arrived() },
            failed = { failed(it) },
            exit = { confirmExit = true })
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
    controller.Content(
        requestPermission = {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        requestNotification = {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        notificationAllowed = notificationAllowed,
        onAdjustRoute = onAdjustRoute,
    )
    val active = controller.phase in setOf(
        NavigationPhase.READY,
        NavigationPhase.NAVIGATING,
        NavigationPhase.REPLANNING,
        NavigationPhase.PAUSED,
    )
    if (confirmExit) AppConfirmDialog(
        title = when {
            controller.arrived -> "确认到达终点？"
            active -> "结束本次导航？"
            else -> "返回行程？"
        },
        message = if (active) {
            "导航结束后方案会保存在行程记录；下次出发重新定位并规划，未办事项保留。"
        } else {
            "尚未开始导航。返回后仍可从行程页重新查看方案。"
        },
        confirmLabel = when {
            controller.arrived -> "确认到达"
            active -> "结束导航"
            else -> "返回行程"
        },
        onConfirm = { confirmExit = false; exit(controller.arrived) },
        onDismiss = { confirmExit = false },
        dismissLabel = if (active) "继续导航" else "留在导航",
    )
}

internal class NavigationController(
    private val context: Context,
    val sessionId: String,
    private val mode: String,
    private val destination: Place,
    private val via: List<Place>,
    requireExplicitStart: Boolean = false,
    private val meter: NavigationMeter = NavigationMeter(),
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
    private var onArrived: () -> Unit = {}
    private var onFailed: (String) -> Unit = {}
    private var failureReported = false
    private var arrivalReported = false
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
    // Set when a confirmed adjustment replaced a running route: reaching READY must not auto-start.
    private var requireExplicitStart = requireExplicitStart
    private var instruction by mutableStateOf("正在准备路线")
    private var turnIcon by mutableStateOf<android.graphics.Bitmap?>(null)
    private var spokenInstruction = false
    private val stops = via + destination
    private var currentStopIndex by mutableStateOf(0)
    private var stepRetainDistance by mutableStateOf(0)
    private var routeRetainDistance by mutableStateOf(0)
    private var routeRetainTime by mutableStateOf(0)
    private var legRetainDistance by mutableStateOf(0)
    private var legRetainTime by mutableStateOf(0)
    private var currentRoad by mutableStateOf("")
    private var nextRoad by mutableStateOf("")
    private var gpsWeak by mutableStateOf(false)
    private var sawViaInfo = false
    private var routeLine: Polyline? = null
    private var foreground = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timeout = Runnable {
        if (calculating || phase == NavigationPhase.STARTING) serviceFailed("导航准备超时，请检查网络后重试")
    }
    private val listener = object : SimpleNaviListener() {
        override fun onInitNaviSuccess() {
            NavigationRuntime.naviInitialized = true
            if (!disposed) { initialized = true; handler.post { if (!disposed) calculate() } }
        }
        override fun onInitNaviFailure() { if (!disposed) serviceFailed("导航初始化失败，请检查 Android Key") }
        override fun onStartNavi(type: Int) {
            if (disposed || phase != NavigationPhase.STARTING) return
            if (startCommit != null) return
            meter.start(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
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
                        gpsWeak = false
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
                meter.sample(info.pathRetainDistance)
                routeRetainDistance = info.pathRetainDistance
                routeRetainTime = info.pathRetainTime
                stepRetainDistance = info.curStepRetainDistance
                currentRoad = info.currentRoadName.orEmpty()
                nextRoad = info.nextRoadName.orEmpty()
                turnIcon = info.iconBitmap
                val viaInfos = info.toViaInfo.orEmpty()
                val nextVia = viaInfos.firstOrNull()
                if (nextVia != null) {
                    sawViaInfo = true
                    legRetainDistance = nextVia.distance
                    legRetainTime = nextVia.time
                    // toViaInfo 只列剩余途经点，用数量推算当前站，不依赖未公开的 viaIndex 起点。
                    val byRemaining = via.size - viaInfos.size
                    val candidate = when {
                        byRemaining in stops.indices -> byRemaining
                        nextVia.viaIndex in stops.indices -> nextVia.viaIndex
                        else -> currentStopIndex
                    }
                    currentStopIndex = maxOf(currentStopIndex, candidate)
                } else if (sawViaInfo) {
                    // 途经点信息消失说明只剩最终目的地。
                    legRetainDistance = info.pathRetainDistance
                    legRetainTime = info.pathRetainTime
                    currentStopIndex = stops.lastIndex.coerceAtLeast(0)
                }
                if (!spokenInstruction) {
                    instruction = "前方 ${formatDistance(info.curStepRetainDistance)} · ${info.nextRoadName.orEmpty()}"
                }
            }
        }
        override fun onGetNavigationText(text: String?) { if (!text.isNullOrBlank()) { instruction = text; spokenInstruction = true } }
        @Deprecated("SDK compatibility callback")
        override fun onGetNavigationText(type: Int, text: String?) { if (!text.isNullOrBlank()) { instruction = text; spokenInstruction = true } }
        override fun onManeuverInfoUpdate(type: Int, bitmap: android.graphics.Bitmap?) { turnIcon = bitmap }
        override fun onArriveDestination() {
            if (disposed || !running) return
            currentStopIndex = stops.lastIndex.coerceAtLeast(0)
            gpsWeak = false
            meter.finish(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
            transition(NavigationEvent.ARRIVE)
            stopEngine()
            stopService()
            status = "已到达终点，请确认到达"
            if (!arrivalReported) {
                arrivalReported = true
                onArrived()
            }
        }
        override fun onArrivedWayPoint(wayID: Int) {
            if (disposed || !running) return
            currentStopIndex = (wayID + 1).coerceIn(0, stops.lastIndex.coerceAtLeast(0))
        }
        override fun onGpsSignalWeak(weak: Boolean) { if (running) gpsWeak = weak }
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
    fun Content(
        requestPermission: () -> Unit,
        requestNotification: () -> Unit,
        notificationAllowed: Boolean,
        onAdjustRoute: () -> Unit,
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                !BuildConfig.AMAP_CONFIGURED -> NavigationSetup(
                    icon = R.drawable.ic_route,
                    title = "导航服务未配置",
                    body = "请配置 local.properties 中的 amap.android.key 后重新安装。",
                    actionLabel = "返回行程",
                    onAction = { onExit() },
                    onAdjustRoute = onAdjustRoute,
                )
                !consent -> NavigationSetup(
                    icon = R.drawable.ic_route,
                    title = "开始导航前",
                    body = "导航期间，高德 SDK 会使用位置、设备及网络信息，提供实时引导。结束导航后方案会保存在行程记录。",
                    actionLabel = "同意并继续",
                    onAction = {
                        context.getSharedPreferences("embedded_navigation", Context.MODE_PRIVATE)
                            .edit().putBoolean("consent", true).apply()
                        consent = true
                        requestNavigation(requestPermission)
                    },
                    secondaryLabel = "查看高德隐私政策",
                    onSecondary = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://lbs.amap.com/pages/privacy/".toUri()))
                    },
                    onAdjustRoute = onAdjustRoute,
                )
                !hasPermission -> NavigationSetup(
                    icon = R.drawable.ic_location,
                    title = "导航需要位置权限",
                    body = "允许后从当前位置算路，并在导航中更新剩余距离。",
                    actionLabel = "允许使用位置",
                    onAction = { requestNavigation(requestPermission) },
                    onAdjustRoute = onAdjustRoute,
                )
                else -> NavigationStage(
                    requestNotification = requestNotification,
                    notificationAllowed = notificationAllowed,
                    onAdjustRoute = onAdjustRoute,
                )
            }
        }
    }

    @Composable
    private fun NavigationSetup(
        icon: Int,
        title: String,
        body: String,
        actionLabel: String,
        onAction: () -> Unit,
        onAdjustRoute: () -> Unit,
        secondaryLabel: String? = null,
        onSecondary: (() -> Unit)? = null,
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                NavigationTopBar()
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                        shadowElevation = 6.dp,
                    ) {
                        Column(
                            Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppIconBadge(icon, size = 60.dp)
                            Text(
                                title,
                                Modifier.padding(top = 18.dp),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                body,
                                Modifier.padding(top = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            secondaryLabel?.let {
                                TextButton(onClick = { onSecondary?.invoke() }, modifier = Modifier.padding(top = 4.dp)) {
                                    Text(it)
                                }
                            }
                            AppPrimaryButton(actionLabel, onAction, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
                if (stops.size > 1) {
                    StopStrip()
                    Spacer(Modifier.height(6.dp))
                }
                TextButton(onClick = onAdjustRoute, modifier = Modifier.fillMaxWidth()) {
                    Text("和助手调整路线")
                }
            }
        }
    }

    @Composable
    private fun NavigationStage(
        requestNotification: () -> Unit,
        notificationAllowed: Boolean,
        onAdjustRoute: () -> Unit,
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            view?.let { mapView ->
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            }
            Column(
                Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NavigationTopBar()
                if (!notificationAllowed && !running) {
                    NotificationHint(onRequest = requestNotification)
                }
                InstructionCard()
                if (stops.size > 1 && !arrived) StopStrip()
                if (gpsWeak && running) StatusBanner("定位信号较弱，请留意实际道路")
            }
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                BottomPanel(onAdjustRoute = onAdjustRoute)
            }
        }
    }

    @Composable
    private fun NavigationTopBar() {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                modifier = Modifier.clip(CircleShape).clickable { onExit() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.96f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                shadowElevation = 4.dp,
            ) {
                Row(
                    Modifier.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_close), null, Modifier.size(16.dp))
                    Text("结束", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavPill(
                    if (mode == "walking") "步行" else "骑行",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (stops.size > 1) {
                    NavPill(
                        "${stops.size} 站",
                        MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.96f),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        bordered = true,
                    )
                }
            }
        }
    }

    @Composable
    private fun NavPill(text: String, container: Color, content: Color, bordered: Boolean = false) {
        Surface(
            shape = CircleShape,
            color = container,
            contentColor = content,
            border = if (bordered) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)) else null,
            shadowElevation = if (bordered) 3.dp else 0.dp,
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun NotificationHint(onRequest: () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onRequest),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 3.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_clock), null, Modifier.size(17.dp))
                Text("开启通知，锁屏后更容易回到导航", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("允许", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    @Composable
    private fun InstructionCard() {
        val active = running
        val kicker: String
        val title: String
        val detail: String
        when {
            arrived -> {
                kicker = "ARRIVED"
                title = "已到达 ${destination.name}"
                detail = "请在行程页确认到达，并标记办完的事项。"
            }
            active -> {
                kicker = if (phase == NavigationPhase.REPLANNING) "正在重新算路" else "下一步"
                title = instruction.ifBlank { "沿路线前进" }
                detail = roadLine()
            }
            phase == NavigationPhase.PAUSED -> {
                kicker = "PAUSED"
                title = "导航已暂停"
                detail = "继续时将从当前位置重新算路。"
            }
            phase == NavigationPhase.READY -> {
                kicker = "ROUTE READY"
                title = "路线已就绪"
                detail = if (stops.size > 1) "共 ${stops.size} 站，点击下方按钮开始。" else "点击下方按钮开始导航。"
            }
            phase == NavigationPhase.FAILED -> {
                kicker = "NEEDS ATTENTION"
                title = "导航暂时无法继续"
                detail = status
            }
            phase == NavigationPhase.STARTING -> {
                kicker = "STARTING"
                title = "正在启动导航"
                detail = status
            }
            else -> {
                kicker = "PREPARING"
                title = "正在准备路线"
                detail = status
            }
        }
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 10.dp,
        ) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    Modifier.size(58.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val icon = turnIcon?.takeUnless { it.isRecycled }
                        when {
                            arrived -> Icon(painterResource(R.drawable.ic_check), null, Modifier.size(28.dp))
                            icon != null && active -> Image(icon.asImageBitmap(), "转向提示", Modifier.size(42.dp))
                            else -> Icon(painterResource(R.drawable.ic_route), null, Modifier.size(26.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            kicker,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.68f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (active && stepRetainDistance > 0) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)) {
                                Text(
                                    "${formatDistance(stepRetainDistance)}后",
                                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.74f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusBanner(text: String) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 3.dp,
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    @Composable
    private fun StopStrip() {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "路线节点",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (currentStopIndex >= stops.lastIndex) "最后一段" else "第 ${currentStopIndex + 1} / ${stops.size} 站",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 2.dp, end = 12.dp),
            ) {
                itemsIndexed(stops) { index, place ->
                    val done = index < currentStopIndex
                    val current = index == currentStopIndex
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = when {
                            current -> MaterialTheme.colorScheme.primary
                            done -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.94f)
                        },
                        contentColor = when {
                            current -> MaterialTheme.colorScheme.onPrimary
                            done -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        border = if (!current && !done) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)) else null,
                        shadowElevation = if (current) 4.dp else 0.dp,
                    ) {
                        Row(
                            Modifier.padding(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(20.dp).clip(CircleShape).background(
                                    when {
                                        current -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)
                                        done -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (done) {
                                    Icon(
                                        painterResource(R.drawable.ic_check),
                                        null,
                                        Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                place.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 124.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BottomPanel(onAdjustRoute: () -> Unit) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (arrived) "本次导航" else "全程剩余",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                when {
                                    arrived -> "已到达"
                                    phase == NavigationPhase.PAUSED -> "已暂停"
                                    routeRetainTime > 0 -> formatDuration(routeRetainTime)
                                    calculating -> "正在算路"
                                    else -> "等待路线"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            if (!arrived && phase != NavigationPhase.PAUSED && routeRetainDistance > 0) {
                                Text(
                                    formatDistance(routeRetainDistance),
                                    Modifier.padding(bottom = 3.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (stops.size > 1 && !arrived) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Text(
                                "第 ${currentStopIndex + 1}/${stops.size} 站",
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                if (!arrived) {
                    val next = stops.getOrElse(currentStopIndex) { destination }
                    val prefix = if (currentStopIndex >= stops.lastIndex) "终点" else "下一站"
                    val leg = if (legRetainDistance > 0 && (sawViaInfo || currentStopIndex >= stops.lastIndex)) {
                        buildString {
                            append(" · ")
                            append(formatDistance(legRetainDistance))
                            if (legRetainTime > 0) {
                                append(" / ")
                                append(formatDuration(legRetainTime))
                            }
                        }
                    } else {
                        ""
                    }
                    Text(
                        "$prefix · ${next.name}$leg",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (calculating) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (running) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolButton(
                            "全览",
                            R.drawable.ic_route,
                            onClick = { toggleOverview() },
                            modifier = Modifier.weight(0.8f),
                        )
                        ToolButton(
                            "调整",
                            R.drawable.ic_assistant,
                            onClick = onAdjustRoute,
                            modifier = Modifier.weight(0.8f),
                        )
                        AppPrimaryButton(
                            "暂停",
                            { pauseNavigation() },
                            Modifier.weight(1.4f),
                            enabled = !calculating,
                        )
                    }
                } else {
                    AppPrimaryButton(
                        text = primaryLabel(),
                        onClick = ::primaryAction,
                        enabled = !calculating &&
                            phase != NavigationPhase.STARTING &&
                            phase != NavigationPhase.PREPARING,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!arrived) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (ready) {
                                TextButton({ toggleOverview() }) { Text("查看全程") }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            TextButton(onClick = onAdjustRoute) { Text("和助手调整路线") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ToolButton(
        label: String,
        icon: Int,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            modifier = modifier.height(54.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(icon), null, Modifier.size(18.dp))
                Text(
                    label,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    private fun primaryLabel(): String = when {
        phase == NavigationPhase.PREPARING -> "正在准备…"
        phase == NavigationPhase.STARTING -> "正在启动…"
        arrived -> "确认到达"
        phase == NavigationPhase.PAUSED -> "重新定位并继续"
        phase == NavigationPhase.READY -> "开始导航"
        calculating -> "正在准备路线…"
        else -> "重新算路"
    }

    private fun primaryAction() {
        when {
            arrived -> onExit()
            calculating || phase == NavigationPhase.STARTING -> Unit
            ready -> startNavigation()
            engine == null -> initialize()
            else -> calculate()
        }
    }

    private fun roadLine(): String {
        val first = currentRoad.trim()
        val second = nextRoad.trim()
        return when {
            first.isNotEmpty() && second.isNotEmpty() && first != second -> "$first → $second"
            first.isNotEmpty() -> first
            second.isNotEmpty() -> second
            else -> ""
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
            if (initialized || NavigationRuntime.naviInitialized) calculate()
        } catch (_: Exception) { serviceFailed("导航初始化失败，请检查配置后返回重试") }
    }

    private fun calculate() {
        val navi = engine ?: return
        if (calculating) return
        if (needsService || disposed) return
        stopEngine()
        failureReported = false
        arrivalReported = false
        startRequested = true
        currentStopIndex = 0
        stepRetainDistance = 0
        routeRetainDistance = 0
        routeRetainTime = 0
        legRetainDistance = 0
        legRetainTime = 0
        currentRoad = ""
        nextRoad = ""
        gpsWeak = false
        sawViaInfo = false
        transition(NavigationEvent.CALCULATE)
        status = "正在获取当前位置"
        originRequest = scope.launch {
            val origin = try {
                val fix = readCurrentLocation(context, allowCached = false).location
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
        val calculatedRoute = engine?.naviPath
        transition(NavigationEvent.ROUTE_READY)
        // The initial baseline is captured before auto-start; later route
        // replacements (yaw, pause/resume, confirmed adjustment) reset it.
        if (meter.started) meter.rerouted(calculatedRoute?.allLength)
        else meter.initialRoute(calculatedRoute?.allLength)
        if (startRequested && foreground && !requireExplicitStart) startNavigation(checkFreshness = false)
        if (ready) {
            status = "路线已就绪" + if (via.isEmpty()) "" else " · 途经 ${via.size} 站"
            engine?.naviPath?.let {
                routeRetainDistance = it.allLength
                routeRetainTime = it.allTime
            }
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
        failureReported = false
        requireExplicitStart = false
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
        gpsWeak = false
        stopEngine()
        stopService()
        status = reason
        if (!failureReported) {
            failureReported = true
            onFailed(reason)
        }
    }
    private fun pauseNavigation() {
        transition(NavigationEvent.PAUSE)
        startRequested = false
        gpsWeak = false
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

    fun attach(
        activityContext: Context,
        started: () -> Unit,
        arrived: () -> Unit,
        failed: (String) -> Unit,
        exit: () -> Unit,
    ) {
        viewContext = activityContext
        onStarted = started
        onArrived = arrived
        onFailed = failed
        onExit = exit
        if (engine != null) createView()
        if (running) onStarted()
    }
    private fun createView() {
        val activityContext = viewContext ?: return
        if (view != null) return
        view = AMapNaviView(activityContext).also {
            it.viewOptions = it.viewOptions.apply {
                setLayoutVisible(false)
                setAutoChangeZoom(true)
                setAutoNaviViewNightMode(true)
                setAutoLockCar(true)
            }
            it.setAMapNaviViewListener(viewListener)
            it.onCreate(null)
            it.setOnMapLoadedListener { drawRoute() }
            if (foreground) it.onResume()
        }
    }
    fun toggleOverview() {
        val naviView = view ?: return
        if (naviView.isRouteOverviewNow) naviView.recoverLockMode() else naviView.displayOverview()
    }

    fun resume() {
        foreground = true
        view?.onResume()
        if (needsService && !hasLocationPermission(context)) {
            hasPermission = false
            serviceFailed("位置权限已关闭，导航已停止，请允许定位后重试")
        }
        if (startRequested && ready && !requireExplicitStart) startNavigation()
    }
    fun pause() { foreground = false; view?.onPause() }
    fun detach() {
        foreground = false
        routeLine?.remove(); routeLine = null
        view?.onPause(); view?.onDestroy(); view = null
        viewContext = null
        onStarted = {}; onArrived = {}; onFailed = {}; onExit = {}
        if (disposed && (NavigationRuntime.current == null || NavigationRuntime.current === this)) {
            AMapNavi.destroy()
            NavigationRuntime.naviInitialized = false
        }
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
        if (view == null) {
            AMapNavi.destroy()
            NavigationRuntime.naviInitialized = false
        }
    }
}
