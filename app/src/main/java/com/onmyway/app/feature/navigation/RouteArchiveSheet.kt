package com.onmyway.app.feature.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.format.displayTimestamp
import com.onmyway.app.core.format.formatDuration
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.AppBottomSheet
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppKicker
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.BottomSheetTitle
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.feature.planning.RouteMapPreview
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

private data class ArchiveRoute(
    val sessionId: String,
    val timestamp: Long,
    val state: String,
    val request: JSONObject,
    val result: JSONObject,
    val event: RouteHistory?,
    val planId: String = event?.planId.orEmpty(),
)

private fun routeLabel(state: String): String = when (state) {
    "READY" -> "待出发"
    "NAVIGATION_OPENED" -> "导航中"
    "ARRIVED" -> "已到达"
    "CANCELLED" -> "已结束"
    "REPLANNING" -> "已替换的方案"
    else -> "历史行程"
}

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
    var showVersions by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<List<RouteHistory>>(emptyList()) }
    var versionsError by remember { mutableStateOf(false) }
    LaunchedEffect(selected?.sessionId, showVersions) {
        versions = emptyList(); versionsError = false
        if (showVersions) selected?.let { route ->
            try { versions = vm.routeVersions(route.sessionId, route.planId).distinctBy { it.requestJson to it.resultJson } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { versionsError = true }
        }
    }
    var missingLinkedRoute by remember { mutableStateOf(false) }
    LaunchedEffect(initialEventId) {
        if (initialEventId != null) {
            try {
                selected = vm.routeHistoryById(initialEventId)?.let { event ->
                    runCatching {
                        ArchiveRoute(
                            event.sessionId, event.timestamp, event.state,
                            JSONObject(event.requestJson), JSONObject(event.resultJson), event,
                        )
                    }.getOrNull()
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
        runCatching {
            ArchiveRoute(
                session.id, session.plannedAt, session.state.name,
                JSONObject(session.requestJson), JSONObject(session.routeJson), null,
                vm.planningBase()?.planId.orEmpty(),
            )
        }.getOrNull()
    }
    val past = events.orEmpty().distinctBy { it.planId.ifBlank { it.sessionId } }
        .filter { it.sessionId != current?.sessionId && (it.planId.isBlank() || it.planId != current?.planId) }
        .mapNotNull { event ->
            runCatching {
                ArchiveRoute(
                    event.sessionId, event.timestamp, event.state,
                    JSONObject(event.requestJson), JSONObject(event.resultJson), event,
                )
            }.getOrNull()
        }
    AppBottomSheet(onDismissRequest = dismiss) { close ->
        Column(
            Modifier.fillMaxHeight(.9f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected == null) {
                BottomSheetTitle("路线记录", close, subtitle = "旧方案只供查看")
                if (events == null && !loadError) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在读取路线记录…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (loadError) Text("行程记录读取失败，请关闭后重试。", color = MaterialTheme.colorScheme.error)
                if (missingLinkedRoute) Text(
                    "这条旧消息没有可读取的路线快照，可在下方查找其他行程。",
                    color = MaterialTheme.colorScheme.error,
                )
                val routes = listOfNotNull(current) + past
                if (events != null && routes.isEmpty()) {
                    Text(
                        "还没有保存的路线。",
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                routes.forEach { route ->
                    val destination = route.request.optJSONObject("destination")?.optString("name").orEmpty()
                    val origin = route.request.optJSONObject("origin")?.optString("name").orEmpty()
                    // 普通记录使用紧凑列表行：目的地＋时间＋状态，整行可点。
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { selected = route }
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                if (origin.isBlank()) destination else "$origin → $destination",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${displayTimestamp(route.timestamp)} · ${if (route.request.optString("mode") == "cycling") "骑行" else "步行"} · ${routeLabel(route.state)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            } else {
                val route = checkNotNull(selected)
                TextButton(
                    onClick = { selected = null; showVersions = false },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("返回路线记录") }
                AppKicker("ROUTE ARCHIVE")
                Text(
                    route.request.optJSONObject("destination")?.optString("name").orEmpty(),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "${displayTimestamp(route.timestamp)} · ${routeLabel(route.state)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val stops = route.result.optJSONArray("stops")
                AppCard(contentPadding = PaddingValues(16.dp)) {
                    val total = route.result.optInt("total_seconds", -1)
                    // Measured time/distance stays in navigation_metrics for later
                    // route selection; the archive shows only the original estimate.
                    if (total >= 0) Text(
                        "当时预计 ${formatDuration(total)} · ${if (route.request.optString("mode") == "cycling") "骑行" else "步行"}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (stops != null) {
                        val names = (0 until stops.length()).mapNotNull { i ->
                            stops.optJSONObject(i)?.optJSONObject("place")?.optString("name")?.takeIf(String::isNotBlank)
                        }
                        Text(
                            if (names.isEmpty()) "当时为直达路线" else "当时停靠：" + names.joinToString(" → "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (route.result.optJSONArray("legs") != null && route.result.optJSONObject("origin") != null &&
                    route.result.optJSONObject("destination") != null && stops != null) RouteMapPreview(route.result)
                if (route.event == null) {
                    AppPrimaryButton(
                        "查看当前路线",
                        onClick = { close(); openCurrent() },
                        enabled = vm.result != null && !vm.editingPlan,
                    )
                } else {
                    // 使用限制紧挨重新规划按钮，避免在页面顶部重复说明。
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "历史路线不能直接继续导航；再次出发会从当前位置重新定位、算路并筛选待办。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AppPrimaryButton(
                            "从当前位置重新规划",
                            onClick = { close(); repeatRoute(route.event) },
                            enabled = vm.result == null && !vm.busy && !vm.restoringTrip && !vm.updatingTrip,
                        )
                    }
                }
                TextButton({ showVersions = !showVersions }) {
                    Text(if (showVersions) "收起修改记录" else "查看修改记录")
                }
                if (showVersions) {
                    if (versionsError) Text("修改记录读取失败，请收起后重试。", color = MaterialTheme.colorScheme.error)
                    // 修改记录直接展开为时间列表。
                    Column {
                        versions.forEach { event ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selected = ArchiveRoute(
                                            event.sessionId, event.timestamp, event.state,
                                            JSONObject(event.requestJson), JSONObject(event.resultJson), event,
                                        )
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    displayTimestamp(event.timestamp) + " · " +
                                        JSONObject(event.requestJson).optJSONObject("destination")?.optString("name").orEmpty(),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}
