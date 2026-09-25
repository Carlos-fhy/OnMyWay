package com.onmyway.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

private data class ArchiveRoute(
    val sessionId: String,
    val timestamp: Long,
    val state: String,
    val request: JSONObject,
    val result: JSONObject,
    val event: RouteHistory?,
)

private fun routeLabel(state: String): String = when (state) {
    "READY" -> "待出发"
    "NAVIGATION_OPENED" -> "导航中"
    "ARRIVED" -> "已到达"
    "CANCELLED" -> "已结束"
    "REPLANNING" -> "已替换的方案"
    else -> "历史行程"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteArchiveSheet(
    vm: AppViewModel,
    initialEventId: String? = null,
    dismiss: () -> Unit,
    repeatRoute: (RouteHistory) -> Unit,
    openCurrent: () -> Unit,
) {
    var events by remember { mutableStateOf<List<RouteHistory>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ArchiveRoute?>(null) }
    var missingLinkedRoute by remember { mutableStateOf(false) }
    LaunchedEffect(initialEventId) {
        if (initialEventId != null) {
            try {
                selected = vm.routeHistoryById(initialEventId)?.let { event ->
                    runCatching { ArchiveRoute(event.sessionId, event.timestamp, event.state,
                        JSONObject(event.requestJson), JSONObject(event.resultJson), event) }.getOrNull()
                }
                missingLinkedRoute = selected == null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { missingLinkedRoute = true }
        }
    }
    LaunchedEffect(Unit) {
        try { events = vm.routeHistory() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = true }
    }
    val current = vm.routeSession?.let { session ->
        runCatching { ArchiveRoute(session.id, session.plannedAt, session.state.name,
            JSONObject(session.requestJson), JSONObject(session.routeJson), null) }.getOrNull()
    }
    val past = events.orEmpty().distinctBy { it.sessionId }.filter { it.sessionId != current?.sessionId }
        .mapNotNull { event ->
            runCatching { ArchiveRoute(event.sessionId, event.timestamp, event.state,
                JSONObject(event.requestJson), JSONObject(event.resultJson), event) }.getOrNull()
        }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected == null) {
                Text("路线记录", style = MaterialTheme.typography.headlineSmall)
                Text("旧方案只供查看；再次出发会从当前位置重新定位、重新算路和筛选待办。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (events == null && !loadError) CircularProgressIndicator()
                if (loadError) Text("行程记录读取失败，请关闭后重试。", color = MaterialTheme.colorScheme.error)
                if (missingLinkedRoute) Text("这条旧消息没有可读取的路线快照，可在下方查找其他行程。",
                    color = MaterialTheme.colorScheme.error)
                val routes = listOfNotNull(current) + past
                if (events != null && routes.isEmpty()) Text("还没有保存的路线。")
                routes.forEach { route ->
                    val destination = route.request.optJSONObject("destination")?.optString("name").orEmpty()
                    val origin = route.request.optJSONObject("origin")?.optString("name").orEmpty()
                    OutlinedCard(onClick = { selected = route }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("$origin → $destination", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${displayTimestamp(route.timestamp)} · ${if (route.request.optString("mode") == "cycling") "骑行" else "步行"} · ${routeLabel(route.state)}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("查看当时路线与停靠点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                val route = checkNotNull(selected)
                TextButton(onClick = { selected = null }) { Text("返回路线记录") }
                Text(route.request.optJSONObject("destination")?.optString("name").orEmpty(), style = MaterialTheme.typography.headlineSmall)
                Text("${displayTimestamp(route.timestamp)} · ${routeLabel(route.state)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val total = route.result.optInt("total_seconds", -1)
                if (total >= 0) Text("当时预计 ${formatDuration(total)} · ${if (route.request.optString("mode") == "cycling") "骑行" else "步行"}")
                val stops = route.result.optJSONArray("stops")
                if (stops != null) {
                    val names = (0 until stops.length()).mapNotNull { i ->
                        stops.optJSONObject(i)?.optJSONObject("place")?.optString("name")?.takeIf(String::isNotBlank)
                    }
                    Text(if (names.isEmpty()) "当时为直达路线" else "当时停靠：" + names.joinToString(" → "))
                }
                if (route.result.optJSONArray("legs") != null && route.result.optJSONObject("origin") != null &&
                    route.result.optJSONObject("destination") != null && stops != null) RouteMapPreview(route.result)
                if (route.event == null) {
                    Button(onClick = { dismiss(); openCurrent() }, enabled = vm.result != null && !vm.editingPlan) { Text("查看当前路线") }
                } else {
                    Text("历史路线不能直接继续导航。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { dismiss(); repeatRoute(route.event) },
                        enabled = vm.result == null && !vm.busy && !vm.restoringTrip && !vm.updatingTrip) {
                        Text("从当前位置重新规划")
                    }
                }
            }
        }
    }
}
