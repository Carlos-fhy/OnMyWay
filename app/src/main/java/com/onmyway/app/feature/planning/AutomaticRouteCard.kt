package com.onmyway.app.feature.planning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.format.formatDistance
import com.onmyway.app.core.format.formatDuration
import com.onmyway.app.core.memory.GuidanceSource
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppPill
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.SectionLabel
import com.onmyway.app.core.ui.appTextFieldColors
import com.onmyway.app.feature.assistant.clarificationPrompt
import kotlin.math.ceil
import org.json.JSONObject

@Composable
internal fun AutomaticRouteCard(auto: AutomaticRoutePlanner, vm: AppViewModel, retry: () -> Unit,
    manual: () -> Unit, cancel: () -> Unit, accepted: (JSONObject) -> Unit,
    canAct: () -> Boolean = { true }) {
    var details by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf(false) }
    var customBudget by remember { mutableStateOf("") }
    var directBecauseDetour by remember(auto.preview) { mutableStateOf(false) }
    AppCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBadge(R.drawable.ic_route, size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("顺路建议", style = MaterialTheme.typography.titleMedium)
                Text(
                    "先看方案，再决定",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (auto.busy || vm.busy) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.height(6.dp))
            Text(if (vm.busy) "正在确认并更新行程…" else auto.stage, style = MaterialTheme.typography.bodyMedium)
        }
        if (auto.choices.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(clarificationPrompt("DESTINATION"), style = MaterialTheme.typography.bodyMedium)
        }
        auto.choices.forEachIndexed { index, place ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                { if (canAct()) auto.choose(vm, place) },
                enabled = !auto.busy && !vm.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text("${index + 1}. ${place.name}")
                    if (place.address.isNotBlank()) Text(
                        place.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        auto.preview?.let { preview ->
            val direct = preview.getJSONObject("direct")
            val suggested = preview.optJSONObject("suggestion")
            Spacer(Modifier.height(12.dp))
            Text(
                Place.from(direct.getJSONObject("destination")).name,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "直达约 ${formatDuration(direct.getInt("total_seconds"))}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${if (direct.getString("mode") == "cycling") "骑行" else "步行"} · ${formatDistance(direct.optInt("total_meters"))} · 返回候选中距离最短",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SectionLabel("本次顺路预算")
            Text("最多多花 ${auto.budget} 分钟", style = MaterialTheme.typography.titleMedium)
            val budgetSource = if (vm.modeDetourOverride(auto.travelMode) == auto.budget.toInt()) {
                "你设定的${if (auto.travelMode == "cycling") "骑行" else "步行"}预算"
            } else when (auto.budgetGuidance.source) {
                GuidanceSource.EXPLICIT -> "你设定的${if (auto.travelMode == "cycling") "骑行" else "步行"}预算"
                GuidanceSource.LEARNED -> "根据同种出行方式的 ${auto.budgetGuidance.evidenceCount} 次选择估计"
                GuidanceSource.DEFAULT -> "默认预算；积累选择后可按出行方式调整"
            }
            Text(budgetSource, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            listOf(listOf(5, 10, 15), listOf(20, 30)).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { mins ->
                        AppPill(
                            text = "${mins}分钟",
                            selected = auto.budget == mins.toString(),
                            onClick = { if (canAct()) { auto.updateBudget(mins); editingBudget = false } },
                            enabled = !auto.busy && !vm.busy,
                        )
                    }
                    if (row.size == 2) AppPill(
                        text = "自定义",
                        selected = auto.budget.toIntOrNull() !in listOf(5, 10, 15, 20, 30),
                        onClick = { customBudget = auto.budget; editingBudget = !editingBudget },
                        enabled = !auto.busy && !vm.busy,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (editingBudget) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        customBudget,
                        { customBudget = it.filter(Char::isDigit).take(3) },
                        label = { Text("0–240 分钟") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = appTextFieldColors(),
                    )
                    Button(
                        onClick = {
                            if (canAct()) customBudget.toIntOrNull()?.takeIf { it in 0..240 }?.let {
                                auto.updateBudget(it); editingBudget = false
                            }
                        },
                        enabled = customBudget.toIntOrNull()?.let { it in 0..240 } == true,
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("应用") }
                }
            }
            if (auto.budget.toInt() != vm.modeDetourOverride(auto.travelMode)) {
                TextButton({ if (canAct()) vm.saveModeDetourOverride(auto.travelMode, auto.budget.toInt()) }) { Text("设为此出行方式的预算") }
            }
            if (auto.budgetChanged) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("预算已变，助手建议需按新预算重新计算。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            { if (canAct()) auto.replanForBudget(vm) },
                            enabled = !auto.busy && !auto.selectionBusy && !vm.busy,
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("按新预算重新规划建议") }
                    }
                }
            }
            val assessments = preview.optJSONArray("assessments")
            if (assessments != null && assessments.length() > 0) {
                Spacer(Modifier.height(12.dp))
                SectionLabel("助手建议的顺路事项")
                Text(
                    "助手已试算并勾选建议；你可增减事项，确认后才会加入路线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                        for (i in 0 until assessments.length()) {
                            val entry = assessments.getJSONObject(i)
                            val id = entry.getString("task_id")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = id in auto.selectedIds,
                                    onCheckedChange = { if (canAct()) auto.toggle(vm, id) },
                                    enabled = !auto.busy && !vm.busy && !entry.isNull("extra_seconds"),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        if (entry.isNull("extra_seconds")) "${entry.getString("title")}：${entry.getString("reason")}"
                                        else "${entry.getString("title")} · 多 ${formatDistance(entry.optInt("extra_meters"))} / 约 ${formatDuration(entry.getInt("extra_seconds"))} · ${if (entry.getInt("extra_seconds") <= auto.budget.toInt() * 60) "预算内" else "超出预算"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (!auto.budgetChanged && id in auto.suggestedIds()) Text(
                                        "助手建议",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "单项耗时不能直接相加，组合方案会重新算路。",
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (auto.selectionBusy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(4.dp))
                Text("正在计算所选 ${auto.selectedIds.size} 件事的组合路线…")
            }
            if (auto.selectionError.isNotBlank()) {
                Text(auto.selectionError, color = MaterialTheme.colorScheme.error)
                TextButton({ if (canAct()) auto.select(vm, auto.selectedIds) }, enabled = !vm.busy) { Text("重试所选方案") }
            }
            auto.selectedRoute?.let { route ->
                Spacer(Modifier.height(12.dp))
                Text(
                    if (auto.selectedIds.isEmpty()) "当前选择：直达" else "当前选择：${auto.selectedIds.size} 件事",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("全程 ${formatDistance(route.optInt("total_meters"))} · 约 ${formatDuration(route.getInt("total_seconds"))}")
                Text(
                    "比直达多 ${formatDistance(route.optInt("extra_meters"))} / 约 ${formatDuration(route.getInt("extra_seconds"))}（含办事）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (route.getInt("extra_seconds") > auto.budget.toInt() * 60) {
                    val neededMins = ceil(route.getInt("extra_seconds") / 60.0).toInt()
                    Column {
                        Text("所选组合超出预算（需 ${neededMins} 分钟）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { if (canAct()) auto.updateBudget(neededMins) }) { Text("本次预算调至 ${neededMins} 分钟") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                RouteMapPreview(route)
                val stops = route.getJSONArray("stops")
                for (i in 0 until stops.length()) {
                    val stop = stops.getJSONObject(i)
                    val titles = stop.getJSONArray("task_titles")
                    Text(
                        (0 until titles.length()).joinToString("、") { titles.getString(it) } + " · " + Place.from(stop.getJSONObject("place")).name,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (auto.note.isNotBlank()) Text(
                auto.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (auto.selectedIds.isEmpty() && suggested != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = directBecauseDetour, onCheckedChange = { directBecauseDetour = it })
                    Text("这次直达是因为绕路太久（可选，仅用于学习预算）", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            AppPrimaryButton(
                if (auto.selectedIds.isEmpty()) "确认直达" else "确认所选 ${auto.selectedIds.size} 件事并规划",
                onClick = { if (canAct()) auto.accept(vm, auto.selectedIds, directBecauseDetour, accepted) },
                enabled = !vm.busy && auto.canConfirm,
            )
            Text(
                "按所选事项重新计算组合路线；若超出预算，会提示调整。",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton({ details = !details }) { Text(if (details) "收起说明" else "查看说明") }
            if (details) {
                if (preview.optString("note").isNotBlank()) Text(preview.getString("note"), style = MaterialTheme.typography.bodySmall)
                suggested?.let { route ->
                    val omitted = route.getJSONArray("omitted")
                    for (i in 0 until omitted.length()) Text(
                        omitted.getJSONObject(i).let { "${it.getString("title")}：${it.getString("reason")}" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val warnings = route.getJSONArray("warnings")
                    for (i in 0 until warnings.length()) Text(warnings.getString(i), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (auto.error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                Text(
                    auto.error,
                    Modifier.fillMaxWidth().padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (vm.message.isNotBlank()) Text(
            vm.message,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!auto.busy && !vm.busy) {
            Row {
                TextButton({ if (canAct()) retry() }) { Text("重新计算") }
                TextButton({ if (canAct()) manual() }) { Text("手动调整") }
                TextButton({ if (canAct()) cancel() }) { Text("取消") }
            }
        }
    }
}
