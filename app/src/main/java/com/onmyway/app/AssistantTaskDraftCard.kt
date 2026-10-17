package com.onmyway.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class TaskDraftEdit(val task: Errand, val draftId: String, val itemId: String, val version: Int)

@Composable
internal fun AssistantTaskDraftCard(
    proposal: JSONObject, enabled: Boolean,
    edit: (TaskDraftEdit) -> Unit, confirm: (Map<String, Int>) -> Unit, cancel: () -> Unit,
) {
    val draftId = proposal.getString("_draft_id")
    val items = taskDraftItems(proposal)
    var selected by rememberSaveable(draftId) { mutableStateOf(emptyList<String>()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("待确认 · ${items.size} 件事", style = MaterialTheme.typography.titleMedium)
            Text("修改会保留为草稿；只保存勾选项，其余继续保留。", style = MaterialTheme.typography.bodySmall)
            items.forEachIndexed { index, item ->
                val id = item.getString("item_id")
                val task = taskDraftErrand(item)
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(id in selected, onCheckedChange = { checked ->
                        selected = if (checked) selected + id else selected - id
                    }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "选择第 ${index + 1} 项：${task.title}" })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}. ${task.title}", style = MaterialTheme.typography.titleSmall)
                        Text(listOf(task.plannedDate.ifBlank { "未设计划日期" }, priorities[task.priority].orEmpty()).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall)
                        Text(task.place()?.name ?: placeTypes[task.placeType].orEmpty(), style = MaterialTheme.typography.bodySmall)
                        if (task.deadline.isNotBlank()) Text("截止 ${displayTime(task.deadline)}", style = MaterialTheme.typography.bodySmall)
                        if (requiresFixedPlace(task.placeType) && task.place() == null)
                            Text("保存前请选择实际领取地点", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { edit(TaskDraftEdit(task, draftId, id, item.getInt("version"))) }, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "修改第 ${index + 1} 项：${task.title}" }) {
                            Text("修改日期 / 地点 / 内容")
                        }
                    }
                }
                HorizontalDivider()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = items.map { it.getString("item_id") } }, enabled = enabled) { Text("全选") }
                TextButton(onClick = { selected = emptyList() }, enabled = enabled && selected.isNotEmpty()) { Text("清除选择") }
            }
            Button(onClick = { confirm(items.filter { it.getString("item_id") in selected }
                .associate { it.getString("item_id") to it.getInt("version") }) },
                enabled = enabled && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("保存所选 ${selected.size} 项")
            }
            TextButton(cancel, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("取消剩余草稿") }
        }
    }
}
