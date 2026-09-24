package com.onmyway.app

import android.Manifest
import android.os.Build
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
internal fun TaskScreen(tasks: List<Errand>, add: () -> Unit, edit: (Errand) -> Unit, delete: (Errand) -> Unit, save: (Errand) -> Unit, acknowledge: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("pending") }
    val visible = tasks.filter { it.status == filter }
    val reminders = tasks.count { it.status == "pending" && it.reminderPending }
    val listState = rememberLazyListState()
    val expandAdd by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("待办", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("${tasks.count { it.status == "pending" }} 件事，留给下一次顺路", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceRow(listOf("pending" to "待办", "deferred" to "暂缓", "done" to "已完成"), filter) { filter = it }
            ReminderPermission()
            if (reminders > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$reminders 件事满三天未完成，已提醒并更新优先级", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(acknowledge) { Text("知道了") }
            }
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (visible.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (filter == "done") "✓" else "○", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text(when (filter) { "done" -> "完成的事，会留在这里"; "deferred" -> "暂时没有搁置的事"; else -> "记下第一件小事" }, style = MaterialTheme.typography.titleMedium)
                    if (filter == "pending") Text("买东西、取快递，出门时一起安排。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(visible, key = { it.id }) { task ->
                var menu by remember { mutableStateOf(false) }
                val completed = task.status == "done"
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(completed, onCheckedChange = { save(task.copy(status = if (it) "done" else "pending")) })
                        Column(Modifier.weight(1f).clickable { edit(task) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text("${task.place()?.name ?: if (requiresFixedPlace(task.placeType)) "请补全领取地点" else "${placeTypes[task.placeType]} · 系统选店"} · ${task.serviceMinutes} 分钟",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!completed && (task.priority > 1 || task.deadline.isNotBlank() || task.reminderPending)) Text(
                                listOfNotNull(if (task.priority > 1) priorities[task.priority] else null, if (task.reminderPending) "三天提醒" else null, if (task.deadline.isNotBlank()) "${displayTime(task.deadline)} 前" else null).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Box {
                            IconButton({ menu = true }) { Icon(painterResource(R.drawable.ic_more), "${task.title}的更多操作") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; edit(task) })
                                DropdownMenuItem(text = { Text(if (task.status == "pending") "暂缓" else "恢复待办") }, onClick = {
                                    menu = false; save(task.copy(status = if (task.status == "pending") "deferred" else "pending"))
                                })
                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete(task) })
                            }
                        }
                    }
                }
            }
        }
    }
        ExtendedFloatingActionButton(onClick = add, expanded = expandAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            icon = { Icon(painterResource(R.drawable.ic_add), "新增待办") }, text = { Text("记一件事") },
            containerColor = MaterialTheme.colorScheme.primaryContainer)
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("每三天提醒 · 优先级逐级提升", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!allowed) TextButton({
            if (Build.VERSION.SDK_INT >= 33 && !denied) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text(if (denied) "通知设置" else "开启通知") }
    }
}
