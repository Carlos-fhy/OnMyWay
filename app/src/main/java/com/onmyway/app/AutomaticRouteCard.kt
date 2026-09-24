package com.onmyway.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.ceil

@Composable
internal fun AutomaticRouteCard(auto: AutomaticRoutePlanner, vm: AppViewModel, retry: () -> Unit,
    manual: () -> Unit, cancel: () -> Unit, accepted: (JSONObject) -> Unit) {
    var details by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (auto.busy || vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (vm.busy) "正在确认并更新行程…" else auto.stage, style = MaterialTheme.typography.bodyMedium)
            }
            auto.choices.forEach { place ->
                OutlinedButton({ auto.choose(vm, place) }, enabled = !auto.busy && !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Column { Text(place.name); if (place.address.isNotBlank()) Text(place.address, style = MaterialTheme.typography.bodySmall) }
                }
            }
            auto.preview?.let { preview ->
                val direct = preview.getJSONObject("direct")
                val suggested = preview.optJSONObject("suggestion")
                Text(Place.from(direct.getJSONObject("destination")).name, style = MaterialTheme.typography.titleLarge)
                Text("直达约 ${ceil(direct.getInt("total_seconds") / 60.0).toInt()} 分钟")
                Text("${if (direct.getString("mode") == "cycling") "骑行" else "步行"} · ${direct.optInt("total_meters")} 米 · 返回候选中距离最短", style = MaterialTheme.typography.bodySmall)
                Text("顺路预算：最多多花 ${auto.budget} 分钟（含办事，可调整）", style = MaterialTheme.typography.bodySmall)
                val assessments = preview.optJSONArray("assessments")
                if (assessments != null && assessments.length() > 0) {
                    Text("选择这次要办的事", style = MaterialTheme.typography.labelLarge)
                    Text("默认不选，确认后才会加入路线。", style = MaterialTheme.typography.bodySmall)
                    for (i in 0 until assessments.length()) {
                        val entry = assessments.getJSONObject(i)
                        val id = entry.getString("task_id")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = id in auto.selectedIds,
                            onCheckedChange = { auto.toggle(vm, id) },
                            enabled = !auto.busy && !vm.busy && !entry.isNull("extra_seconds"))
                        Column(Modifier.weight(1f)) {
                        Text(if (entry.isNull("extra_seconds")) "${entry.getString("title")}：${entry.getString("reason")}"
                            else "${entry.getString("title")} · 多 ${entry.optInt("extra_meters")} 米 / 约 ${ceil(entry.getInt("extra_seconds") / 60.0).toInt()} 分钟 · ${if (entry.getBoolean("within_budget")) "预算内" else "超出预算"}",
                            style = MaterialTheme.typography.bodyMedium)
                        if (id in auto.suggestedIds()) Text("推荐", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        }
                    }
                    Text("单项耗时不能直接相加，组合方案会重新算路。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (auto.selectionBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在计算所选 ${auto.selectedIds.size} 件事的组合路线…")
                }
                if (auto.selectionError.isNotBlank()) {
                    Text(auto.selectionError, color = MaterialTheme.colorScheme.error)
                    TextButton({ auto.select(vm, auto.selectedIds) }, enabled = !vm.busy) { Text("重试所选方案") }
                }
                auto.selectedRoute?.let { route ->
                    Text(if (auto.selectedIds.isEmpty()) "当前选择：直达" else "当前选择：${auto.selectedIds.size} 件事", style = MaterialTheme.typography.titleMedium)
                    Text("全程 ${route.optInt("total_meters")} 米 · 约 ${ceil(route.getInt("total_seconds") / 60.0).toInt()} 分钟")
                    Text("比直达多 ${route.optInt("extra_meters")} 米 / 约 ${ceil(route.getInt("extra_seconds") / 60.0).toInt()} 分钟（含办事）", style = MaterialTheme.typography.bodySmall)
                    if (route.getInt("extra_seconds") > auto.budget.toInt() * 60) Text("所选组合超出预算，请减少事项或手动调整预算。", color = MaterialTheme.colorScheme.error)
                    RouteMapPreview(route)
                    val stops = route.getJSONArray("stops")
                    for (i in 0 until stops.length()) {
                        val stop = stops.getJSONObject(i)
                        val titles = stop.getJSONArray("task_titles")
                        Text((0 until titles.length()).joinToString("、") { titles.getString(it) } + " · " + Place.from(stop.getJSONObject("place")).name)
                    }
                }
                if (auto.note.isNotBlank()) Text(auto.note, style = MaterialTheme.typography.bodySmall)
                Button({ auto.accept(vm, auto.selectedIds, accepted) }, enabled = !vm.busy && auto.canConfirm) {
                    Text(if (auto.selectedIds.isEmpty()) "确认直达" else "确认所选 ${auto.selectedIds.size} 件事并规划")
                }
                Text("按所选事项重新计算组合路线；若超出预算，会提示调整。", style = MaterialTheme.typography.bodySmall)
                TextButton({ details = !details }) { Text(if (details) "收起说明" else "查看说明") }
                if (details) {
                    if (preview.optString("note").isNotBlank()) Text(preview.getString("note"), style = MaterialTheme.typography.bodySmall)
                    suggested?.let { route ->
                        val omitted = route.getJSONArray("omitted")
                        for (i in 0 until omitted.length()) Text(omitted.getJSONObject(i).let { "${it.getString("title")}：${it.getString("reason")}" }, style = MaterialTheme.typography.bodySmall)
                        val warnings = route.getJSONArray("warnings")
                        for (i in 0 until warnings.length()) Text(warnings.getString(i), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (auto.error.isNotBlank()) Text(auto.error, color = MaterialTheme.colorScheme.error)
            if (vm.message.isNotBlank()) Text(vm.message, style = MaterialTheme.typography.bodySmall)
            if (!auto.busy && !vm.busy) Row {
                TextButton(retry) { Text("重新计算") }
                TextButton(manual) { Text("手动调整") }
                TextButton(cancel) { Text("取消") }
            }
        }
    }
}
