package com.onmyway.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ChatLine(
    val role: String,
    val text: String,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val routeHistoryId: String? = null,
)

internal fun chatDateLabel(timestamp: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (timestamp <= 0) return "较早对话"
    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    return when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> day.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
    }
}
internal class AssistantViewModel internal constructor(application: Application, private val conversationPrefs: SharedPreferences) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, application.getSharedPreferences("assistant_conversation", 0))
    // Conversation and unconfirmed intent live outside the screen and the trip history.
    private fun restoredLines(): List<ChatLine> = runCatching {
        val saved = JSONArray(conversationPrefs.getString("lines", "[]"))
        (0 until saved.length()).mapNotNull { index ->
            val line = saved.optJSONObject(index) ?: return@mapNotNull null
            val role = line.optString("role")
            val content = line.optString("text")
            if (role !in listOf("user", "assistant") || content.isBlank()) null
            else ChatLine(role, content.take(4000), line.optString("id").ifBlank { UUID.randomUUID().toString() },
                line.optLong("created_at", 0), line.optString("route_history_id").takeUnless { it.isBlank() || it == "null" })
        }.takeLast(60)
    }.getOrDefault(emptyList()).ifEmpty {
        listOf(ChatLine("assistant", "今天想做什么？可以让我记待办，或告诉我准备去哪儿。"))
    }
    private fun restoredProposal(): JSONObject? {
        val saved = conversationPrefs.getString("proposal", null) ?: return null
        val savedAt = conversationPrefs.getLong("proposal_saved_at", 0)
        return runCatching { JSONObject(saved) }.getOrNull()?.takeIf {
            it.optString("action") != "plan_route" || savedAt > 0 && System.currentTimeMillis() - savedAt in 0..30 * 60_000L
        }?.takeIf {
            when (it.optString("action")) {
                "create_task" -> it.optString("title").isNotBlank()
                "plan_route" -> it.optString("destination").isNotBlank()
                else -> false
            }
        }
    }
    private var storedLines by mutableStateOf(restoredLines())
    val lines: List<ChatLine> get() = storedLines
    private fun addLine(role: String, text: String, routeHistoryId: String? = null) {
        storedLines = (storedLines + ChatLine(role, text, routeHistoryId = routeHistoryId)).takeLast(60)
        val saved = JSONArray()
        storedLines.forEach {
            val line = JSONObject().put("role", it.role).put("text", it.text)
                .put("id", it.id).put("created_at", it.createdAt)
            it.routeHistoryId?.let { id -> line.put("route_history_id", id) }
            saved.put(line)
        }
        conversationPrefs.edit { putString("lines", saved.toString()) }
    }
    private var storedProposal by mutableStateOf(restoredProposal())
    var proposal: JSONObject?
        get() = storedProposal
        set(value) {
            storedProposal = value
            conversationPrefs.edit {
                if (value == null) { remove("proposal"); remove("proposal_saved_at") }
                else { putString("proposal", value.toString()); putLong("proposal_saved_at", System.currentTimeMillis()) }
            }
        }
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var preparingRoute by mutableStateOf(false)
    var routeResult by mutableStateOf<JSONObject?>(null)
    var historyDestination by mutableStateOf<Place?>(null)
    val automatic = AutomaticRoutePlanner(viewModelScope)
    fun chooseMode(mode: String) {
        if (mode !in listOf("walking", "cycling")) return
        val current = proposal ?: return
        automatic.clear(); error = ""
        proposal = JSONObject(current.toString()).put("mode", mode)
    }
    fun record(text: String) = addLine("assistant", text)
    fun recordRoute(text: String, historyId: String?) = addLine("assistant", text, historyId)
    fun repeatRoute(event: RouteHistory) {
        val request = JSONObject(event.requestJson)
        val place = Place.from(request.getJSONObject("destination"))
        val travelMode = request.optString("mode").takeIf { it in listOf("walking", "cycling") } ?: "walking"
        automatic.clear(); routeResult = null; error = ""; preparingRoute = false
        historyDestination = place
        proposal = JSONObject().put("action", "plan_route").put("destination", place.name)
            .put("mode", travelMode).put("known_destination", place.json())
        record("已从行程记录选择${place.name}；会从当前位置重新定位，并按现在的待办重新规划。")
    }
    fun routeEnded() {
        automatic.clear(); routeResult = null; historyDestination = null; preparingRoute = false
        if (proposal?.optString("action") == "plan_route") proposal = null
    }
    fun saveTask(vm: AppViewModel, task: Errand) {
        if (busy) return
        busy = true; error = ""
        viewModelScope.launch {
            try { vm.saveAssistantTask(task); proposal = null; record(if (vm.routeSession == null)
                "已记下：${task.title}。准备出发时告诉我目的地，我会重新规划。" else "已记下：${task.title}") }
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
                addLine("user", input.trim())
                input = ""; chooseMode(mode); return
            }
            if (proposal?.optString("mode").isNullOrBlank()) { error = "这次步行还是骑行？请选择或直接告诉我。"; return }
        }
        if (automatic.preview != null) {
            val text = input.trim()
            if (text.trimEnd('。', '！', '!') in listOf("确认", "确认加入", "按所选规划")) {
                addLine("user", text); input = ""
                automatic.accept(vm, automatic.selectedIds) { routeResult = it; proposal = null; recordRoute("已按你的选择更新方案，请核对后开始导航。", vm.lastPlannedHistoryId) }
                return
            }
            val ids = automatic.command(text)
            if (ids != null) {
                addLine("user", text); input = ""
                automatic.select(vm, ids)
                record(if (ids.isEmpty()) "已选择直达，请确认后规划。" else "已选 ${ids.size} 件事，请核对勾选项，再说“确认”或点击确认按钮。")
                return
            }
            error = "可勾选任务，或说“都去”“直接走”“只取快递”，选好后说“确认”。"; return
        }
        if (proposal != null) return
        val text = input.trim().take(4000)
        input = ""; error = ""; busy = true
        addLine("user", text)
        viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val messages = JSONArray().apply {
                    lines.filter { it.createdAt > 0 && Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).toLocalDate() == today }
                        .takeLast(20).forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
                }
                val answer = Api(vm.server).assistant(messages, vm.tripStarted)
                when (answer.getString("action")) {
                    "list_tasks" -> {
                        val pending = vm.tasks.value.filter { it.status == "pending" }
                        val today = LocalDate.now()
                        val groups = taskDateSections(pending, "pending", today)
                        val queried = when {
                            "明天" in text -> groups.filter { it.key == today.plusDays(1).toString() }
                            "今天" in text -> groups.filter { it.key != "none" && LocalDate.parse(it.key) <= today }
                            else -> groups
                        }
                        val label = if ("明天" in text) "明天" else if ("今天" in text) "今天及已逾期" else "全部"
                        val summary = queried.flatMap { group ->
                            listOf(group.title) + group.tasks.map { "· ${it.title}" }
                        }.take(30).joinToString("\n")
                        val unscheduled = if ("今天" in text || "明天" in text) pending.count { it.deadline.isBlank() } else 0
                        record(if (summary.isBlank()) "$label 没有设截止时间的待办。" else "$label 待办：\n$summary" +
                            if (unscheduled > 0) "\n另有 $unscheduled 件未设截止时间的待办，可在待办页查看。" else "")
                    }
                    "create_task", "plan_route" -> { if (answer.getString("action") == "plan_route") historyDestination = null; proposal = answer; record(if (answer.getString("action") == "create_task") "请确认这件事，保存后会加入待办。" else if (answer.optString("mode").isBlank()) "这次步行还是骑行？" else "我来定位并查看有哪些事可以顺路办。") }
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
    var showArchive by remember { mutableStateOf(false) }
    var archiveEventId by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scroll = rememberLazyListState()
    val context = LocalContext.current
    val auto = chat.automatic
    fun beginAutomatic() {
        chat.proposal?.takeIf { it.optString("action") == "plan_route" && it.optString("mode") in listOf("walking", "cycling") }?.let {
            val knownDestination = it.optJSONObject("known_destination")?.let(Place::from) ?: chat.historyDestination
            auto.start(vm, it.getString("destination"), it.optString("mode", "walking"), knownDestination)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) beginAutomatic()
        else chat.error = "未获得位置权限，可重试或手动设置起点。"
    }
    LaunchedEffect(chat.proposal) {
        // Re-entering this tab must keep the existing preview, destination choices and edits.
        // Only a new proposal (or an explicit retry) starts a fresh calculation.
        if (chat.proposal?.optString("action") == "plan_route" &&
            chat.proposal?.optString("mode") in listOf("walking", "cycling") &&
            !chat.preparingRoute && !auto.busy && auto.preview == null &&
            auto.choices.isEmpty() && auto.error.isBlank()
        ) {
            if (hasLocationPermission(context)) beginAutomatic()
            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    LaunchedEffect(vm.result) { if (chat.routeResult !== vm.result) chat.routeResult = null }
    fun chooseDestination(place: Place) {
        if (vm.busy || vm.updatingTrip || vm.restoringTrip) return
        vm.beginPlanEdit()
        auto.clear()
        vm.inputsChanged(); vm.destination = place
        vm.mode = chat.proposal?.optString("mode", "walking") ?: "walking"
        chat.preparingRoute = true
        focus.clearFocus(); keyboard?.hide()
    }
    LaunchedEffect(chat.lines.size, chat.proposal, chat.busy, chat.error, chat.routeResult) {
        val count = snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it > 0 }
        scroll.animateScrollToItem(count - 1)
    }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("助手", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton({ archiveEventId = null; showArchive = true }) { Text("路线记录") }
                TextButton(settings) { Text("设置") }
            }
        }
        LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            chat.lines.forEachIndexed { index, line ->
                val day = chatDateLabel(line.createdAt)
                if (index == 0 || chatDateLabel(chat.lines[index - 1].createdAt) != day) {
                    item(key = "date:${line.id}") {
                        Text(day, Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = line.id) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (line.role == "user") Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(color = if (line.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier.widthIn(max = 320.dp),
                            border = if (line.role == "user") null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(line.text)
                                if (line.routeHistoryId != null) {
                                    TextButton(onClick = { archiveEventId = line.routeHistoryId; showArchive = true }) { Text("查看当时路线") }
                                } else if (line.role == "assistant" &&
                                    listOf("路线已算好", "已更新方案", "已按你的选择更新方案").any { it in line.text }) {
                                    TextButton(onClick = { archiveEventId = null; showArchive = true }) { Text("在路线记录中查找") }
                                }
                            }
                        }
                    }
                }
            }
            if (chat.lines.size <= 1 && chat.proposal == null && chat.routeResult == null && !chat.busy) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text("试试快捷指令：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val promptChips = listOf(
                                Triple("导航去图书馆", R.drawable.ic_compass, "导航去图书馆"),
                                Triple("顺路取快递", R.drawable.ic_parcel, "顺路取快递"),
                                Triple("今天有哪些待办？", R.drawable.ic_tasks, "今天有哪些待办？"),
                                Triple("顺便买杯咖啡", R.drawable.ic_takeout, "顺便买杯咖啡")
                            )
                            promptChips.forEach { (label, iconRes, command) ->
                                AssistChip(
                                    onClick = {
                                        chat.input = command
                                        chat.send(vm)
                                    },
                                    leadingIcon = {
                                        Icon(painterResource(iconRes), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
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
                        chat.routeResult = it; chat.proposal = null; chat.recordRoute("已更新方案，请核对后开始导航。", vm.lastPlannedHistoryId)
                    })
                    } else TextButton({ auto.dismiss(vm); chat.proposal = null }) { Text("取消") }
                } else {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val task = proposal.getString("action") == "create_task"
                        val fixedLocation = task && requiresFixedPlace(proposal.optString("place_type"))
                        Text(if (task) "待确认任务" else "待确认目的地", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(if (task) proposal.getString("title") else proposal.getString("destination"), style = MaterialTheme.typography.titleMedium)
                        if (task && proposal.optString("deadline").isNotBlank()) Text("截止 ${displayTime(proposal.getString("deadline"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (fixedLocation) Text("请选择这次实际领取点；可从常用地点中挑选。", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (task) {
                                val suggestedTask = Errand(title = proposal.getString("title"), placeType = proposal.getString("place_type"), deadline = proposal.optString("deadline"))
                                if (fixedLocation) draft = suggestedTask else chat.saveTask(vm, suggestedTask)
                            } else {
                                val keyword = proposal.getString("destination")
                                val familiar = vm.familiarPlace(keyword)
                                if (familiar != null) chooseDestination(familiar) else search = keyword
                            }
                        }, modifier = Modifier.weight(1f), enabled = !chat.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip) {
                            Text(if (fixedLocation) "选择领取地点" else if (task) "快速保存" else "选择目的地")
                        }
                        if (task && !fixedLocation) {
                            TextButton(onClick = {
                                draft = Errand(title = proposal.getString("title"), placeType = proposal.getString("place_type"), deadline = proposal.optString("deadline"))
                            }, enabled = !chat.busy && !vm.busy) {
                                Text("微调")
                            }
                        }
                        TextButton({ chat.proposal = null; chat.record("已取消这条建议，未执行操作。") }, enabled = !chat.busy) { Text("取消") }
                        }
                    }
                }
                }
            } }
            vm.result?.let { route -> item {
                val current = route === vm.result && !vm.editingPlan && !vm.restoringTrip
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (current) "路线已算好" else "此方案已更新，请查看当前行程", style = MaterialTheme.typography.labelLarge)
                        Text(Place.from(route.getJSONObject("destination")).name, style = MaterialTheme.typography.titleLarge)
                        if (current) {
                            Text("全程 ${formatDuration(route.getInt("total_seconds"))} · 多花 ${formatDuration(route.getInt("extra_seconds"))}")
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
                            if (current) TextButton(openPlan) { Text("查看路线") }
                            else TextButton({ archiveEventId = null; showArchive = true }) { Text("路线记录") }
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
                OutlinedTextField(
                    value = chat.input,
                    onValueChange = { chat.input = it.take(4000) },
                    placeholder = { Text("记件事，或告诉我去哪儿") },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    enabled = !chat.busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (chat.input.isNotBlank() && !chat.busy && !auto.busy && !vm.busy && (chat.proposal == null || chat.proposal?.optString("action") == "plan_route")) {
                            chat.send(vm)
                        }
                    }),
                    trailingIcon = if (chat.input.isNotEmpty()) {
                        {
                            IconButton(onClick = { chat.input = "" }, modifier = Modifier.size(24.dp)) {
                                Text("✕", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    } else null
                )
                FilledIconButton({ chat.send(vm) }, enabled = !chat.busy && !auto.busy && !vm.busy && chat.input.isNotBlank() && (chat.proposal == null || chat.proposal?.optString("action") == "plan_route")) {
                    Icon(painterResource(R.drawable.ic_send), "发送")
                }
            }
        }
    }
    if (showArchive) RouteArchiveSheet(vm, initialEventId = archiveEventId, dismiss = { showArchive = false },
        repeatRoute = { event -> chat.repeatRoute(event) }, openCurrent = openPlan)
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
                        chat.recordRoute("路线已算好，请核对方案后开始导航。", vm.lastPlannedHistoryId)
                    }
                }, cancel = dismissRoute)
            }
        }
    }
}
