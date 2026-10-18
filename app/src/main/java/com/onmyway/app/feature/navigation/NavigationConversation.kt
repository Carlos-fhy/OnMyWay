package com.onmyway.app.feature.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.format.formatDistance
import com.onmyway.app.core.format.formatDuration
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.state.networkMessage
import com.onmyway.app.core.ui.AppBottomSheet
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.BottomSheetTitle
import com.onmyway.app.core.ui.SectionLabel
import com.onmyway.app.core.ui.appTextFieldColors
import com.onmyway.app.core.voice.CloudVoiceButton
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.remote.assistantTripContext
import com.onmyway.app.feature.planning.selectRouteTasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal fun navigationSelection(text: String, tasks: List<Errand>): Set<String>? {
    val clean = text.trim().trimEnd('。', '！', '!')
    if (clean in listOf("直接走", "直接去目的地", "不办事", "只去目的地")) return emptySet()
    // Only removal from this trip; never mark the underlying task complete.
    if (!clean.startsWith("不")) return null
    return selectRouteTasks(clean, tasks, tasks.map { it.id }.toSet())
}

internal fun spokenPlaceIndex(text: String): Int? {
    val clean = text.trim().trimEnd('。', '！', '!')
    val value = Regex("(?:选|选择)?第?([一二三四五六七八九十]|10|[1-9])个").matchEntire(clean)?.groupValues?.get(1) ?: return null
    return (value.toIntOrNull() ?: ("一二三四五六七八九十".indexOf(value) + 1)) - 1
}

internal class NavigationConversationState {
    var input by mutableStateOf("")
    var draft by mutableStateOf<NavigationChange?>(null)
    var proposedBody: JSONObject? = null
    var pendingNewTask: Errand? = null
    var places by mutableStateOf<List<Place>>(emptyList())
    var pendingInstruction by mutableStateOf("")
    var pendingIds: Set<String>? = null
    var pendingChoiceNewTask: Errand? = null
    var history by mutableStateOf(listOf<Pair<String, String>>())
    var persist: (() -> Unit)? = null

    fun record(role: String, text: String) {
        history = (history + (role to text.take(4000))).takeLast(12)
        persist?.invoke()
    }
    fun setProposal(body: JSONObject?, newTask: Errand?) {
        proposedBody = body
        pendingNewTask = newTask
        persist?.invoke()
    }
    fun setChoices(options: List<Place>, keep: Set<String>, instruction: String, newTask: Errand?) {
        places = options.take(10)
        pendingIds = keep
        pendingChoiceNewTask = newTask
        pendingInstruction = instruction
        persist?.invoke()
    }
    fun invalidatePreview() {
        draft = null; places = emptyList(); pendingInstruction = ""; pendingIds = null; pendingChoiceNewTask = null
    }
    fun clear() {
        input = ""; invalidatePreview(); proposedBody = null; pendingNewTask = null; history = emptyList()
        persist?.invoke()
    }
    fun snapshot(trip: SavedTrip): JSONObject = JSONObject()
        .put("session_id", trip.sessionId).put("request_json", trip.requestJson)
        .put("base_fingerprint", assistantTripContext(trip))
        .put("saved_at", System.currentTimeMillis())
        .put("history", JSONArray(history.map { (role, content) ->
            JSONObject().put("role", role).put("content", content)
        }))
        .put("proposed_body", proposedBody ?: JSONObject.NULL)
        .put("pending_new_task", pendingNewTask?.let { task ->
            JSONObject().put("id", task.id).put("title", task.title)
                .put("place_type", task.placeType).put("service_minutes", task.serviceMinutes)
                .put("place_json", task.placeJson)
        } ?: JSONObject.NULL)
        .put("places", JSONArray(places.map(Place::json)))
        .put("pending_instruction", pendingInstruction)
        .put("pending_ids", pendingIds?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
        .put("pending_choice_new_task", pendingChoiceNewTask?.let { task ->
            JSONObject().put("id", task.id).put("title", task.title)
                .put("place_type", task.placeType).put("service_minutes", task.serviceMinutes)
                .put("place_json", task.placeJson)
        } ?: JSONObject.NULL)

    fun restore(snapshot: JSONObject, trip: SavedTrip): Boolean {
        val savedAt = snapshot.optLong("saved_at")
        val now = System.currentTimeMillis()
        if (snapshot.optString("base_fingerprint") != assistantTripContext(trip) ||
            snapshot.optString("session_id") != trip.sessionId ||
            snapshot.optString("request_json") != trip.requestJson ||
            savedAt <= 0 || now < savedAt || now - savedAt > 1_800_000L) return false
        val entries = snapshot.optJSONArray("history") ?: JSONArray()
        history = (0 until entries.length()).mapNotNull { index ->
            val item = entries.optJSONObject(index) ?: return@mapNotNull null
            val role = item.optString("role")
            if (role !in listOf("user", "assistant")) null
            else role to item.optString("content").take(4000)
        }.takeLast(12)
        proposedBody = snapshot.optJSONObject("proposed_body")
        pendingNewTask = snapshot.optJSONObject("pending_new_task")?.let {
            Errand(id = it.getString("id"), title = it.getString("title"),
                placeType = it.getString("place_type"), serviceMinutes = it.getInt("service_minutes"),
                placeJson = it.optString("place_json"))
        }
        places = snapshot.optJSONArray("places")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(Place::from)
            }.take(10)
        } ?: emptyList()
        pendingInstruction = snapshot.optString("pending_instruction")
        pendingIds = snapshot.optJSONArray("pending_ids")?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        pendingChoiceNewTask = snapshot.optJSONObject("pending_choice_new_task")?.let {
            Errand(id = it.getString("id"), title = it.getString("title"),
                placeType = it.getString("place_type"), serviceMinutes = it.getInt("service_minutes"),
                placeJson = it.optString("place_json"))
        }
        // Never restore an executable preview: its route and origin must be checked again.
        draft = null
        return true
    }
}

@Composable
internal fun NavigationConversation(vm: AppViewModel, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val state = vm.navigationConversation
    var input by state::input
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var draft by state::draft
    var places by state::places
    var pendingInstruction by state::pendingInstruction
    val pendingRequest = vm.pendingNavigationRequest
    fun cancelAdjustment() {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { vm.cancelNavigationAdjustment(); dismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
    }
    fun recoverRequest() {
        if (busy) return
        busy = true; error = ""; progress = "正在取回上次调整结果"
        scope.launch {
            try { vm.recoverNavigationRequest { progress = it }?.let { draft = it } }
            catch (_: NavigationDestinationChoice) { /* Choices were committed with the delivered result. */ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
    }
    fun recalculatePending() {
        if (busy || vm.pendingNavigationRequest != null) return
        busy = true; error = ""; progress = "正在重新定位并计算方案"
        scope.launch {
            try { draft = vm.recalculateNavigationProposal() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
    }
    fun confirm(change: NavigationChange) {
        if (busy || vm.pendingNavigationRequest != null) return
        busy = true; error = ""; progress = "正在保存并更新导航"
        scope.launch {
            try { vm.confirmNavigationChange(change); dismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e); draft = null }
            finally { busy = false }
        }
    }
    fun send(text: String, place: Place? = null) {
        if (busy || text.isBlank()) return
        val clean = text.trim().trimEnd('。', '！', '!')
        if (clean in listOf("取消", "保留原路线", "不改了")) { cancelAdjustment(); return }
        if (vm.pendingNavigationRequest != null) {
            if (clean in listOf("继续", "重试", "继续获取结果")) recoverRequest()
            else error = "请先取回上次结果，或重新编辑上次指令。"
            return
        }
        if (clean in listOf("确认", "确认修改", "确认并更新导航")) {
            val current = draft
            if (current != null) confirm(current) else error = "还没有待确认的新方案，请先告诉我怎么调整。"
            return
        }
        val index = spokenPlaceIndex(text)
        val chosen = place ?: index?.let { places.getOrNull(it) }
        if (index != null && chosen == null) { error = "没有这个选项，请说列表中的序号。"; return }
        focus.clearFocus()
        val instruction = if (chosen != null && pendingInstruction.isNotBlank()) pendingInstruction else text
        input = text; error = ""; busy = true; progress = "正在处理调整"
        scope.launch {
            try { draft = vm.previewNavigationChange(instruction, chosen) { progress = it }; places = emptyList(); state.pendingIds = null }
            catch (e: NavigationDestinationChoice) { state.setChoices(e.places, e.keepIds, instruction, e.newTask) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
    }
    AppBottomSheet(onDismissRequest = { if (!busy) dismiss() }) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BottomSheetTitle(
                "调整这趟路线",
                onClose = { if (!busy) dismiss() },
                subtitle = "说出调整，看到方案后再确认",
            )
            Text(
                "按住说话，松开自动处理。可说“附近顺路买瓶水”“不取快递了”，看到方案后说“确认”或“取消”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pendingRequest != null) {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconBadge(R.drawable.ic_clock, size = 34.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("上次调整尚未完成", style = MaterialTheme.typography.titleMedium)
                            Text(
                                pendingRequest.userText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        vm.navigationRecoveryMessage.ifBlank { "可以继续获取结果；当前路线会保持到你确认新方案。" },
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppPrimaryButton("继续获取结果", onClick = ::recoverRequest, enabled = !busy)
                    TextButton(onClick = {
                        if (!busy) {
                            busy = true; error = ""
                            scope.launch {
                                try { vm.editNavigationRequest() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = networkMessage(e) }
                                finally { busy = false }
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("重新编辑") }
                }
            }
            OutlinedTextField(
                input,
                { input = it },
                enabled = !busy && pendingRequest == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                label = { Text("告诉我怎么调整") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = appTextFieldColors(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    CloudVoiceButton(vm.server, !busy, { send(it) }, { error = it })
                }
                Button(
                    { send(input) },
                    modifier = Modifier.heightIn(min = 56.dp),
                    enabled = !busy && pendingRequest == null && input.isNotBlank(),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("发送") }
            }
            if (busy) {
                AppCard(contentPadding = PaddingValues(14.dp)) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    Text(
                        progress,
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (pendingRequest == null && draft == null && state.proposedBody != null) {
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    Text("有一份未确认的调整方案。请重新计算当前位置和路线，检查后再确认。")
                    OutlinedButton(
                        onClick = ::recalculatePending,
                        enabled = !busy,
                        modifier = Modifier.padding(top = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("重新计算上次方案") }
                }
            }
            if (pendingRequest == null && places.isNotEmpty()) {
                SectionLabel("请选择新的目的地，可以说“第一个”")
            }
            (if (pendingRequest == null) places else emptyList()).forEachIndexed { index, place ->
                Surface(
                    onClick = { send(input, place) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconBadge(
                            R.drawable.ic_place_pin,
                            size = 34.dp,
                            tint = MaterialTheme.colorScheme.tertiary,
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${index + 1}. ${place.name}", style = MaterialTheme.typography.titleSmall)
                            if (place.address.isNotBlank()) Text(
                                place.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            draft?.takeIf { pendingRequest == null }?.let { change ->
                val route = change.route
                val stops = route.getJSONArray("stops")
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconBadge(R.drawable.ic_route, size = 36.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("调整后方案", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "终点：${route.getJSONObject("destination").getString("name")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "调整后约 ${formatDuration(route.getInt("total_seconds"))} · ${formatDistance(route.optInt("total_meters"))}",
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "比直达多 ${formatDuration(route.optInt("extra_seconds"))} · ${formatDistance(route.optInt("extra_meters"))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    change.addedTask?.let {
                        Text(
                            "新增事项：${it.title}。确认后才会保存到待办。",
                            Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (stops.length() == 0) Text(
                        "直接前往终点",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    ) else {
                        SectionLabel("此次路线停靠点")
                        for (i in 0 until stops.length()) Text(
                            "${i + 1}. ${stops.getJSONObject(i).getJSONObject("place").getString("name")}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val proposedIds = change.body.getJSONArray("tasks").let { tasks ->
                        (0 until tasks.length()).map { tasks.getJSONObject(it).getString("id") }.toSet()
                    }
                    val removed = JSONObject(change.previous.requestJson).getJSONArray("tasks").let { tasks ->
                        (0 until tasks.length()).map { tasks.getJSONObject(it) }
                            .filter { it.getString("id") !in proposedIds }.map { it.getString("title") }
                    }
                    if (removed.isNotEmpty()) Text(
                        "移出本次路线：${removed.joinToString("、")}（仍保留在待办中）",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppPrimaryButton(
                        "确认并更新导航",
                        onClick = { confirm(change) },
                        enabled = !busy,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            if (error.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        error,
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            TextButton(
                onClick = ::cancelAdjustment,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保留原路线") }
        }
    }
}

/** A confirmation always commits the immutable route and request that were previewed. */
internal data class NavigationChange(
    val previous: SavedTrip,
    val bodyJson: String,
    val routeJson: String,
    val tasks: List<Errand>,
    val addedTask: Errand?,
    val createdAt: Long,
    val versionId: String,
) {
    constructor(previous: SavedTrip, body: JSONObject, route: JSONObject, tasks: List<Errand>,
        addedTask: Errand? = null, createdAt: Long = System.currentTimeMillis(),
        versionId: String = java.util.UUID.randomUUID().toString()) :
        this(previous, body.toString(), route.toString(), tasks.toList(), addedTask, createdAt, versionId)

    val body: JSONObject get() = JSONObject(bodyJson)
    val route: JSONObject get() = JSONObject(routeJson)
}

internal class NavigationDestinationChoice(val places: List<Place>, val keepIds: Set<String>, val newTask: Errand? = null) : Exception()
