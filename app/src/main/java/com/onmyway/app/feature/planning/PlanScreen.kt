package com.onmyway.app.feature.planning

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.onmyway.app.R
import com.onmyway.app.core.format.displayTime
import com.onmyway.app.core.format.formatDuration
import com.onmyway.app.core.location.hasLocationPermission
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.RouteCandidatesResult
import com.onmyway.app.core.model.isIndoor
import com.onmyway.app.core.model.placeTypeIcon
import com.onmyway.app.core.model.routeBlockedLabel
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.state.networkMessage
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppConfirmDialog
import com.onmyway.app.core.ui.AppDateTimeDialog
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppPill
import com.onmyway.app.core.ui.AppPillRow
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.AppRouteArt
import com.onmyway.app.core.ui.AppScreenHeader
import com.onmyway.app.core.ui.AppStatusPill
import com.onmyway.app.core.ui.BottomSheetTitle
import com.onmyway.app.core.ui.ChoiceRow
import com.onmyway.app.core.ui.PlacePicker
import com.onmyway.app.core.ui.SectionLabel
import com.onmyway.app.core.ui.appTextFieldColors
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.feature.navigation.EmbeddedNavigationScreen
import com.onmyway.app.feature.navigation.NavigationConversation
import com.onmyway.app.feature.navigation.nativeRouteUri
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

private fun minutes(seconds: Int) = formatDuration(seconds)

private fun taskPlaceSummary(task: Errand): String = listOfNotNull(
    when {
        isIndoor(task.placeType) -> "不用出门"
        task.place() != null -> task.place()?.name
        else -> "待补办理地点"
    },
    if (!isIndoor(task.placeType)) "${task.serviceMinutes} 分钟" else null,
).joinToString(" · ")

@Composable
internal fun PlanScreen(vm: AppViewModel, tasks: List<Errand>, navigatingHere: Boolean, onNavigationChanged: (Boolean) -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.cancelLocation() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.cancelLocation() }
    }
    BackHandler(vm.editingPlan && !vm.busy) { vm.cancelPlanEdit() }
    if (vm.restoringTrip) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else if (vm.result != null && !vm.editingPlan) {
        TripOverview(vm.result!!, tasks, vm, navigatingHere, onNavigationChanged)
    } else {
        PrepareTrip(vm, tasks)
    }
}

@Composable
private fun PlanAction(label: String, enabled: Boolean, action: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            AppPrimaryButton(label, action, enabled = enabled)
        }
    }
}

@Composable
private fun SummaryRow(title: String, detail: String, enabled: Boolean = true, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = action)
            .heightIn(min = 56.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            null,
            Modifier.padding(start = 10.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun EndpointRow(
    label: String,
    title: String,
    icon: Int,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (title == "选择起点" || title == "去哪里？") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun RouteConnector() {
    val line = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.width(22.dp).height(16.dp)) {
        drawLine(
            line,
            Offset(size.width / 2, 0f),
            Offset(size.width / 2, size.height),
            1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrepareTrip(vm: AppViewModel, tasks: List<Errand>, submit: () -> Unit = vm::plan, cancel: () -> Unit = vm::cancelPlanEdit) {
    val context = LocalContext.current
    var pick by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf("") }
    var pickingArrival by remember { mutableStateOf(false) }
    val pending = tasks.filter { it.status == "pending" }
    val chosen = pending.filter { it.id in vm.selected }
    var eligibility by remember { mutableStateOf<RouteCandidatesResult?>(null) }
    var eligibilityLoading by remember { mutableStateOf(false) }
    var eligibilityError by remember { mutableStateOf("") }
    var eligibilityAttempt by remember { mutableIntStateOf(0) }
    val candidateTasks = pending.map(vm::taskForTrip)
    LaunchedEffect(candidateTasks, eligibilityAttempt) {
        if (pending.isEmpty()) {
            eligibility = null; eligibilityLoading = false; eligibilityError = ""
            return@LaunchedEffect
        }
        eligibilityLoading = true; eligibilityError = ""; eligibility = null
        try {
            val result = vm.routeCandidates(candidateTasks, limit = 6)
            eligibility = result
            val eligible = result.candidates.map { it.taskId }.toSet()
            val pruned = vm.selected.intersect(eligible)
            if (pruned != vm.selected) {
                vm.selected = pruned
                vm.inputsChanged()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            eligibility = null
            eligibilityError = networkMessage(e)
        } finally {
            eligibilityLoading = false
        }
    }
    val blocked = eligibility?.blocked.orEmpty()
    val canPlan = !vm.busy && !vm.locating && vm.origin != null && vm.destination != null &&
        !eligibilityLoading && (pending.isEmpty() || eligibility != null)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) vm.locate() else vm.message = "未获得定位权限，可手动选择起点。"
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        AppScreenHeader(
            if (vm.editingPlan) "调整行程" else "顺路出门",
            kicker = "PLAN",
            subtitle = if (vm.editingPlan) "改完重新规划，不改动待办本身" else "起点、终点，加上顺路的小事",
            actions = { if (vm.editingPlan) TextButton(cancel, enabled = !vm.busy) { Text("取消") } },
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            EndpointRow(
                                "起点",
                                vm.origin?.name ?: "选择起点",
                                R.drawable.ic_location,
                                MaterialTheme.colorScheme.primary,
                                !vm.busy && !vm.locating,
                                { pick = "origin" },
                            )
                        }
                        TextButton(onClick = {
                            if (hasLocationPermission(context)) vm.locate()
                            else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, enabled = !vm.busy && !vm.locating) { Text(if (vm.locating) "定位中" else "定位") }
                    }
                    if (vm.locating) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().padding(start = 32.dp, bottom = 2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    RouteConnector()
                    EndpointRow(
                        "终点",
                        vm.destination?.name ?: "去哪里？",
                        R.drawable.ic_place_pin,
                        MaterialTheme.colorScheme.tertiary,
                        !vm.busy,
                        { sheet = "destination" },
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            item {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    SectionLabel("怎么走")
                    Spacer(Modifier.height(10.dp))
                    AppPillRow {
                        AppPill("步行", vm.mode == "walking", {
                            vm.mode = "walking"; vm.inputsChanged()
                        }, enabled = !vm.busy)
                        AppPill("骑行", vm.mode == "cycling", {
                            vm.mode = "cycling"; vm.inputsChanged()
                        }, enabled = !vm.busy)
                    }
                }
            }
            item {
                AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    SummaryRow(
                        if (chosen.isEmpty()) "这次办什么" else "已选 ${chosen.size} 件事",
                        if (chosen.isEmpty()) "不选事项，直接去目的地" else chosen.take(3).joinToString("、") { it.title } + if (chosen.size > 3) "等" else "",
                        !vm.busy,
                    ) { sheet = "tasks" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    SummaryRow(
                        "出行要求",
                        listOf(
                            when (vm.preference) { "fastest" -> "少花时间"; else -> "多办事情" },
                            if (vm.partial) "允许部分完成" else "全部完成",
                            if (vm.extra.isBlank()) "额外时间不限" else "最多多花 ${vm.extra} 分钟",
                            if (vm.arriveBy.isBlank()) "到达不限" else "${vm.arriveBy} 前到达",
                        ).joinToString(" · "),
                        !vm.busy,
                    ) { sheet = "conditions" }
                }
            }
            if (eligibilityError.isNotBlank()) item {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    Text("暂时无法检查待办能否参与路线", style = MaterialTheme.typography.titleSmall)
                    Text(
                        eligibilityError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton({ eligibilityAttempt++ }, enabled = !eligibilityLoading) { Text("重新检查") }
                }
            }
            if (blocked.isNotEmpty()) item {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    SectionLabel("本次不参与路线")
                    blocked.forEach { state ->
                        Text(
                            "${state.title} · ${routeBlockedLabel(state.reason)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "补全办理地点或改为「不用出门」后，会重新检查。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (vm.busy) item {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("正在查询路线，首次可能需要 1–3 分钟。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (vm.editingPlan && vm.tripPlaces.any { (id, _) -> id in vm.selected }) item {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    SectionLabel("本次指定门店")
                    Spacer(Modifier.height(4.dp))
                    chosen.forEach { task -> vm.tripPlaces[task.id]?.let { place ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIconBadge(placeTypeIcon(task.placeType), size = 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("${task.title} · ${place.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton({ vm.resetTripPlace(task.id) }, enabled = !vm.busy) { Text("恢复默认") }
                        }
                    } }
                    Text("重新规划后生效，不修改任务默认地点。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        PlanAction(
            if (vm.busy) "正在规划…" else if (eligibilityLoading) "正在检查待办…" else
                if (vm.editingPlan) "重新规划" else if (chosen.isEmpty()) "查看直达路线" else "规划这趟行程",
            canPlan,
            submit,
        )
    }
    if (pick.isNotBlank()) PlacePicker(vm, { pick = "" }) {
        if (pick == "origin") vm.origin = it else vm.destination = it
        vm.inputsChanged(); pick = ""
    }
    if (sheet.isNotBlank()) ModalBottomSheet(
        onDismissRequest = { sheet = "" },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (sheet == "destination") {
            Column(Modifier.padding(bottom = 12.dp).imePadding()) {
                BottomSheetTitle("选择目的地", { sheet = "" }, subtitle = "输入地点，或按住说话")
                Column(Modifier.padding(horizontal = 20.dp)) {
                    DestinationInput(vm, enabled = !vm.busy && !vm.locating) { place ->
                        vm.destination = place; vm.inputsChanged(); sheet = ""
                    }
                }
            }
        } else if (sheet == "tasks") {
            Column {
                BottomSheetTitle("选择事项 · ${chosen.size}/6", { sheet = "" }, subtitle = "最多选 6 件；不用出门或待补地点的待办不可选")
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    if (pending.isEmpty()) item {
                        Text(
                            "先在「任务」中添加待办。",
                            Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(pending, key = { it.id }) { task ->
                        val checked = task.id in vm.selected
                        val blockedReason = eligibility?.blockedById?.get(task.id)
                        val canToggle = blockedReason == null && (checked || chosen.size < 6)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = canToggle) {
                                    vm.selected = if (checked) vm.selected - task.id else vm.selected + task.id
                                    vm.inputsChanged()
                                }
                                .heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked,
                                onCheckedChange = {
                                    vm.selected = if (it) vm.selected + task.id else vm.selected - task.id
                                    vm.inputsChanged()
                                },
                                enabled = canToggle,
                            )
                            AppIconBadge(placeTypeIcon(task.placeType), size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(task.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    taskPlaceSummary(task),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (blockedReason != null) Text(
                                    routeBlockedLabel(blockedReason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
                PlanAction("完成", true) { sheet = "" }
            }
        } else {
            Column {
                BottomSheetTitle("出行要求", { sheet = "" }, subtitle = "决定这趟路怎么取舍")
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SectionLabel("优先考虑")
                    ChoiceRow(listOf("fastest" to "少花时间", "most_tasks" to "多办事情"), vm.preference) {
                        vm.preference = it; vm.inputsChanged()
                    }
                    AppCard(contentPadding = PaddingValues(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("允许部分完成", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (vm.partial) "可取舍已选任务，并说明原因" else "已选任务必须全部安排",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(vm.partial, { vm.partial = it; vm.inputsChanged() })
                        }
                    }
                    OutlinedTextField(
                        vm.extra,
                        { vm.extra = it; vm.inputsChanged() },
                        label = { Text("最多多花几分钟") },
                        supportingText = { Text("含绕路和办事；留空不限，0–240 分钟") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = appTextFieldColors(),
                    )
                    AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        SummaryRow("最晚到达", vm.arriveBy.ifBlank { "不限制" }) { pickingArrival = true }
                    }
                    if (vm.arriveBy.isNotBlank()) TextButton({ vm.arriveBy = ""; vm.inputsChanged() }) { Text("取消到达限制") }
                }
                PlanAction("完成", true) { sheet = "" }
            }
        }
    }
    if (pickingArrival) {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val current = runCatching { LocalDateTime.parse(vm.arriveBy, format) }.getOrElse { LocalDateTime.now().plusHours(1) }
        AppDateTimeDialog(current, { pickingArrival = false }, title = "选择最晚到达时间") { picked ->
            vm.arriveBy = picked.format(format); vm.inputsChanged(); pickingArrival = false
        }
    }
}

@Composable
private fun TripOverview(result: JSONObject, tasks: List<Errand>, vm: AppViewModel, navigatingHere: Boolean, onNavigationChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val stops = result.getJSONArray("stops").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
    fun unfinished(stop: JSONObject): List<String> = stop.getJSONArray("task_ids").let { ids ->
        (0 until ids.length()).map { ids.getString(it) }.filter { id -> tasks.find { it.id == id }?.status != "done" }
    }
    val remaining = stops.filter { unfinished(it).isNotEmpty() }
    val allIds = stops.flatMap { stop -> stop.getJSONArray("task_ids").let { ids -> (0 until ids.length()).map { ids.getString(it) } } }.distinct()
    val done = allIds.count { id -> tasks.any { it.id == id && it.status == "done" } }
    val destination = Place.from(result.getJSONObject("destination"))
    var menu by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var omittedExpanded by rememberSaveable { mutableStateOf(false) }
    var fallback by rememberSaveable { mutableStateOf(false) }
    var end by remember { mutableStateOf(false) }
    var arrived by remember { mutableStateOf(false) }
    var cycling by remember { mutableStateOf<(() -> Unit)?>(null) }
    var changingPlace by remember { mutableStateOf<Errand?>(null) }
    val enabled = !vm.updatingTrip && !vm.busy
    fun launchEmbedded() {
        if (vm.planNeedsRefresh && !vm.tripStarted) { vm.beginPlanEdit(); return }
        onNavigationChanged(true)
    }
    fun launch(place: Place, whole: Boolean) {
        val action = {
            vm.launchTrip {
                try {
                    val origin = if (vm.tripStarted || done > 0 || !whole) null else Place.from(result.getJSONObject("origin"))
                    val via = if (whole) remaining.map { Place.from(it.getJSONObject("place")) } else emptyList()
                    context.startActivity(Intent(Intent.ACTION_VIEW, nativeRouteUri(result.getString("mode"), place, via, origin).toUri()).setPackage("com.autonavi.minimap"))
                    true
                } catch (_: ActivityNotFoundException) {
                    fallback = true; vm.message = "无法打开高德，请安装或更新高德地图。"; false
                } catch (e: IllegalArgumentException) { fallback = true; vm.message = e.message ?: "导航打开失败"; false }
            }
        }
        if (result.getString("mode") == "cycling") cycling = action else action()
    }
    if (navigatingHere) {
        var conversation by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            EmbeddedNavigationScreen(
                vm.routeSession?.id.orEmpty(), result.getString("mode"), destination,
                remaining.map { Place.from(it.getJSONObject("place")) }.distinctBy { it.id }
                    .filterNot { it.latitude == destination.latitude && it.longitude == destination.longitude },
                onStarted = vm::embeddedNavigationStarted,
                onArrived = vm::embeddedNavigationArrived,
                onFailed = vm::embeddedNavigationFailed,
                onAdjustRoute = { conversation = true },
                autoStart = !vm.explicitNavigationStartRequired,
            ) { arrived ->
                vm.finishTrip(arrived) { onNavigationChanged(false) }
            }
        }
        if (conversation) NavigationConversation(vm) { conversation = false }
        return
    }
    Column(Modifier.fillMaxSize()) {
        AppScreenHeader(
            if (vm.tripStarted) "这趟行程" else "顺路方案",
            kicker = "ROUTE",
            subtitle = listOfNotNull(
                destination.name,
                "预计 ${displayTime(result.getString("arrival"))} 到达",
            ).joinToString(" · "),
            actions = {
                if (!vm.tripStarted) TextButton(vm::beginPlanEdit, enabled = enabled) { Text("调整") }
                Box {
                    IconButton({ menu = true }, enabled = enabled) {
                        Icon(painterResource(R.drawable.ic_more), "行程更多操作")
                    }
                    DropdownMenu(menu, { menu = false }) {
                        if (vm.tripStarted) DropdownMenuItem(text = { Text("已到达终点") }, onClick = { menu = false; arrived = true })
                        if (vm.tripStarted) DropdownMenuItem(text = { Text("重新规划") }, onClick = { menu = false; vm.beginPlanEdit() })
                        DropdownMenuItem(text = { Text("导航帮助") }, onClick = { menu = false; fallback = !fallback })
                        DropdownMenuItem(text = { Text("使用外部高德") }, onClick = { menu = false; launch(destination, true) })
                        DropdownMenuItem(text = { Text(if (vm.tripStarted) "结束行程" else "清除方案") }, onClick = { menu = false; end = true })
                    }
                }
            },
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (vm.planNeedsRefresh) item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIconBadge(
                                R.drawable.ic_clock,
                                size = 32.dp,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                container = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
                            )
                            Text("待办有变化，当前路线仍是上次规划结果。", style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton({ vm.beginPlanEdit() }, enabled = enabled) { Text("检查并重新规划") }
                    }
                }
            }
            if (vm.tripStarted) {
                item {
                    Text(
                        if (allIds.isEmpty()) "行程已开启 · 到达后请确认" else "已完成 $done / ${allIds.size} 件",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_place_pin),
                                    null,
                                    Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    if (remaining.isEmpty()) "前往最终目的地" else "下一处办事",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Text(
                                if (remaining.isEmpty()) destination.name else Place.from(remaining.first().getJSONObject("place")).name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            remaining.firstOrNull()?.let { stop ->
                                StopTasks(stop, tasks, vm, true)
                            }
                        }
                    }
                }
                item {
                    AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        SummaryRow("完整行程", "终点 · ${destination.name}") { expanded = !expanded }
                    }
                }
            } else {
                item {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraLarge) {
                        Box(Modifier.fillMaxWidth()) {
                            AppRouteArt(
                                Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp).width(176.dp).height(92.dp),
                                color = Color.White.copy(alpha = 0.42f),
                                endColor = MaterialTheme.colorScheme.tertiaryContainer,
                            )
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (allIds.isEmpty()) "直达 ${minutes(result.getInt("total_seconds"))}" else "多花 ${minutes(result.getInt("extra_seconds"))}",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Text(
                                    if (allIds.isEmpty()) destination.name else "顺路办 ${allIds.size} 件事 · 全程 ${minutes(result.getInt("total_seconds"))}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f),
                                )
                                Spacer(Modifier.height(2.dp))
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)) {
                                    Text(
                                        "规划预计 ${displayTime(result.getString("arrival"))} 到达",
                                        Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("路线预览")
                    RouteMapPreview(result)
                }
            }
            if (!vm.tripStarted || expanded) {
                item {
                    AppCard(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)) {
                        RouteTimelineItem(Place.from(result.getJSONObject("origin")).name, first = true) {}
                        stops.indices.forEach { index ->
                            val stop = stops[index]
                            RouteTimelineItem(Place.from(stop.getJSONObject("place")).name, number = index + 1) {
                                StopTasks(stop, tasks, vm, vm.tripStarted) { changingPlace = it }
                                if (fallback) TextButton({ launch(Place.from(stop.getJSONObject("place")), false) }, enabled = enabled) { Text("只导航到此站") }
                            }
                        }
                        RouteTimelineItem(destination.name, last = true) {}
                    }
                }
            }
            val omitted = result.getJSONArray("omitted")
            if (omitted.length() > 0) item {
                AppCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                    SummaryRow("${omitted.length()} 件未安排", "已安排 ${allIds.size} / ${allIds.size + omitted.length()} 件 · 查看原因") { omittedExpanded = !omittedExpanded }
                    if (omittedExpanded) {
                        Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in 0 until omitted.length()) {
                                val entry = omitted.getJSONObject(i)
                                Text(
                                    "${entry.getString("title")}：${entry.getString("reason")}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    Text("原计划估算 · 非实时到达时间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!vm.tripStarted) Text("办完后返回行程标记完成。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val warnings = result.getJSONArray("warnings")
                    for (i in 0 until warnings.length()) {
                        val warning = warnings.getString(i)
                        val summary = when (warning) {
                            "办事时长为估算，未包含未知排队时间；营业时间尚未校验。" -> "营业时间未核实 · 耗时不含排队"
                            "仅在已搜索到的候选地点中规划，不保证全城最优。" -> null
                            else -> warning
                        }
                        if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    SummaryRow("耗时明细", "") { details = !details }
                    if (details) {
                        Text(
                            "直达 ${minutes(result.getInt("baseline_seconds"))}\n额外路途 ${minutes(result.getInt("extra_travel_seconds"))}\n办事 ${minutes(result.getInt("service_seconds"))}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        for (i in 0 until warnings.length()) Text(warnings.getString(i), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (fallback) item {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    Text("请在高德确认途经点完整；办完后返回标记。若途经点缺失，可展开行程逐站导航。", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ expanded = true }) { Text("展开全部站点") }
                        TextButton({ launch(destination, false) }, enabled = enabled) { Text("只导航到终点") }
                    }
                }
            }
        }
        PlanAction(
            if (vm.updatingTrip) "正在保存…" else if (vm.planNeedsRefresh && !vm.tripStarted) "重新规划" else if (vm.tripStarted) "继续导航" else "开始导航",
            enabled,
        ) {
            if (vm.planNeedsRefresh && !vm.tripStarted) vm.beginPlanEdit() else launchEmbedded()
        }
    }
    if (cycling != null) {
        AppConfirmDialog(
            title = "确认自行车模式",
            message = "高德可能沿用电动车设置，请确认选择自行车，并检查途经点。",
            confirmLabel = "打开高德",
            onConfirm = { val action = cycling; cycling = null; action?.invoke() },
            onDismiss = { cycling = null },
        )
    }
    if (end) {
        AppConfirmDialog(
            title = "清除本次行程？",
            message = "未完成的事项仍保留在待办中。",
            confirmLabel = "确认",
            onConfirm = { end = false; vm.finishTrip(false) },
            onDismiss = { end = false },
        )
    }
    if (arrived) {
        AppConfirmDialog(
            title = "已到达最终目的地？",
            message = "结束本次行程；未完成的事项仍保留。",
            confirmLabel = "确认到达",
            onConfirm = { arrived = false; vm.finishTrip(true) },
            onDismiss = { arrived = false },
        )
    }
    changingPlace?.let { task ->
        PlacePicker(vm, { changingPlace = null }) { place ->
            changingPlace = null
            vm.chooseTripPlace(task.id, place)
        }
    }
}

@Composable
private fun RouteTimelineItem(title: String, number: Int? = null, first: Boolean = false, last: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val pinColor = if (last) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    val pinHole = MaterialTheme.colorScheme.surfaceContainerLowest
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(28.dp).fillMaxHeight().drawBehind {
            val x = size.width / 2
            val y = 14.dp.toPx()
            if (!first) drawLine(line, Offset(x, 0f), Offset(x, y), 2.dp.toPx())
            if (!last) drawLine(line, Offset(x, y), Offset(x, size.height), 2.dp.toPx())
            drawCircle(pinColor, 11.dp.toPx(), Offset(x, y))
            if (number == null) drawCircle(pinHole, 4.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
        }, contentAlignment = Alignment.TopCenter) {
            if (number != null) Text("$number", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = if (last) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary)
        }
        Column(
            Modifier.weight(1f).padding(bottom = if (last) 0.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StopTasks(stop: JSONObject, tasks: List<Errand>, vm: AppViewModel, actionable: Boolean, changePlace: ((Errand) -> Unit)? = null) {
    val ids = stop.getJSONArray("task_ids")
    val titles = stop.getJSONArray("task_titles")
    for (i in 0 until ids.length()) {
        val task = tasks.find { it.id == ids.getString(i) }
        val done = task?.status == "done"
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (task != null) {
                Icon(
                    painterResource(placeTypeIcon(task.placeType)),
                    null,
                    Modifier.size(16.dp),
                    tint = if (done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                (if (done) "✓ " else "") + titles.getString(i),
                Modifier.weight(1f),
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (actionable && !done) TextButton({ task?.let(vm::completeFromPlan) }, enabled = task != null && !vm.updatingTrip) { Text("办完了") }
            if (actionable && done) TextButton({ vm.save(task!!.copy(status = "pending")) }, enabled = !vm.updatingTrip) { Text("撤销", color = MaterialTheme.colorScheme.outline) }
            if (!actionable && !done && task != null && changePlace != null) TextButton({ changePlace(task) }, enabled = !vm.updatingTrip) { Text("更换地点") }
        }
    }
    Text("预计停留 ${minutes(stop.getInt("service_seconds"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
