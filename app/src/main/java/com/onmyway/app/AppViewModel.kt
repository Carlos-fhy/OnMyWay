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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** Route constraints must come from current tasks; preserve a stop only for a compatible generic task. */
internal fun refreshedNavigationTasks(original: JSONArray, selected: JSONArray, current: List<Errand>): List<JSONObject> {
    val originalById = (0 until original.length()).associate { index ->
        original.getJSONObject(index).let { it.getString("id") to it }
    }
    val currentById = current.associateBy { it.id }
    return (0 until selected.length()).map { index ->
        val planned = selected.getJSONObject(index)
        val id = planned.getString("id")
        val task = checkNotNull(currentById[id]) { "待办已经变化，请重新提出调整。" }
        val updated = task.json()
        val previous = originalById[id]
        val selectedPlace = planned.optJSONObject("place")
        if (task.placeJson.isBlank() && previous?.isNull("place") == true &&
            previous.optString("place_type") == task.placeType && selectedPlace != null) {
            updated.put("place", JSONObject(selectedPlace.toString())).put("place_type", JSONObject.NULL)
        }
        updated
    }
}


class AppViewModel internal constructor(application: Application, private val database: TaskDatabase,
    private val navigationDelivery: NavigationRequestDelivery = NavigationRequestDelivery(application),
    private val navigationTools: NavigationRouteTools? = null,
) : AndroidViewModel(application) {
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
    private val navigationWriteMutex = Mutex()
    private var navigationPersistenceRevision = 0L
    private var navigationSnapshot: JSONObject? = null
    internal var pendingNavigationRequest by mutableStateOf<PendingAssistantRequest?>(null)
        private set
    internal var navigationRecoveryMessage by mutableStateOf("")
        private set
    private fun navigationSnapshotNow(): JSONObject? = navigationMemoryTrip?.let { navigationMemory.snapshot(it) }
    private val navigationContextPrefs = application.getSharedPreferences("navigation_context", 0)
    private fun persistNavigationMemory() {
        val snapshot = navigationSnapshotNow()
        val revision = ++navigationPersistenceRevision
        navigationSnapshot = snapshot
        viewModelScope.launch {
            try { navigationWriteMutex.withLock {
                if (revision == navigationPersistenceRevision) navigationDelivery.saveContext(snapshot)
            } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "导航对话保存失败，请稍后重试。" }
        }
    }
    internal suspend fun persistNavigationMemoryNow() {
        val snapshot = navigationSnapshotNow()
        ++navigationPersistenceRevision
        navigationWriteMutex.withLock { navigationDelivery.saveContext(snapshot) }
        navigationSnapshot = snapshot
    }
    private suspend fun clearNavigationRecovery() {
        ++navigationAttempt
        ++navigationPersistenceRevision
        navigationMemory.persist = null; navigationMemory.clear(); navigationMemoryTrip = null
        navigationSnapshot = null
        navigationWriteMutex.withLock {
            navigationDelivery.pending()?.let { navigationDelivery.finish(it.requestId) }
            navigationDelivery.saveContext(null)
        }
        pendingNavigationRequest = null; navigationRecoveryMessage = ""
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
            val snapshot = navigationSnapshot
            if (stored != null && snapshot != null) {
                val restored = runCatching { navigationMemory.restore(snapshot, stored) }.getOrDefault(false)
                if (!restored) navigationSnapshot = null
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
        viewModelScope.coroutineContext[Job]?.invokeOnCompletion { navigationDelivery.close() }
        viewModelScope.launch {
            try {
                navigationSnapshot = navigationDelivery.loadContext()
                pendingNavigationRequest = navigationDelivery.pending()
                if (navigationSnapshot == null) {
                    navigationContextPrefs.getString("snapshot", null)?.let { old ->
                        navigationSnapshot = runCatching { JSONObject(old) }.getOrNull()
                        navigationDelivery.saveContext(navigationSnapshot)
                    }
                }
                navigationContextPrefs.edit { remove("snapshot") }
                tripMutex.withLock {
                    val stored = trips.load()
                    if (stored == null) clearNavigationRecovery()
                    if (stored != null && stored.started && revision == 0 && !NavigationRuntime.owns(stored.sessionId)) {
                        database.withTransaction {
                            trips.record(RouteHistory(sessionId = stored.sessionId, planId = stored.planId, state = NavigationState.CANCELLED.name,
                                timestamp = System.currentTimeMillis(), requestJson = stored.requestJson, resultJson = stored.resultJson))
                            trips.clear()
                        }
                        terminalState = NavigationState.CANCELLED
                        clearNavigationRecovery()
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
                            clearNavigationRecovery()
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
                    message = if (arrived) "已到达，行程保存在记录中；未办事项仍保留。"
                        else "导航已结束，方案保存在行程记录；再次出发请重新规划。"
                    finished = true
                    try { clearNavigationRecovery() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message += " 上次调整记录清理失败，旧方案不会再应用。" }
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
    suspend fun saveAssistantTask(task: Errand, expectedDraftId: String): Boolean = tripMutex.withLock {
        val store = AssistantStore(database, getApplication<Application>().getSharedPreferences("assistant_conversation", 0))
        val successText = if (routeSession == null)
            "已记下：${task.title}。准备出发时告诉我目的地，我会重新规划。" else "已记下：${task.title}"
        val created = store.confirmTask(expectedDraftId, task, successText) { saved ->
            trace("ACTION_CREATED", "ASSISTANT", actionId = saved.id, correlationId = expectedDraftId)
        }
        if (created) revision++
        created
    }
    suspend fun saveAssistantTasks(expectedDraftId: String, selections: Map<String, Int>): Boolean = tripMutex.withLock {
        val store = AssistantStore(database, getApplication<Application>().getSharedPreferences("assistant_conversation", 0))
        val created = store.confirmTasks(expectedDraftId, selections,
            "已保存所选 ${selections.size} 件事；未选草稿继续保留。") { saved ->
            trace("ACTION_CREATED", "ASSISTANT", actionId = saved.id, correlationId = expectedDraftId)
        }
        if (created) revision++
        created
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
    private var navigationAttempt = 0L
    private var adjustingNavigation = false
    private suspend fun <T> navigationAdjustment(block: suspend () -> T): T {
        check(!adjustingNavigation && !updatingTrip) { "正在处理上次调整，请稍后重试。" }
        adjustingNavigation = true
        return try { block() } finally { adjustingNavigation = false }
    }
    private suspend fun navigationOrigin() = navigationTools?.origin() ?: assistantOrigin()
    private suspend fun navigationPlan(body: JSONObject) = navigationTools?.plan(server, body) ?: Api(server).plan(body)
    private suspend fun navigationSearch(keyword: String, searchCity: String) =
        navigationTools?.search(server, keyword, searchCity) ?: search(keyword, searchCity)
    private suspend fun navigationRequestContext(previous: SavedTrip): String {
        val ids = JSONObject(previous.requestJson).getJSONArray("tasks")
        val rows = (0 until ids.length()).map { dao.get(ids.getJSONObject(it).getString("id")) }
        val memory = navigationConversation
        val content = JSONObject().put("trip", assistantTripContext(previous))
            .put("tasks", rows.toString()).put("proposed", memory.proposedBody ?: JSONObject.NULL)
            .put("new_task", memory.pendingNewTask?.json() ?: JSONObject.NULL).toString()
        return java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    internal suspend fun editNavigationRequest() = navigationAdjustment { withContext(NonCancellable) {
        val request = pendingNavigationRequest ?: return@withContext
        navigationAttempt++
        navigationDelivery.finish(request.requestId)
        pendingNavigationRequest = null; navigationRecoveryMessage = ""
        navigationConversation.input = request.userText
    } }
    internal suspend fun cancelNavigationAdjustment() = navigationAdjustment { withContext(NonCancellable) {
        navigationAttempt++
        pendingNavigationRequest?.let { navigationDelivery.finish(it.requestId) }
        pendingNavigationRequest = null; navigationRecoveryMessage = ""
        navigationConversation.clear()
        persistNavigationMemoryNow()
    } }
    internal suspend fun recoverNavigationRequest(onProgress: (String) -> Unit = {}): NavigationChange? =
        navigationAdjustment { recoverNavigationRequestOnce(onProgress) }

    private suspend fun recoverNavigationRequestOnce(onProgress: (String) -> Unit): NavigationChange? {
        check(!busy && !updatingTrip && !editingPlan && !restoringTrip) { "行程正在更新，请稍后重试。" }
        val request = pendingNavigationRequest ?: return null
        val previous = savedTrip
        if (previous == null || trips.load() != previous || Api.validateBase(server) != request.server ||
            navigationRequestContext(previous) != request.contextKey) {
            navigationRecoveryMessage = "行程、待办或服务地址已变化，旧指令不会应用。请重新编辑。"
            return null
        }
        onProgress("正在取回上次调整结果")
        return when (val delivered = navigationDelivery.obtain(request)) {
            is NavigationDeliveryResult.Ready -> buildNavigationChange(request.userText, null, onProgress, request, delivered.result)
            is NavigationDeliveryResult.Waiting -> {
                navigationRecoveryMessage = when (delivered.state) {
                    "running" -> "仍在理解上次指令，请稍后继续获取，不会重复发送。"
                    "failed" -> "上次指令处理失败，可重新编辑后发送。"
                    "superseded" -> "请求已取消或替换，请重新打开调整页面。"
                    else -> "暂时无法确定结果，可稍后查询或重新编辑。"
                }
                null
            }
        }
    }
    private suspend fun commitNavigationOutcome(previous: SavedTrip, request: PendingAssistantRequest?,
        validate: suspend () -> Unit = {}, update: () -> Unit) = withContext(NonCancellable) { tripMutex.withLock {
        check(savedTrip == previous && trips.load() == previous) { "行程已变化，请重新提出调整。" }
        validate()
        request?.let { check(navigationRequestContext(previous) == it.contextKey && Api.validateBase(server) == it.server) {
            "行程、待办或服务地址已变化，旧回复未应用。"
        } }
        val memory = navigationConversation
        val before = memory.snapshot(previous)
        memory.persist = null
        try {
            ++navigationPersistenceRevision
            update()
            val snapshot = memory.snapshot(previous)
            navigationWriteMutex.withLock {
                if (request == null) navigationDelivery.saveContext(snapshot)
                else check(navigationDelivery.consume(request.requestId, snapshot)) { "请求已取消或替换。" }
            }
            navigationSnapshot = snapshot
            if (pendingNavigationRequest?.requestId == request?.requestId) pendingNavigationRequest = null
            navigationRecoveryMessage = ""
        } catch (e: Exception) {
            memory.restore(before, previous)
            throw e
        } finally { memory.persist = ::persistNavigationMemory }
    }
    }
    internal suspend fun previewNavigationChange(text: String, chosenDestination: Place? = null, onProgress: (String) -> Unit = {}): NavigationChange {
        check(pendingNavigationRequest == null) { "上次调整尚未取回，请先继续获取结果或重新编辑。" }
        return navigationAdjustment { buildNavigationChange(text, chosenDestination, onProgress) }
    }
    private suspend fun buildNavigationChange(text: String, chosenDestination: Place?, onProgress: (String) -> Unit,
        recoveredRequest: PendingAssistantRequest? = null, recoveredAnswer: JSONObject? = null): NavigationChange {
        check(!busy && !updatingTrip && !editingPlan && !restoringTrip) { "行程正在更新，请稍后重试。" }
        val attempt = ++navigationAttempt
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
        if (recoveredRequest != null) {
            check(Api.validateBase(server) == recoveredRequest.server && navigationRequestContext(previous) == recoveredRequest.contextKey) {
                "当前上下文已变化，旧请求不会应用。"
            }
            val originalHistory = JSONObject(recoveredRequest.bodyJson).getJSONArray("history")
            memory.history = (0 until originalHistory.length()).map { originalHistory.getJSONObject(it).let { turn ->
                turn.getString("role") to turn.getString("content")
            } }
        }
        memory.record("user", if (target == null) text else "选择地点：${target.name}")
        var deliveryRequest = recoveredRequest
        if (keep == null || recoveredAnswer != null) {
            onProgress("正在理解调整指令")
            val answer = recoveredAnswer ?: run {
                val requestId = java.util.UUID.randomUUID().toString()
                val body = JSONObject().put("request_id", requestId).put("text", text)
                    .put("destination", old.getJSONObject("destination").getString("name"))
                    .put("tasks", JSONArray(active.map { JSONObject().put("id", it.id).put("title", it.title).put("place_type", it.placeType) }))
                    .put("history", history).put("proposed_destination", proposed?.getJSONObject("destination")?.getString("name") ?: "")
                    .put("proposed_task_ids", if (proposed == null) JSONObject.NULL else JSONArray(proposedIds.toList()))
                val request = PendingAssistantRequest(requestId, Api.validateBase(server), body.toString(), text,
                    "navigation-user-$requestId", System.currentTimeMillis(), navigationRequestContext(previous))
                deliveryRequest = request
                try {
                    persistNavigationMemoryNow()
                    val delivered = navigationDelivery.submit(request)
                    check(delivered is NavigationDeliveryResult.Ready) { "请求仍待处理，请继续获取结果。" }
                    delivered.result
                } finally { withContext(NonCancellable) { pendingNavigationRequest = navigationDelivery.pending() } }
            }
            check(attempt == navigationAttempt && savedTrip == previous &&
                navigationRequestContext(previous) == deliveryRequest?.contextKey && Api.validateBase(server) == deliveryRequest?.server) {
                "行程、待办或服务地址已变化，旧回复未应用。"
            }
            val action = answer.optString("action")
            if (action == "clarify") {
                val reply = answer.optString("reply").ifBlank { "请说明要保留的事项和终点。" }
                commitNavigationOutcome(previous, deliveryRequest) { memory.record("assistant", reply) }
                error(reply)
            }
            check(action == "adjust" || action == "add_stop") { "无法识别导航调整结果。" }
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
                requestedNewTask = Errand(id = java.util.UUID.nameUUIDFromBytes(("navigation-stop-" + deliveryRequest!!.requestId)
                    .toByteArray(Charsets.UTF_8)).toString(), title = title, placeType = type)
            }
            val keyword = answer.optString("destination")
            if (target == null && keyword.isNotBlank()) target = familiarPlace(keyword)
            if (target == null && keyword.isNotBlank()) {
                val searchCity = city.ifBlank {
                    onProgress("正在定位以确定搜索城市")
                    val current = navigationOrigin()
                    Regex("(?:省)?([^省]+?市)").find(current.address)?.groupValues?.get(1).orEmpty()
                }
                check(searchCity.isNotBlank()) { "无法确定搜索城市，请在设置中填写城市。" }
                onProgress("正在搜索新的目的地")
                val places = navigationSearch(keyword, searchCity)
                check(places.isNotEmpty()) { "没有找到该地点，请补充城市或完整名称。" }
                check(attempt == navigationAttempt && savedTrip == previous) { "行程已变化，请重新提出调整。" }
                commitNavigationOutcome(previous, deliveryRequest) {
                    memory.setChoices(places, checkNotNull(keep), text, requestedNewTask)
                    memory.record("assistant", "请选目的地：" + places.mapIndexed { index, place -> "${index + 1}. ${place.name}" }.joinToString("；"))
                }
                throw NavigationDestinationChoice(places, checkNotNull(keep), requestedNewTask)
            }
        }
        val keptIds = checkNotNull(keep)
        val tentativeNewTask = requestedNewTask ?: previousNewTask?.takeIf { it.id in keptIds }
        val selectedOld = retainedNavigationTasks(oldTasks, JSONObject(previous.resultJson), keptIds)
        val retainedOld = refreshedNavigationTasks(oldTasks, JSONArray(selectedOld), existing)
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
            .put("origin", navigationOrigin().json())
            .put("departure", OffsetDateTime.now().toString()).put("allow_partial", false)
            .put("tasks", JSONArray(retainedOld + listOfNotNull(tentativeNewTask?.json())))
        onProgress("正在计算调整后的路线")
        val route = navigationPlan(body)
        check(attempt == navigationAttempt && savedTrip == previous && existing.all { dao.get(it.id) == it }) { "行程或待办已变化，请重新提出调整。" }
        deliveryRequest?.let { check(navigationRequestContext(previous) == it.contextKey && Api.validateBase(server) == it.server) { "当前调整已变化，请重新编辑。" } }
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
        commitNavigationOutcome(previous, deliveryRequest) {
            memory.setProposal(body, newTask)
            memory.record("assistant", "待确认方案：终点${effectiveDestination.getString("name")}；停靠点：" +
            (0 until route.getJSONArray("stops").length()).joinToString("、") {
                route.getJSONArray("stops").getJSONObject(it).getJSONObject("place").getString("name")
            })
        }
        return NavigationChange(previous, body, route, existing, newTask).also { memory.draft = it }
    }

    internal suspend fun recalculateNavigationProposal(): NavigationChange = navigationAdjustment {
        check(pendingNavigationRequest == null) { "上次调整尚未取回，请先继续获取结果或重新编辑。" }
        recalculateNavigationProposalOnce()
    }

    private suspend fun recalculateNavigationProposalOnce(): NavigationChange {
        check(!busy && !updatingTrip && !editingPlan && !restoringTrip) { "行程正在更新，请稍后重试。" }
        val previous = savedTrip ?: error("没有正在进行的行程。")
        val memory = navigationConversation
        val proposedJson = memory.proposedBody?.toString() ?: error("没有待恢复的方案。")
        val proposed = JSONObject(proposedJson)
        val requested = proposed.getJSONArray("tasks")
        val ids = (0 until requested.length()).map { requested.getJSONObject(it).getString("id") }
        val pending = memory.pendingNewTask
        memory.invalidatePreview()
        check(ids.size == ids.toSet().size && (pending == null || pending.id in ids)) {
            "待办已经变化，请重新提出调整。"
        }
        val existing = ids.filter { it != pending?.id }.map { id ->
            val task = dao.get(id)
            check(task != null && task.status == "pending") { "待办已经变化，请重新提出调整。" }
            task
        }
        check(pending == null || dao.get(pending.id) == null) { "新增事项已经存在，请重新提出调整。" }
        val originalTasks = JSONObject(previous.requestJson).getJSONArray("tasks")
        val existingRequests = JSONArray((0 until requested.length()).map { requested.getJSONObject(it) }
            .filter { it.getString("id") != pending?.id })
        val refreshed = refreshedNavigationTasks(originalTasks, existingRequests, existing)
            .associateBy { it.getString("id") }
        val recalculatedTasks = ids.map { id ->
            if (id == pending?.id) checkNotNull(pending).json() else checkNotNull(refreshed[id])
        }
        val body = JSONObject(proposedJson).put("tasks", JSONArray(recalculatedTasks))
            .put("origin", navigationOrigin().json()).put("departure", OffsetDateTime.now().toString())
        val route = navigationPlan(body)
        check(savedTrip == previous && trips.load() == previous) { "行程已变化，请重新提出调整。" }
        check(memory.proposedBody?.toString() == proposedJson && memory.pendingNewTask == pending) {
            "调整方案已经变化，请重新提出调整。"
        }
        check(existing.all { dao.get(it.id) == it } && (pending == null || dao.get(pending.id) == null)) {
            "待办已经变化，请重新提出调整。"
        }
        commitNavigationOutcome(previous, null, validate = {
            check(memory.proposedBody?.toString() == proposedJson && memory.pendingNewTask == pending &&
                existing.all { dao.get(it.id) == it } && (pending == null || dao.get(pending.id) == null)) {
                "调整方案或待办已经变化，请重新提出调整。"
            }
        }) {
            memory.setProposal(body, pending)
            memory.record("assistant", "已重新计算待确认方案，请检查路线后再确认。")
        }
        return NavigationChange(previous, body, route, existing, pending).also { memory.draft = it }
    }

    internal suspend fun confirmNavigationChange(change: NavigationChange) {
        check(!adjustingNavigation && pendingNavigationRequest == null) { "调整尚未完成，请先获取完整方案。" }
        check(!busy && !updatingTrip && !editingPlan && !restoringTrip) { "行程正在更新，请稍后重试。" }
        val memory = navigationConversation
        fun validatePreview() {
            check(memory.draft?.versionId == change.versionId && memory.draft == change) {
                "待确认方案已变化，请重新核对。"
            }
            val now = System.currentTimeMillis()
            check(change.createdAt > 0 && now >= change.createdAt && now - change.createdAt <= 60_000L) {
                "方案已过期，请重新计算。"
            }
        }
        validatePreview()
        updatingTrip = true
        try {
            withContext(NonCancellable) { tripMutex.withLock {
                validatePreview()
                val body = JSONObject(change.bodyJson)
                val route = JSONObject(change.routeJson)
                val stored = change.previous.copy(requestJson = change.bodyJson, resultJson = change.routeJson,
                    plannedAt = System.currentTimeMillis(), needsReplan = false)
                database.withTransaction {
                    validatePreview()
                    check(trips.historyById(change.versionId) == null) { "此方案已经确认，请勿重复提交。" }
                    validateRouteConfirmation(change.createdAt, System.currentTimeMillis(),
                        trips.load() == change.previous && savedTrip == change.previous,
                        change.tasks.all { dao.get(it.id) == it })
                    check(change.addedTask == null || dao.get(change.addedTask.id) == null) {
                        "新增事项已经存在，请重新计算。"
                    }
                    trips.record(RouteHistory(id = "${change.versionId}:replanning", sessionId = stored.sessionId,
                        planId = stored.planId, state = NavigationState.REPLANNING.name,
                        timestamp = stored.plannedAt, requestJson = change.previous.requestJson,
                        resultJson = change.previous.resultJson))
                    change.addedTask?.let { dao.save(it) }
                    trips.save(stored)
                    trips.record(RouteHistory(id = change.versionId, sessionId = stored.sessionId, planId = stored.planId,
                        state = NavigationState.NAVIGATION_OPENED.name, timestamp = stored.plannedAt,
                        requestJson = stored.requestJson, resultJson = stored.resultJson))
                    validatePreview()
                }
                savedTrip = stored; result = route
                navigationMemoryTrip = stored
                memory.clear()
                memory.record("assistant", "用户已确认，路线已更新。终点：${body.getJSONObject("destination").getString("name")}")
                origin = Place.from(body.getJSONObject("origin"))
                destination = Place.from(body.getJSONObject("destination"))
                val chosen = body.getJSONArray("tasks")
                selected = (0 until chosen.length()).map { chosen.getJSONObject(it).getString("id") }.toSet()
                revision++
                // Room already committed the route. A separate context checkpoint failure must not
                // make a successful confirmation look retryable or leave screen fields half updated.
                try { persistNavigationMemoryNow() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "路线已更新，调整记录暂未保存；旧方案不会再次应用。" }
            } }
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
