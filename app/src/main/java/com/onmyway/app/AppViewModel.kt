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

class AppViewModel internal constructor(application: Application, private val database: TaskDatabase) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, TaskDatabase.get(application))
    private val dao = database.tasks()
    private val trips = database.trips()
    private val tripMutex = Mutex()
    private var savedTrip by mutableStateOf<SavedTrip?>(null)
    private val navigationMemory = NavigationConversationState()
    private var navigationMemoryTrip: SavedTrip? = null
    internal val navigationConversation: NavigationConversationState get() {
        if (navigationMemoryTrip != savedTrip) {
            navigationMemory.clear()
            navigationMemoryTrip = savedTrip
        }
        return navigationMemory
    }
    private var terminalState by mutableStateOf(NavigationState.IDLE)
    val navigationState: NavigationState get() = when {
        busy -> if (result == null) NavigationState.PLANNING else NavigationState.REPLANNING
        result != null -> if (tripStarted) NavigationState.NAVIGATION_OPENED else NavigationState.READY
        else -> terminalState
    }
    val routeSession: RouteSession? get() = savedTrip?.let { stored ->
        val request = JSONObject(stored.requestJson)
        RouteSession(stored.sessionId, navigationState, Place.from(request.getJSONObject("origin")),
            Place.from(request.getJSONObject("destination")), stored.plannedAt, stored.startedAt, stored.requestJson, stored.resultJson)
    }
    private val prefs = application.getSharedPreferences("settings", 0)
    var familiarPlaces by mutableStateOf<Map<String, Place>>(runCatching {
        val json = JSONObject(prefs.getString("familiar_places", "{}")!!)
        json.keys().asSequence().associateWith { Place.from(json.getJSONObject(it)) }
    }.getOrDefault(emptyMap()))
        private set
    fun saveFamiliarPlace(name: String, place: Place?) {
        familiarPlaces = if (place == null) familiarPlaces - name else familiarPlaces + (name to place)
        val json = JSONObject()
        familiarPlaces.forEach { (alias, point) -> json.put(alias, point.json()) }
        prefs.edit { putString("familiar_places", json.toString()) }
    }
    fun familiarPlace(name: String): Place? = familiarPlaces[familiarPlaceAlias(name)]
    suspend fun routeHistory() = trips.history()
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

    fun invalidate() {
        terminalState = NavigationState.IDLE
        tripPlaces = emptyMap()
        draftBackup = null; editingPlan = false
        revision++; result = null; savedTrip = null; tripStarted = false; message = ""
        val invalidatedRevision = revision
        viewModelScope.launch {
            try { tripMutex.withLock { if (revision == invalidatedRevision && savedTrip == null) trips.clear() } }
            catch (_: Exception) { message = "旧行程清理失败，请重试。" }
        }
    }
    fun launchTrip(launch: () -> Boolean) {
        if (updatingTrip || restoringTrip) return
        val stored = savedTrip ?: return
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
                            if (!stored.started) trips.record(RouteHistory(sessionId = stored.sessionId,
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
    fun finishTrip(arrived: Boolean) {
        if (busy || updatingTrip || restoringTrip) return
        val stored = savedTrip ?: return
        updatingTrip = true
        viewModelScope.launch {
            try {
                tripMutex.withLock {
                    if (savedTrip != stored) return@withLock
                    val state = if (arrived) NavigationState.ARRIVED else NavigationState.CANCELLED
                    database.withTransaction {
                        trips.record(RouteHistory(sessionId = stored.sessionId, state = state.name,
                            timestamp = System.currentTimeMillis(), requestJson = stored.requestJson, resultJson = stored.resultJson))
                        trips.clear()
                    }
                    revision++; savedTrip = null; result = null; tripStarted = false
                    editingPlan = false; draftBackup = null; tripPlaces = emptyMap(); terminalState = state
                    message = if (arrived) "行程已结束，未办完的事项仍保留。" else "已结束本应用行程；高德导航需在高德内结束。"
                }
            } catch (_: Exception) { message = "行程结束失败，请重试" }
            finally { updatingTrip = false }
        }
    }
    fun cancelLocation() { locationJob?.cancel() }
    suspend fun assistantOrigin(): Place {
        val fix = readCurrentLocation(getApplication())
        check(fix.accuracy <= 200) { "当前位置精度较低，请开启精确定位后重试，或手动调整起点。" }
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
                message = "已设为起点，定位精度约 ${kotlin.math.ceil(fix.accuracy.toDouble()).toInt()} 米。" +
                    if (fix.accuracy > 200) "当前位置较粗略，建议开启精确位置或手动调整。" else ""
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
        invalidate()
        viewModelScope.launch {
            try {
                persistTask(task)
                if (task.status != "pending") selected = selected - task.id
            } catch (e: Exception) { message = "任务保存失败，请重试。" }
        }
    }
    suspend fun saveAssistantTask(task: Errand) {
        require(!requiresFixedPlace(task.placeType) || task.place() != null) { "请指定实际领取地点" }
        persistTask(task)
        invalidate()
    }
    fun delete(task: Errand) {
        invalidate()
        viewModelScope.launch {
            try { dao.delete(task); selected = selected - task.id }
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
    private suspend fun persistTask(task: Errand) = database.withTransaction {
        val old = dao.get(task.id)
        val now = System.currentTimeMillis()
        dao.save(task.copy(createdAt = old?.createdAt ?: task.createdAt, updatedAt = now,
            completedAt = if (task.status == "done") { if (old?.status == "done") old.completedAt else now } else 0,
            lastRemindedAt = old?.lastRemindedAt ?: 0, reminderCount = old?.reminderCount ?: 0,
            reminderPending = task.status == "pending" && (old?.reminderPending ?: false)))
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
            invalidate()
            message = "设置已保存"
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
    internal suspend fun previewNavigationChange(text: String, chosenDestination: Place? = null): NavigationChange {
        check(!busy && !updatingTrip && !editingPlan) { "行程正在更新，请稍后重试。" }
        val previous = savedTrip ?: error("没有正在进行的行程。")
        val old = JSONObject(previous.requestJson)
        val oldTasks = old.getJSONArray("tasks")
        val activeIds = (0 until oldTasks.length()).map { oldTasks.getJSONObject(it).getString("id") }.toSet()
        val active = tasks.value.filter { it.id in activeIds && it.status == "pending" }
        val memory = navigationConversation
        val proposed = memory.proposedBody
            ?: memory.draft?.takeIf { it.previous == previous }?.body
        val pendingIds = memory.pendingIds
        // Keep the last understood proposal as context, but never leave an older
        // executable preview available after a new request fails or is cancelled.
        memory.proposedBody = proposed
        memory.invalidatePreview()
        val selectedTasks = proposed?.getJSONArray("tasks")?.let { ids ->
            val chosenIds = (0 until ids.length()).map { ids.getJSONObject(it).getString("id") }.toSet()
            active.filter { it.id in chosenIds }
        } ?: active
        var target = chosenDestination
        var keep = if (chosenDestination != null) pendingIds else navigationSelection(text, selectedTasks)
        val history = JSONArray(memory.history.map { (role, content) -> JSONObject().put("role", role).put("content", content) })
        memory.record("user", if (chosenDestination == null) text else "选择地点：${chosenDestination.name}")
        if (keep == null) {
            val answer = Api(server).navigation(text, old.getJSONObject("destination").getString("name"), active, history, proposed)
            memory.record("assistant", answer.optString("reply").ifBlank { "已理解调整意图，尚未执行。" })
            check(answer.optString("action") == "adjust") { answer.optString("reply").ifBlank { "请说明要保留的事项和终点。" } }
            val ids = answer.getJSONArray("keep_task_ids")
            keep = (0 until ids.length()).map { ids.getString(it) }.toSet()
            check(keep.all { id -> active.any { it.id == id } }) { "返回的任务不在当前行程中，请重试。" }
            val keyword = answer.optString("destination")
            if (target == null && keyword.isNotBlank()) target = familiarPlace(keyword)
            if (target == null && keyword.isNotBlank()) {
                val current = assistantOrigin()
                val searchCity = city.ifBlank { Regex("(?:省)?([^省]+?市)").find(current.address)?.groupValues?.get(1).orEmpty() }
                check(searchCity.isNotBlank()) { "无法确定搜索城市，请在设置中填写城市。" }
                val places = search(keyword, searchCity)
                check(places.isNotEmpty()) { "没有找到该地点，请补充城市或完整名称。" }
                memory.record("assistant", "请选目的地：" + places.mapIndexed { index, place -> "${index + 1}. ${place.name}" }.joinToString("；"))
                throw NavigationDestinationChoice(places, checkNotNull(keep))
            }
        }
        val keptIds = checkNotNull(keep)
        val effectiveDestination = target?.json() ?: proposed?.getJSONObject("destination") ?: old.getJSONObject("destination")
        check(effectiveDestination.toString() != old.getJSONObject("destination").toString() || keptIds != active.map { it.id }.toSet()) { "这条指令没有改变当前路线。" }
        val body = JSONObject(previous.requestJson)
            .put("destination", effectiveDestination)
            .put("origin", assistantOrigin().json())
            .put("departure", OffsetDateTime.now().toString()).put("allow_partial", false)
            .put("tasks", JSONArray((0 until oldTasks.length()).map { oldTasks.getJSONObject(it) }.filter { it.getString("id") in keptIds }))
        val route = Api(server).plan(body)
        check(savedTrip == previous) { "行程已变化，请重新提出调整。" }
        memory.proposedBody = body
        memory.record("assistant", "待确认方案：终点${body.getJSONObject("destination").getString("name")}；保留事项：" + active.filter { it.id in keptIds }.joinToString("、") { it.title })
        return NavigationChange(previous, body, route, active)
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
                val stored = change.previous.copy(requestJson = change.body.toString(), resultJson = change.route.toString(), plannedAt = System.currentTimeMillis())
                database.withTransaction {
                    trips.record(RouteHistory(sessionId = stored.sessionId, state = NavigationState.REPLANNING.name,
                        timestamp = System.currentTimeMillis(), requestJson = change.previous.requestJson, resultJson = change.previous.resultJson))
                    trips.save(stored)
                }
                savedTrip = stored; result = change.route
                navigationMemoryTrip = stored
                navigationMemory.invalidatePreview()
                navigationMemory.proposedBody = null
                navigationMemory.record("assistant", "用户已确认，路线已更新。终点：${change.body.getJSONObject("destination").getString("name")}")
                origin = Place.from(change.body.getJSONObject("origin"))
                destination = Place.from(change.body.getJSONObject("destination"))
                val chosen = change.body.getJSONArray("tasks")
                selected = (0 until chosen.length()).map { chosen.getJSONObject(it).getString("id") }.toSet()
                revision++
            }
        } finally { updatingTrip = false }
    }
    fun plan() = runPlan(null)
    fun planForAssistant(onPlanned: (JSONObject) -> Unit) = runPlan(onPlanned)
    private fun runPlan(onPlanned: ((JSONObject) -> Unit)?) {
        if (busy || locating || restoringTrip) return
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
                            sessionId = previous?.sessionId ?: java.util.UUID.randomUUID().toString())
                        database.withTransaction {
                            if (previous != null) trips.record(RouteHistory(sessionId = previous.sessionId,
                                state = NavigationState.REPLANNING.name, timestamp = System.currentTimeMillis(),
                                requestJson = previous.requestJson, resultJson = previous.resultJson))
                            trips.save(stored)
                        }
                        if (revision == requestRevision) {
                            savedTrip = stored; result = response; tripStarted = false
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
