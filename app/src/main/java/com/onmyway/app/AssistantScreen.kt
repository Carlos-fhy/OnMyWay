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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val routeSessionId: String? = null,
    val routeAbandoned: Boolean = false,
    val routePlanId: String? = null,
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
internal class AssistantViewModel internal constructor(
    application: Application,
    conversationPrefs: SharedPreferences,
    database: TaskDatabase = TaskDatabase.get(application),
    private val requestTransport: AssistantRequestTransport = HttpAssistantRequestTransport,
    private val requestJournalName: String = "onmyway-assistant-requests.db",
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, application.getSharedPreferences("assistant_conversation", 0))
    private val store = AssistantStore(database, conversationPrefs)
    private val writeMutex = Mutex()
    private val welcome = ChatLine("assistant", "今天想做什么？可以让我记待办，或告诉我准备去哪儿。")
    private var storedLines by mutableStateOf(listOf(welcome))
    val lines: List<ChatLine> get() = storedLines
    var ready by mutableStateOf(false)
        private set
    private fun addLine(role: String, text: String, routeHistoryId: String? = null): ChatLine {
        val line = ChatLine(role, text, routeHistoryId = routeHistoryId)
        storedLines = ((if (storedLines.size == 1 && storedLines[0].id == welcome.id) emptyList() else storedLines)
            + line).takeLast(500)
        viewModelScope.launch {
            try { writeMutex.withLock { store.add(line) } }
            catch (e: Exception) { error = "对话保存失败，请稍后重试：" + (e.message ?: "数据库不可用") }
        }
        return line
    }
    private var storedProposal by mutableStateOf<JSONObject?>(null)
    var proposal: JSONObject?
        get() = storedProposal
        set(value) {
            val identified = value?.let {
                val previousPlan = storedProposal?.takeIf { old -> old.optString("action") == "plan_route" }?.optString("_plan_id")
                JSONObject(it.toString()).put("_draft_id", UUID.randomUUID().toString())
                    .put("_plan_id", it.optString("_plan_id").ifBlank { previousPlan.orEmpty() }.ifBlank { UUID.randomUUID().toString() })
            }
            storedProposal = identified
            clarification = null
            viewModelScope.launch {
                try { writeMutex.withLock { store.setDraft(identified) } }
                catch (e: Exception) { error = "待确认方案保存失败，请稍后重试：" + (e.message ?: "数据库不可用") }
            }
        }
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var pendingRequest by mutableStateOf<PendingAssistantRequest?>(null)
        private set
    var recoveryMessage by mutableStateOf("")
        private set
    private suspend fun <T> journal(block: (AssistantRequestJournal) -> T): T = withContext(Dispatchers.IO) {
        AssistantRequestJournal(getApplication(), requestJournalName).use(block)
    }
    var preparingRoute by mutableStateOf(false)
    var routeResult by mutableStateOf<JSONObject?>(null)
    var historyDestination by mutableStateOf<Place?>(null)
    var clarification by mutableStateOf<ClarificationState?>(null)
        private set
    val automatic = AutomaticRoutePlanner(viewModelScope, persistChoices = { choices ->
        viewModelScope.launch {
            try { writeMutex.withLock { store.saveChoices(choices) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "规划选择保存失败，请稍后重试。" }
        }
    }, persistClarification = { state ->
        if (state.question.draftId == proposal?.optString("_draft_id")) clarification = state
        viewModelScope.launch {
            try { writeMutex.withLock { store.saveClarification(state) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "追问状态保存失败，请稍后重试。" }
        }
    })
    init {
        viewModelScope.launch {
            try {
                val restored = writeMutex.withLock { store.load() }
                storedLines = restored.first.ifEmpty { listOf(welcome) }
                automatic.restoredChoices = writeMutex.withLock { store.planningChoices() }
                clarification = writeMutex.withLock { store.clarification() }
                clarification?.let(automatic::restoreQuestion)
                storedProposal = restored.second
                val interrupted = journal { it.pending() }
                if (interrupted != null && store.responseApplied(interrupted.requestId)) {
                    journal { it.finish(interrupted.requestId) }
                } else {
                    pendingRequest = interrupted
                    if (interrupted != null) recoveryMessage = "上次请求尚未取回结果，可以继续获取。"
                }
                ready = true
                if (store.legacyWarning) error = "旧版对话中有无法读取的记录；原始数据已保留，其余功能可继续使用。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "对话记录读取失败：" + (e.message ?: "请重试") }
        }
    }
    suspend fun conversationDays() = writeMutex.withLock { store.days() }
    suspend fun conversationDay(day: LocalDate) = writeMutex.withLock { store.day(day) }

    fun chooseMode(mode: String) {
        if (!ready || busy || pendingRequest != null || mode !in listOf("walking", "cycling")) return
        val current = proposal ?: return
        if (!automatic.clear()) return
        error = ""
        proposal = JSONObject(current.toString()).put("mode", mode)
    }
    fun record(text: String) = addLine("assistant", text)
    fun recordRoute(text: String, historyId: String?) = addLine("assistant", text, historyId)
    fun repeatRoute(event: RouteHistory, vm: AppViewModel): Boolean {
        if (!ready || busy || pendingRequest != null || vm.busy || vm.updatingTrip || vm.restoringTrip) return false
        if (proposal?.optString("action") in listOf("create_task", "create_tasks")) {
            error = "请先保存或取消当前待办草稿，再从历史记录重新规划。"
            vm.message = error
            return false
        }
        if (!automatic.clear()) return false
        val request = JSONObject(event.requestJson)
        val place = Place.from(request.getJSONObject("destination"))
        val travelMode = request.optString("mode").takeIf { it in listOf("walking", "cycling") } ?: "walking"
        routeResult = null; error = ""; preparingRoute = false
        historyDestination = place
        proposal = JSONObject().put("action", "plan_route").put("destination", place.name)
            .put("mode", travelMode).put("known_destination", place.json()).put("_plan_id", UUID.randomUUID().toString())
        record("已从行程记录选择${place.name}；会从当前位置重新定位，并按现在的待办重新规划。")
        return true
    }
    fun routeEnded() {
        if (!automatic.clear()) return
        routeResult = null; historyDestination = null; preparingRoute = false
        if (proposal?.optString("action") == "plan_route") proposal = null
    }
    fun completeRoute(draftId: String?, result: JSONObject, historyId: String?, message: String) {
        // A late completion must not dismiss a newer proposal or its manual editor.
        if (proposal?.optString("_draft_id") == draftId) {
            routeResult = result
            preparingRoute = false
            proposal = null
        }
        recordRoute(message, historyId)
    }
    fun saveTask(vm: AppViewModel, task: Errand, expectedDraftId: String): Boolean {
        if (busy || !ready || pendingRequest != null || automatic.committing) return false
        if (proposal?.optString("_draft_id") != expectedDraftId || proposal?.optString("action") != "create_task") {
            error = "待确认任务已变化，请关闭旧编辑页并重新确认。"
            return false
        }
        busy = true; error = ""
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    vm.saveAssistantTask(task, expectedDraftId)
                    val restored = store.load()
                    storedLines = restored.first.ifEmpty { listOf(welcome) }
                    // Do not enqueue another clear: the transaction already consumed the
                    // draft, and a replacement may have arrived while Room was suspended.
                    if (storedProposal?.optString("_draft_id") == expectedDraftId) {
                        val restoredQuestion = store.clarification()
                        val restoredChoices = store.planningChoices()
                        if (storedProposal?.optString("_draft_id") == expectedDraftId) {
                            automatic.clear()
                            automatic.restoredChoices = restoredChoices
                            storedProposal = restored.second
                            clarification = restoredQuestion
                            historyDestination = null
                        }
                    }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
        return true
    }
    private fun mutateTaskDraft(vm: AppViewModel, expectedDraftId: String, operation: suspend () -> Unit): Boolean {
        if (!ready || busy || pendingRequest != null || automatic.committing || vm.busy || vm.updatingTrip || vm.restoringTrip) return false
        if (proposal?.optString("_draft_id") != expectedDraftId || proposal?.optString("action") != "create_tasks") {
            error = "待确认任务已变化，请重新打开当前草稿。"
            return false
        }
        busy = true; error = ""
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    operation()
                    val restored = store.load()
                    storedLines = restored.first.ifEmpty { listOf(welcome) }
                    if (storedProposal?.optString("_draft_id") == expectedDraftId) {
                        storedProposal = restored.second
                        clarification = store.clarification()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
        return true
    }

    fun saveTasks(vm: AppViewModel, expectedDraftId: String, selections: Map<String, Int>): Boolean =
        mutateTaskDraft(vm, expectedDraftId) { vm.saveAssistantTasks(expectedDraftId, selections) }

    fun editTaskDraft(vm: AppViewModel, edit: TaskDraftEdit, task: Errand): Boolean =
        mutateTaskDraft(vm, edit.draftId) { store.updateDraftItem(edit.draftId, edit.itemId, edit.version, task) }

    private fun requestContext(vm: AppViewModel): String {
        return JSONObject().put("draft_id", proposal?.optString("_draft_id") ?: JSONObject.NULL)
            .put("trip", assistantTripContext(vm.planningBase()))
            .put("started", vm.tripStarted).toString()
    }

    private fun contextMatches(request: PendingAssistantRequest, vm: AppViewModel): Boolean =
        !vm.restoringTrip && !vm.updatingTrip && !vm.busy && !automatic.busy &&
            runCatching { Api.validateBase(vm.server) == request.server && requestContext(vm) == request.contextKey }.getOrDefault(false)

    /** Explicit recovery queries the saved request before considering a same-ID resubmission. */
    fun recoverRequest(vm: AppViewModel) {
        val request = pendingRequest ?: return
        if (!ready || busy || vm.restoringTrip || vm.updatingTrip || vm.busy || automatic.busy) return
        busy = true; error = ""
        viewModelScope.launch {
            try {
                if (writeMutex.withLock { store.responseApplied(request.requestId) }) {
                    refreshConversation(vm)
                    finishRequest(request)
                    return@launch
                }
                if (!contextMatches(request, vm)) {
                    recoveryMessage = "服务地址或当前方案已变化，旧结果不会应用。请重新编辑原指令。"
                    return@launch
                }
                if (request.resultJson != null) {
                    receiveResponse(request, JSONObject(request.resultJson), vm)
                    return@launch
                }
                val status = requestTransport.status(request.server, JSONObject(request.bodyJson))
                check(status.optString("request_id") == request.requestId && status.optString("operation") == "chat") {
                    "恢复接口返回了不匹配的请求。"
                }
                when (status.getString("state")) {
                    "completed" -> receiveResponse(request, status.getJSONObject("result"), vm)
                    "not_found" -> {
                        // The server did not record this request. Still use the original body
                        // and ID; a concurrent/late first attempt is deduplicated by the server.
                        if (contextMatches(request, vm))
                            receiveResponse(request, requestTransport.submit(request.server, JSONObject(request.bodyJson)), vm)
                        else recoveryMessage = "当前方案已变化，请重新编辑原指令。"
                    }
                    "running" -> recoveryMessage = "服务仍在处理，请稍后继续获取结果；不会重复发送。"
                    "failed" -> recoveryMessage = "上次请求处理失败（HTTP ${status.optInt("error_status", 500)}），可重新编辑后发送。"
                    "unresolved" -> recoveryMessage = "暂时无法确定处理结果，可稍后查询，或重新编辑；旧请求的建议不会自动执行。"
                    else -> error("无法识别请求恢复状态。")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e); recoveryMessage = "原请求仍已保留，可以稍后继续获取结果。" }
            finally { busy = false }
        }
    }

    fun editInterruptedRequest() {
        val request = pendingRequest ?: return
        if (!ready || busy) return
        busy = true
        viewModelScope.launch {
            try {
                writeMutex.withLock { store.add(ChatLine("user", request.userText, request.userMessageId, request.createdAt)) }
                finishRequest(request)
                input = request.userText
                error = ""
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "暂时无法更新请求记录，请重试。" }
            finally { busy = false }
        }
    }

    private suspend fun finishRequest(request: PendingAssistantRequest) {
        journal { it.finish(request.requestId) }
        if (pendingRequest?.requestId == request.requestId) {
            pendingRequest = null
            recoveryMessage = ""
        }
    }

    private suspend fun receiveResponse(request: PendingAssistantRequest, answer: JSONObject, vm: AppViewModel) {
        if (!journal { it.saveResult(request.requestId, answer.toString()) }) return
        pendingRequest = request.copy(resultJson = answer.toString())
        if (writeMutex.withLock { store.responseApplied(request.requestId) }) {
            refreshConversation(vm)
            finishRequest(request)
            return
        }
        if (!contextMatches(request, vm)) {
            recoveryMessage = "当前方案或服务地址已变化，回复已保留但未应用。请重新编辑原指令。"
            return
        }
        val action = answer.getString("action")
        val batch = action in listOf("create_tasks", "update_task_drafts") ||
            (action == "create_task" && (answer.has("planned_date") || answer.has("priority")))
        val replace = batch || action in listOf("create_task", "plan_route")
        check(!replace || batch || taskDraftItems(proposal).isEmpty()) { "请先保存或取消剩余任务草稿。" }
        val replacement = if (batch) taskDraftReplacement(answer, proposal, request.requestId)
        else if (replace) JSONObject(answer.toString()).apply {
            put("_draft_id", request.requestId)
            val previousPlan = proposal?.takeIf { action == "plan_route" && it.optString("action") == "plan_route" }
                ?.optString("_plan_id").orEmpty()
            val activePlan = if (action == "plan_route" && !vm.tripStarted)
                vm.planningBase()?.let { it.planId.ifBlank { it.sessionId } }.orEmpty() else ""
            put("_plan_id", previousPlan.ifBlank { activePlan }.ifBlank { request.requestId })
        } else null
        val reply = when (action) {
            "create_tasks" -> "已整理为多项草稿，请逐项核对，可以只保存其中几项。"
            "update_task_drafts" -> "已修改指定草稿，其他条目保留；核对后再保存。"
            "create_task" -> "请确认这件事，保存后会加入待办。"
            "plan_route" -> if (answer.optString("mode").isBlank()) "这次步行还是骑行？" else "我来定位并查看有哪些事可以顺路办。"
            "list_tasks" -> taskListReply(request)
            else -> answer.getString("reply")
        }
        val expectedDraft = JSONObject(request.contextKey).optString("draft_id").takeIf { it.isNotBlank() && it != "null" }
        val applied = writeMutex.withLock {
            if (!contextMatches(request, vm)) false else store.applyResponse(request.requestId, expectedDraft,
                ChatLine("user", request.userText, request.userMessageId, request.createdAt),
                ChatLine("assistant", reply), replacement, replace,
                expectedTripContext = JSONObject(request.contextKey).getString("trip"))
        }
        if (!applied && !writeMutex.withLock { store.responseApplied(request.requestId) }) {
            recoveryMessage = "待确认方案已更新，旧回复未应用。请重新编辑原指令。"
            return
        }
        // Room's receipt is authoritative. If the process dies before journal deletion,
        // the next launch only removes the journal entry; it never recreates the draft.
        refreshConversation(vm)
        finishRequest(request)
    }

    private suspend fun refreshConversation(vm: AppViewModel) {
        val (restored, question) = writeMutex.withLock { store.load() to store.clarification() }
        storedLines = restored.first.ifEmpty { listOf(welcome) }
        if (storedProposal?.optString("_draft_id") != restored.second?.optString("_draft_id")) {
            check(automatic.supersede(vm)) { "行程正在更新，请稍后继续获取结果。" }
            historyDestination = null
            storedProposal = restored.second
            clarification = question
        }
    }

    private suspend fun taskListReply(request: PendingAssistantRequest): String {
        val text = request.userText
        val pending = store.pendingTasks()
        val day = java.time.OffsetDateTime.parse(JSONObject(request.bodyJson).getString("now")).toLocalDate()
        val groups = taskDateSections(pending, "pending", LocalDate.now())
        val queried = when {
            "明天" in text -> groups.filter { it.key == day.plusDays(1).toString() }
            "今天" in text -> groups.filter { it.key != "none" && LocalDate.parse(it.key) <= day }
            else -> groups
        }
        val label = if ("明天" in text) "${day.plusDays(1)}" else if ("今天" in text) "$day 及此前" else "全部"
        val summary = queried.flatMap { group -> listOf(group.title) + group.tasks.map { "· ${it.title}" } }
            .take(30).joinToString("\n")
        val unscheduled = if ("今天" in text || "明天" in text) pending.count { it.plannedDate.isBlank() && it.deadline.isBlank() } else 0
        return (if (summary.isBlank()) "$label 没有安排日期的待办。" else "$label 待办：\n$summary") +
            if (unscheduled > 0) "\n另有 $unscheduled 件未安排日期的待办，可在待办页查看。" else ""
    }

    fun send(vm: AppViewModel) {
        if (!ready || busy || vm.busy || vm.updatingTrip || vm.restoringTrip || automatic.busy || input.isBlank()) return
        if (pendingRequest != null) {
            error = "上一条请求尚未取回，请先继续获取结果或重新编辑。"
            return
        }
        val pendingAtSend = proposal
        val command = input.trim().trimEnd('。', '！', '!')
        if (pendingAtSend != null && command in listOf("取消", "不要了", "不做了", "保留原方案")) {
            addLine("user", input.trim()); input = ""; error = ""
            if (!automatic.dismiss(vm)) return
            proposal = null
            record("已取消待确认建议，没有执行。")
            return
        }
        if (pendingAtSend?.optString("action") == "create_task" && command in listOf("确认", "保存", "记下")) {
            if (requiresFixedPlace(pendingAtSend.optString("place_type"))) {
                error = "请先在卡片中选择实际领取地点。"; return
            }
            addLine("user", input.trim()); input = ""; error = ""
            saveTask(vm, Errand(title = pendingAtSend.getString("title"),
                placeType = pendingAtSend.getString("place_type"),
                deadline = pendingAtSend.optString("deadline")), pendingAtSend.getString("_draft_id"))
            return
        }
        if (pendingAtSend?.optString("action") == "create_tasks") {
            val items = taskDraftItems(pendingAtSend)
            val selections = taskDraftConfirmation(command, items)
            if (selections != null) {
                addLine("user", input.trim()); input = ""; error = ""
                saveTasks(vm, pendingAtSend.getString("_draft_id"), selections)
                return
            }
            if (command in listOf("确认", "保存", "记下") ||
                Regex("(?:只)?(?:确认|保存|记下).+").matches(command)) {
                error = "请说明保存哪一项，例如“保存第二项”、“保存打印论文”，或说“全部保存”。"
                return
            }
        }
        if (pendingAtSend?.optString("action") == "plan_route" && command in listOf("确认", "确认加入", "按所选规划") &&
            automatic.preview == null) {
            error = "路线尚未计算完成，请先选择出行方式或重试规划。"; return
        }
        if (automatic.choices.isNotEmpty()) {
            resolvePlaceReply(input, automatic.choices)?.let { chosen ->
                addLine("user", input.trim()); input = ""; error = ""
                automatic.choose(vm, chosen)
                record("已选择${chosen.name}，我来重新定位并计算路线。")
                return
            }
        }
        if (proposal?.optString("action") == "plan_route") {
            val mode = travelModeReply(input)
            if (mode != null) {
                addLine("user", input.trim())
                input = ""; chooseMode(mode); return
            }
        }
        if (automatic.preview != null) {
            val text = input.trim()
            if (text.trimEnd('。', '！', '!') in listOf("确认", "确认加入", "按所选规划")) {
                addLine("user", text); input = ""
                automatic.accept(vm, automatic.selectedIds) {
                    completeRoute(pendingAtSend?.optString("_draft_id"), it, vm.lastPlannedHistoryId, "已按你的选择更新方案，请核对后开始导航。")
                }
                return
            }
            val ids = automatic.command(text)
            if (ids != null) {
                addLine("user", text); input = ""
                automatic.select(vm, ids)
                record(if (ids.isEmpty()) "已选择直达，请确认后规划。" else "已选 ${ids.size} 件事，请核对勾选项，再说“确认”或点击确认按钮。")
                return
            }
            // Other instructions may replace the pending proposal. Keep it until
            // the model has returned a valid new action.
        }
        val text = input.trim().take(4000)
        input = ""; error = ""; busy = true
        val userLine = addLine("user", text)
        viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val pendingContext = pendingAtSend?.let(::assistantDraftContext)
                // Keep short follow-ups across date boundaries, but mark old turns so
                // yesterday's route is not mistaken for an active trip.
                val recentLines = assistantContextLines(lines)
                fun contextualText(line: ChatLine, latest: Boolean): String {
                    val day = Instant.ofEpochMilli(line.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    return if (latest || day == today) line.text else "[${day}] ${line.text}".take(4000)
                }
                val messages = JSONArray().apply {
                    (if (pendingContext == null) recentLines else recentLines.dropLast(1)).forEachIndexed { index, line ->
                        put(JSONObject().put("role", line.role)
                            .put("content", contextualText(line, pendingContext == null && index == recentLines.lastIndex)))
                    }
                    if (pendingContext != null) {
                        put(JSONObject().put("role", "assistant").put("content", pendingContext))
                        recentLines.lastOrNull()?.let {
                            put(JSONObject().put("role", it.role).put("content", contextualText(it, true)))
                        }
                    }
                }
                clarification?.takeIf { it.question.draftId == pendingAtSend?.optString("_draft_id") }?.let { state ->
                    val content = "当前等待回答：" + state.question.prompt + state.choices.take(10).joinToString(
                        prefix = "；候选：", separator = "；") { "${it.position + 1}. ${it.name} ${it.address}" }
                    if (messages.length() > 0) messages.remove(messages.length() - 1)
                    messages.put(JSONObject().put("role", "assistant").put("content", content.take(4000)))
                    messages.put(JSONObject().put("role", "user").put("content", text))
                }
                val requestId = UUID.randomUUID().toString()
                val body = JSONObject().put("request_id", requestId).put("messages", messages)
                    .put("route_active", vm.tripStarted).put("now", java.time.OffsetDateTime.now().toString())
                if (pendingAtSend?.optString("action") == "create_tasks")
                    body.put("draft_items", taskDraftRequestItems(pendingAtSend))
                val request = PendingAssistantRequest(requestId, Api.validateBase(vm.server), body.toString(),
                    text, userLine.id, userLine.createdAt, requestContext(vm))
                journal { it.create(request) }
                pendingRequest = request
                // The journal always precedes transport. A crash before this message write is
                // repaired by the response transaction using the same userMessageId.
                writeMutex.withLock { store.add(userLine) }
                receiveResponse(request, requestTransport.submit(request.server, JSONObject(request.bodyJson)), vm)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = networkMessage(e)
                if (pendingRequest == null) input = text
                else recoveryMessage = "结果尚未取回，原指令已保留；继续获取不会重复创建请求。"
            }
            finally { busy = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantScreen(vm: AppViewModel, tasks: List<Errand>, settings: () -> Unit, openPlan: () -> Unit, openNavigation: () -> Unit,
    chat: AssistantViewModel = viewModel()) {
    var draft by remember { mutableStateOf<Pair<Errand, String>?>(null) }
    var taskDraftEdit by remember { mutableStateOf<TaskDraftEdit?>(null) }
    var search by remember { mutableStateOf<String?>(null) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    val historyDrawer = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var selectedHistoryDate by rememberSaveable { mutableStateOf<String?>(null) }
    var screenTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) screenTime = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            screenTime = System.currentTimeMillis()
        }
    }
    val today = Instant.ofEpochMilli(screenTime).atZone(ZoneId.systemDefault()).toLocalDate()
    val displayedDay = selectedHistoryDate?.let(LocalDate::parse) ?: today
    val viewingToday = displayedDay == today
    var visibleLines by remember { mutableStateOf<List<ChatLine>>(emptyList()) }
    var dayLoadError by remember { mutableStateOf("") }
    LaunchedEffect(chat.ready, chat.lines, displayedDay, vm.routeSession?.id, vm.tripStarted) {
        if (chat.ready) try {
            visibleLines = latestRouteEntries(chat.conversationDay(displayedDay))
            dayLoadError = ""
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { dayLoadError = "对话读取失败，请重新选择日期。" }
    }
    var archiveEventId by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scroll = rememberLazyListState()
    val context = LocalContext.current
    val auto = chat.automatic
    fun beginAutomatic() {
        if (chat.pendingRequest != null || chat.busy) return
        chat.proposal?.takeIf { it.optString("action") == "plan_route" && it.optString("mode") in listOf("walking", "cycling") }?.let {
            val knownDestination = it.optJSONObject("known_destination")?.let(Place::from) ?: chat.historyDestination
            auto.start(vm, it.getString("destination"), it.optString("mode", "walking"), knownDestination, it.getString("_draft_id"), it.getString("_plan_id"))
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocationPermission(context)) beginAutomatic()
        else chat.error = "未获得位置权限，可重试或手动设置起点。"
    }
    LaunchedEffect(chat.proposal, chat.ready, chat.pendingRequest, chat.busy, vm.restoringTrip, vm.updatingTrip, vm.busy) {
        // Re-entering this tab must keep the existing preview, destination choices and edits.
        // Only a new proposal (or an explicit retry) starts a fresh calculation.
        if (chat.ready && chat.pendingRequest == null && !chat.busy && !vm.restoringTrip && !vm.updatingTrip && !vm.busy && chat.proposal?.optString("action") == "plan_route" &&
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
        if (vm.busy || vm.updatingTrip || vm.restoringTrip || auto.committing) return
        vm.beginPlanEdit()
        auto.clear()
        vm.inputsChanged(); vm.destination = place
        vm.mode = chat.proposal?.optString("mode", "walking") ?: "walking"
        chat.preparingRoute = true
        focus.clearFocus(); keyboard?.hide()
    }
    LaunchedEffect(visibleLines.lastOrNull()?.id, visibleLines.size, chat.proposal, chat.busy, chat.error, chat.routeResult) {
        val count = snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it > 0 }
        scroll.animateScrollToItem(count - 1)
    }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    ConversationHistoryDrawer(chat, historyDrawer, displayedDay, { day ->
        selectedHistoryDate = if (day == today) null else day.toString()
        drawerScope.launch { historyDrawer.close() }
    }) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ drawerScope.launch { historyDrawer.open() } }) {
                    Icon(painterResource(R.drawable.ic_history_menu), "打开历史对话")
                }
                Text("助手", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.headlineSmall)
            }
            Row {
                TextButton({ archiveEventId = null; showArchive = true }) { Text("路线记录") }
                TextButton(settings) { Text("设置") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text((if (viewingToday) "今天 · " else "") + displayedDay.format(DateTimeFormatter.ofPattern("M月d日")),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!viewingToday) TextButton({ selectedHistoryDate = null }) { Text("返回今天") }
        }
        LazyColumn(Modifier.weight(1f), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (dayLoadError.isNotBlank()) item { Text(dayLoadError, color = MaterialTheme.colorScheme.error) }
            visibleLines.forEachIndexed { index, line ->
                val day = chatDateLabel(line.createdAt)
                if (index == 0 || chatDateLabel(visibleLines[index - 1].createdAt) != day) {
                    item(key = "date:${line.id}") {
                        Text(day, Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = line.id) {
                    if (line.routeHistoryId != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("路线方案 · " + Instant.ofEpochMilli(line.createdAt).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ archiveEventId = line.routeHistoryId; showArchive = true }) { Text("查看记录") }
                        }
                    } else Box(Modifier.fillMaxWidth(), contentAlignment = if (line.role == "user") Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(color = if (line.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier.widthIn(max = 320.dp),
                            border = if (line.role == "user") null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(line.text)
                                if (line.role == "assistant" &&
                                    listOf("路线已算好", "已更新方案", "已按你的选择更新方案").any { it in line.text }) {
                                    TextButton(onClick = { archiveEventId = null; showArchive = true }) { Text("在路线记录中查找") }
                                }
                            }
                        }
                    }
                }
            }
            if (viewingToday) {
            chat.pendingRequest?.let { request -> item(key = "interrupted-request") {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (chat.busy) "正在获取结果" else "上次请求尚未完成", style = MaterialTheme.typography.titleSmall)
                        Text(request.userText, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (chat.recoveryMessage.isNotBlank()) Text(chat.recoveryMessage, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ chat.recoverRequest(vm) }, enabled = !chat.busy && !auto.busy && !vm.busy && !vm.restoringTrip && !vm.updatingTrip) {
                                Text("继续获取结果")
                            }
                            TextButton({ chat.editInterruptedRequest() }, enabled = !chat.busy) { Text("重新编辑") }
                        }
                    }
                }
            } }
            if (chat.ready && chat.pendingRequest == null && visibleLines.size <= 1 && chat.proposal == null && vm.result == null && !chat.busy) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text("今天想做什么？", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            chat.proposal?.takeIf { chat.pendingRequest == null }?.let { proposal -> item {
                if (proposal.optString("action") == "plan_route") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("walking" to "步行", "cycling" to "骑行").forEach { (mode, label) ->
                            FilterChip(selected = proposal.optString("mode") == mode, onClick = { chat.chooseMode(mode) },
                                enabled = !auto.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip, label = { Text(label) })
                        }
                    }
                    if (proposal.optString("mode").isNotBlank()) {
                    AutomaticRouteCard(auto, vm, retry = {
                        chat.error = ""
                        if (hasLocationPermission(context)) beginAutomatic()
                        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, manual = {
                        if (auto.prepareManual(vm)) chat.preparingRoute = true else search = proposal.getString("destination")
                    }, cancel = { if (auto.dismiss(vm)) chat.proposal = null }, accepted = {
                        chat.completeRoute(proposal.optString("_draft_id"), it, vm.lastPlannedHistoryId, "已更新方案，请核对后开始导航。")
                    })
                    } else TextButton({ if (auto.dismiss(vm)) chat.proposal = null },
                        enabled = !auto.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip) { Text("取消") }
                } else if (proposal.optString("action") == "create_tasks") {
                    AssistantTaskDraftCard(proposal,
                        enabled = chat.ready && !chat.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip,
                        edit = { taskDraftEdit = it },
                        confirm = { chat.saveTasks(vm, proposal.getString("_draft_id"), it) },
                        cancel = { chat.proposal = null; chat.record("已取消剩余草稿；已保存的待办保留。") })
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
                                val draftId = proposal.getString("_draft_id")
                                if (fixedLocation) draft = suggestedTask to draftId else chat.saveTask(vm, suggestedTask, draftId)
                            } else {
                                val keyword = proposal.getString("destination")
                                val familiar = vm.familiarPlace(keyword)
                                if (familiar != null) chooseDestination(familiar) else search = keyword
                            }
                        }, modifier = Modifier.weight(1f), enabled = chat.ready && !chat.busy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip) {
                            Text(if (fixedLocation) "选择领取地点" else if (task) "快速保存" else "选择目的地")
                        }
                        if (task && !fixedLocation) {
                            TextButton(onClick = {
                                draft = Errand(title = proposal.getString("title"), placeType = proposal.getString("place_type"), deadline = proposal.optString("deadline")) to proposal.getString("_draft_id")
                            }, enabled = chat.ready && !chat.busy && !vm.busy) {
                                Text("微调")
                            }
                        }
                        TextButton({ chat.proposal = null; chat.record("已取消这条建议，未执行操作。") }, enabled = chat.ready && !chat.busy) { Text("取消") }
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
                            if (vm.planNeedsRefresh) Text(if (vm.tripStarted) "待办有变化，当前导航路线未自动更改，建议重新规划。" else "待办有变化，此路线需重新规划后才能开始。", style = MaterialTheme.typography.bodySmall)
                            val stops = route.getJSONArray("stops")
                            for (i in 0 until stops.length()) Text("${i + 1}. ${Place.from(stops.getJSONObject(i).getJSONObject("place")).name}", style = MaterialTheme.typography.bodyMedium)
                            val omitted = route.getJSONArray("omitted")
                            if (omitted.length() > 0) Text("${omitted.length()} 件未安排，请在详情查看原因。", style = MaterialTheme.typography.bodySmall)
                            val warnings = route.getJSONArray("warnings")
                            for (i in 0 until warnings.length()) Text(warnings.getString(i), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (current) Button({
                                if (vm.planNeedsRefresh && !vm.tripStarted) { vm.beginPlanEdit(); openPlan() }
                                else openNavigation()
                            }, enabled = !vm.busy && !vm.updatingTrip && chat.proposal == null && chat.pendingRequest == null) {
                                Text(if (vm.planNeedsRefresh && !vm.tripStarted) "重新规划" else if (vm.tripStarted) "继续导航" else "开始导航")
                            }
                            if (current) TextButton(openPlan) { Text("查看路线") }
                            else TextButton({ archiveEventId = null; showArchive = true }) { Text("路线记录") }
                        }
                    }
                }
            } }
            if (!chat.ready) item { Text("正在读取本机对话记录…", style = MaterialTheme.typography.bodySmall) }
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
                        Text(if (chat.pendingRequest != null) "原请求已保留，请使用上方恢复入口。" else if (chat.proposal != null) "建议已保留，可再次确认。" else if (chat.input.isNotBlank()) "内容已保留，可修改后重新发送。" else "可重新录音，或切换文字输入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            }
        }
        if (!viewingToday) TextButton({ selectedHistoryDate = null }, Modifier.fillMaxWidth()) {
            Text("返回今天继续对话")
        } else
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ focus.clearFocus(); keyboard?.hide(); showVoice = !showVoice }, enabled = chat.ready && !chat.busy) {
                Icon(painterResource(if (showVoice) R.drawable.ic_keyboard else R.drawable.ic_mic), if (showVoice) "切换文字输入" else "切换语音输入")
            }
            if (showVoice) {
                Box(Modifier.weight(1f)) {
                    CloudVoiceButton(vm.server, enabled = chat.ready && chat.pendingRequest == null && !chat.busy && !auto.busy && !vm.busy,
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
                    enabled = chat.ready && !chat.busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (chat.input.isNotBlank() && !chat.busy && !auto.busy && !vm.busy) {
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
                FilledIconButton({ chat.send(vm) }, enabled = chat.ready && chat.pendingRequest == null && !chat.busy && !auto.busy && !vm.busy && chat.input.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_send), "发送")
                }
            }
        }
    }
    }
    if (showArchive) RouteArchiveSheet(vm, initialEventId = archiveEventId, dismiss = { showArchive = false },
        repeatRoute = { event -> chat.repeatRoute(event, vm) }, openCurrent = openPlan)
    draft?.let { (initial, draftId) -> TaskEditor(vm, initial, { draft = null }) { updated ->
        if (chat.saveTask(vm, updated, draftId)) draft = null
    } }
    taskDraftEdit?.let { edit -> key(edit.draftId, edit.itemId, edit.version) {
        TaskEditor(vm, edit.task, { taskDraftEdit = null }, allowIncompleteDraft = true, saveLabel = "保留草稿修改") { updated ->
            if (chat.editTaskDraft(vm, edit, updated)) taskDraftEdit = null
        }
    } }
    search?.let { keyword -> PlacePicker(vm, { search = null }, initialKeyword = keyword) { place ->
        search = null
        chooseDestination(place)
    } }
    if (chat.preparingRoute) {
        val dismissRoute = {
            if (!vm.busy && !vm.updatingTrip) { vm.cancelPlanEdit(); chat.preparingRoute = false }
        }
        ModalBottomSheet(onDismissRequest = dismissRoute,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || (!vm.busy && !vm.updatingTrip) })) {
            Column(Modifier.fillMaxHeight(.94f)) {
                if (vm.message.isNotBlank()) Text(vm.message, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                PrepareTrip(vm, tasks, submit = {
                    val confirmingDraft = chat.proposal
                    vm.planForAssistant(confirmingDraft?.optString("_plan_id"), confirmingDraft?.optString("_draft_id")) { response ->
                        chat.completeRoute(confirmingDraft?.optString("_draft_id"), response, vm.lastPlannedHistoryId, "路线已算好，请核对方案后开始导航。")
                    }
                }, cancel = dismissRoute)
            }
        }
    }
}
