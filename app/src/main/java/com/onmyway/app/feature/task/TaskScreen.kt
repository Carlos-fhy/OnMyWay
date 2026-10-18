package com.onmyway.app.feature.task

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.format.displayTime
import com.onmyway.app.core.model.isIndoor
import com.onmyway.app.core.model.placeTypeIcon
import com.onmyway.app.core.reminder.notificationsAllowed
import com.onmyway.app.core.time.currentDate
import com.onmyway.app.core.time.rememberCurrentDate
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppRouteArt
import com.onmyway.app.core.ui.AppScreenHeader
import com.onmyway.app.core.ui.AppStatusPill
import com.onmyway.app.core.ui.ChoiceRow
import com.onmyway.app.data.local.entity.Errand

@Composable
internal fun TaskScreen(tasks: List<Errand>, add: () -> Unit, edit: (Errand) -> Unit, delete: (Errand) -> Unit,
    save: (Errand) -> Unit, snooze: (Errand, Long) -> Unit, dismissReminder: (Errand) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("pending") }
    var reminderTarget by remember { mutableStateOf<Errand?>(null) }
    val currentDate = rememberCurrentDate()
    val sections = taskDateSections(tasks, filter, currentDate.date, currentDate.zone)
    val visible = sections.flatMap { it.tasks }
    val reminders = tasks.count { it.status == "pending" && it.reminderPending }
    val listState = rememberLazyListState()
    val expandAdd by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppScreenHeader(
                "待办",
                kicker = "TODAY",
                subtitle = "${tasks.count { it.status == "pending" }} 件事，留给下一次顺路",
                actions = {
                    if (reminders > 0) {
                        AppStatusPill(
                            "$reminders 个提醒",
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                },
            )
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val pendingCount = tasks.count { it.status == "pending" }
                val deferredCount = tasks.count { it.status == "deferred" }
                val doneCount = tasks.count { it.status == "done" }
                ChoiceRow(
                    listOf(
                        "pending" to "待办 ($pendingCount)",
                        "deferred" to "暂缓 ($deferredCount)",
                        "done" to "已完成 ($doneCount)",
                    ),
                    filter,
                ) { filter = it }
                ReminderPermission()
            }
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (visible.isEmpty()) item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppRouteArt(Modifier.width(190.dp).height(96.dp))
                        Spacer(Modifier.height(18.dp))
                        Text(
                            when (filter) {
                                "done" -> "完成的事，会留在这里"
                                "deferred" -> "暂时没有搁置的事"
                                else -> "记下第一件小事"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (filter == "pending") {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "买东西、取快递，出门时一起安排。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                sections.forEach { section ->
                    item(key = "section:${section.key}") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${section.tasks.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    items(section.tasks, key = { it.id }) { task ->
                        TaskRow(task, save, edit, delete, { reminderTarget = it }, dismissReminder,
                            modifier = Modifier.animateItem())
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = add,
            expanded = expandAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            icon = { Icon(painterResource(R.drawable.ic_add), "新增待办") },
            text = { Text("记一件事", style = MaterialTheme.typography.titleSmall) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(19.dp),
        )
    }
    reminderTarget?.let { expected ->
        val current = tasks.firstOrNull { it.id == expected.id }
        if (current == expected && current?.status == "pending") {
            TaskReminderDialog(expected, dismiss = { reminderTarget = null }) { at ->
                snooze(expected, at)
                reminderTarget = null
            }
        } else LaunchedEffect(expected, current) { reminderTarget = null }
    }
}

@Composable
private fun TaskRow(
    task: Errand,
    save: (Errand) -> Unit,
    edit: (Errand) -> Unit,
    delete: (Errand) -> Unit,
    remind: (Errand) -> Unit,
    dismissReminder: (Errand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val completed = task.status == "done"
    val indoor = isIndoor(task.placeType)
    val missingPlace = !indoor && task.place() == null
    val meta = listOfNotNull(
        task.plannedDate.takeIf { it.isNotBlank() }?.let { "计划 $it" },
        if (task.reminderPending) "有待处理提醒" else null,
        task.reminderAt.takeIf { it > 0 }?.let { "提醒 ${displayReminderTime(it)}" },
        if (task.deadline.isNotBlank()) "${displayTime(task.deadline)} 前" else null,
    )
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                completed,
                onCheckedChange = { save(task.copy(status = if (it) "done" else "pending")) },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.semantics { contentDescription = "完成${task.title}" },
            )
            Column(
                Modifier.weight(1f).clickable { edit(task) }.padding(vertical = 6.dp, horizontal = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        painterResource(placeTypeIcon(task.placeType)),
                        null,
                        Modifier.size(14.dp),
                        tint = when {
                            completed -> MaterialTheme.colorScheme.outline
                            missingPlace -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    Text(
                        listOfNotNull(
                            when {
                                indoor -> "不用出门"
                                missingPlace -> "待补办理地点"
                                else -> task.place()?.name
                            },
                            if (!indoor) "${task.serviceMinutes} 分钟" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (missingPlace && !completed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!completed && meta.isNotEmpty()) {
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Box {
                IconButton({ menu = true }, Modifier.size(42.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_more),
                        "${task.title}的更多操作",
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; edit(task) })
                    if (task.status == "pending") {
                        DropdownMenuItem(text = { Text("稍后提醒") }, onClick = { menu = false; remind(task) })
                        if (task.reminderPending) DropdownMenuItem(text = { Text("忽略本次提醒") }, onClick = {
                            menu = false; dismissReminder(task)
                        })
                    }
                    DropdownMenuItem(text = { Text(if (task.status == "pending") "暂缓" else "恢复待办") }, onClick = {
                        menu = false; save(task.copy(status = if (task.status == "pending") "deferred" else "pending"))
                    })
                    DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete(task) })
                }
            }
        }
    }
}

@Composable
private fun ReminderPermission() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = notificationsAllowed(context); denied = !allowed
    }
    if (allowed) return
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppIconBadge(
                R.drawable.ic_clock,
                size = 32.dp,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                container = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
            )
            Text(
                "可单独预约提醒 · 未预约时每三天提醒",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton({
                if (Build.VERSION.SDK_INT >= 33 && !denied) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(if (denied) "通知设置" else "开启通知") }
        }
    }
}
