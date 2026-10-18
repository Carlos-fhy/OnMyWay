package com.onmyway.app.feature.planning

import androidx.compose.runtime.*
import com.onmyway.app.core.format.parseTime
import com.onmyway.app.core.memory.DetourGuidance
import com.onmyway.app.core.memory.GuidanceSource
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.routeBlockedLabel
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.state.networkMessage
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.remote.Api
import com.onmyway.app.feature.assistant.AssistantClarification
import com.onmyway.app.feature.assistant.ClarificationState
import com.onmyway.app.feature.assistant.clarificationPrompt
import com.onmyway.app.feature.assistant.toClarificationChoice
import com.onmyway.app.feature.navigation.destinationQuery
import java.time.OffsetDateTime
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Suggestions are kept separate from the persisted, user-confirmed trip. */
internal class AutomaticRoutePlanner(private val scope: CoroutineScope,
    private val persistChoices: (PlanningChoices) -> Unit = {},
    private val persistClarification: (ClarificationState) -> Unit = {}) {
    var restoredChoices: PlanningChoices? = null
    private var restoreTasks: List<PlanningDraftTask>? = null
    private var destinationQuery = ""
    private var draftIdentity = ""
    private var planIdentity = ""
    fun restoreQuestion(state: ClarificationState) {
        if (state.question.kind != "DESTINATION" || state.choices.isEmpty()) return
        if (!clear()) return
        draftIdentity = state.question.draftId; planIdentity = state.question.planId
        destinationQuery = state.question.destination; mode = state.question.mode
        choices = state.choices.sortedBy { it.position }.map { it.toPlace() }
        stage = state.question.prompt
    }
    private fun rememberQuestion(kind: String, places: List<Place> = emptyList()) {
        persistClarification(ClarificationState(AssistantClarification(draftId = draftIdentity, planId = planIdentity,
            kind = kind, prompt = clarificationPrompt(kind), destination = destinationQuery, mode = mode,
            savedAt = System.currentTimeMillis()), places.mapIndexed { index, place -> place.toClarificationChoice(draftIdentity, index) }))
    }

    private fun saveChoices() {
        val place = end ?: return
        persistChoices(PlanningChoices(PlanningDraft(draftId = draftIdentity, planId = planIdentity, destination = destinationQuery, mode = mode,
            budget = budget.toInt(), placeId = place.id, placeName = place.name,
            longitude = place.longitude, latitude = place.latitude, address = place.address,
            savedAt = System.currentTimeMillis()), candidates.map { PlanningDraftTask(it.id, it.id in selectedIds) }))
    }
    var busy by mutableStateOf(false)
        private set
    var committing by mutableStateOf(false)
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
    var plannedBudget by mutableIntStateOf(10)
        private set
    val budgetChanged: Boolean get() = budget.toInt() != plannedBudget

    fun updateBudget(minutes: Int) {
        if (committing) return
        val clamped = minutes.coerceIn(0, 240)
        budget = clamped.toString()
        saveChoices()
    }
    fun replanForBudget(vm: AppViewModel) {
        if (busy || selectionBusy || vm.busy || vm.updatingTrip || vm.restoringTrip || start == null || end == null) return
        restoreTasks = candidates.map { PlanningDraftTask(it.id, it.id in selectedIds) }
        val request = generation
        job = scope.launch {
            busy = true; error = ""
            try { calculate(vm) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == request) error = networkMessage(e) }
            finally { if (generation == request) busy = false }
        }
    }
    private var job: Job? = null
    private var generation = 0
    private var calculatedAt = 0L
    private var selectedRequest: JSONObject? = null
    private var baseTrip: SavedTrip? = null
    private var previewVersionId = java.util.UUID.randomUUID().toString()
    private var feedbackId: String? = null
    private var ownsEdit = false
    private var start: Place? = null
    private var end: Place? = null
    private var mode = "walking"
    val travelMode: String get() = mode
    var budgetGuidance by mutableStateOf(DetourGuidance(10, GuidanceSource.DEFAULT))
        private set
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
    val canConfirm: Boolean get() = !busy && !selectionBusy && !budgetChanged && selectedRequest != null && selectedRoute != null &&
        selectionError.isBlank() && selectedRoute!!.optInt("extra_seconds") <= budget.toInt() * 60

    fun select(vm: AppViewModel, ids: Set<String>) {
        if (busy || vm.busy || vm.updatingTrip || vm.restoringTrip) return
        selectedIds = ids.intersect(candidates.map { it.id }.toSet())
        saveChoices()
        error = ""
        selectionRevision++
        selectedRoute = null; selectedRequest = null; selectionError = ""; selectionBusy = true
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
                        previewVersionId = java.util.UUID.randomUUID().toString()
                        selectedRoute = route
                        selectedRequest = JSONObject(body.toString()).put("max_extra_minutes", budget.toInt())
                        calculatedAt = System.currentTimeMillis()
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

    fun cancel(): Boolean {
        if (committing) return false
        generation++; job?.cancel(); selectionJob?.cancel(); selectionJob = null
        selectionBusy = false; selectedRoute = null; selectionError = ""; busy = false; stage = ""
        return true
    }
    fun clear(): Boolean {
        if (!cancel()) return false
        selectedRequest = null; baseTrip = null; feedbackId = null; preview = null; choices = emptyList()
        error = ""; start = null; end = null; selectedIds = emptySet()
        return true
    }
    fun supersede(vm: AppViewModel): Boolean {
        if (committing || vm.busy || vm.updatingTrip || vm.restoringTrip) return false
        feedbackId?.let { vm.recordSuggestionDecision(it, "SUPERSEDED", 0, 0) }
        if (ownsEdit) vm.cancelPlanEdit()
        ownsEdit = false; clear()
        return true
    }
    fun dismiss(vm: AppViewModel): Boolean {
        if (committing || vm.busy || vm.updatingTrip || vm.restoringTrip) return false
        feedbackId?.let { vm.recordSuggestionDecision(it, "REJECTED", 0, 0) }
        if (ownsEdit) vm.cancelPlanEdit()
        ownsEdit = false; clear()
        return true
    }

    fun prepareManual(vm: AppViewModel): Boolean {
        if (committing || start == null || end == null || vm.busy || vm.updatingTrip || vm.restoringTrip) return false
        if (vm.editingPlan && !ownsEdit) return false
        vm.beginPlanEdit(); ownsEdit = false
        vm.origin = start; vm.destination = end; vm.mode = mode
        vm.selected = selectedIds; vm.extra = budget; vm.arriveBy = latest; vm.partial = false
        vm.inputsChanged()
        feedbackId?.let { vm.recordSuggestionDecision(it, "MANUAL", selectedIds.size, selectedRoute?.optInt("extra_seconds") ?: 0) }
        clear()
        return true
    }

    fun start(vm: AppViewModel, keyword: String, travelMode: String, knownDestination: Place? = null, draftId: String = java.util.UUID.randomUUID().toString(), planId: String = draftId) {
        if (committing || vm.busy || vm.updatingTrip || vm.restoringTrip) return
        if (ownsEdit && !vm.busy) { vm.cancelPlanEdit(); ownsEdit = false }
        val retainedDestination = end?.takeIf { destinationQuery == keyword && draftIdentity == draftId }
        val restored = restoredChoices?.takeIf { it.draft.draftId == draftId && it.draft.destination == keyword && it.draft.mode == travelMode }
        restoredChoices = null
        clear()
        destinationQuery = keyword
        draftIdentity = draftId
        planIdentity = planId
        restoreTasks = restored?.tasks
        val request = generation
        job = scope.launch {
            busy = true
            try {
                check(!vm.busy && !vm.updatingTrip && !vm.restoringTrip) { "当前行程正在更新，请稍后重试。" }
                check(!vm.editingPlan) { "有尚未完成的行程调整，请先确认或取消。" }
                baseTrip = vm.planningBase()
                stage = "正在获取当前位置…"
                mode = travelMode
                budgetGuidance = vm.planningContext(travelMode).detour
                budget = (restored?.draft?.budget?.coerceIn(0, 240) ?: budgetGuidance.minutes).toString()
                start = vm.assistantOrigin()
                latest = vm.arriveBy
                val resolved = knownDestination ?: retainedDestination ?: restored?.draft?.place()
                if (resolved != null) { end = resolved; calculate(vm); return@launch }
                stage = "正在查找目的地…"
                val familiar = vm.familiarPlace(keyword)
                val city = vm.city.ifBlank { Regex("(?:省)?([^省]+?市)").find(start!!.address)?.groupValues?.get(1).orEmpty() }
                check(familiar != null || city.isNotBlank()) { "无法确定搜索城市，请在设置中填写城市，或手动选择目的地。" }
                val found = if (familiar != null) listOf(familiar) else vm.search(keyword, city)
                check(found.isNotEmpty()) { "没有找到这个地点，请换一种说法或手动选择。" }
                fun normalized(s: String) = s.filter { it.isLetterOrDigit() }
                val exact = found.filter { normalized(it.name) == normalized(keyword) }
                val destination = if (exact.size == 1) exact.single() else found.singleOrNull()
                if (destination == null) { choices = found; stage = clarificationPrompt("DESTINATION"); rememberQuestion("DESTINATION", found) }
                else { end = destination; calculate(vm) }
            } catch (e: TimeoutCancellationException) { restoredChoices = restored; error = "定位超时，请到信号较好的地方重试，或手动调整。" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { restoredChoices = restored; error = networkMessage(e) }
            finally { if (generation == request) busy = false }
        }
    }

    fun choose(vm: AppViewModel, place: Place) {
        if (busy || vm.busy || vm.updatingTrip || vm.restoringTrip) return
        val request = ++generation
        choices = emptyList(); end = place; error = ""
        // Persist the user's resolved destination even if location acquisition fails.
        saveChoices()
        rememberQuestion("CONFIRM_ROUTE")
        job = scope.launch {
            busy = true
            try {
                check(!vm.busy && !vm.updatingTrip && !vm.restoringTrip && !vm.editingPlan) { "行程正在更新，请稍后选择。" }
                if (start == null) {
                    baseTrip = vm.planningBase()
                    stage = "正在获取当前位置…"
                    budgetGuidance = vm.planningContext(mode).detour
                    budget = budgetGuidance.minutes.toString()
                    start = vm.assistantOrigin()
                    latest = vm.arriveBy
                }
                saveChoices()
                rememberQuestion("CONFIRM_ROUTE")
                calculate(vm)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { if (generation == request) busy = false }
        }
    }

    private suspend fun calculate(vm: AppViewModel) {
        stage = "正在计算直达与顺路方案…"
        val pending = vm.tasks.value.filter { it.status == "pending" }
        // The backend owns eligibility, ordering and the six-item window.
        val eligibility = if (pending.isEmpty()) null else vm.routeCandidates(pending, limit = 6)
        val byId = pending.associateBy { it.id }
        candidates = eligibility?.selectedIds.orEmpty().mapNotNull(byId::get)
        note = buildList {
            eligibility?.blocked.orEmpty().groupBy { it.reason }.forEach { (reason, states) ->
                add("${states.joinToString("、") { it.title }}：${routeBlockedLabel(reason)}")
            }
            if ((eligibility?.candidates?.size ?: 0) > 6) {
                add("本次先检查时间较早的 6 件事，其余可在手动调整中添加。")
            }
        }.joinToString("\n")
        val body = JSONObject().put("origin", start!!.json()).put("destination", end!!.json())
            .put("mode", mode).put("tasks", JSONArray(candidates.map { it.json() }))
            .put("preference", vm.preference).put("departure", OffsetDateTime.now().toString())
            .put("allow_partial", true).put("max_extra_minutes", budget.toInt())
            .put("arrive_by", parseTime(latest) ?: JSONObject.NULL)
        val result = Api(vm.server).recommend(body)
        feedbackId?.let { vm.recordSuggestionDecision(it, "SUPERSEDED", 0, 0) }
        feedbackId = null
        previewVersionId = java.util.UUID.randomUUID().toString()
        preview = result
        plannedBudget = budget.toInt()
        calculatedAt = System.currentTimeMillis()
        selectedIds = suggestedIds()
        val suggestion = result.optJSONObject("suggestion")
        selectedRoute = suggestion ?: result.getJSONObject("direct")
        selectedRequest = JSONObject(body.toString()).put("tasks", JSONArray(candidates.filter { it.id in selectedIds }.map { it.json() }))
            .put("allow_partial", false)
        if (suggestion != null) {
            feedbackId = vm.recordSuggestionShown(mode, selectedIds.size, suggestion.optInt("extra_seconds"))
        }
        restoreTasks?.let { saved ->
            // Unknown/new tasks stay unselected on recovery; never silently add errands.
            val ids = saved.filter { it.selected }.map { it.id }.toSet()
                .intersect(candidates.map { it.id }.toSet())
            selectedIds = ids
            selectedRoute = null; selectedRequest = null
            val restoredRequest = JSONObject(body.toString())
                .put("tasks", JSONArray(candidates.filter { it.id in ids }.map { it.json() }))
                .put("allow_partial", false).put("max_extra_minutes", JSONObject.NULL)
            val restoredRoute = Api(vm.server).plan(restoredRequest)
            selectedRequest = restoredRequest.put("max_extra_minutes", budget.toInt())
            selectedRoute = restoredRoute
            previewVersionId = java.util.UUID.randomUUID().toString()
            calculatedAt = System.currentTimeMillis()
            restoreTasks = null
            note = listOf(note, "已恢复上次选择，并按当前位置重新计算，请核对后确认。").filter { it.isNotBlank() }.joinToString("\n")
        }
        saveChoices()
        rememberQuestion("CONFIRM_ROUTE")
        stage = ""
    }

    fun suggestedIds(): Set<String> {
        val stops = preview?.optJSONObject("suggestion")?.optJSONArray("stops") ?: return emptySet()
        return buildSet { for (i in 0 until stops.length()) {
            val ids = stops.getJSONObject(i).getJSONArray("task_ids")
            for (j in 0 until ids.length()) add(ids.getString(j))
        } }
    }

    /** Commit the exact selected preview. Ordinary replanning cannot cancel this write. */
    fun accept(vm: AppViewModel, ids: Set<String>, directBecauseDetour: Boolean = false, done: (JSONObject) -> Unit) {
        if (busy || vm.busy || vm.updatingTrip || vm.restoringTrip) return
        if (!canConfirm || ids != selectedIds) { error = "请等待所选方案计算完成；超出预算时请减少事项或手动调整预算。"; return }
        if (preview == null || start == null || end == null) return
        if (vm.editingPlan && !ownsEdit) { error = "请先完成顺路页的行程调整，再确认建议。"; return }
        if (System.currentTimeMillis() - calculatedAt > 5 * 60_000) { error = "方案已超过五分钟，请重新定位并计算。"; return }
        if (ids.any { id -> candidates.none { it.id == id } || candidates.find { it.id == id } != vm.tasks.value.find { it.id == id && it.status == "pending" } }) {
            error = "待办已发生变化，请重新计算后确认。"; return
        }
        val request = selectedRequest ?: return
        val route = selectedRoute ?: return
        val snapshot = ConfirmedRouteSnapshot(previewVersionId, request.toString(), route.toString(), baseTrip,
            candidates.filter { it.id in ids }, calculatedAt, planId = planIdentity, draftId = draftIdentity)
        val traceId = feedbackId
        val suggestedAtConfirmation = suggestedIds()
        committing = true; busy = true; error = ""; stage = "正在保存所选方案…"
        // Do not share the cancellable calculation job: Room may already have committed.
        scope.launch {
            try {
                val result = vm.confirmAssistantRoute(snapshot)
                traceId?.let {
                    val choice = when {
                        ids.isEmpty() -> if (directBecauseDetour) "DIRECT_TIME" else "DIRECT"
                        ids == suggestedAtConfirmation -> "ACCEPTED"
                        else -> "ADJUSTED"
                    }
                    vm.recordSuggestionDecision(it, choice, ids.size, result.optInt("extra_seconds"),
                        vm.routeSession?.id.orEmpty())
                }
                ownsEdit = false
                // Do not cancel this coroutine while its completion callback is still pending.
                feedbackId = null; preview = null; selectedRoute = null; selectedRequest = null
                choices = emptyList(); start = null; end = null; selectedIds = emptySet()
                done(result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { committing = false; busy = false; stage = "" }
        }
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
