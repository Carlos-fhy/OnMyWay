package com.onmyway.app.feature.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.format.displayTime
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.isIndoor
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.feature.task.displayReminderTime
import org.json.JSONObject

internal data class TaskDraftEdit(val task: Errand, val draftId: String, val itemId: String, val version: Int)

@Composable
internal fun AssistantTaskDraftCard(
    proposal: JSONObject, enabled: Boolean,
    edit: (TaskDraftEdit) -> Unit, confirm: (Map<String, Int>) -> Unit, cancel: () -> Unit,
    familiarPlaces: Map<String, Place> = emptyMap(),
) {
    val issue = assistantCardIssue(proposal)
        ?: if (proposal.optString("action") != "create_tasks") "建议类型暂不支持，请重新输入需求。" else null
    if (issue != null) {
        UnavailableAssistantCard(issue)
        return
    }
    val draftId = proposal.getString("_draft_id")
    val items = taskDraftItems(proposal)
    val allIndoor = items.all { isIndoor(taskDraftErrand(it).placeType) }
    var selected by rememberSaveable(draftId) { mutableStateOf(emptyList<String>()) }
    AppCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBadge(R.drawable.ic_tasks, size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("待确认 · ${items.size} 件事", style = MaterialTheme.typography.titleMedium)
                Text(
                    "修改会保留为草稿；只保存勾选项，其余继续保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (allIndoor) "这些事项都不用出门，只记录，不参与路线规划。"
                    else "地点只在 3 公里内匹配常用地点时自动填入；请核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        items.forEachIndexed { index, item ->
            val id = item.getString("item_id")
            val task = taskDraftErrand(item)
            val place = task.place()
            val familiarAlias = familiarAliasFor(place, familiarPlaces)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
                    Checkbox(
                        id in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + id else selected - id
                        },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "选择第 ${index + 1} 项：${task.title}" },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${index + 1}. ${task.title}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            task.plannedDate.ifBlank { "未设计划日期" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when {
                                isIndoor(task.placeType) -> "不用出门"
                                place != null && familiarAlias != null -> "${place.name} · 常用地点「$familiarAlias」"
                                place != null -> place.name
                                else -> "待补办理地点"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (task.deadline.isNotBlank()) Text(
                            "截止 ${displayTime(task.deadline)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (task.reminderAt > 0) Text(
                            "提醒 ${displayReminderTime(task.reminderAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!isIndoor(task.placeType) && task.place() == null) Text(
                            "未补地点，暂不参与路线规划",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = { edit(TaskDraftEdit(task, draftId, id, item.getInt("version"))) },
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                            modifier = Modifier.semantics { contentDescription = "修改第 ${index + 1} 项：${task.title}" },
                        ) { Text("修改日期 / 地点 / 内容") }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selected = items.map { it.getString("item_id") } }, enabled = enabled) { Text("全选") }
            TextButton(onClick = { selected = emptyList() }, enabled = enabled && selected.isNotEmpty()) { Text("清除选择") }
        }
        AppPrimaryButton(
            "保存所选 ${selected.size} 项",
            onClick = {
                confirm(
                    items.filter { it.getString("item_id") in selected }
                        .associate { it.getString("item_id") to it.getInt("version") },
                )
            },
            enabled = enabled && selected.isNotEmpty(),
        )
        TextButton(cancel, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("取消剩余草稿") }
    }
}
