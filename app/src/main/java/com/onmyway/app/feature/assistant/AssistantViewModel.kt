package com.onmyway.app.feature.assistant

import android.app.Application
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.isIndoor
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.state.NavigationEndEvent
import com.onmyway.app.core.state.networkMessage
import com.onmyway.app.data.local.AssistantRequestJournal
import com.onmyway.app.data.local.PendingAssistantRequest
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.remote.Api
import com.onmyway.app.data.remote.AssistantRequestTransport
import com.onmyway.app.data.remote.HttpAssistantRequestTransport
import com.onmyway.app.data.remote.assistantTripContext
import com.onmyway.app.feature.planning.AutomaticRoutePlanner
import com.onmyway.app.feature.planning.travelModeReply
import com.onmyway.app.feature.task.taskDateSections
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    private fun addLine(
        role: String,
        text: String,
        routeHistoryId: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): ChatLine {
        val line = ChatLine(role, text, id = id, routeHistoryId = routeHistoryId)
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
            if (value != null && !assistantCardVersionSupported(value)) {
                error = "此建议版本暂不受支持，请重新输入。"
                return
            }
            if (value != null && listOf("_draft_id", "_plan_id").any { value.has(it) && cardIdentity(value, it) == null }) {
                error = "建议标识无效，请重新输入。"
                return
            }
            val identified = value?.let {
                val previousPlan = storedProposal?.takeIf { old ->
                    old.optString("action") == "plan_route" && assistantCardIssue(old) == null
                }?.optString("_plan_id")
                JSONObject(it.toString()).put("_draft_id", UUID.randomUUID().toString())
                    .put("_plan_id", it.optString("_plan_id").ifBlank { previousPlan.orEmpty() }.ifBlank { UUID.randomUUID().toString() })
                    .put("_card_version", 1).apply {
                        if (optString("action") == "plan_route" && !has("_expires_at"))
                            put("_expires_at", System.currentTimeMillis() + 30 * 60_000L)
                    }
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
    var tripCompletion by mutableStateOf<TripCompletionPrompt?>(null)
        private set
    private var lastRouteEndEventId: String? = null
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
                if (store.legacyWarning) error = "部分旧记录或建议无法恢复，已保留有效内容；请重新输入无法操作的建议。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "对话记录读取失败：" + (e.message ?: "请重试") }
        }
    }
    suspend fun conversationDays() = writeMutex.withLock { store.days() }
    suspend fun conversationDay(day: LocalDate) = writeMutex.withLock { store.day(day) }

    fun chooseMode(mode: String) = chooseMode(mode, proposal?.optString("_draft_id").orEmpty())

    fun chooseMode(mode: String, expectedDraftId: String) {
        if (!ready || busy || pendingRequest != null || mode !in listOf("walking", "cycling")) return
        val current = proposal ?: return
        if (expectedDraftId.isBlank() || current.optString("_draft_id") != expectedDraftId ||
            current.optString("action") != "plan_route") {
            error = "待确认路线已变化，请使用当前卡片。"
            return
        }
        assistantCardIssue(current)?.let { error = it; return }
        if (!automatic.clear()) return
        error = ""
        proposal = JSONObject(current.toString()).put("mode", mode)
    }

    fun dismissDraft(expectedDraftId: String, expectedPlanId: String, vm: AppViewModel? = null): Boolean {
        if (!ready || busy || pendingRequest != null || automatic.committing ||
            vm?.let { it.busy || it.updatingTrip || it.restoringTrip } == true) return false
        val current = proposal
        if (expectedDraftId.isBlank() || expectedPlanId.isBlank() ||
            current?.optString("_draft_id") != expectedDraftId || current.optString("_plan_id") != expectedPlanId) {
            error = "待确认建议已变化，请使用当前卡片。"
            return false
        }
        assistantCardIssue(current)?.let { error = it; return false }
        val text = if (current.optString("action") == "create_tasks")
            "已取消剩余草稿；已保存的待办保留。" else "已取消待确认建议，没有执行。"
        busy = true; error = ""
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    val cancelled = store.cancelDraft(expectedDraftId, expectedPlanId)
                    val restored = store.load()
                    storedLines = restored.first.ifEmpty { listOf(welcome) }
                    if (storedProposal?.optString("_draft_id") == expectedDraftId &&
                        storedProposal?.optString("_plan_id") == expectedPlanId) {
                        if (cancelled && vm != null) automatic.dismiss(vm) else automatic.clear()
                        automatic.restoredChoices = store.planningChoices()
                        storedProposal = restored.second
                        clarification = store.clarification()
                        historyDestination = null
                        preparingRoute = false
                    }
                    if (cancelled) record(text)
                    else error = "待确认建议已变化，请使用当前卡片。"
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
        return true
    }
    fun record(text: String, id: String = UUID.randomUUID().toString()) = addLine("assistant", text, id = id)
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
    fun routeEnded(event: NavigationEndEvent? = null, tasks: List<Errand> = emptyList()) {
        automatic.clear()
        routeResult = null; historyDestination = null; preparingRoute = false
        if (proposal?.optString("action") == "plan_route") proposal = null
        if (event == null || lastRouteEndEventId == event.id) return
        lastRouteEndEventId = event.id
        val tasksById = tasks.associateBy { it.id }
        val tripTasks = event.taskIds.distinct().mapNotNull { tasksById[it] }
        val unfinished = tripTasks.filter { it.status != "done" }
        val lineId = "route-end:${event.id}"
        if (unfinished.isEmpty()) {
            // Nothing to confirm; end the trip with a summary immediately.
            if (storedLines.none { it.id == lineId }) {
                record(tripCompletionSummary(event.arrived, event.destination,
                    tripTasks.filter { it.status == "done" }.map { it.title }, emptyList()), lineId)
            }
            tripCompletion = null
        } else {
            // The card comes first; the ending bubble is written after the user confirms.
            tripCompletion = TripCompletionPrompt(
                eventId = event.id,
                arrived = event.arrived,
                destination = event.destination,
                tasks = tripTasks.map { task ->
                    TripCompletionTask(task.id, task.title, tripCompletionPlaceLabel(task), task.status == "done")
                },
            )
        }
    }
    fun confirmTripCompletion(vm: AppViewModel, completedIds: Set<String>) {
        val prompt = tripCompletion ?: return
        val completed = prompt.tasks.filter { it.id in completedIds }
        val pending = prompt.tasks.filter { it.id !in completedIds }
        vm.setTripCompletion(
            doneIds = completed.map { it.id }.toSet(),
            pendingIds = pending.map { it.id }.toSet(),
        ) {
            record(tripCompletionSummary(prompt.arrived, prompt.destination,
                completed.map { it.title }, pending.map { it.title }), "route-end:${prompt.eventId}")
            if (tripCompletion?.eventId == prompt.eventId) tripCompletion = null
        }
    }
    fun dismissTripCompletion() {
        val prompt = tripCompletion ?: return
        val completed = prompt.tasks.filter { it.done }.map { it.title }
        val pending = prompt.tasks.filterNot { it.done }.map { it.title }
        record(tripCompletionSummary(prompt.arrived, prompt.destination, completed, pending,
            confirmed = false), "route-end:${prompt.eventId}")
        tripCompletion = null
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
        assistantCardIssue(proposal!!)?.let { error = it; return false }
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
        assistantCardIssue(proposal!!)?.let { error = it; return false }
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
        require(assistantCardVersionSupported(answer)) { "建议版本暂不支持，回复未应用，请重新输入需求。" }
        val action = answer.getString("action")
        val actionableProposal = proposal?.takeIf { assistantCardIssue(it) == null }
        val batch = action in listOf("create_tasks", "update_task_drafts") ||
            (action == "create_task" && answer.has("planned_date"))
        val replace = batch || action in listOf("create_task", "plan_route")
        check(!replace || batch || taskDraftItems(actionableProposal).isEmpty()) { "请先保存或取消剩余任务草稿。" }
        val replacement = if (batch) taskDraftReplacement(answer, actionableProposal, request.requestId)
        else if (replace) JSONObject(answer.toString()).apply {
            put("_draft_id", request.requestId)
            val previousPlan = actionableProposal?.takeIf { action == "plan_route" && it.optString("action") == "plan_route" }
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
        if (store.legacyWarning) error = "部分旧记录或建议无法恢复，已保留有效内容；请重新输入无法操作的建议。"
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

    private fun isExistingCardAction(command: String): Boolean {
        if (command in listOf("确认", "保存", "记下", "确认加入", "按所选规划", "取消", "不要了", "不做了",
                "保留原方案", "确认全部", "全部确认", "保存全部", "全部保存", "都保存", "都记下",
                "全选", "清除选择", "都去", "全部加入", "都加入", "直接走", "直接去目的地", "不办事", "只去目的地")) return true
        if (travelModeReply(command) != null || Regex("(?:只)?(?:确认|保存|记下).+").matches(command)) return true
        if (Regex("(?:选|选择)?\\s*(?:第\\s*)?([0-9]+|[一二三四五六七八九十]+)\\s*(?:个)?").matches(command)) return true
        if (Regex("(?:只(?:去|取|拿)?|不(?:去|取)?)(?:快递|打印|买药|药店|购物|超市|外卖)(?:了)?").matches(command)) return true
        return automatic.choices.any { it.name.trim() == command }
    }

    fun send(vm: AppViewModel) {
        if (!ready || busy || vm.busy || vm.updatingTrip || vm.restoringTrip || automatic.busy || input.isBlank()) return
        if (pendingRequest != null) {
            error = "上一条请求尚未取回，请先继续获取结果或重新编辑。"
            return
        }
        val originalProposal = proposal
        val cardIssue = originalProposal?.let { assistantCardIssue(it) }
        val command = input.trim().trimEnd('。', '！', '!')
        if (cardIssue != null && isExistingCardAction(command)) { error = cardIssue; return }
        val pendingAtSend = originalProposal?.takeIf { cardIssue == null }
        if (pendingAtSend != null && command in listOf("取消", "不要了", "不做了", "保留原方案")) {
            addLine("user", input.trim()); input = ""; error = ""
            dismissDraft(pendingAtSend.getString("_draft_id"), pendingAtSend.getString("_plan_id"), vm)
            return
        }
        if (pendingAtSend?.optString("action") == "create_task" && command in listOf("确认", "保存", "记下")) {
            if (!isIndoor(pendingAtSend.optString("place_type"))) {
                error = "请先在卡片中选择办理地点。"; return
            }
            addLine("user", input.trim()); input = ""; error = ""
            saveTask(vm, Errand(title = pendingAtSend.getString("title"),
                placeType = pendingAtSend.getString("place_type"),
                deadline = pendingAtSend.optString("deadline"),
                plannedDate = pendingAtSend.optString("planned_date")), pendingAtSend.getString("_draft_id"))
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
        if (pendingAtSend?.optString("action") == "plan_route" && automatic.choices.isNotEmpty()) {
            resolvePlaceReply(input, automatic.choices)?.let { chosen ->
                addLine("user", input.trim()); input = ""; error = ""
                automatic.choose(vm, chosen)
                record("已选择${chosen.name}，我来重新定位并计算路线。")
                return
            }
        }
        if (pendingAtSend?.optString("action") == "plan_route") {
            val mode = travelModeReply(input)
            if (mode != null) {
                addLine("user", input.trim())
                input = ""; chooseMode(mode, pendingAtSend.getString("_draft_id")); return
            }
        }
        if (pendingAtSend?.optString("action") == "plan_route" && automatic.preview != null) {
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
