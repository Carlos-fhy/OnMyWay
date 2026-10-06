package com.onmyway.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.edit
import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.LocalDateTime


internal fun retainedNavigationTasks(request: JSONArray, result: JSONObject, keepIds: Set<String>): List<JSONObject> {
    val actualStops = result.optJSONArray("stops") ?: JSONArray()
    val actualPlaces = mutableMapOf<String, JSONObject>()
    for (index in 0 until actualStops.length()) {
        val stop = actualStops.optJSONObject(index) ?: continue
        val place = stop.optJSONObject("place") ?: continue
        val ids = stop.optJSONArray("task_ids") ?: continue
        for (taskIndex in 0 until ids.length()) actualPlaces[ids.getString(taskIndex)] = place
    }
    return (0 until request.length()).map { JSONObject(request.getJSONObject(it).toString()) }
        .filter { it.getString("id") in keepIds }
        .onEach { task ->
            if (task.isNull("place")) actualPlaces[task.getString("id")]?.let { selected ->
                task.put("place", JSONObject(selected.toString()))
                task.put("place_type", JSONObject.NULL)
            }
        }
}


class AppViewModel internal constructor(application: Application, private val database: TaskDatabase) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, TaskDatabase.get(application))
    private val dao = database.tasks()
    private val trips = database.trips()
    private val suggestionFeedback = database.suggestionFeedback()
    private val agentEvents = database.agentEvents()
    private val tripMutex = Mutex()
    private var savedTrip by mutableStateOf<SavedTrip?>(null)
    val planNeedsRefresh: Boolean get() = savedTrip?.needsReplan == true
    private val navigationMemory = NavigationConversationState()
    private var navigationMemoryTrip: SavedTrip? = null
    private val navigationContextPrefs = application.getSharedPreferences("navigation_context", 0)
    private fun persistNavigationMemory() {
        val trip = navigationMemoryTrip
        if (trip == null || navigationMemory.history.isEmpty() && navigationMemory.proposedBody == null) {
            navigationContextPrefs.edit { remove("snapshot") }
        } else {
            navigationContextPrefs.edit { putString("snapshot", navigationMemory.snapshot(trip).toString()) }
        }
    }
    internal val navigationConversation: NavigationConversationState get() {
        val currentTrip = savedTrip
        val previousTrip = navigationMemoryTrip
        val samePlan = if (previousTrip == null || currentTrip == null) {
            previousTrip == currentTrip
        } else {
            previousTrip.sessionId == currentTrip.sessionId &&
                previousTrip.requestJson == currentTrip.requestJson &&
                previousTrip.resultJson == currentTrip.resultJson &&
                previousTrip.plannedAt == currentTrip.plannedAt
        }
        if (!samePlan) {
            navigationMemory.persist = null
            navigationMemory.clear()
            navigationMemoryTrip = savedTrip
            val stored = savedTrip
            val snapshot = navigationContextPrefs.getString("snapshot", null)
            if (stored != null && snapshot != null) {
                val restored = runCatching { navigationMemory.restore(JSONObject(snapshot), stored) }.getOrDefault(false)
                if (!restored) navigationContextPrefs.edit { remove("snapshot") }
            }
            navigationMemory.persist = ::persistNavigationMemory
        }
        return navigationMemory
    }
    private var terminalState by mutableStateOf(NavigationState.IDLE)
    val navigationState: NavigationState get() = when {
        busy -> if (result == null) NavigationState.PLANNING else NavigationState.REPLANNING
        result != null -> when (NavigationRuntime.current?.takeIf { it.sessionId == savedTrip?.sessionId }?.phase) {
            NavigationPhase.STARTING -> NavigationState.STARTING
            NavigationPhase.NAVIGATING -> NavigationState.NAVIGATING
            NavigationPhase.REPLANNING -> NavigationState.REPLANNING
            NavigationPhase.PAUSED -> NavigationState.PAUSED
            NavigationPhase.FAILED -> NavigationState.FAILED
            NavigationPhase.ARRIVED -> NavigationState.ARRIVED
            else -> if (tripStarted) NavigationState.NAVIGATION_OPENED else NavigationState.READY
        }
        else -> terminalState
    }
    val routeSession: RouteSession? get() = savedTrip?.let { stored ->
        val request = JSONObject(stored.requestJson)
        RouteSession(stored.sessionId, navigationState, Place.from(request.getJSONObject("origin")),
            Place.from(request.getJSONObject("destination")), stored.plannedAt, stored.startedAt, stored.requestJson, stored.resultJson, stored.planId)
    }
    private val prefs = application.getSharedPreferences("settings", 0)
    var preferredDetourMinutes by mutableStateOf(prefs.getInt("preferred_detour_minutes", 10).coerceIn(0, 240))
        private set
    fun savePreferredDetourMinutes(minutes: Int) {
        preferredDetourMinutes = minutes.coerceIn(0, 240)
        prefs.edit { putInt("preferred_detour_minutes", preferredDetourMinutes) }
    }
    var learningEnabled by mutableStateOf(prefs.getBoolean("learning_enabled", true))
        private set
    fun updateLearningEnabled(enabled: Boolean) {
        learningEnabled = enabled
        prefs.edit { putBoolean("learning_enabled", enabled) }
    }
    private val modeOverrides = mutableStateOf(mapOf(
        "walking" to if (prefs.contains("walking_detour_minutes")) prefs.getInt("walking_detour_minutes", 10) else null,
        "cycling" to if (prefs.contains("cycling_detour_minutes")) prefs.getInt("cycling_detour_minutes", 10) else null,
    ))
    fun modeDetourOverride(mode: String): Int? = modeOverrides.value[mode]
    fun saveModeDetourOverride(mode: String, minutes: Int?) {
        require(mode in setOf("walking", "cycling"))
        require(minutes == null || minutes in 0..240)
        modeOverrides.value = modeOverrides.value + (mode to minutes)
        prefs.edit {
            if (minutes == null) remove("${mode}_detour_minutes")
            else putInt("${mode}_detour_minutes", minutes)
        }
    }
    internal suspend fun planningContext(travelMode: String): PlanningContext {
        val band = timeBand(LocalDateTime.now().hour)
        val override = modeDetourOverride(travelMode)
        val history = if (learningEnabled && override == null) suggestionFeedback.recentDecisions() else emptyList()
        val guidance = detourGuidance(travelMode, band, preferredDetourMinutes,
            override, learningEnabled, history)
        return PlanningContext(travelMode, band, tasks.value.count { it.status == "pending" },
            routeSession?.id, guidance)
    }
    suspend fun clearLearningHistory() = database.withTransaction {
        suggestionFeedback.clear()
        agentEvents.clear()
    }
    suspend fun recentAgentEvents() = agentEvents.recent()
    private suspend fun trace(type: String, source: String, actionId: String = "",
        routeSessionId: String = "", mode: String = "", correlationId: String = "",
        itemCount: Int = 0, extraSeconds: Int = 0) {
        if (!learningEnabled) return
        agentEvents.append(AgentEvent(type = type, source = source, actionId = actionId,
            routeSessionId = routeSessionId, mode = mode, correlationId = correlationId,
            timeBand = timeBand(LocalDateTime.now().hour), itemCount = itemCount,
            extraSeconds = extraSeconds))
    }
    var familiarPlaces by mutableStateOf<Map<String, Place>>(runCatching {
        val json = JSONObject(prefs.getString("familiar_places", "{}")!!)
        json.keys().asSequence().associateWith { Place.from(json.getJSONObject(it)) }
    }.getOrDefault(emptyMap()))
        private set
    private fun persistFamiliarPlaces() {
        val json = JSONObject()
        familiarPlaces.forEach { (alias, point) -> json.put(alias, point.json()) }
        prefs.edit { putString("familiar_places", json.toString()) }
    }
    fun saveFamiliarPlace(name: String, place: Place?) {
        val alias = familiarPlaceAlias(name)
        require(alias.isNotBlank() && alias.length <= 24) { "常用地点称呼应为 1–24 个字" }
        familiarPlaces = if (place == null) familiarPlaces - alias else familiarPlaces + (alias to place)
        persistFamiliarPlaces()
    }
    fun renameFamiliarPlace(oldName: String, newName: String): Boolean {
        val oldAlias = familiarPlaceAlias(oldName)
        val newAlias = familiarPlaceAlias(newName)
        val place = familiarPlaces[oldAlias] ?: return false
        if (newAlias.isBlank() || newAlias.length > 24 || newAlias != oldAlias && newAlias in familiarPlaces) return false
        familiarPlaces = if (newAlias == oldAlias) familiarPlaces else (familiarPlaces - oldAlias) + (newAlias to place)
        persistFamiliarPlaces()
        return true
    }
    fun familiarPlace(name: String): Place? = familiarPlaces[familiarPlaceAlias(name)]
    suspend fun routeHistory() = trips.history()
    suspend fun routeVersions(sessionId: String, planId: String = "") = if (planId.isBlank()) trips.historyForSession(sessionId) else trips.historyForPlan(planId)
    suspend fun routeHistoryById(id: String) = trips.historyById(id)
    var lastPlannedHistoryId: String? = null
        private set
    suspend fun recordSuggestionShown(mode: String, taskCount: Int, extraSeconds: Int): String? {
        if (!learningEnabled || taskCount <= 0 || mode !in listOf("walking", "cycling")) return null
        val feedback = SuggestionFeedback(mode = mode, offeredTaskCount = taskCount,
            offeredExtraSeconds = extraSeconds, timeBand = timeBand(LocalDateTime.now().hour))
        return try {
            database.withTransaction {
                suggestionFeedback.show(feedback)
                trace("SUGGESTION_SHOWN", "ASSISTANT", mode = mode,
                    correlationId = feedback.id, itemCount = taskCount, extraSeconds = extraSeconds)
            }
            feedback.id
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
    fun recordSuggestionDecision(id: String, decision: String, taskCount: Int,
        extraSeconds: Int, routeSessionId: String = "") {
        if (decision !in setOf("ACCEPTED", "ADJUSTED", "DIRECT", "DIRECT_TIME", "REJECTED", "MANUAL", "SUPERSEDED")) return
        viewModelScope.launch {
            try {
                database.withTransaction {
                    val updated = suggestionFeedback.decide(id, decision, taskCount.coerceAtLeast(0),
                        extraSeconds, System.currentTimeMillis(), routeSessionId)
                    if (updated > 0) trace("SUGGESTION_$decision", "USER",
                        routeSessionId = routeSessionId, correlationId = id,
                        itemCount = taskCount, extraSeconds = extraSeconds)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }
    suspend fun suggestionDecisions(): List<SuggestionFeedback> = suggestionFeedback.recentDecisions()
    val tasks = dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var server by mutableStateOf(prefs.getString("server", "http://127.0.0.1:8000")!!)
        private set
    var city by mutableStateOf(prefs.getString("city", "")!!)
        private set
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)
        private set
    var locating by mutableStateOf(false)
        private set
    private var locationJob: Job? = null
    var origin by mutableStateOf<Place?>(null)
    var destination by mutableStateOf<Place?>(null)
    var selected by mutableStateOf(setOf<String>())
    var mode by mutableStateOf("walking")
    var preference by mutableStateOf("most_tasks")
    var extra by mutableStateOf("")
    var arriveBy by mutableStateOf("")
    var partial by mutableStateOf(false)
    var tripPlaces by mutableStateOf<Map<String, Place>>(emptyMap())
        private set
    var result by mutableStateOf<JSONObject?>(null)
        private set
    private var revision = 0
    var tripStarted by mutableStateOf(false)
        private set
    var restoringTrip by mutableStateOf(true)
        private set
    var updatingTrip by mutableStateOf(false)
        private set
    var editingPlan by mutableStateOf(false)
        private set
    private var draftBackup: PlanInputs? = null
    private data class PlanInputs(val origin: Place?, val destination: Place?, val selected: Set<String>,
        val mode: String, val preference: String, val extra: String, val arriveBy: String, val partial: Boolean,
        val places: Map<String, Place>)

    fun beginPlanEdit() {
        if (busy || updatingTrip || editingPlan) return
        draftBackup = PlanInputs(origin, destination, selected, mode, preference, extra, arriveBy, partial, tripPlaces)
        editingPlan = true
        message = ""
    }
    fun cancelPlanEdit() {
        if (busy) return
        cancelLocation()
        draftBackup?.let {
            origin = it.origin; destination = it.destination; selected = it.selected
            mode = it.mode; preference = it.preference; extra = it.extra; arriveBy = it.arriveBy; partial = it.partial
            tripPlaces = it.places
        }
        draftBackup = null; editingPlan = false; message = ""
    }
    fun inputsChanged() { revision++; message = "" }
    fun chooseTripPlace(taskId: String, place: Place) {
        if (busy || updatingTrip) return
        beginPlanEdit()
        tripPlaces = tripPlaces + (taskId to place)
        inputsChanged()
    }
    fun resetTripPlace(taskId: String) {
        tripPlaces = tripPlaces - taskId
        inputsChanged()
    }
    internal fun taskForTrip(task: Errand): Errand = tripPlaces[task.id]?.let {
        task.copy(placeJson = it.json().toString())
    } ?: task

    init {
        viewModelScope.launch {
            try {
                tripMutex.withLock {
                    val stored = trips.load()
                    if (stored != null && stored.started && revision == 0 && !NavigationRuntime.owns(stored.sessionId)) {
                        database.withTransaction {
                            trips.record(RouteHistory(sessionId = stored.sessionId, planId = stored.planId, state = NavigationState.CANCELLED.name,
                                timestamp = System.currentTimeMillis(), requestJson = stored.requestJson, resultJson = stored.resultJson))
                            trips.clear()
                        }
                        terminalState = NavigationState.CANCELLED
                        navigationContextPrefs.edit { remove("snapshot") }
                        message = "上次导航已结束，方案保存在行程记录；再次出发请重新规划。"
                        return@withLock
                    }
                    if (stored != null && revision == 0) {
                        val request = JSONObject(stored.requestJson)
                        val response = JSONObject(stored.resultJson)
                        val storedTasks = request.getJSONArray("tasks")
                        if ((0 until storedTasks.length()).any {
                            val task = storedTasks.getJSONObject(it)
                            task.isNull("place") && requiresFixedPlace(task.optString("place_type"))
                        }) {
                            trips.clear()
                            message = "旧方案含未指定领取地点的任务，请补全地点后重新规划。"
                            return@withLock
                        }
                        origin = Place.from(request.getJSONObject("origin"))
                        destination = Place.from(request.getJSONObject("destination"))
                        mode = request.getString("mode")
                        preference = request.getString("preference")
                        partial = request.getBoolean("allow_partial")
                        extra = if (request.isNull("max_extra_minutes")) "" else request.getInt("max_extra_minutes").toString()
                        arriveBy = if (request.isNull("arrive_by")) "" else displayTime(request.getString("arrive_by"))
                        val chosen = request.getJSONArray("tasks")
                        selected = (0 until chosen.length()).map { chosen.getJSONObject(it).getString("id") }.toSet()
                        tripPlaces = (0 until chosen.length()).map { chosen.getJSONObject(it) }
                            .filter { !it.isNull("place") }.associate { it.getString("id") to Place.from(it.getJSONObject("place")) }
                        savedTrip = stored; result = response; tripStarted = stored.started
                        message = "已恢复上次行程"
                    }
                }
            } catch (_: Exception) { message = "上次行程读取失败，请重新规划。" }
            finally { restoringTrip = false }
        }
    }

    fun embeddedNavigationStarted() {
        viewModelScope.launch {
            try {
                tripMutex.withLock {
                    val active = trips.load()
                    if (active != null && active.sessionId == savedTrip?.sessionId && active.started) {
                        savedTrip = active
                        tripStarted = true
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "导航已启动，但页面状态读取失败，请重新打开页面。" }
        }
    }

    fun launchTrip(launch: () -> Boolean) {
        if (updatingTrip || restoringTrip) return
        val stored = savedTrip ?: return
        if (stored.needsReplan && !stored.started) {
            message = "待办已变化，请重新规划后再开始导航。"
            return
        }
        updatingTrip = true
        viewModelScope.launch {
            try {
                tripMutex.withLock {
                    if (savedTrip != stored) return@withLock
                    val started = stored.copy(started = true, startedAt = stored.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
                    if (savedTrip != stored) return@withLock
                    if (launch()) {
                        database.withTransaction {
                            trips.save(started)
                            if (!stored.started) trips.record(RouteHistory(sessionId = stored.sessionId, planId = stored.planId,
                                state = NavigationState.NAVIGATION_OPENED.name, timestamp = started.startedAt,
                                requestJson = stored.requestJson, resultJson = stored.resultJson))
                        }
                        savedTrip = started; tripStarted = true
                    }
                }
            } catch (e: Exception) { message = "行程保存或打开失败，请重试。" }
            finally { updatingTrip = false }
        }
    }
    fun finishTrip(arrived: Boolean, onFinished: () -> Unit = {}): Boolean {
        if (busy || updatingTrip || restoringTrip) return false
        val stored = savedTrip ?: return false
        updatingTrip = true
        viewModelScope.launch {
            var finished = false
            try {
                tripMutex.withLock {
                    if (savedTrip != stored) return@withLock
                    val state = if (arrived) NavigationState.ARRIVED else NavigationState.CANCELLED
                    database.withTransaction {
                        trips.record(RouteHistory(sessionId = stored.sessionId, planId = stored.planId, state = state.name,
                            timestamp = System.currentTimeMillis(), requestJson = stored.requestJson, resultJson = stored.resultJson))
                        trips.clear()
                    }
                    NavigationRuntime.finish(stored.sessionId)
                    revision++; savedTrip = null; result = null; tripStarted = false
                    origin = null; destination = null; selected = emptySet(); extra = ""; arriveBy = ""; partial = false
                    editingPlan = false; draftBackup = null; tripPlaces = emptyMap(); terminalState = state
                    navigationMemory.persist = null; navigationMemory.clear(); navigationMemoryTrip = null
                    navigationContextPrefs.edit { remove("snapshot") }
                    message = if (arrived) "已到达，行程保存在记录中；未办事项仍保留。"
                        else "导航已结束，方案保存在行程记录；再次出发请重新规划。"
                    finished = true
                }
            } catch (_: Exception) { message = "行程结束失败，请重试" }
            finally { updatingTrip = false }
            if (finished) onFinished()
        }
        return true
    }
    fun cancelLocation() { locationJob?.cancel() }
    suspend fun assistantOrigin(): Place {
        val fix = readCurrentLocation(getApplication())
        return Api(server).currentPlace(fix.longitude, fix.latitude)
    }
    fun locate() {
        if (locating || busy) return
        locating = true
        inputsChanged()
        locationJob = viewModelScope.launch {
            try {
                val fix = readCurrentLocation(getApplication())
                val place = Api(server).currentPlace(fix.longitude, fix.latitude)
                origin = place
                inputsChanged()
                message = "已设为起点，定位精度约 ${kotlin.math.ceil(fix.accuracy.toDouble()).toInt()} 米。"
            } catch (_: TimeoutCancellationException) {
                message = "定位超时，请确认手机定位已开启，到信号较好的地方重试，或手动选择起点。"
            } catch (_: SecurityException) {
                message = "位置权限未开启，请在系统应用设置中允许后重试。"
            } catch (e: Exception) { message = networkMessage(e) }
            finally { locating = false }
        }
    }
    fun save(task: Errand) {
        if (requiresFixedPlace(task.placeType) && task.place() == null) {
            message = "请为${placeTypes[task.placeType]}指定实际领取地点"; return
        }
        viewModelScope.launch {
            try {
                persistTask(task)
                if (task.status != "pending") selected = selected - task.id
            } catch (e: Exception) { message = "任务保存失败，请重试。" }
        }
    }
    suspend fun saveAssistantTask(task: Errand) {
        require(!requiresFixedPlace(task.placeType) || task.place() != null) { "请指定实际领取地点" }
        persistTask(task, source = "ASSISTANT")
    }
    fun delete(task: Errand) {
        viewModelScope.launch {
            try {
                tripMutex.withLock {
                    val affectsRoute = routeIncludesTask(task.id)
                    database.withTransaction {
                        dao.delete(task)
                        agentEvents.deleteForAction(task.id)
                        if (affectsRoute) trips.markNeedsReplan()
                    }
                    if (affectsRoute) savedTrip = savedTrip?.copy(needsReplan = true)
                    revision++
                }
                selected = selected - task.id
            }
            catch (e: Exception) { message = "任务删除失败，请重试。" }
        }
    }
    fun completeFromPlan(task: Errand) {
        if (updatingTrip) return
        updatingTrip = true
        viewModelScope.launch {
            try { persistTask(task.copy(status = "done")); selected = selected - task.id }
            catch (e: Exception) { message = "任务保存失败，请重试。" }
            finally { updatingTrip = false }
        }
    }
    private fun routeIncludesTask(id: String): Boolean {
        val stored = savedTrip ?: return false
        return runCatching {
            val array = JSONObject(stored.requestJson).getJSONArray("tasks")
            (0 until array.length()).any { array.getJSONObject(it).getString("id") == id }
        }.getOrDefault(true)
    }
    private suspend fun persistTask(task: Errand, source: String = "USER") = tripMutex.withLock {
        val affectsRoute = routeIncludesTask(task.id)
        database.withTransaction {
            val old = dao.get(task.id)
            val now = System.currentTimeMillis()
            dao.save(task.copy(createdAt = old?.createdAt ?: task.createdAt, updatedAt = now,
                completedAt = if (task.status == "done") { if (old?.status == "done") old.completedAt else now } else 0,
                lastRemindedAt = old?.lastRemindedAt ?: 0, reminderCount = old?.reminderCount ?: 0,
                reminderPending = task.status == "pending" && (old?.reminderPending ?: false)))
            if (old == null) trace("ACTION_CREATED", source, actionId = task.id)
            else if (old.status != task.status && task.status == "done")
                trace("ACTION_COMPLETED", source, actionId = task.id)
            if (affectsRoute) trips.markNeedsReplan()
        }
        if (affectsRoute) savedTrip = savedTrip?.copy(needsReplan = true)
        revision++
    }
    fun acknowledgeReminders() {
        viewModelScope.launch {
            try { dao.acknowledgeReminders() }
            catch (_: Exception) { message = "提醒更新失败，请重试" }
        }
    }
    fun settings(address: String, newCity: String) {
        try {
            val valid = Api.validateBase(address)
            server = valid
            city = newCity.trim()
            prefs.edit { putString("server", server); putString("city", city) }
            revision++
            message = "设置已保存，当前方案仍保留。"
        } catch (e: Exception) { message = e.message ?: "地址格式不正确" }
    }
    fun health() {
        if (checking) return
        checking = true
        viewModelScope.launch {
            try {
                val health = Api(server).health()
                message = if (health.optBoolean("amap_configured")) "服务已连接，高德 Key 已配置（尚未验证有效性）" else "服务已连接，请在电脑上填写高德 Key 并重启服务"
            } catch (e: Exception) { message = networkMessage(e) }
            finally { checking = false }
        }
    }
    suspend fun search(keyword: String, searchCity: String) = Api(server).search(keyword, searchCity)
    internal suspend fun previewNavigationChange(text: String, chosenDestination: Place? = null, onProgress: (String) -> Unit = {}): NavigationChange {
        check(!busy && !updatingTrip && !editingPlan) { "行程正在更新，请稍后重试。" }
        val previous = savedTrip ?: error("没有正在进行的行程。")
        val old = JSONObject(previous.requestJson)
        val oldTasks = old.getJSONArray("tasks")
        val oldIds = (0 until oldTasks.length()).map { oldTasks.getJSONObject(it).getString("id") }
        val existing = oldIds.mapNotNull { dao.get(it) }.filter { it.status == "pending" }
        val memory = navigationConversation
        val proposed = memory.proposedBody ?: memory.draft?.takeIf { it.previous == previous }?.body
        val proposedIds = proposed?.optJSONArray("tasks")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
        } ?: emptySet()
        val previousNewTask = memory.pendingNewTask?.takeIf { it.id in proposedIds }
        val active = existing + listOfNotNull(previousNewTask)
        memory.setProposal(proposed, previousNewTask)
        val selectedTasks = if (proposed == null) active else active.filter { it.id in proposedIds }
        val pendingIds = memory.pendingIds
        val pendingChoiceNewTask = memory.pendingChoiceNewTask
        memory.invalidatePreview()
        var target = chosenDestination
        var keep = if (target != null) pendingIds else navigationSelection(text, selectedTasks)
        var requestedNewTask: Errand? = if (target != null) pendingChoiceNewTask else null
        val history = JSONArray(memory.history.map { (role, content) -> JSONObject().put("role", role).put("content", content) })
        memory.record("user", if (target == null) text else "选择地点：${target.name}")
        if (keep == null) {
            onProgress("正在理解调整指令")
            val answer = Api(server).navigation(text, old.getJSONObject("destination").getString("name"), active, history, proposed)
            memory.record("assistant", answer.optString("reply").ifBlank { "已理解调整意图，尚未执行。" })
            val action = answer.optString("action")
            check(action == "adjust" || action == "add_stop") {
                answer.optString("reply").ifBlank { "请说明要保留的事项和终点。" }
            }
            val ids = answer.getJSONArray("keep_task_ids")
            keep = (0 until ids.length()).map { ids.getString(it) }.toSet()
            check(keep.all { id -> active.any { it.id == id } }) { "返回的任务不在当前行程中，请重试。" }
            if (action == "add_stop") {
                check(previousNewTask == null) { "上一个新增事项尚未确认，请先确认或取消后再添加。" }
                val type = answer.getString("new_place_type")
                val title = answer.getString("new_task_title").trim()
                check(type in listOf("SUPERMARKET", "PRINT_SHOP", "PHARMACY") && title.isNotBlank()) {
                    "新增事项类型不受支持，请换一种说法。"
                }
                requestedNewTask = Errand(title = title, placeType = type)
            }
            val keyword = answer.optString("destination")
            if (target == null && keyword.isNotBlank()) target = familiarPlace(keyword)
            if (target == null && keyword.isNotBlank()) {
                val searchCity = city.ifBlank {
                    onProgress("正在定位以确定搜索城市")
                    val current = assistantOrigin()
                    Regex("(?:省)?([^省]+?市)").find(current.address)?.groupValues?.get(1).orEmpty()
                }
                check(searchCity.isNotBlank()) { "无法确定搜索城市，请在设置中填写城市。" }
                onProgress("正在搜索新的目的地")
                val places = search(keyword, searchCity)
                check(places.isNotEmpty()) { "没有找到该地点，请补充城市或完整名称。" }
                memory.record("assistant", "请选目的地：" + places.mapIndexed { index, place -> "${index + 1}. ${place.name}" }.joinToString("；"))
                throw NavigationDestinationChoice(places, checkNotNull(keep), requestedNewTask)
            }
        }
        val keptIds = checkNotNull(keep)
        val tentativeNewTask = requestedNewTask ?: previousNewTask?.takeIf { it.id in keptIds }
        val retainedOld = retainedNavigationTasks(oldTasks, JSONObject(previous.resultJson), keptIds)
        check(retainedOld.size + (if (tentativeNewTask == null) 0 else 1) <= 6) {
            "一趟路线最多 6 件事，请先移除一件。"
        }
        val effectiveDestination = target?.json() ?: proposed?.getJSONObject("destination") ?: old.getJSONObject("destination")
        check(effectiveDestination.toString() != old.getJSONObject("destination").toString() ||
            keptIds != existing.map { it.id }.toSet() || requestedNewTask != null || tentativeNewTask != null) {
            "这条指令没有改变当前路线。"
        }
        onProgress("正在获取当前位置")
        val body = JSONObject(previous.requestJson)
            .put("destination", effectiveDestination)
            .put("origin", assistantOrigin().json())
            .put("departure", OffsetDateTime.now().toString()).put("allow_partial", false)
            .put("tasks", JSONArray(retainedOld + listOfNotNull(tentativeNewTask?.json())))
        onProgress("正在计算调整后的路线")
        val route = Api(server).plan(body)
        check(savedTrip == previous) { "行程已变化，请重新提出调整。" }
        val newTask = tentativeNewTask?.let { task ->
            val stops = route.getJSONArray("stops")
            val stop = (0 until stops.length()).map { stops.getJSONObject(it) }.firstOrNull { stop ->
                val ids = stop.getJSONArray("task_ids")
                (0 until ids.length()).any { ids.getString(it) == task.id }
            } ?: error("没有找到新增事项的实际停靠点，请重试。")
            task.copy(placeJson = stop.getJSONObject("place").toString())
        }
        if (newTask != null) {
            val tasksInBody = body.getJSONArray("tasks")
            for (index in 0 until tasksInBody.length()) {
                if (tasksInBody.getJSONObject(index).getString("id") == newTask.id)
                    tasksInBody.put(index, newTask.json())
            }
        }
        memory.setProposal(body, newTask)
        memory.record("assistant", "待确认方案：终点${effectiveDestination.getString("name")}；停靠点：" +
            (0 until route.getJSONArray("stops").length()).joinToString("、") {
                route.getJSONArray("stops").getJSONObject(it).getJSONObject("place").getString("name")
            })
        return NavigationChange(previous, body, route, existing, newTask)
    }

    internal suspend fun recalculateNavigationProposal(): NavigationChange {
        check(!busy && !updatingTrip && !editingPlan) { "行程正在更新，请稍后重试。" }
        val previous = savedTrip ?: error("没有正在进行的行程。")
        val memory = navigationConversation
        val proposed = memory.proposedBody ?: error("没有待恢复的方案。")
        val ids = proposed.getJSONArray("tasks")
        val pending = memory.pendingNewTask
        val existing = (0 until ids.length()).mapNotNull { index ->
            val id = ids.getJSONObject(index).getString("id")
            if (id == pending?.id) null else dao.get(id)
        }
        check(existing.all { it.status == "pending" } && existing.size + (if (pending == null) 0 else 1) == ids.length()) {
            "待办已经变化，请重新提出调整。"
        }
        val body = JSONObject(proposed.toString()).put("origin", assistantOrigin().json())
            .put("departure", OffsetDateTime.now().toString())
        val route = Api(server).plan(body)
        check(savedTrip == previous) { "行程已变化，请重新提出调整。" }
        memory.setProposal(body, pending)
        memory.record("assistant", "已重新计算待确认方案，请检查路线后再确认。")
        return NavigationChange(previous, body, route, existing, pending)
    }

    internal suspend fun confirmNavigationChange(change: NavigationChange) {
        check(!busy && !updatingTrip && !editingPlan) { "行程正在更新，请稍后重试。" }
        check(System.currentTimeMillis() - change.createdAt < 60_000) { "方案已过期，请重新计算。" }
        fun validate() {
            check(savedTrip == change.previous) { "行程已变化，请重新计算。" }
            check(change.tasks.all { old -> tasks.value.any { it == old } }) { "待办已变化，请重新计算。" }
        }
        validate()
        updatingTrip = true
        try {
            tripMutex.withLock {
                validate()
                val stored = change.previous.copy(requestJson = change.body.toString(), resultJson = change.route.toString(), plannedAt = System.currentTimeMillis(), needsReplan = false)
                database.withTransaction {
                    trips.record(RouteHistory(sessionId = stored.sessionId, planId = stored.planId, state = NavigationState.REPLANNING.name,
                        timestamp = System.currentTimeMillis(), requestJson = change.previous.requestJson, resultJson = change.previous.resultJson))
                    change.addedTask?.let { dao.save(it) }
                    trips.save(stored)
                    trips.record(RouteHistory(sessionId = stored.sessionId, planId = stored.planId,
                        state = NavigationState.NAVIGATION_OPENED.name, timestamp = stored.plannedAt,
                        requestJson = stored.requestJson, resultJson = stored.resultJson))
                }
                savedTrip = stored; result = change.route
                navigationMemoryTrip = stored
                navigationMemory.invalidatePreview()
                navigationMemory.setProposal(null, null)
                navigationMemory.record("assistant", "用户已确认，路线已更新。终点：${change.body.getJSONObject("destination").getString("name")}")
                origin = Place.from(change.body.getJSONObject("origin"))
                destination = Place.from(change.body.getJSONObject("destination"))
                val chosen = change.body.getJSONArray("tasks")
                selected = (0 until chosen.length()).map { chosen.getJSONObject(it).getString("id") }.toSet()
                revision++
            }
        } finally { updatingTrip = false }
    }
    internal fun planningBase(): SavedTrip? = savedTrip

    /** Commit exactly the preview the user saw. No network request is allowed here. */
    internal suspend fun confirmAssistantRoute(snapshot: ConfirmedRouteSnapshot): JSONObject {
        check(!tripStarted) { "导航已经开始，请通过导航中的调整入口修改路线。" }
        check(!busy && !updatingTrip && !editingPlan && !restoringTrip) { "行程正在更新，请稍后重试。" }
        updatingTrip = true
        try {
            return tripMutex.withLock {
                val response = JSONObject(snapshot.routeJson)
                val body = JSONObject(snapshot.requestJson)
                val stored = SavedTrip(requestJson = snapshot.requestJson, resultJson = snapshot.routeJson,
                    sessionId = snapshot.previous?.sessionId ?: java.util.UUID.randomUUID().toString(), planId = snapshot.planId)
                val event = RouteHistory(id = snapshot.versionId, sessionId = stored.sessionId, planId = stored.planId, state = NavigationState.READY.name,
                    timestamp = stored.plannedAt, requestJson = stored.requestJson, resultJson = stored.resultJson)
                database.withTransaction {
                    snapshot.draftId?.let { draftId ->
                        val draft = database.assistant().draft()
                        check(draft?.draftId == draftId && draft.planId == snapshot.planId) { "待确认方案已变化，请重新核对。" }
                    }
                    check(trips.historyById(snapshot.versionId) == null) { "此方案已经确认，请勿重复提交。" }
                    validateRouteConfirmation(snapshot.createdAt, System.currentTimeMillis(),
                        trips.load() == snapshot.previous && savedTrip == snapshot.previous,
                        snapshot.tasks.all { dao.get(it.id) == it })
                    trips.save(stored)
                    trips.record(event)
                    if (snapshot.draftId != null) {
                        database.assistant().clearDraft()
                        database.assistant().clearPlanningDraft(); database.assistant().clearPlanningTasks()
                        database.assistant().clearClarification(); database.assistant().clearPlaceChoices()
                    }
                    trace("ROUTE_PLANNED", "PLANNER", routeSessionId = stored.sessionId,
                        mode = body.getString("mode"), itemCount = snapshot.tasks.size,
                        extraSeconds = response.optInt("extra_seconds"))
                }
                savedTrip = stored; result = response; tripStarted = false
                lastPlannedHistoryId = event.id
                origin = Place.from(body.getJSONObject("origin"))
                destination = Place.from(body.getJSONObject("destination"))
                mode = body.getString("mode")
                selected = snapshot.tasks.map { it.id }.toSet()
                extra = body.optString("max_extra_minutes").takeUnless { it == "null" }.orEmpty()
                arriveBy = if (body.isNull("arrive_by")) "" else displayTime(body.getString("arrive_by"))
                preference = body.optString("preference", preference)
                partial = false; tripPlaces = emptyMap()
                draftBackup = null; editingPlan = false
                revision++
                response
            }
        } finally { updatingTrip = false }
    }

    fun plan() = runPlan(null)
    fun planForAssistant(planId: String? = null, draftId: String? = null, onPlanned: (JSONObject) -> Unit) = runPlan(onPlanned, planId, draftId)
    private fun runPlan(onPlanned: ((JSONObject) -> Unit)?, planId: String? = null, draftId: String? = null) {
        if (busy || updatingTrip || locating || restoringTrip) return
        val start = origin ?: run { message = "请选择起点"; return }
        val end = destination ?: run { message = "请选择目的地"; return }
        val chosen = tasks.value.filter { it.id in selected && it.status == "pending" }.map(::taskForTrip)
        if (chosen.any { requiresFixedPlace(it.placeType) && it.place() == null }) {
            message = "取快递、取外卖需先在任务中补全实际领取地点"; return
        }
        if (chosen.size > 6) { message = "最多选择 6 个待办任务"; return }
        val body = try {
            val budget = if (extra.isBlank()) null else extra.toInt().also { require(it in 0..240) }
            val latest = parseTime(arriveBy)
            val departure = OffsetDateTime.now()
            require(latest == null || !OffsetDateTime.parse(latest).isBefore(departure)) { "最晚到达时间已过去" }
            JSONObject().put("origin", start.json()).put("destination", end.json())
                .put("tasks", JSONArray(chosen.map { it.json() })).put("mode", mode)
                .put("preference", preference).put("departure", departure.toString())
                .put("arrive_by", latest ?: JSONObject.NULL).put("max_extra_minutes", budget ?: JSONObject.NULL)
                .put("allow_partial", partial)
        } catch (e: Exception) { message = "请检查时间（yyyy-MM-dd HH:mm）和额外分钟数（0–240），到达时间需晚于现在。"; return }
        inputsChanged()
        val requestRevision = revision
        busy = true
        viewModelScope.launch {
            try {
                val response = Api(server).plan(body)
                tripMutex.withLock {
                    if (revision == requestRevision) {
                        val previous = savedTrip
                        val stored = SavedTrip(requestJson = body.toString(), resultJson = response.toString(),
                            sessionId = previous?.sessionId ?: java.util.UUID.randomUUID().toString(),
                            planId = planId ?: previous?.planId ?: java.util.UUID.randomUUID().toString())
                        val plannedEvent = RouteHistory(sessionId = stored.sessionId, planId = stored.planId,
                            state = NavigationState.READY.name, timestamp = stored.plannedAt,
                            requestJson = stored.requestJson, resultJson = stored.resultJson)
                        database.withTransaction {
                            draftId?.let {
                                val draft = database.assistant().draft()
                                check(draft?.draftId == it && draft.planId == planId) { "待确认建议已变化，请重新规划。" }
                            }
                            if (previous != null) trips.record(RouteHistory(sessionId = previous.sessionId, planId = previous.planId,
                                state = NavigationState.REPLANNING.name, timestamp = System.currentTimeMillis(),
                                requestJson = previous.requestJson, resultJson = previous.resultJson))
                            trips.save(stored)
                            trips.record(plannedEvent)
                            if (draftId != null) {
                                database.assistant().clearDraft()
                                database.assistant().clearPlanningDraft(); database.assistant().clearPlanningTasks()
                                database.assistant().clearClarification(); database.assistant().clearPlaceChoices()
                            }
                            trace("ROUTE_PLANNED", "PLANNER", routeSessionId = stored.sessionId,
                                mode = mode, itemCount = chosen.size,
                                extraSeconds = response.optInt("extra_seconds"))
                        }
                        if (revision == requestRevision) {
                            savedTrip = stored; result = response; tripStarted = false
                            lastPlannedHistoryId = plannedEvent.id
                            draftBackup = null; editingPlan = false
                            onPlanned?.invoke(response)
                        }
                    }
                }
            } catch (e: Exception) { if (revision == requestRevision) message = networkMessage(e) }
            finally { busy = false }
        }
    }
}

fun networkMessage(e: Exception): String {
    if (e is CancellationException) throw e
    return when (e) {
        is java.net.SocketTimeoutException -> "请求超时，首次规划需要查询多段路线，请稍后重试。"
        is java.io.IOException -> "无法连接服务，请检查电脑服务、地址和 USB 转发或局域网连接。"
        else -> e.message ?: "请求失败，请重试。"
    }
}
