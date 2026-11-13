package com.onmyway.app.feature.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.format.formatDuration
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppPrimaryButton

/** Shared by the conversation and trip pages; all recovery actions retain the original request. */
@Composable
internal fun PlanningRecoveryCard(vm: AppViewModel) {
    if (!vm.planningRecoveryVisible || vm.pendingPlanningRequest == null) return
    val enabled = !vm.planningRecoveryBusy && !vm.busy && !vm.updatingTrip && !vm.restoringTrip
    AppCard(modifier = Modifier.fillMaxWidth().testTag("planning-recovery"), contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("上次路线尚未确认", style = MaterialTheme.typography.titleSmall)
            Text(vm.planningRecoveryMessage.ifBlank { "原规划已保留，可以继续核对结果。" },
                style = MaterialTheme.typography.bodyMedium)
            if (vm.planningRecoverySummary.isNotBlank()) {
                Text(vm.planningRecoverySummary, style = MaterialTheme.typography.bodyMedium)
            }
            vm.recoveredPlanningPreview?.let { route ->
                Text("全程 ${formatDuration(route.getInt("total_seconds"))} · 多花 ${formatDuration(route.getInt("extra_seconds"))}",
                    style = MaterialTheme.typography.bodyMedium)
                val stops = route.getJSONArray("stops")
                if (stops.length() == 0) Text("直接前往目的地", style = MaterialTheme.typography.bodySmall)
                for (index in 0 until stops.length()) {
                    Text("${index + 1}. ${stops.getJSONObject(index).getJSONObject("place").getString("name")}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                route.optJSONArray("omitted")?.let { omitted ->
                    if (omitted.length() > 0) Text("有 ${omitted.length()} 件待办未安排在这条路线中。",
                        style = MaterialTheme.typography.bodySmall)
                }
                route.optJSONArray("warnings")?.let { warnings ->
                    for (index in 0 until warnings.length()) Text(warnings.getString(index),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (vm.canConfirmPlanningRecovery) {
                    AppPrimaryButton("使用此方案", vm::confirmRecoveredPlanning, enabled = enabled)
                } else {
                    AppPrimaryButton(if (vm.planningRecoveryBusy) "正在核对路线" else "继续核对路线",
                        vm::recoverPlanningRequest, enabled = enabled)
                }
                TextButton(vm::discardPlanningRecovery, enabled = enabled) { Text("放弃此次规划") }
            }
        }
    }
}
