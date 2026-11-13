package com.onmyway.app.feature.settings

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.onmyway.app.BuildConfig
import com.onmyway.app.core.format.displayTimestamp
import com.onmyway.app.core.location.FamiliarPlacesSettings
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppConfirmDialog
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.SectionLabel
import com.onmyway.app.core.ui.appTextFieldColors
import com.onmyway.app.data.event.HarnessStatus
import com.onmyway.app.data.event.HarnessStatusRepository
import com.onmyway.app.data.remote.Api
import com.onmyway.app.data.sync.BackupCredentials
import com.onmyway.app.data.sync.PcBackupSync
import com.onmyway.app.feature.assistant.AssistantViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    OutlinedTextField(value, onValueChange, label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType), singleLine = true,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = appTextFieldColors(), enabled = enabled)
}

@Composable
internal fun SettingsScreen(vm: AppViewModel, chat: AssistantViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by rememberSaveable(vm.server) { mutableStateOf(vm.server) }
    var city by rememberSaveable(vm.city) { mutableStateOf(vm.city) }
    // Credentials never enter saved-state bundles. They are cleared after use or address changes.
    var backupToken by remember { mutableStateOf("") }
    var backupBusy by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val backupPrefs = remember(context) { context.getSharedPreferences("pc_backup", 0) }
    var backupRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(backupPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> backupRevision++ }
        backupPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { backupPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val configured = remember(vm.server, backupRevision) {
        BackupCredentials.load(context)?.server == vm.server
    }
    val syncState = remember(backupRevision) { backupPrefs.getString("sync_state", "pending").orEmpty() }
    val lastBackupAt = remember(backupRevision) { backupPrefs.getLong("last_success_at", 0) }
    val lastBackupError = remember(backupRevision) { backupPrefs.getString("last_error", "").orEmpty() }
    val taskProtocol = remember(backupRevision) { backupPrefs.getInt("task_protocol", -1) }
    val needsReload = remember(backupRevision) { backupPrefs.getBoolean("needs_reload", false) }
    LaunchedEffect(vm.server) { backupToken = "" }
    val savedAddress = runCatching { Api.validateBase(server) }.getOrNull() == vm.server
    // The management page refreshes its server-scoped snapshot on entry; a home
    // summary must not attach an older server's record count to a new address.
    val memorySummary = if (configured) "查看和管理偏好、临时情境与经历" else "连接个人服务后管理偏好与经历"
    val syncSummary = when {
        !configured -> "尚未连接个人数据服务"
        syncState == "synced" -> "业务数据已同步"
        syncState == "syncing" -> "业务数据正在同步"
        syncState == "conflict" -> "同步有冲突，需要检查"
        else -> "业务数据等待同步"
    }
    SettingsNavigation(memorySummary,
        "顺路预算 ${vm.preferredDetourMinutes} 分钟 · ${vm.familiarPlaces.size} 个常用地点",
        syncSummary) { page ->
        when (page) {
            SettingsPage.MEMORY -> AssistantMemorySettings(chat, vm.server)
            SettingsPage.TRAVEL -> TravelSettings(vm)
            SettingsPage.CONNECTION -> {
                Text("规划、个人记忆与业务同步使用同一个个人服务。", style = MaterialTheme.typography.bodyMedium)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsField(server, {
                        if (server != it) backupToken = ""
                        server = it
                    }, "个人服务地址", enabled = !backupBusy)
                    SettingsField(city, { city = it }, "常用搜索城市", enabled = !backupBusy)
                    AppPrimaryButton("保存连接设置", onClick = {
                        if (chat.memoryBusy) vm.message = "记忆更新尚未完成，请稍后修改连接。"
                        else vm.settings(server, city)
                    }, enabled = !backupBusy && !vm.busy && !chat.memoryBusy)
                    if (chat.memoryBusy) Text("记忆更新尚未完成，连接设置暂时不可更改。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!savedAddress) Text("地址尚未保存。保存后才能检查连接或使用口令。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(vm::health, enabled = !backupBusy && !vm.checking && savedAddress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (vm.checking) "检查中…" else "检查服务连接")
                    }
                }
                SectionLabel("业务数据同步")
                Text("待办、行程、对话和出行设置会同步到电脑；离线修改先留在手机。",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(backupToken, { backupToken = it }, label = { Text("数据服务口令") },
                    supportingText = { Text(if (backupToken.isNotEmpty() && backupToken.length < 32) "口令至少需要 32 个字符。"
                        else if (configured) "已保存口令；留空继续使用，更换时重新填写。" else "使用个人服务提供的口令。") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    enabled = !backupBusy && savedAddress, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp), colors = appTextFieldColors())
                AppPrimaryButton(if (backupBusy) "同步中…" else if (configured) "立即同步" else "连接并同步",
                    onClick = {
                        // Capture and validate the destination before retaining or using a credential.
                        val destination = vm.server
                        if (chat.memoryBusy) {
                            vm.message = "记忆更新尚未完成，请稍后修改连接。"
                        } else if (runCatching { Api.validateBase(server) }.getOrNull() != destination) {
                            vm.message = "请先保存当前服务地址。"
                        } else {
                            val enteredToken = backupToken
                            backupBusy = true
                            scope.launch {
                                try {
                                    check(!chat.memoryBusy && vm.server == destination) { "连接或记忆状态已变化，请稍后重试。" }
                                    val credential = resolveSettingsCredential(server, destination, enteredToken, BackupCredentials.load(context))
                                    PcBackupSync.enable(context, credential.server, credential.token)
                                    backupToken = ""
                                    val restored = PcBackupSync.synchronizeNow(context, credential)
                                    vm.message = if (restored) "电脑记录已恢复，请重新打开 App 加载。"
                                        else if (backupPrefs.getString("sync_state", "pending") != "synced") "本次同步完成，新增修改正在排队保存。"
                                        else "业务数据已同步，后续修改将自动同步。"
                                    chat.refreshPersonalMemory(destination)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (e: Exception) {
                                    if (vm.server == destination) backupPrefs.edit().putString("last_error", e.message ?: "同步失败").apply()
                                    vm.message = "同步尚未完成：" + (e.message ?: "请检查连接与口令")
                                } finally { backupBusy = false }
                            }
                        }
                    }, enabled = !backupBusy && !chat.memoryBusy && savedAddress && (backupToken.length >= 32 || backupToken.isEmpty() && configured))
                if (configured) {
                    Text(syncSummary, style = MaterialTheme.typography.titleSmall,
                        color = if (syncState == "conflict") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    if (lastBackupAt > 0) Text("最近成功：${displayTimestamp(lastBackupAt)}", style = MaterialTheme.typography.bodySmall)
                    if (taskProtocol == 0) Text("当前服务版本较旧，请升级电脑后端以启用待办版本校验。",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (syncState == "conflict") Text("双方记录均已保留，请核对后再继续。", style = MaterialTheme.typography.bodySmall)
                    if (lastBackupError.isNotBlank()) Text("最近同步失败：$lastBackupError",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton({ confirmDisconnect = true }, enabled = !backupBusy && !chat.memoryBusy) { Text("断开数据服务") }
                }
                if (needsReload) Text("已恢复电脑记录，请关闭并重新打开 App，以加载恢复的对话与设置。",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("个人记忆单独保存", style = MaterialTheme.typography.titleSmall)
                Text("个人记忆直接保存在服务端，手机保留查看副本。上面的同步成功不代表记忆已有备份；记忆的完整备份与恢复尚未提供。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsPage.HELP -> SettingsHelp(vm.server, configured)
            SettingsPage.HOME -> Unit
        }
    }
    if (confirmDisconnect) AppConfirmDialog(title = "断开数据服务？",
        message = "自动同步和个人记忆连接都会停止，电脑上的记录保留。重新连接后可以继续使用。",
        confirmLabel = "断开", onConfirm = {
            confirmDisconnect = false
            if (backupBusy || chat.memoryBusy) vm.message = "数据更新尚未完成，请稍后断开。"
            else {
                PcBackupSync.disable(context)
                backupToken = ""
                chat.refreshPersonalMemory(vm.server)
                vm.message = "已断开数据服务，电脑记录仍保留。"
            }
        }, onDismiss = { confirmDisconnect = false })
}

@Composable
private fun TravelSettings(vm: AppViewModel) {
    var preferred by rememberSaveable(vm.preferredDetourMinutes) { mutableStateOf(vm.preferredDetourMinutes.toString()) }
    var walking by rememberSaveable(vm.modeDetourOverride("walking")) { mutableStateOf(vm.modeDetourOverride("walking")?.toString().orEmpty()) }
    var cycling by rememberSaveable(vm.modeDetourOverride("cycling")) { mutableStateOf(vm.modeDetourOverride("cycling")?.toString().orEmpty()) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SectionLabel("顺路预算")
    Text("设置愿意为顺路办事多花的时间；0 表示不额外绕路。", style = MaterialTheme.typography.bodyMedium)
    SettingsField(preferred, { preferred = it.filter(Char::isDigit).take(3) }, "默认预算（分钟）",
        "没有足够历史选择时使用。", KeyboardType.Number)
    SettingsField(walking, { walking = it.filter(Char::isDigit).take(3) }, "步行预算（可留空）",
        "填写后优先使用；留空时跟随自动调整或默认预算。", KeyboardType.Number)
    SettingsField(cycling, { cycling = it.filter(Char::isDigit).take(3) }, "骑行预算（可留空）",
        "填写后优先使用；留空时跟随自动调整或默认预算。", KeyboardType.Number)
    AppPrimaryButton("保存预算", onClick = {
        val minutes = preferred.toIntOrNull()
        val walk = walking.toIntOrNull()
        val cycle = cycling.toIntOrNull()
        if (minutes == null || minutes !in 0..240 || walking.isNotBlank() && (walk == null || walk !in 0..240) ||
            cycling.isNotBlank() && (cycle == null || cycle !in 0..240)) vm.message = "预算请输入 0–240 分钟，步行和骑行预算也可留空。"
        else {
            vm.savePreferredDetourMinutes(minutes)
            vm.saveModeDetourOverride("walking", walk)
            vm.saveModeDetourOverride("cycling", cycle)
            vm.message = "预算已保存。"
        }
    }, enabled = !vm.busy)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("自动调整顺路预算", style = MaterialTheme.typography.titleSmall)
            Text("参考你接受、调整或拒绝建议的选择；手动预算优先。这项设置不控制个人记忆。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = vm.learningEnabled, onCheckedChange = vm::updateLearningEnabled,
            modifier = Modifier.semantics { contentDescription = "自动调整顺路预算" })
    }
    TextButton({ confirmClear = true }, contentPadding = PaddingValues(0.dp)) { Text("清空预算学习记录") }
    HorizontalDivider()
    SectionLabel("常用地点")
    FamiliarPlacesSettings(vm)
    if (confirmClear) AppConfirmDialog(title = "清空预算学习记录？",
        message = "删除本机用于预算学习的建议选择与事件记录。个人记忆、待办和行程保留。",
        confirmLabel = "清空", destructive = true, onConfirm = {
            confirmClear = false
            scope.launch {
                try { vm.clearLearningHistory() }
                catch (_: Exception) { vm.message = "清空记录失败，请重试。" }
            }
        }, onDismiss = { confirmClear = false })
}

@Composable
private fun SettingsHelp(server: String, connected: Boolean) {
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    SectionLabel("连接与恢复")
    Text("手机通过 USB 调试转发连接电脑时使用 http://127.0.0.1:8000；模拟器使用 http://10.0.2.2:8000。服务启动步骤见项目 README。",
        style = MaterialTheme.typography.bodyMedium)
    Text("新安装连接原来的服务地址和口令后，可恢复已同步的业务记录。离线修改先留在手机，电脑确认后才显示同步成功；未同步内容无法在卸载后找回。",
        style = MaterialTheme.typography.bodyMedium)
    HorizontalDivider()
    SectionLabel("记忆与隐私")
    Text("个人记忆可在记忆页面纠正或忘记。忘记会撤销相关归纳，但不会删除聊天原文；关闭自动保存也不会删除已有记忆。",
        style = MaterialTheme.typography.bodyMedium)
    Text("位置用于查找地点和规划路线。使用缓存位置时请核对起点；结束导航后的方案可在助手的路线记录中查看，再次出发会重新规划。",
        style = MaterialTheme.typography.bodyMedium)
    HorizontalDivider()
    OutlinedButton({ diagnostics = !diagnostics }, modifier = Modifier.fillMaxWidth()) {
        Text(if (diagnostics) "收起运行诊断" else "查看运行诊断")
    }
    if (diagnostics) HarnessStatusPanel(server, connected)
    Text("OnMyWay ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HarnessStatusPanel(server: String, connected: Boolean) {
    val context = LocalContext.current
    val repository = remember(context) { HarnessStatusRepository(context) }
    var status by remember { mutableStateOf<HarnessStatus?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(server, connected, refresh) {
        loading = true
        status = null
        error = ""
        try {
            status = repository.read(server)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "本机确认状态读取失败，请重试。"
        } finally {
            loading = false
        }
    }
    AppCard(contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("确认与导航状态", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton({ refresh++ }, enabled = !loading) { Text(if (loading) "读取中…" else "刷新") }
            }
            status?.let { result ->
                val queue = result.queue
                Text("等待上报：确认 ${queue.pendingConfirmations} 条 · 导航与任务事件 ${queue.pendingEvents} 条",
                    style = MaterialTheme.typography.bodySmall)
                Text("终止失败：确认 ${queue.terminalConfirmations} 条 · 事件 ${queue.terminalEvents} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (queue.terminalConfirmations + queue.terminalEvents > 0)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (queue.terminalConfirmations > 0) Text("存在被拒绝的确认，请重新规划或确认；重复刷新不会重试冲突记录。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                result.localExecutions.forEach { run ->
                    val action = when (run.capabilityId) {
                        "read_tasks" -> "读取任务"
                        "plan_route" -> "规划路线"
                        else -> "导航"
                    }
                    val state = when (run.state) {
                        "waiting_confirmation" -> "等待明确开始"
                        "running" -> "执行中"
                        "completed" -> if (run.capabilityId == "start_navigation") "已记录到达" else "已完成"
                        "failed" -> if (run.step in setOf("manual_end", "user_cancelled")) "已手动结束" else "失败"
                        else -> "结果未知，需核对"
                    }
                    Text("本机 $action：$state", style = MaterialTheme.typography.bodySmall)
                    if (run.state == "unknown") Text("不会自动重复执行。请核对导航实际状态；再次出发需重新规划并确认。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                result.remote?.let { remote ->
                    Text(if (remote.recordedCount > 0) "服务端已记录 ${remote.recordedCount} 条确认" else "服务端尚未记录本次确认",
                        style = MaterialTheme.typography.bodySmall)
                    remote.currentPlan?.let { plan ->
                        Text("服务端最新确认版本：${plan.version}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("该版本的设备回执：${remote.executionLabel}", style = MaterialTheme.typography.bodyMedium)
                    if (remote.latestObservationMatches == false) Text("最近导航回执与当前确认版本不匹配。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else if (remote.latestObservationMatches == null) Text("尚无可核对当前计划版本的导航回执。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("确认已记录仅表示服务端收到确认；导航状态以当前计划的设备回执为准。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (result.message.isNotBlank()) Text(result.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
