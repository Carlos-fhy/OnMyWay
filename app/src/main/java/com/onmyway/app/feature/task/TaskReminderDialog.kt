package com.onmyway.app.feature.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.onmyway.app.core.ui.AppBottomSheet
import com.onmyway.app.core.ui.AppDateTimeDialog
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.AppSheetActionBar
import com.onmyway.app.core.ui.BottomSheetTitle
import com.onmyway.app.data.local.entity.Errand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun displayReminderTime(at: Long): String = Instant.ofEpochMilli(at)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@Composable
internal fun TaskReminderDialog(task: Errand, dismiss: () -> Unit, confirm: (Long) -> Unit) {
    var choice by rememberSaveable(task.id) { mutableStateOf(if (task.reminderAt > System.currentTimeMillis()) "custom" else "hour") }
    var customAt by rememberSaveable(task.id) { mutableLongStateOf(task.reminderAt) }
    var error by rememberSaveable(task.id) { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    AppBottomSheet(onDismissRequest = dismiss) { close ->
        Column(Modifier.fillMaxWidth().imePadding()) {
            BottomSheetTitle("稍后提醒：${task.title}", close, subtitle = "只改提醒，不改计划")
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "仅调整本任务的提醒，不改变计划日期、截止时间和优先级。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (task.reminderAt > 0) Text(
                    "当前预约：${displayReminderTime(task.reminderAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                listOf("hour" to "1 小时后", "tomorrow" to "明天 09:00", "custom" to "自选日期时间").forEach { (value, label) ->
                    val selected = choice == value
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else Color.Transparent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth()
                                    .selectable(selected, role = Role.RadioButton, onClick = {
                                        choice = value; error = ""
                                    })
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(6.dp))
                                Text(label, style = MaterialTheme.typography.titleSmall)
                            }
                            // 自定义时间直接展开在选项下方，不再套一层卡片和描边按钮。
                            if (value == "custom" && selected) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 48.dp, end = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (customAt > 0) displayReminderTime(customAt) else "尚未选择时间",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { picking = true }) {
                                        Text(if (customAt > 0) "修改" else "选择日期和时间")
                                    }
                                }
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(
                    error,
                    Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
            }
            AppSheetActionBar {
                AppPrimaryButton(
                    "确认稍后提醒",
                    onClick = {
                        val now = System.currentTimeMillis()
                        val at = when (choice) {
                            "hour" -> now + 3_600_000
                            "tomorrow" -> Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                                .plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            else -> customAt
                        }
                        if (at <= now) error = "请选择未来的提醒时间" else confirm(at)
                    },
                )
                TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
    if (picking) {
        val zone = ZoneId.systemDefault()
        val initial = Instant.ofEpochMilli(customAt.takeIf { it > 0 } ?: System.currentTimeMillis() + 3_600_000)
            .atZone(zone).toLocalDateTime()
        AppDateTimeDialog(initial, { picking = false }, title = "选择提醒时间") { picked ->
            customAt = picked.atZone(zone).toInstant().toEpochMilli()
            error = ""
            picking = false
        }
    }
}
