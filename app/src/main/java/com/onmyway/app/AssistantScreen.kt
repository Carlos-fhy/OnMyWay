package com.onmyway.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ChatLine(val role: String, val text: String, val id: String = UUID.randomUUID().toString())
internal class AssistantViewModel : ViewModel() {
    var lines by mutableStateOf(listOf(ChatLine("assistant", "今天想做什么？可以让我记待办，或告诉我准备去哪儿。")))
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var proposal by mutableStateOf<JSONObject?>(null)
    var error by mutableStateOf("")
    var preparingRoute by mutableStateOf(false)
    var routeResult by mutableStateOf<JSONObject?>(null)
    val automatic = AutomaticRoutePlanner(viewModelScope)
    fun chooseMode(mode: String) {
        if (mode !in listOf("walking", "cycling")) return
        val current = proposal ?: return
        automatic.clear(); error = ""
        proposal = JSONObject(current.toString()).put("mode", mode)
    }
    fun record(text: String) { lines = (lines + ChatLine("assistant", text)).takeLast(60) }
    fun saveTask(vm: AppViewModel, task: Errand) {
        if (busy) return
        busy = true; error = ""
        viewModelScope.launch {
            try { vm.saveAssistantTask(task); proposal = null; record("已记下：${task.title}") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
    }
    fun send(vm: AppViewModel) {
        if (busy || vm.busy || automatic.busy || input.isBlank()) return
        if (proposal?.optString("action") == "plan_route") {
            val mode = travelModeReply(input)
            if (mode != null) {
                lines = (lines + ChatLine("user", input.trim())).takeLast(60)
                input = ""; chooseMode(mode); return
            }
            if (proposal?.optString("mode").isNullOrBlank()) { error = "这次步行还是骑行？请选择或直接告诉我。"; return }
        }
        if (automatic.preview != null) {
            val text = input.trim()
            if (text.trimEnd('。', '！', '!') in listOf("确认", "确认加入", "按所选规划")) {
                lines = (lines + ChatLine("user", text)).takeLast(60); input = ""
                automatic.accept(vm, automatic.selectedIds) { routeResult = it; proposal = null; record("已按你的选择更新方案，请核对后开始导航。") }
                return
            }
            val ids = automatic.command(text)
            if (ids != null) {
                lines = (lines + ChatLine("user", text)).takeLast(60); input = ""
                automatic.select(vm, ids)
                record(if (ids.isEmpty()) "已选择直达，请确认后规划。" else "已选 ${ids.size} 件事，请核对勾选项，再说“确认”或点击确认按钮。")
                return
            }
            error = "可勾选任务，或说“都去”“直接走”“只取快递”，选好后说“确认”。"; return
        }
        if (proposal != null) return
        val text = input.trim().take(4000)
        input = ""; error = ""; busy = true
        lines = (lines + ChatLine("user", text)).takeLast(60)
        viewModelScope.launch {
            try {
                val messages = JSONArray().apply { lines.takeLast(20).forEach { put(JSONObject().put("role", it.role).put("content", it.text)) } }
                val answer = Api(vm.server).assistant(messages)
                when (answer.getString("action")) {
                    "list_tasks" -> {
                        val pending = vm.tasks.value.filter { it.status == "pending" }
                        record(if (pending.isEmpty()) "目前没有待办。" else pending.take(20).joinToString("\n") { "· ${it.title}" })
                    }
                    "create_task", "plan_route" -> { proposal = answer; record(if (answer.getString("action") == "create_task") "请确认这件事，保存后会加入待办。" else if (answer.optString("mode").isBlank()) "这次步行还是骑行？" else "我来定位并查看有哪些事可以顺路办。") }
                    else -> record(answer.getString("reply"))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e); input = text }
            finally { busy = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantScreen(vm: AppViewModel, tasks: List<Errand>, settings: () -> Unit, openPlan: () -> Unit, openNavigation: () -> Unit,
    chat: AssistantViewModel = viewModel()) {
    var draft by remember { mutableStateOf<Errand?>(null) }
    var search by remember { mutableStateOf<String?>(null) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scroll = rememberLazyListState()
    val context = LocalContext.current
    val auto = chat.automatic
    fun beginAutomatic() {
        chat.proposal?.takeIf { it.optString("action") == "plan_route" && it.optString("mode") in listOf("walking", "cycling") }?.let {
            auto.start(vm, it.getString("destination"), it.optString("mode", "walking"))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) beginAutomatic()
        else chat.error = "未获得位置权限，可重试或手动设置起点。"
    }
    LaunchedEffect(chat.proposal) {
        if (chat.proposal?.optString("action") == "plan_route" && chat.proposal?.optString("mode") in listOf("walking", "cycling") && !chat.preparingRoute) {
            if (hasLocationPermission(context)) beginAutomatic()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { vm.cancelLocation(); auto.cancel() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.cancelLocation(); auto.cancel() }
    }
    fun chooseDestination(place: Place) {
        if (vm.busy || vm.updatingTrip || vm.restoringTrip) return
        vm.beginPlanEdit()
        auto.clear()
        vm.inputsChanged(); vm.destination = place
        vm.mode = chat.proposal?.optString("mode", "walking") ?: "walking"
        chat.preparingRoute = true
        focus.clearFocus(); keyboard?.hide()
    }
    LaunchedEffect(chat.lines.size, chat.proposal, chat.busy, chat.error, chat.routeResult) { if (scroll.layoutInfo.totalItemsCount > 0) scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("助手", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.headlineSmall)
            TextButton(settings) { Text("设置") }
        }
        LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(chat.lines, key = { it.id }) { line ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (line.role == "user") Alignment.CenterEnd else Alignment.CenterStart) {
                Surface(color = if (line.role == "user") MaterialTheme.colorScheme.primaryContainer else Color.White,
                    modifier = Modifier.widthIn(max = 320.dp),
                    border = if (line.role == "user") null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = MaterialTheme.shapes.large) { Text(line.text, Modifier.padding(14.dp)) }
                }
            }
            chat.proposal?.let { proposal -> item {
                if (proposal.optString("action") == "plan_route") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("walking" to "步行", "cycling" to "骑行").forEach { (mode, label) ->
                            FilterChip(selected = proposal.optString("mode") == mode, onClick = { chat.chooseMode(mode) },
                                enabled = !auto.busy && !vm.busy, label = { Text(label) })
                        }
                    }
                    if (proposal.optString("mode").isNotBlank()) {
                    AutomaticRouteCard(auto, vm, retry = {
                        chat.error = ""
                        if (hasLocationPermission(context)) beginAutomatic()
                        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, manual = {
                        if (auto.prepareManual(vm)) chat.preparingRoute = true else search = proposal.getString("destination")
                    }, cancel = { auto.dismiss(vm); chat.proposal = null }, accepted = {
                        chat.routeResult = it; chat.proposal = null; chat.record("已更新方案，请核对后开始导航。")
                    })
                    } else TextButton({ auto.dismiss(vm); chat.proposal = null }) { Text("取消") }
                } else {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val task = proposal.getString("action") == "create_task"
                        Text(if (task) "待确认任务" else "待确认目的地", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(if (task) proposal.getString("title") else proposal.getString("destination"), style = MaterialTheme.typography.titleMedium)
                        if (task && proposal.optString("deadline").isNotBlank()) Text("截止 ${displayTime(proposal.getString("deadline"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({
                            if (task) draft = Errand(title = proposal.getString("title"), placeType = proposal.getString("place_type"), deadline = proposal.optString("deadline"))
                            else {
                                val keyword = proposal.getString("destination")
                                val familiar = vm.familiarPlace(keyword)
                                if (familiar != null) chooseDestination(familiar) else search = keyword
                            }
                        }, modifier = Modifier.weight(1f), enabled = !chat.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip) { Text(if (task) "确认任务" else "选择目的地") }
                        TextButton({ chat.proposal = null; chat.record("已取消这条建议，未执行操作。") }, enabled = !chat.busy) { Text("取消") }
                        }
                    }
                }
                }
            } }
            chat.routeResult?.let { route -> item {
                val current = route === vm.result && !vm.editingPlan && !vm.restoringTrip
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (current) "路线已算好" else "此方案已更新，请查看当前行程", style = MaterialTheme.typography.labelLarge)
                        Text(Place.from(route.getJSONObject("destination")).name, style = MaterialTheme.typography.titleLarge)
                        if (current) {
                            Text("全程 ${kotlin.math.ceil(route.getInt("total_seconds") / 60.0).toInt()} 分钟 · 多花 ${kotlin.math.ceil(route.getInt("extra_seconds") / 60.0).toInt()} 分钟")
                            Text("预计 ${displayTime(route.getString("arrival"))} 到达 · 规划估算", style = MaterialTheme.typography.bodySmall)
                            val stops = route.getJSONArray("stops")
                            for (i in 0 until stops.length()) Text("${i + 1}. ${Place.from(stops.getJSONObject(i).getJSONObject("place")).name}", style = MaterialTheme.typography.bodyMedium)
                            val omitted = route.getJSONArray("omitted")
                            if (omitted.length() > 0) Text("${omitted.length()} 件未安排，请在详情查看原因。", style = MaterialTheme.typography.bodySmall)
                            val warnings = route.getJSONArray("warnings")
                            for (i in 0 until warnings.length()) Text(warnings.getString(i), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (current) Button({ vm.launchTrip { openNavigation(); true } }, enabled = !vm.busy && !vm.updatingTrip && chat.proposal == null) { Text(if (vm.tripStarted) "继续导航" else "开始导航") }
                            TextButton(openPlan) { Text("查看详情") }
                        }
                    }
                }
            } }
            if (chat.busy) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在处理…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (chat.error.isNotBlank()) item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(chat.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text(if (chat.proposal != null) "建议已保留，可再次确认。" else if (chat.input.isNotBlank()) "内容已保留，可修改后重新发送。" else "可重新录音，或切换文字输入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ focus.clearFocus(); keyboard?.hide(); showVoice = !showVoice }, enabled = !chat.busy) {
                Icon(painterResource(if (showVoice) R.drawable.ic_keyboard else R.drawable.ic_mic), if (showVoice) "切换文字输入" else "切换语音输入")
            }
            if (showVoice) {
                Box(Modifier.weight(1f)) {
                    CloudVoiceButton(vm.server, enabled = !chat.busy && !auto.busy && !vm.busy && (chat.proposal == null || chat.proposal?.optString("action") == "plan_route"),
                        result = {
                            chat.input = it.take(4000)
                            chat.send(vm)
                        }, failure = { chat.error = it })
                }
            } else {
                OutlinedTextField(chat.input, { chat.input = it.take(4000) }, placeholder = { Text("记件事，或告诉我去哪儿") },
                    modifier = Modifier.weight(1f), maxLines = 3, enabled = !chat.busy)
                FilledIconButton({ chat.send(vm) }, enabled = !chat.busy && !auto.busy && !vm.busy && chat.input.isNotBlank() && (chat.proposal == null || chat.proposal?.optString("action") == "plan_route")) {
                    Icon(painterResource(R.drawable.ic_send), "发送")
                }
            }
        }
    }
    draft?.let { initial -> TaskEditor(vm, initial, { draft = null }) { updated ->
        if (!chat.busy) {
            draft = null; chat.saveTask(vm, updated)
        }
    } }
    search?.let { keyword -> PlacePicker(vm, { search = null }, initialKeyword = keyword) { place ->
        search = null
        chooseDestination(place)
    } }
    if (chat.preparingRoute) {
        val dismissRoute = {
            if (!vm.busy) { vm.cancelPlanEdit(); chat.preparingRoute = false }
        }
        ModalBottomSheet(onDismissRequest = dismissRoute,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !vm.busy })) {
            Column(Modifier.fillMaxHeight(.94f)) {
                if (vm.message.isNotBlank()) Text(vm.message, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                PrepareTrip(vm, tasks, submit = {
                    vm.planForAssistant { response ->
                        chat.routeResult = response; chat.preparingRoute = false; chat.proposal = null
                        chat.record("路线已算好，请核对方案后开始导航。")
                    }
                }, cancel = dismissRoute)
            }
        }
    }
}
