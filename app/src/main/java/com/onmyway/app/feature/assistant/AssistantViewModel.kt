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
import com.onmyway.app.core.context.ContextSnapshot
import com.onmyway.app.core.location.FamiliarPlaceLookup
import com.onmyway.app.core.location.FamiliarPlaceResolver
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
import com.onmyway.app.data.remote.AuthenticatedAssistantRequestTransport
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
    private val requestTransport: AssistantRequestTransport = AuthenticatedAssistantRequestTransport(application),
    private val requestJournalName: String = "onmyway-assistant-requests.db",
    private val originProvider: (suspend () -> Place?)? = null,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, application.getSharedPreferences("assistant_conversation", 0))
    constructor(application: Application, vm: AppViewModel) : this(
        application, application.getSharedPreferences("assistant_conversation", 0),
        originProvider = { vm.assistantOriginOrNull() },
    )
    private val store = AssistantStore(database, conversationPrefs)
    private val personalMemoryRepository = PersonalMemoryRepository(application)
    private var personalMemoryServer = ""
    private val writeMutex = Mutex()
    private val welcome = ChatLine("assistant", "今天想做什么？可以让我记待办，或告诉我准备去哪儿。")
    private var loadedConversation by mutableStateOf("")
    val conversationId: String get() = loadedConversation
    private var loadedMemorySession = ""
    private var loadedMemorySessionStartedAt = 0L
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
        val conversation = loadedConversation
        storedLines = ((if (storedLines.size == 1 && storedLines[0].id == welcome.id) emptyList() else storedLines)
            + line).takeLast(500)
        viewModelScope.launch {
            try { writeMutex.withLock { store.add(line, conversation) } }
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
            val conversation = loadedConversation
            viewModelScope.launch {
                try { writeMutex.withLock { store.setDraft(identified, conversationId = conversation) } }
                catch (e: Exception) { error = "待确认方案保存失败，请稍后重试：" + (e.message ?: "数据库不可用") }
            }
        }
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var voiceBusy by mutableStateOf(false)
    var error by mutableStateOf("")
    /** 跨对话长期记忆，最新在前；发送时作为参考随请求附带。 */
    var memories by mutableStateOf<List<AssistantMemory>>(emptyList())
        private set
    var memoryEnabled by mutableStateOf(true)
        private set
    var autoRemember by mutableStateOf(true)
        private set
    var memoryBusy by mutableStateOf(false)
        private set
    var memoryMessage by mutableStateOf("")
        private set
    var personalMemory by mutableStateOf<PersonalMemorySnapshot?>(null)
        private set
    var personalMemoryConnected by mutableStateOf(false)
        private set
    var personalMemoryPending by mutableStateOf(false)
        private set
    var personalReferenceAllowed by mutableStateOf(true)
        private set
    var personalLearningAllowed by mutableStateOf(true)
        private set
    var personalMemoryEvidence by mutableStateOf<List<PersonalMemoryItem>>(emptyList())
        private set
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
                val interrupted = journal { it.pending() }
                val restored = writeMutex.withLock { store.load(
                    interrupted?.let { requestConversationId(it.contextKey, it.createdAt) },
                    interrupted?.let { JSONObject(it.bodyJson).optString("memory_session_id").takeIf(String::isNotBlank) },
                ) }
                loadedConversation = store.activeConversationId
                loadedMemorySession = store.memorySessionId
                loadedMemorySessionStartedAt = store.memorySessionStartedAt
                storedLines = restored.first.ifEmpty { listOf(welcome) }
                automatic.restoredChoices = writeMutex.withLock { store.planningChoices() }
                clarification = writeMutex.withLock { store.clarification() }
                clarification?.let(automatic::restoreQuestion)
                storedProposal = restored.second
                try { refreshMemories() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    memoryEnabled = false
                    autoRemember = false
                    memoryMessage = "暂时无法读取记忆；对话仍可继续，记忆恢复后再使用。"
                }
                if (interrupted != null && store.responseApplied(interrupted.requestId)) {
                    interrupted.resultJson?.let { applyResponseMemories(interrupted, JSONObject(it)) }
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
    suspend fun conversations() = writeMutex.withLock { store.conversations() }
    suspend fun conversation(id: String, limit: Int = 500) = writeMutex.withLock { store.conversation(id, limit) }

    private fun displayMemories(snapshot: MemorySnapshot) {
        memories = snapshot.items.filter { it.status == "active" && (it.expiresAt == 0L || it.expiresAt > System.currentTimeMillis()) }
        memoryEnabled = snapshot.state.useMemory
        autoRemember = snapshot.state.autoRemember
    }

    private suspend fun refreshMemories() = displayMemories(writeMutex.withLock { store.memorySnapshot() })

    private fun changeMemory(success: String, rejected: String, onSaved: () -> Unit = {}, change: suspend () -> Boolean) {
        if (!ready || memoryBusy) return
        memoryBusy = true
        memoryMessage = ""
        viewModelScope.launch {
            try {
                val changed = writeMutex.withLock { change() }
                refreshMemories()
                memoryMessage = if (changed) success else rejected
                if (changed) onSaved()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { memoryMessage = "记忆更新失败，请稍后重试。" }
            finally { memoryBusy = false }
        }
    }

    fun addMemory(text: String, scope: String = "general", onSaved: () -> Unit = {}) = changeMemory(
        "已添加记忆。", "未添加：请检查内容是否重复、包含敏感信息，或记忆是否已满。", onSaved,
    ) { store.addManualMemory(text, scope) }

    fun editMemory(item: AssistantMemory, text: String, scope: String, onSaved: () -> Unit = {}) = changeMemory(
        "已更新记忆。", "未更新：内容可能重复、包含敏感信息，或这条记忆已变化，请重新打开。", onSaved,
    ) { store.editMemory(item.id, item.revision, text, scope) }

    fun forgetMemory(id: String) = changeMemory("已忘记这条记忆。", "这条记忆已不存在。") { store.forget(id) }

    fun setMemorySettings(useMemory: Boolean, autoRemember: Boolean) = changeMemory("记忆设置已更新。", "") {
        store.updateMemorySettings(useMemory, autoRemember)
        true
    }

    fun refreshPersonalMemory(server: String) {
        if (memoryBusy) return
        val base = Api.validateBase(server)
        if (personalMemoryServer != base) personalMemoryEvidence = emptyList()
        personalMemoryServer = base
        personalMemory = null
        personalMemoryConnected = false
        memoryBusy = true
        viewModelScope.launch {
            try {
                val controls = withContext(Dispatchers.IO) { personalMemoryRepository.localControls(server) }
                personalReferenceAllowed = controls.first
                personalLearningAllowed = controls.second
                val client = withContext(Dispatchers.IO) { personalMemoryRepository.client(server) }
                if (client == null) {
                    memoryMessage = "请先在电脑连接中配置当前服务。连接前可正常对话，个人记忆暂不参与。"
                    personalMemoryPending = false
                } else {
                    personalMemory = withContext(Dispatchers.IO) { client.cached() }
                    personalMemoryPending = withContext(Dispatchers.IO) { client.hasPendingCommand() }
                    personalMemory = withContext(Dispatchers.IO) { client.refresh() }
                    personalMemoryEvidence = personalMemoryEvidence.filter { provided ->
                        personalMemory?.items?.any { it.id == provided.id && it.revision == provided.revision && it.status == "active" } == true
                    }
                    personalMemoryConnected = true
                    memoryMessage = if (personalMemoryPending) "上次更新结果尚未确认，请重新获取。" else "已获取最新个人记忆。"
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { memoryMessage = "暂时无法连接记忆服务。此处显示上次保存的副本，新对话会重新检查连接。" }
            finally { memoryBusy = false }
        }
    }

    private fun changePersonalMemory(
        server: String, operation: String, fields: JSONObject = JSONObject(),
        onSaved: () -> Unit = {}, beforeChange: suspend () -> Unit = {},
    ) {
        if (!ready || memoryBusy) return
        memoryBusy = true
        memoryMessage = ""
        viewModelScope.launch {
            try {
                beforeChange()
                personalMemory = withContext(Dispatchers.IO) {
                    val client = personalMemoryRepository.client(server)
                        ?: error("请先配置当前服务的电脑连接。")
                    client.mutate(operation, fields)
                }
                personalMemoryConnected = true
                personalMemoryPending = false
                personalMemoryEvidence = emptyList()
                memoryMessage = "个人记忆已更新。"
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                personalMemoryPending = withContext(Dispatchers.IO) {
                    personalMemoryRepository.client(server)?.hasPendingCommand() == true
                }
                memoryMessage = if (personalMemoryPending) "更新结果尚未确认，请重新获取；不会创建另一条相同操作。"
                    else e.message ?: "记忆更新失败，请刷新后重试。"
            } finally { memoryBusy = false }
        }
    }

    fun savePersonalMemory(
        server: String, previous: PersonalMemoryItem?, text: String, kind: String, topic: String,
        validUntil: String?, onSaved: () -> Unit,
    ) {
        val item = JSONObject().put("kind", kind).put("text", text.trim()).put("topic", topic)
            .put("scope", previous?.scope ?: "personal").put("scope_id", previous?.scopeId.orEmpty())
            .put("valid_from", previous?.validFrom ?: JSONObject.NULL)
            .put("valid_until", validUntil ?: JSONObject.NULL).put("source_kind", "manual")
            .put("source_id", UUID.randomUUID().toString()).put("quote", text.trim()).put("derived_from", JSONArray())
        previous?.let { item.put("id", it.id).put("revision", it.revision) }
        changePersonalMemory(server, "upsert", JSONObject().put("item", item), onSaved)
    }

    fun forgetPersonalMemory(server: String, item: PersonalMemoryItem) = changePersonalMemory(
        server, "forget", JSONObject().put("id", item.id).put("item_revision", item.revision),
    )

    fun setPersonalMemorySettings(server: String, useMemory: Boolean, learn: Boolean) = changePersonalMemory(
        server, "settings", JSONObject().put("reference_enabled", useMemory).put("learning_enabled", learn),
        beforeChange = {
            // Disabling is effective on this phone even if the service cannot be reached.
            withContext(Dispatchers.IO) { personalMemoryRepository.setLocalControls(server, useMemory, learn) }
            personalReferenceAllowed = useMemory
            personalLearningAllowed = learn
        },
    )

    fun retryPersonalMemory(server: String, onRecovered: () -> Unit = {}) {
        if (!ready || memoryBusy) return
        memoryBusy = true
        viewModelScope.launch {
            try {
                personalMemory = withContext(Dispatchers.IO) {
                    checkNotNull(personalMemoryRepository.client(server)) { "请先配置电脑连接。" }.retryPending()
                }
                personalMemoryPending = false
                personalMemoryConnected = true
                memoryMessage = "已确认上次记忆更新结果。"
                onRecovered()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                personalMemoryPending = withContext(Dispatchers.IO) {
                    personalMemoryRepository.client(server)?.hasPendingCommand() == true
                }
                memoryMessage = e.message ?: "暂时无法确认，请稍后重试。"
            } finally { memoryBusy = false }
        }
    }

    fun importLegacyPersonalMemory(server: String) {
        val items = JSONArray(memories.map { item ->
            JSONObject().put("id", item.id).put("kind", "preference").put("text", item.text)
                .put("topic", if (item.scope == "errands") "errand" else item.scope).put("scope", "personal")
                .put("scope_id", "").put("source_kind", "legacy").put("source_id", item.id)
                .put("quote", item.sourceQuote).put("derived_from", JSONArray())
        })
        changePersonalMemory(server, "import", JSONObject().put("items", items))
    }

    fun reflectPersonalMemory(server: String) = changePersonalMemory(server, "reflect")

    fun startConversation(vm: AppViewModel, id: String? = null, onStarted: () -> Unit = {}) {
        if (id == loadedConversation) { onStarted(); return }
        conversationSwitchIssue(ready, busy || voiceBusy || automatic.busy || automatic.committing || preparingRoute ||
            vm.busy || vm.updatingTrip || vm.restoringTrip || tripCompletion != null,
            pendingRequest != null, proposal != null, input)?.let { error = it; return }
        busy = true
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    check(journal { it.pending() } == null) { "上一条请求尚未处理，请先取回结果。" }
                    val target = id ?: "chat-${UUID.randomUUID()}"
                    val restored = if (id == null) emptyList() else store.conversation(target).takeLast(500)
                    store.activateConversation(target)
                    loadedConversation = target
                    loadedMemorySession = store.memorySessionId
                    loadedMemorySessionStartedAt = store.memorySessionStartedAt
                    storedLines = restored.ifEmpty { listOf(welcome) }
                    clarification = null
                    automatic.clear()
                    automatic.restoredChoices = null
                    historyDestination = null
                    preparingRoute = false
                    routeResult = null
                    recoveryMessage = ""
                    personalMemoryEvidence = emptyList()
                    error = ""
                    onStarted()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "切换对话失败：" + (e.message ?: "请重试") }
            finally { busy = false }
        }
    }

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
                    tripTasks.filter { it.status == "done" }.map { it.title }, emptyList(),
                    metrics = event.metrics), lineId)
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
                metrics = event.metrics,
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
                completed.map { it.title }, pending.map { it.title },
                metrics = prompt.metrics), "route-end:${prompt.eventId}")
            if (tripCompletion?.eventId == prompt.eventId) tripCompletion = null
        }
    }
    fun dismissTripCompletion() {
        val prompt = tripCompletion ?: return
        val completed = prompt.tasks.filter { it.done }.map { it.title }
        val pending = prompt.tasks.filterNot { it.done }.map { it.title }
        record(tripCompletionSummary(prompt.arrived, prompt.destination, completed, pending,
            confirmed = false, metrics = prompt.metrics), "route-end:${prompt.eventId}")
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
            .put("conversation_id", loadedConversation).put("memory_session_id", loadedMemorySession)
            .put("started", vm.tripStarted).toString()
    }

    private fun requestSnapshot(request: PendingAssistantRequest): ContextSnapshot {
        val snapshot = JSONObject(request.bodyJson).optJSONObject("context_snapshot")
            ?: error("上次请求缺少可核对的上下文，请重新编辑原指令。")
        return ContextSnapshot.fromJson(snapshot)
    }

    private suspend fun contextMatches(request: PendingAssistantRequest, vm: AppViewModel): Boolean = try {
        check(!vm.restoringTrip && !vm.updatingTrip && !vm.busy && !automatic.busy) {
            "当前行程正在更新，暂时无法核对上次请求。"
        }
        check(Api.validateBase(vm.server) == request.server) { "服务地址已变化，旧请求不会应用。" }
        val snapshot = requestSnapshot(request)
        check(requestConversationId(request.contextKey, request.createdAt) == loadedConversation) {
            "当前对话已变化，旧回复不会应用。"
        }
        val actual = JSONObject(requestContext(vm))
        val expected = JSONObject(request.contextKey)
        listOf("conversation_id", "memory_session_id").filterNot(expected::has).forEach(actual::remove)
        check(actual.toString() == expected.toString()) { "当前草稿、行程或对话已变化，旧回复不会应用。" }
        vm.requireCurrentContext(snapshot)
        recoveryMessage = ""
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        recoveryMessage = (e.message ?: "无法核对请求上下文。") + " 原指令与当前待办已保留，请重新编辑后发送。"
        false
    }

    /** Explicit recovery queries the saved request before considering a same-ID resubmission. */
    fun recoverRequest(vm: AppViewModel) {
        val request = pendingRequest ?: return
        if (!ready || busy || vm.restoringTrip || vm.updatingTrip || vm.busy || automatic.busy) return
        busy = true; error = ""
        viewModelScope.launch {
            try {
                if (writeMutex.withLock { store.responseApplied(request.requestId) }) {
                    refreshConversation(vm)
                    request.resultJson?.let { applyResponseMemories(request, JSONObject(it)) }
                    finishRequest(request)
                    return@launch
                }
                if (!contextMatches(request, vm)) {
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
                writeMutex.withLock { store.add(ChatLine("user", request.userText, request.userMessageId, request.createdAt),
                    requestConversationId(request.contextKey, request.createdAt)) }
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
        // Keep the complete result in the journal so a crash cannot lose its memory evidence.
        if (!journal { it.saveResult(request.requestId, answer.toString()) }) return
        pendingRequest = request.copy(resultJson = answer.toString())
        val response = JSONObject(answer.toString())
        response.remove("memory")
        response.remove("memory_updates")
        response.remove("memory_context")
        response.remove("memory_actions")
        if (writeMutex.withLock { store.responseApplied(request.requestId) }) {
            refreshConversation(vm)
            applyResponseMemories(request, answer)
            finishRequest(request)
            return
        }
        if (!contextMatches(request, vm)) {
            return
        }
        require(assistantCardVersionSupported(response)) { "建议版本暂不支持，回复未应用，请重新输入需求。" }
        val action = response.getString("action")
        val actionableProposal = proposal?.takeIf { assistantCardIssue(it) == null }
        val batch = action in listOf("create_tasks", "update_task_drafts") ||
            (action == "create_task" && response.has("planned_date"))
        val replace = batch || action in listOf("create_task", "plan_route")
        check(!replace || batch || taskDraftItems(actionableProposal).isEmpty()) { "请先保存或取消剩余任务草稿。" }
        val replacement = if (batch) taskDraftReplacement(response, actionableProposal, request.requestId)
        else if (replace) JSONObject(response.toString()).apply {
            put("_draft_id", request.requestId)
            val previousPlan = actionableProposal?.takeIf { action == "plan_route" && it.optString("action") == "plan_route" }
                ?.optString("_plan_id").orEmpty()
            val activePlan = if (action == "plan_route" && !vm.tripStarted)
                vm.planningBase()?.let { it.planId.ifBlank { it.sessionId } }.orEmpty() else ""
            put("_plan_id", previousPlan.ifBlank { activePlan }.ifBlank { request.requestId })
        } else null
        val fill = when {
            replacement == null -> PlaceFillSummary()
            batch -> fillDraftPlaces(replacement, vm)
            action == "create_task" -> fillLegacyPlace(replacement, vm)
            else -> PlaceFillSummary()
        }
        val reply = when (action) {
            "create_tasks" -> "已整理为多项草稿，请逐项核对，可以只保存其中几项。"
            "update_task_drafts" -> "已修改指定草稿，其他条目保留；核对后再保存。"
            "create_task" -> "请确认这件事，保存后会加入待办。"
            "plan_route" -> if (response.optString("mode").isBlank()) "这次步行还是骑行？" else "我来定位并查看有哪些事可以顺路办。"
            "list_tasks" -> taskListReply(request)
            else -> response.getString("reply")
        }.let { text ->
            appendMemoryFeedback(listOf(text, placeFillNote(fill), reminderNote(replacement))
                .filter { it.isNotBlank() }.joinToString(" "), action, answer)
        }
        val expectedDraft = JSONObject(request.contextKey).optString("draft_id").takeIf { it.isNotBlank() && it != "null" }
        val applied = try {
            writeMutex.withLock {
                if (!contextMatches(request, vm)) false else store.applyResponse(request.requestId, expectedDraft,
                    ChatLine("user", request.userText, request.userMessageId, request.createdAt),
                    ChatLine("assistant", reply), replacement, replace,
                    expectedTripContext = JSONObject(request.contextKey).getString("trip"),
                    expectedContextSnapshot = requestSnapshot(request),
                    expectedConversationId = requestConversationId(request.contextKey, request.createdAt))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            recoveryMessage = (e.message ?: "当前待办或行程已变化。") + " 回复和原指令已保留，请重新编辑后发送。"
            return
        }
        if (!applied && !writeMutex.withLock { store.responseApplied(request.requestId) }) {
            if (recoveryMessage.isBlank()) recoveryMessage = "待确认方案已更新，旧回复未应用。请重新编辑原指令。"
            return
        }
        // Room's receipt is authoritative. If the process dies before journal deletion,
        // the next launch may retry memory validation; it never recreates the draft.
        refreshConversation(vm)
        applyResponseMemories(request, answer)
        finishRequest(request)
    }

    /** Memory is auxiliary: invalid suggestions or storage failure cannot trap a completed chat. */
    private suspend fun applyResponseMemories(request: PendingAssistantRequest, answer: JSONObject) {
        try {
            val requestBody = JSONObject(request.bodyJson)
            if (requestBody.optInt("memory_contract") == 2) {
                personalMemoryEvidence = personalMemoryEvidence(requestBody, answer)
                val context = answer.optJSONObject("memory_context")
                val referenceMessage = when (context?.optString("status")) {
                    "ready" -> if (personalMemoryEvidence.isEmpty()) "本轮未提供相关个人记忆。"
                        else "本轮向助手提供了 ${personalMemoryEvidence.size} 条相关记忆，可查看来源。"
                    "stale" -> "记忆已变化，本轮未采用旧记忆；下次发送会重新获取。"
                    "disabled" -> "本轮未参考或学习个人记忆。"
                    else -> "本轮个人记忆暂不可用，对话回复已保存。"
                }
                val writeMessage = personalMemoryWriteMessage(context)
                memoryMessage = listOf(referenceMessage, writeMessage).filter { it.isNotBlank() }.joinToString(" ")
                // The server has already committed accepted actions. Never reapply them to Room.
                viewModelScope.launch {
                    try {
                        personalMemoryRepository.refresh(request.server)?.let { updated ->
                            if ((request.server == personalMemoryServer || personalMemoryServer.isBlank()) &&
                                (personalMemory?.storeId != updated.storeId || updated.revision >= (personalMemory?.revision ?: 0)))
                                personalMemory = updated
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* The committed chat remains usable when a cache refresh fails. */ }
                }
                return
            }
            val batch = memoryResponseBatch(JSONObject(request.bodyJson), answer) ?: return
            if (batch.updates.isEmpty()) return
            val count = writeMutex.withLock {
                store.applyMemoryUpdates(batch.updates, batch.revision, request.userMessageId, request.userText, batch.references)
            }
            refreshMemories()
            if (count > 0) memoryMessage = "已依据你的原话更新 $count 条记忆，可在这里查看和修改。"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            memoryMessage = "本轮回复已保存，但记忆未能更新；需要时可手动添加。"
        }
    }

    /**
     * Deterministic client-side fill. It never asks for a location when no candidate exists,
     * and a missing provider means tests/previews keep the old manual-selection behavior.
     */
    private suspend fun fillDraftPlaces(proposal: JSONObject, vm: AppViewModel): PlaceFillSummary {
        val requests = placeFillRequests(proposal)
        if (requests.isEmpty()) return PlaceFillSummary()
        if (requests.none { FamiliarPlaceResolver.hasCandidates(it.placeType, vm.familiarPlaces, vm.familiarPlacePurposes) })
            return PlaceFillSummary()
        val provider = originProvider ?: return PlaceFillSummary()
        val origin = try {
            provider()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return applyFamiliarPlaceFills(proposal, requests, origin, vm.familiarPlaces, vm.familiarPlacePurposes)
    }

    private suspend fun fillLegacyPlace(proposal: JSONObject, vm: AppViewModel): PlaceFillSummary {
        if (proposal.has("known_destination") && !proposal.isNull("known_destination")) return PlaceFillSummary()
        if (proposal.has("place") && !proposal.isNull("place")) return PlaceFillSummary()
        val type = proposal.optString("place_type")
        if (!FamiliarPlaceResolver.hasCandidates(type, vm.familiarPlaces, vm.familiarPlacePurposes))
            return PlaceFillSummary()
        val provider = originProvider ?: return PlaceFillSummary()
        val origin = try {
            provider()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return try {
            when (val lookup = FamiliarPlaceResolver.resolve(type, origin, vm.familiarPlaces, vm.familiarPlacePurposes)) {
                is FamiliarPlaceLookup.Matched -> {
                    proposal.put("known_destination", lookup.match.place.json())
                    proposal.put("place", lookup.match.place.json())
                    PlaceFillSummary(filled = listOf(lookup.match))
                }
                FamiliarPlaceLookup.NoOrigin ->
                    PlaceFillSummary(missingCount = 1, locationUnavailable = true)
                is FamiliarPlaceLookup.OutOfRange ->
                    PlaceFillSummary(missingCount = 1, outOfRange = true)
                FamiliarPlaceLookup.NoCandidate, FamiliarPlaceLookup.NotApplicable ->
                    PlaceFillSummary(missingCount = 1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PlaceFillSummary()
        }
    }

    private suspend fun refreshConversation(vm: AppViewModel) {
        val (restored, question) = writeMutex.withLock { store.load() to store.clarification() }
        loadedConversation = store.activeConversationId
        loadedMemorySession = store.memorySessionId
        loadedMemorySessionStartedAt = store.memorySessionStartedAt
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
            val indoor = isIndoor(pendingAtSend.optString("place_type"))
            val place = pendingAtSend.optJSONObject("place")
                ?.takeUnless { indoor }
                ?.let { runCatching { Place.from(it) }.getOrNull() }
            if (!indoor && place == null) {
                error = "请先在卡片中选择办理地点。"; return
            }
            addLine("user", input.trim()); input = ""; error = ""
            saveTask(vm, Errand(title = pendingAtSend.getString("title"),
                placeType = pendingAtSend.getString("place_type"),
                deadline = pendingAtSend.optString("deadline"),
                plannedDate = pendingAtSend.optString("planned_date"),
                reminderAt = reminderAtMillis(pendingAtSend.optString("reminder_at")),
                placeJson = place?.json()?.toString().orEmpty()), pendingAtSend.getString("_draft_id"))
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
                val pendingContext = pendingAtSend?.let(::assistantDraftContext)
                val contextNow = System.currentTimeMillis()
                val recentLines = assistantContextLines(lines.mapIndexed { index, line ->
                    line.copy(text = assistantContextText(line, index == lines.lastIndex, contextNow, loadedMemorySessionStartedAt))
                })
                val messages = JSONArray().apply {
                    (if (pendingContext == null) recentLines else recentLines.dropLast(1)).forEach { line ->
                        put(JSONObject().put("role", line.role)
                            .put("content", line.text))
                    }
                    if (pendingContext != null) {
                        put(JSONObject().put("role", "assistant").put("content", pendingContext))
                        recentLines.lastOrNull()?.let {
                            put(JSONObject().put("role", it.role).put("content", it.text))
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
                val contextKey = requestContext(vm)
                val snapshot = vm.captureContextSnapshot()
                val body = JSONObject().put("request_id", requestId).put("messages", messages)
                    .put("route_active", vm.tripStarted).put("now", java.time.OffsetDateTime.now().toString())
                    .put("context_snapshot", snapshot.json())
                val remoteMemory = try {
                    val controls = withContext(Dispatchers.IO) { personalMemoryRepository.localControls(vm.server) }
                    personalReferenceAllowed = controls.first
                    personalLearningAllowed = controls.second
                    personalMemoryRepository.refresh(vm.server).also {
                        personalMemoryServer = Api.validateBase(vm.server)
                        personalMemory = it
                        personalMemoryConnected = it != null
                        if (it == null) memoryMessage = "尚未配置个人记忆连接，本轮正常对话。"
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    personalMemoryConnected = false
                    memoryMessage = "本轮无法读取服务端记忆，已跳过参考与学习。"
                    null
                }
                val memoryFields = personalMemoryRequestFields(remoteMemory, loadedMemorySession,
                    vm.planningBase()?.sessionId.orEmpty(), personalReferenceAllowed, personalLearningAllowed)
                memoryFields.keys().forEach { key -> body.put(key, memoryFields.get(key)) }
                if (pendingAtSend?.optString("action") == "create_tasks")
                    body.put("draft_items", taskDraftRequestItems(pendingAtSend))
                val request = PendingAssistantRequest(requestId, Api.validateBase(vm.server), body.toString(),
                    text, userLine.id, userLine.createdAt, contextKey)
                check(contextMatches(request, vm)) { recoveryMessage }
                journal { it.create(request) }
                pendingRequest = request
                // The journal always precedes transport. A crash before this message write is
                // repaired by the response transaction using the same userMessageId.
                writeMutex.withLock { store.add(userLine, requestConversationId(request.contextKey, request.createdAt)) }
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
