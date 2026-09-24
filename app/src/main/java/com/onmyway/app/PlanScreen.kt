package com.onmyway.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

private fun minutes(seconds: Int) = "${ceil(seconds / 60.0).toInt()} 分钟"

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
    Surface {
        Button(action, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SummaryRow(title: String, detail: String, enabled: Boolean = true, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).heightIn(min = 56.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrepareTrip(vm: AppViewModel, tasks: List<Errand>, submit: () -> Unit = vm::plan, cancel: () -> Unit = vm::cancelPlanEdit) {
    val context = LocalContext.current
    var pick by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf("") }
    val pending = tasks.filter { it.status == "pending" }
    val chosen = pending.filter { it.id in vm.selected }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) vm.locate() else vm.message = "未获得定位权限，可手动选择起点。"
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vm.editingPlan) "调整行程" else "顺路出门", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (vm.editingPlan) TextButton(cancel, enabled = !vm.busy) { Text("取消") }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = androidx.compose.ui.graphics.Color.White) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SummaryRow("○  ${vm.origin?.name ?: "选择起点"}", "", !vm.busy && !vm.locating) { pick = "origin" }
                        }
                        TextButton(onClick = {
                            if (hasLocationPermission(context)) vm.locate()
                            else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }, enabled = !vm.busy && !vm.locating) { Text(if (vm.locating) "定位中" else "定位") }
                    }
                    if (vm.locating) LinearProgressIndicator(Modifier.fillMaxWidth())
                    HorizontalDivider()
                    SummaryRow("◎  ${vm.destination?.name ?: "去哪里？"}", "", !vm.busy) { sheet = "destination" }
                }
                }
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("walking" to "步行", "cycling" to "骑行").forEachIndexed { i, (value, label) ->
                        SegmentedButton(selected = vm.mode == value, onClick = { vm.mode = value; vm.inputsChanged() },
                            shape = SegmentedButtonDefaults.itemShape(i, 2), enabled = !vm.busy) { Text(label) }
                    }
                }
            }
            item {
                SummaryRow(if (chosen.isEmpty()) "这次办什么" else "已选 ${chosen.size} 件事",
                    if (chosen.isEmpty()) "不选事项，直接去目的地" else chosen.take(3).joinToString("、") { it.title } + if (chosen.size > 3) "等" else "",
                    !vm.busy) { sheet = "tasks" }
                HorizontalDivider()
                SummaryRow("出行要求", listOf(
                    when (vm.preference) { "fastest" -> "少花时间"; "priority" -> "重要优先"; else -> "多办事情" },
                    if (vm.partial) "允许部分完成" else "全部完成",
                    if (vm.extra.isBlank()) "额外时间不限" else "最多多花 ${vm.extra} 分钟",
                    if (vm.arriveBy.isBlank()) "到达不限" else "${vm.arriveBy} 前到达"
                ).joinToString(" · "), !vm.busy) { sheet = "conditions" }
            }
            if (vm.busy) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在查询路线，首次可能需要 1–3 分钟。", style = MaterialTheme.typography.bodySmall)
            }
            if (vm.editingPlan && vm.tripPlaces.any { (id, _) -> id in vm.selected }) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本次指定门店", style = MaterialTheme.typography.titleSmall)
                    chosen.forEach { task -> vm.tripPlaces[task.id]?.let { place ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${task.title} · ${place.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton({ vm.resetTripPlace(task.id) }, enabled = !vm.busy) { Text("恢复默认") }
                        }
                    } }
                    Text("重新规划后生效，不修改任务默认地点。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        PlanAction(if (vm.busy) "正在规划…" else if (vm.editingPlan) "重新规划" else if (chosen.isEmpty()) "查看直达路线" else "规划这趟行程",
            !vm.busy && !vm.locating && vm.origin != null && vm.destination != null, submit)
    }
    if (pick.isNotBlank()) PlacePicker(vm, { pick = "" }) {
        if (pick == "origin") vm.origin = it else vm.destination = it
        vm.inputsChanged(); pick = ""
    }
    if (sheet.isNotBlank()) ModalBottomSheet(onDismissRequest = { sheet = "" }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (sheet == "destination") {
            Column(Modifier.padding(20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择目的地", style = MaterialTheme.typography.titleLarge)
                DestinationInput(vm, enabled = !vm.busy && !vm.locating) { place ->
                    vm.destination = place; vm.inputsChanged(); sheet = ""
                }
            }
        } else if (sheet == "tasks") {
            Text("选择事项 · ${chosen.size}/6", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(20.dp)) {
                if (pending.isEmpty()) item { Text("先在「任务」中添加待办。") }
                items(pending, key = { it.id }) { task ->
                    val checked = task.id in vm.selected
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = {
                            vm.selected = if (it) vm.selected + task.id else vm.selected - task.id; vm.inputsChanged()
                        }, enabled = checked || chosen.size < 6)
                        Column(Modifier.weight(1f)) {
                            Text(task.title)
                            Text("${task.place()?.name ?: placeTypes[task.placeType]} · ${task.serviceMinutes} 分钟", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("出行要求", style = MaterialTheme.typography.titleLarge)
                ChoiceRow(listOf("fastest" to "少花时间", "most_tasks" to "多办事情", "priority" to "重要优先"), vm.preference) { vm.preference = it; vm.inputsChanged() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("允许部分完成", Modifier.weight(1f))
                    Switch(vm.partial, { vm.partial = it; vm.inputsChanged() })
                }
                Text(if (vm.partial) "可取舍已选任务，并说明原因" else "已选任务必须全部安排", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(vm.extra, { vm.extra = it; vm.inputsChanged() }, label = { Text("最多多花几分钟") },
                    supportingText = { Text("含绕路和办事；留空不限，0–240 分钟") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                SummaryRow("最晚到达", vm.arriveBy.ifBlank { "不限制" }) {
                    val current = runCatching { LocalDateTime.parse(vm.arriveBy, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrElse { LocalDateTime.now().plusHours(1) }
                    DatePickerDialog(context, { _, y, m, d ->
                        TimePickerDialog(context, { _, h, min ->
                            vm.arriveBy = LocalDateTime.of(y, m + 1, d, h, min).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")); vm.inputsChanged()
                        }, current.hour, current.minute, true).show()
                    }, current.year, current.monthValue - 1, current.dayOfMonth).show()
                }
                if (vm.arriveBy.isNotBlank()) TextButton({ vm.arriveBy = ""; vm.inputsChanged() }) { Text("取消到达限制") }
            }
        }
        PlanAction("完成", true) { sheet = "" }
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
        vm.launchTrip {
            onNavigationChanged(true)
            true
        }
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
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
        EmbeddedNavigationScreen(result.getString("mode"), destination,
            remaining.map { Place.from(it.getJSONObject("place")) }.distinctBy { it.id }
                .filterNot { it.latitude == destination.latitude && it.longitude == destination.longitude }) {
            onNavigationChanged(false)
        }
        }
        TextButton({ conversation = true }, modifier = Modifier.fillMaxWidth()) { Text("和助手调整路线") }
        }
        if (conversation) NavigationConversation(vm) { conversation = false }
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (vm.tripStarted) "这趟行程" else "顺路方案", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (!vm.tripStarted) TextButton(vm::beginPlanEdit, enabled = enabled) { Text("调整") }
                    Box {
                        IconButton({ menu = true }, enabled = enabled) { Icon(painterResource(R.drawable.ic_more), "行程更多操作") }
                        DropdownMenu(menu, { menu = false }) {
                            if (vm.tripStarted) DropdownMenuItem(text = { Text("已到达终点") }, onClick = { menu = false; arrived = true })
                            if (vm.tripStarted) DropdownMenuItem(text = { Text("重新规划") }, onClick = { menu = false; vm.beginPlanEdit() })
                            DropdownMenuItem(text = { Text("导航帮助") }, onClick = { menu = false; fallback = !fallback })
                            DropdownMenuItem(text = { Text("使用外部高德") }, onClick = { menu = false; launch(destination, true) })
                            DropdownMenuItem(text = { Text(if (vm.tripStarted) "结束行程" else "清除方案") }, onClick = { menu = false; end = true })
                        }
                    }
                }
            }
            if (vm.tripStarted) {
                item {
                    Text(if (allIds.isEmpty()) "行程已开启 · 到达后请确认" else "已完成 $done / ${allIds.size} 件", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (remaining.isEmpty()) "前往最终目的地" else "下一处办事", style = MaterialTheme.typography.labelLarge)
                            Text(if (remaining.isEmpty()) destination.name else Place.from(remaining.first().getJSONObject("place")).name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            remaining.firstOrNull()?.let { stop ->
                                StopTasks(stop, tasks, vm, true)
                            }
                        }
                    }
                }
                item { SummaryRow("完整行程", "终点 · ${destination.name}") { expanded = !expanded } }
            } else {
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (allIds.isEmpty()) "直达 ${minutes(result.getInt("total_seconds"))}" else "多花 ${minutes(result.getInt("extra_seconds"))}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(if (allIds.isEmpty()) destination.name else "顺路办 ${allIds.size} 件事 · 全程 ${minutes(result.getInt("total_seconds"))}")
                            Text("规划预计 ${displayTime(result.getString("arrival"))} 到达", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { RouteMapPreview(result) }
            if (!vm.tripStarted || expanded) {
                item { Column {
                RouteTimelineItem(Place.from(result.getJSONObject("origin")).name, first = true) {}
                stops.indices.forEach { index ->
                    val stop = stops[index]
                    RouteTimelineItem(Place.from(stop.getJSONObject("place")).name, number = index + 1) {
                        StopTasks(stop, tasks, vm, vm.tripStarted) { changingPlace = it }
                        if (fallback) TextButton({ launch(Place.from(stop.getJSONObject("place")), false) }, enabled = enabled) { Text("只导航到此站") }
                    }
                }
                RouteTimelineItem(destination.name, last = true) {}
                } }
            }
            val omitted = result.getJSONArray("omitted")
            if (omitted.length() > 0) item {
                SummaryRow("${omitted.length()} 件未安排", "已安排 ${allIds.size} / ${allIds.size + omitted.length()} 件 · 查看原因") { omittedExpanded = !omittedExpanded }
                if (omittedExpanded) for (i in 0 until omitted.length()) {
                    val entry = omitted.getJSONObject(i)
                    Text("${entry.getString("title")}：${entry.getString("reason")}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
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
                SummaryRow("耗时明细", "") { details = !details }
                if (details) {
                    Text("直达 ${minutes(result.getInt("baseline_seconds"))}\n额外路途 ${minutes(result.getInt("extra_travel_seconds"))}\n办事 ${minutes(result.getInt("service_seconds"))}", style = MaterialTheme.typography.bodyMedium)
                    for (i in 0 until warnings.length()) Text(warnings.getString(i), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (fallback) item {
                Text("请在高德确认途经点完整；办完后返回标记。若途经点缺失，可展开行程逐站导航。", style = MaterialTheme.typography.bodyMedium)
                TextButton({ expanded = true }) { Text("展开全部站点") }
                TextButton({ launch(destination, false) }, enabled = enabled) { Text("只导航到终点") }
            }
        }
        PlanAction(if (vm.updatingTrip) "正在保存…" else if (vm.tripStarted) "继续导航" else "开始导航", enabled) { launchEmbedded() }
    }
    if (cycling != null) AlertDialog(onDismissRequest = { cycling = null }, title = { Text("确认自行车模式") },
        text = { Text("高德可能沿用电动车设置，请确认选择自行车，并检查途经点。") },
        confirmButton = { TextButton({ val action = cycling; cycling = null; action?.invoke() }) { Text("打开高德") } },
        dismissButton = { TextButton({ cycling = null }) { Text("取消") } })
    if (end) AlertDialog(onDismissRequest = { end = false }, title = { Text("清除本次行程？") },
        text = { Text("未完成的事项仍保留在待办中。") },
        confirmButton = { TextButton({ end = false; vm.finishTrip(false) }) { Text("确认") } },
        dismissButton = { TextButton({ end = false }) { Text("取消") } })
    if (arrived) AlertDialog(onDismissRequest = { arrived = false }, title = { Text("已到达最终目的地？") },
        text = { Text("结束本次行程；未完成的事项仍保留。") },
        confirmButton = { TextButton({ arrived = false; vm.finishTrip(true) }) { Text("确认到达") } },
        dismissButton = { TextButton({ arrived = false }) { Text("取消") } })
    changingPlace?.let { task ->
        PlacePicker(vm, { changingPlace = null }) { place ->
            changingPlace = null
            vm.chooseTripPlace(task.id, place)
        }
    }
}

@Composable
private fun RouteTimelineItem(title: String, number: Int? = null, first: Boolean = false, last: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(28.dp).fillMaxHeight().drawBehind {
            val x = size.width / 2
            val y = 14.dp.toPx()
            if (!first) drawLine(line, Offset(x, 0f), Offset(x, y), 2.dp.toPx())
            if (!last) drawLine(line, Offset(x, y), Offset(x, size.height), 2.dp.toPx())
            drawCircle(primary, 11.dp.toPx(), Offset(x, y))
            if (number == null) drawCircle(androidx.compose.ui.graphics.Color.White, 4.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
        }, contentAlignment = Alignment.TopCenter) {
            if (number != null) Text("$number", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Text((if (done) "✓ " else "") + titles.getString(i), Modifier.weight(1f))
            if (actionable && !done) TextButton({ task?.let(vm::completeFromPlan) }, enabled = task != null && !vm.updatingTrip) { Text("办完了") }
            if (!actionable && !done && task != null && changePlace != null) TextButton({ changePlace(task) }, enabled = !vm.updatingTrip) { Text(if (requiresFixedPlace(task.placeType)) "更正地点" else "换一家") }
        }
    }
    Text("预计停留 ${minutes(stop.getInt("service_seconds"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
