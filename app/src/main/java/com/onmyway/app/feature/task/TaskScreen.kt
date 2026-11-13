package com.onmyway.app.feature.task

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.format.displayTime
import com.onmyway.app.core.model.isIndoor
import com.onmyway.app.core.model.placeTypeIcon
import com.onmyway.app.core.reminder.notificationsAllowed
import com.onmyway.app.core.time.rememberCurrentDate
import com.onmyway.app.core.ui.AppScreenHeader
import com.onmyway.app.data.local.entity.Errand

private val TaskContentInset = 56.dp

@Composable
internal fun TaskScreen(tasks: List<Errand>, add: () -> Unit, edit: (Errand) -> Unit, delete: (Errand) -> Unit,
    save: (Errand) -> Unit, snooze: (Errand, Long) -> Unit, dismissReminder: (Errand) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("pending") }
    var reminderTarget by remember { mutableStateOf<Errand?>(null) }
    val currentDate = rememberCurrentDate()
    val sections = taskDateSections(tasks, filter, currentDate.date, currentDate.zone)
    val visible = sections.flatMap { it.tasks }
    val pendingCount = tasks.count { it.status == "pending" }
    val deferredCount = tasks.count { it.status == "deferred" }
    val doneCount = tasks.count { it.status == "done" }
    val reminders = tasks.count { it.status == "pending" && it.reminderPending }
    val listState = rememberLazyListState()
    val expandAdd by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    val context = LocalContext.current
    var notificationsGranted by remember { mutableStateOf(notificationsAllowed(context)) }
    var notificationDenied by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsGranted = notificationsAllowed(context)
        notificationDenied = !notificationsGranted
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppScreenHeader(
                "待办",
                subtitle = if (reminders > 0) "$pendingCount 件待办 · $reminders 个提醒待处理"
                    else "$pendingCount 件事，留给下一次顺路",
            )
            Column(
                Modifier.padding(horizontal = 16.dp),
            ) {
                TaskFilters(
                    listOf(
                        "pending" to "待办 ($pendingCount)",
                        "deferred" to "暂缓 ($deferredCount)",
                        "done" to "已完成 ($doneCount)",
                    ),
                    filter,
                ) { filter = it }
            }
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (!notificationsGranted) item(key = "reminder-permission") {
                    ReminderPermission(notificationDenied) {
                        if (Build.VERSION.SDK_INT >= 33 && !notificationDenied) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }
                    }
                }
                if (visible.isEmpty()) item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 40.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_tasks),
                                null,
                                Modifier.padding(14.dp).size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            when (filter) {
                                "done" -> "完成的事，会留在这里"
                                "deferred" -> "暂时没有搁置的事"
                                else -> "记下第一件小事"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (filter == "pending") {
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
                            Modifier.fillMaxWidth().padding(start = TaskContentInset, end = 16.dp)
                                .padding(top = 24.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                section.title,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${section.tasks.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    itemsIndexed(section.tasks, key = { _, task -> task.id }) { index, task ->
                        TaskRow(task, save, edit, delete, { reminderTarget = it }, dismissReminder,
                            first = index == 0, last = index == section.tasks.lastIndex,
                            modifier = Modifier.animateItem())
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = add,
            expanded = expandAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(painterResource(R.drawable.ic_add), "新增待办") },
            text = { Text("记一件事", style = MaterialTheme.typography.titleSmall) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
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
private fun TaskFilters(options: List<Pair<String, String>>, selected: String, choose: (String) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
            options.forEach { (value, label) ->
                val active = value == selected
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = active, role = Role.RadioButton, onClick = { choose(value) })
                        .heightIn(min = 52.dp).padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        label,
                        Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    )
                    Box(Modifier.size(width = 24.dp, height = 3.dp).background(
                        if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    ))
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(
    task: Errand,
    save: (Errand) -> Unit,
    edit: (Errand) -> Unit,
    delete: (Errand) -> Unit,
    remind: (Errand) -> Unit,
    dismissReminder: (Errand) -> Unit,
    first: Boolean,
    last: Boolean,
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
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(
            topStart = if (first) 16.dp else 0.dp,
            topEnd = if (first) 16.dp else 0.dp,
            bottomStart = if (last) 16.dp else 0.dp,
            bottomEnd = if (last) 16.dp else 0.dp,
        ),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    completed,
                    onCheckedChange = { save(task.copy(status = if (it) "done" else "pending")) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "完成${task.title}" },
                )
                Column(
                    Modifier.weight(1f).clickable { edit(task) }
                        .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (completed) FontWeight.Normal else FontWeight.Medium,
                        textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            painterResource(placeTypeIcon(task.placeType)),
                            null,
                            Modifier.padding(top = 2.dp).size(14.dp),
                            tint = when {
                                completed -> MaterialTheme.colorScheme.outline
                                missingPlace -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            when {
                                indoor -> "不用出门"
                                missingPlace -> "待补办理地点"
                                else -> task.place()?.name.orEmpty()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (missingPlace && !completed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!indoor || (!completed && meta.isNotEmpty())) {
                        FlowRow(
                            Modifier.padding(start = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (!indoor) Text(
                                "办理约 ${task.serviceMinutes} 分钟",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!completed) meta.forEach { detail ->
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (task.reminderPending) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Box {
                    IconButton({ menu = true }, Modifier.size(48.dp)) {
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
            if (!last) HorizontalDivider(
                modifier = Modifier.padding(start = TaskContentInset, end = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ReminderPermission(denied: Boolean, request: () -> Unit) {
    Surface(
        modifier = Modifier.padding(top = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    painterResource(R.drawable.ic_clock),
                    null,
                    Modifier.padding(top = 2.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "可单独预约提醒 · 未预约时每三天提醒",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(request, Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(if (denied) "通知设置" else "开启通知")
            }
        }
    }
}
