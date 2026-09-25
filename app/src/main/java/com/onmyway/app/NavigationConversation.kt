package com.onmyway.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

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
        if (snapshot.optString("session_id") != trip.sessionId ||
            snapshot.optString("request_json") != trip.requestJson ||
            System.currentTimeMillis() - snapshot.optLong("saved_at") !in 0..1_800_000L) return false
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationConversation(vm: AppViewModel, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val state = vm.navigationConversation
    var input by state::input
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var draft by state::draft
    var places by state::places
    var pendingInstruction by state::pendingInstruction
    fun recalculatePending() {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { draft = vm.recalculateNavigationProposal() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
    }
    fun confirm(change: NavigationChange) {
        if (busy) return
        busy = true; error = ""
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
        if (clean in listOf("取消", "保留原路线", "不改了")) { state.clear(); dismiss(); return }
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
        input = text; error = ""; busy = true
        scope.launch {
            try { draft = vm.previewNavigationChange(instruction, chosen); places = emptyList(); state.pendingIds = null }
            catch (e: NavigationDestinationChoice) { state.setChoices(e.places, e.keepIds, instruction, e.newTask) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = networkMessage(e) }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("调整这趟路线", style = MaterialTheme.typography.titleLarge)
            Text("按住说话，松开自动处理。可说“附近顺路买瓶水”“不取快递了”，看到方案后说“确认”或“取消”。")
            OutlinedTextField(input, { input = it }, enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                label = { Text("告诉我怎么调整") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                CloudVoiceButton(vm.server, !busy, { send(it) }, { error = it })
                }
                Button({ send(input) }, modifier = Modifier.heightIn(min = 56.dp), enabled = !busy && input.isNotBlank()) { Text("发送") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (draft == null && state.proposedBody != null) {
                Text("有一份未确认的调整方案。请重新计算当前位置和路线，检查后再确认。")
                OutlinedButton(onClick = ::recalculatePending, enabled = !busy) { Text("重新计算上次方案") }
            }
            if (places.isNotEmpty()) Text("请选择新的目的地，可以说“第一个”")
            places.forEachIndexed { index, place ->
                OutlinedButton({ send(input, place) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Column { Text("${index + 1}. ${place.name}"); Text(place.address, style = MaterialTheme.typography.bodySmall) }
                }
            }
            draft?.let { change ->
                val route = change.route
                Text("调整后约 ${formatDuration(route.getInt("total_seconds"))} · ${formatDistance(route.optInt("total_meters"))}")
                Text("比直达多 ${formatDuration(route.optInt("extra_seconds"))} · ${formatDistance(route.optInt("extra_meters"))}")
                change.addedTask?.let { Text("新增事项：${it.title}。确认后才会保存到待办。") }
                val stops = route.getJSONArray("stops")
                Text("终点：${route.getJSONObject("destination").getString("name")}")
                if (stops.length() == 0) Text("直接前往终点")
                else Text("此次路线停靠点：")
                for (i in 0 until stops.length()) Text("${i + 1}. ${stops.getJSONObject(i).getJSONObject("place").getString("name")}")
                val proposedIds = change.body.getJSONArray("tasks").let { tasks ->
                    (0 until tasks.length()).map { tasks.getJSONObject(it).getString("id") }.toSet()
                }
                val removed = JSONObject(change.previous.requestJson).getJSONArray("tasks").let { tasks ->
                    (0 until tasks.length()).map { tasks.getJSONObject(it) }
                        .filter { it.getString("id") !in proposedIds }.map { it.getString("title") }
                }
                if (removed.isNotEmpty()) Text("移出本次路线：${removed.joinToString("、")}（仍保留在待办中）",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirm(change) }, enabled = !busy) { Text("确认并更新导航") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton({ state.clear(); dismiss() }, enabled = !busy) { Text("保留原路线") }
        }
    }
}

internal data class NavigationChange(val previous: SavedTrip, val body: JSONObject,
    val route: JSONObject, val tasks: List<Errand>, val addedTask: Errand? = null,
    val createdAt: Long = System.currentTimeMillis())

internal class NavigationDestinationChoice(val places: List<Place>, val keepIds: Set<String>, val newTask: Errand? = null) : Exception()
