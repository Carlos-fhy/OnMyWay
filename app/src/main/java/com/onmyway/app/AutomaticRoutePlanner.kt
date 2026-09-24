package com.onmyway.app

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** Suggestions are kept separate from the persisted, user-confirmed trip. */
internal class AutomaticRoutePlanner(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var stage by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set
    var choices by mutableStateOf<List<Place>>(emptyList())
        private set
    var preview by mutableStateOf<JSONObject?>(null)
        private set
    var note by mutableStateOf("")
        private set
    var budget by mutableStateOf("10")
        private set
    private var job: Job? = null
    private var generation = 0
    private var calculatedAt = 0L
    private var ownsEdit = false
    private var start: Place? = null
    private var end: Place? = null
    private var mode = "walking"
    private var latest = ""
    private var candidates = emptyList<Errand>()
    var selectedIds by mutableStateOf(emptySet<String>())
        private set
    var selectedRoute by mutableStateOf<JSONObject?>(null)
        private set
    var selectionBusy by mutableStateOf(false)
        private set
    var selectionError by mutableStateOf("")
        private set
    private var selectionJob: Job? = null
    private var selectionRevision = 0
    val canConfirm: Boolean get() = !busy && !selectionBusy && selectedRoute != null &&
        selectionError.isBlank() && selectedRoute!!.optInt("extra_seconds") <= budget.toInt() * 60

    fun select(vm: AppViewModel, ids: Set<String>) {
        if (busy || vm.busy) return
        selectedIds = ids.intersect(candidates.map { it.id }.toSet())
        error = ""
        selectionRevision++
        selectedRoute = null; selectionError = ""; selectionBusy = true
        // Keep one request in flight: cancellation cannot stop the server's solver.
        if (selectionJob?.isActive == true) return
        val session = generation
        selectionJob = scope.launch {
            try {
                while (session == generation) {
                    val revision = selectionRevision
                    delay(400)
                    if (revision != selectionRevision) continue
                    val idsSnapshot = selectedIds
                    try {
                        val body = JSONObject().put("origin", start!!.json()).put("destination", end!!.json())
                            .put("mode", mode).put("tasks", JSONArray(candidates.filter { it.id in idsSnapshot }.map { it.json() }))
                            .put("preference", vm.preference).put("departure", OffsetDateTime.now().toString())
                            .put("allow_partial", false).put("max_extra_minutes", JSONObject.NULL)
                            .put("arrive_by", parseTime(latest) ?: JSONObject.NULL)
                        val route = Api(vm.server).plan(body)
                        if (session != generation) return@launch
                        if (revision != selectionRevision) continue
                        selectedRoute = route
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (session != generation) return@launch
                        if (revision != selectionRevision) continue
                        selectionError = networkMessage(e)
                    }
                    break
                }
            } finally { if (session == generation) selectionBusy = false }
        }
    }

    fun toggle(vm: AppViewModel, id: String) = select(vm, if (id in selectedIds) selectedIds - id else selectedIds + id)

    fun cancel() { generation++; job?.cancel(); selectionJob?.cancel(); selectionJob = null; selectionBusy = false; selectedRoute = null; selectionError = ""; busy = false; stage = "" }
    fun clear() { cancel(); preview = null; choices = emptyList(); error = ""; start = null; end = null; selectedIds = emptySet() }
    fun dismiss(vm: AppViewModel) {
        if (vm.busy) return
        if (ownsEdit) vm.cancelPlanEdit()
        ownsEdit = false; clear()
    }

    fun prepareManual(vm: AppViewModel): Boolean {
        if (start == null || end == null || vm.busy || vm.updatingTrip) return false
        if (vm.editingPlan && !ownsEdit) return false
        vm.beginPlanEdit(); ownsEdit = false
        vm.origin = start; vm.destination = end; vm.mode = mode
        vm.selected = selectedIds; vm.extra = budget; vm.arriveBy = latest; vm.partial = false
        vm.inputsChanged(); clear()
        return true
    }

    fun start(vm: AppViewModel, keyword: String, travelMode: String) {
        if (ownsEdit && !vm.busy) { vm.cancelPlanEdit(); ownsEdit = false }
        clear()
        val request = generation
        job = scope.launch {
            busy = true
            try {
                check(!vm.busy && !vm.updatingTrip && !vm.restoringTrip) { "当前行程正在更新，请稍后重试。" }
                check(!vm.editingPlan) { "有尚未完成的行程调整，请先确认或取消。" }
                stage = "正在获取当前位置…"
                start = vm.assistantOrigin()
                mode = travelMode
                budget = vm.extra.ifBlank { "10" }
                latest = vm.arriveBy
                stage = "正在查找目的地…"
                val familiar = vm.familiarPlace(keyword)
                val city = vm.city.ifBlank { Regex("(?:省)?([^省]+?市)").find(start!!.address)?.groupValues?.get(1).orEmpty() }
                check(familiar != null || city.isNotBlank()) { "无法确定搜索城市，请在设置中填写城市，或手动选择目的地。" }
                val found = if (familiar != null) listOf(familiar) else vm.search(keyword, city)
                check(found.isNotEmpty()) { "没有找到这个地点，请换一种说法或手动选择。" }
                fun normalized(s: String) = s.filter { it.isLetterOrDigit() }
                val exact = found.filter { normalized(it.name) == normalized(keyword) }
                val destination = if (exact.size == 1) exact.single() else found.singleOrNull()
                if (destination == null) { choices = found; stage = "请选择具体目的地" }
                else { end = destination; calculate(vm) }
            } catch (e: TimeoutCancellationException) { error = "定位超时，请到信号较好的地方重试，或手动调整。" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { if (generation == request) busy = false }
        }
    }

    fun choose(vm: AppViewModel, place: Place) {
        if (busy) return
        val request = ++generation
        choices = emptyList(); end = place; error = ""
        job = scope.launch {
            busy = true
            try { calculate(vm) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { if (generation == request) busy = false }
        }
    }

    private suspend fun calculate(vm: AppViewModel) {
        stage = "正在计算直达与顺路方案…"
        val pending = vm.tasks.value.filter { it.status == "pending" }
        val missing = pending.filter { requiresFixedPlace(it.placeType) && it.place() == null }
        val eligible = pending.filterNot { it in missing }.filter {
            it.deadline.isBlank() || runCatching { OffsetDateTime.parse(it.deadline).isAfter(OffsetDateTime.now()) }.getOrDefault(false)
        }
        candidates = eligible.sortedWith(compareByDescending<Errand> { it.priority }.thenBy { it.deadline.ifBlank { "9999" } }).take(6)
        note = listOfNotNull(
            if (missing.isNotEmpty()) "${missing.joinToString("、") { it.title }}缺少领取地点，暂未参与。" else null,
            if (eligible.size > 6) "本次先检查优先级较高的 6 件事，其余可手动调整。" else null,
            if (eligible.size + missing.size < pending.size) "已过截止时间的任务暂未参与，请调整时间。" else null
        ).joinToString("\n")
        val body = JSONObject().put("origin", start!!.json()).put("destination", end!!.json())
            .put("mode", mode).put("tasks", JSONArray(candidates.map { it.json() }))
            .put("preference", vm.preference).put("departure", OffsetDateTime.now().toString())
            .put("allow_partial", true).put("max_extra_minutes", budget.toInt())
            .put("arrive_by", parseTime(latest) ?: JSONObject.NULL)
        preview = Api(vm.server).recommend(body)
        calculatedAt = System.currentTimeMillis()
        selectedIds = emptySet()
        selectedRoute = preview!!.getJSONObject("direct")
        stage = ""
    }

    fun suggestedIds(): Set<String> {
        val stops = preview?.optJSONObject("suggestion")?.optJSONArray("stops") ?: return emptySet()
        return buildSet { for (i in 0 until stops.length()) {
            val ids = stops.getJSONObject(i).getJSONArray("task_ids")
            for (j in 0 until ids.length()) add(ids.getString(j))
        } }
    }

    /** An explicit choice recomputes before persisting; previews never overwrite a trip. */
    fun accept(vm: AppViewModel, ids: Set<String>, done: (JSONObject) -> Unit) {
        if (busy || vm.busy || vm.updatingTrip) return
        if (!canConfirm || ids != selectedIds) { error = "请等待所选方案计算完成；超出预算时请减少事项或手动调整预算。"; return }
        if (preview == null || start == null || end == null) return
        if (vm.editingPlan && !ownsEdit) { error = "请先完成顺路页的行程调整，再确认建议。"; return }
        if (System.currentTimeMillis() - calculatedAt > 5 * 60_000) { error = "方案已超过五分钟，请重新定位并计算。"; return }
        if (ids.any { id -> candidates.none { it.id == id } || candidates.find { it.id == id } != vm.tasks.value.find { it.id == id && it.status == "pending" } }) {
            error = "待办已发生变化，请重新计算后确认。"; return
        }
        vm.beginPlanEdit()
        ownsEdit = true
        vm.origin = start; vm.destination = end; vm.mode = mode
        vm.selected = ids; vm.extra = budget; vm.arriveBy = latest; vm.partial = false
        vm.tripPlaces.keys.toList().forEach(vm::resetTripPlace)
        vm.inputsChanged()
        vm.planForAssistant { result -> ownsEdit = false; clear(); done(result) }
    }

    fun command(text: String): Set<String>? {
        val assessments = preview?.optJSONArray("assessments") ?: return null
        val selectable = buildSet {
            for (i in 0 until assessments.length()) {
                val entry = assessments.getJSONObject(i)
                if (!entry.isNull("extra_seconds")) add(entry.getString("task_id"))
            }
        }
        return selectRouteTasks(text, candidates.filter { it.id in selectable }, selectedIds)
    }
}

internal fun selectRouteTasks(text: String, candidates: List<Errand>, confirmedIds: Set<String>): Set<String>? {
        val clean = text.trim().trimEnd('。', '！', '!', '，')
        if (clean in listOf("都去", "全部加入", "都加入")) return candidates.map { it.id }.toSet()
        if (clean in listOf("直接走", "直接去目的地", "不办事", "只去目的地")) return emptySet()
        val aliases = mapOf("快递" to "PARCEL", "打印" to "PRINT_SHOP", "买药" to "PHARMACY", "药店" to "PHARMACY", "购物" to "SUPERMARKET", "超市" to "SUPERMARKET", "外卖" to "TAKEOUT")
        for ((word, type) in aliases) {
            val matching = candidates.filter { it.placeType == type }.map { it.id }.toSet()
            if (matching.isNotEmpty() && clean in listOf("只去$word", "只$word", "只取$word", "只拿$word")) return matching
            if (matching.isNotEmpty() && clean in listOf("不去${word}了", "不取${word}了", "不${word}了")) return confirmedIds - matching
        }
        return null
}
