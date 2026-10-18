package com.onmyway.app.feature.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppPrimaryButton

@Composable
internal fun TripCompletionCard(
    prompt: TripCompletionPrompt,
    enabled: Boolean,
    confirm: (Set<String>) -> Unit,
    dismiss: () -> Unit,
) {
    var selected by remember(prompt.eventId) {
        mutableStateOf(prompt.tasks.filter { it.done }.map { it.id }.toSet())
    }
    LaunchedEffect(prompt.tasks) {
        val valid = prompt.tasks.map { it.id }.toSet()
        selected = selected.intersect(valid)
    }
    AppCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBadge(R.drawable.ic_check, size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("本次行程的待办完成情况", style = MaterialTheme.typography.titleMedium)
                Text(
                    "勾选已完成的事项；未勾选的会继续留在待办里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        prompt.tasks.forEachIndexed { index, task ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = task.id in selected,
                        onCheckedChange = { checked ->
                            selected = if (checked) selected + task.id else selected - task.id
                        },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "选择第 ${index + 1} 项：${task.title}" },
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${index + 1}. ${task.title}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            task.placeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (task.id in selected) "已完成" else "未完成",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (task.id in selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        AppPrimaryButton(
            "确认并结束导航",
            onClick = { confirm(selected) },
            enabled = enabled,
        )
        TextButton(dismiss, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("跳过确认") }
    }
}
