package com.onmyway.app.feature.settings

import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.onmyway.app.R
import com.onmyway.app.core.format.displayTimestamp
import com.onmyway.app.core.format.formatDuration
import com.onmyway.app.core.location.FamiliarPlacesSettings
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppConfirmDialog
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.AppScreenHeader
import com.onmyway.app.core.ui.AppStatusPill
import com.onmyway.app.core.ui.SectionLabel
import com.onmyway.app.core.ui.appTextFieldColors
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SuggestionFeedback
import com.onmyway.app.data.sync.BackupCredential
import com.onmyway.app.data.sync.BackupCredentials
import com.onmyway.app.data.sync.PcBackupSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value,
        onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = appTextFieldColors(),
    )
}

@Composable
private fun InfoCard(icon: Int, title: String, text: String) {
    AppCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AppIconBadge(icon, size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecordDialog(
    title: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.92f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(
                        onClose,
                        Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                    ) {
                        Icon(painterResource(R.drawable.ic_close), "关闭", Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content,
                )
                TextButton(onClose, Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(vm: AppViewModel, repeatRoute: (RouteHistory) -> Unit) {
    var server by rememberSaveable { mutableStateOf(vm.server) }
    var city by rememberSaveable { mutableStateOf(vm.city) }
    var preferredDetour by rememberSaveable { mutableStateOf(vm.preferredDetourMinutes.toString()) }
    var walkingDetour by rememberSaveable { mutableStateOf(vm.modeDetourOverride("walking")?.toString().orEmpty()) }
    var cyclingDetour by rememberSaveable { mutableStateOf(vm.modeDetourOverride("cycling")?.toString().orEmpty()) }
    var confirmClearLearning by remember { mutableStateOf(false) }
    var backupToken by remember { mutableStateOf("") }
    var backupBusy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var backupConfigured by remember { mutableStateOf(BackupCredentials.load(context) != null) }
    val backupPrefs = remember(context) { context.getSharedPreferences("pc_backup", 0) }
    var lastBackupAt by remember { mutableLongStateOf(backupPrefs.getLong("last_success_at", 0)) }
    var lastBackupError by remember { mutableStateOf(backupPrefs.getString("last_error", "").orEmpty()) }
    var syncState by remember { mutableStateOf(backupPrefs.getString("sync_state", "pending")) }
    var taskProtocol by remember { mutableIntStateOf(backupPrefs.getInt("task_protocol", -1)) }
    var needsReload by remember { mutableStateOf(backupPrefs.getBoolean("needs_reload", false)) }
    DisposableEffect(backupPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "last_success_at") lastBackupAt = prefs.getLong(key, 0)
            if (key == "last_error") lastBackupError = prefs.getString(key, "").orEmpty()
            if (key == "sync_state") syncState = prefs.getString(key, "pending")
            if (key == "task_protocol") taskProtocol = prefs.getInt(key, -1)
            if (key == "needs_reload") needsReload = prefs.getBoolean(key, false)
        }
        backupPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { backupPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var history by remember { mutableStateOf<List<RouteHistory>?>(null) }
    var feedback by remember { mutableStateOf<List<SuggestionFeedback>?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppScreenHeader(
            "设置",
            kicker = "ON MY WAY",
            subtitle = "连接电脑数据服务后，记录自动同步；手机保留离线数据。",
        )
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLabel("习惯与预算")
            AppCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsField(
                        preferredDetour,
                        { preferredDetour = it.filter(Char::isDigit).take(3) },
                        "默认顺路预算（分钟）",
                        "没有足够历史选择时使用此预算；0 表示不额外绕路。",
                        KeyboardType.Number,
                    )
                    SettingsField(
                        walkingDetour,
                        { walkingDetour = it.filter(Char::isDigit).take(3) },
                        "步行顺路预算（可留空）",
                        "填写后优先采用你的设定；留空时参考步行历史选择。",
                        KeyboardType.Number,
                    )
                    SettingsField(
                        cyclingDetour,
                        { cyclingDetour = it.filter(Char::isDigit).take(3) },
                        "骑行顺路预算（可留空）",
                        "填写后优先采用你的设定；留空时参考骑行历史选择。",
                        KeyboardType.Number,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("根据建议选择调整预算", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "学习记录包含出行方式、时间段、事项数量与额外耗时，开启同步后会保存在电脑。建议仍需你确认。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(checked = vm.learningEnabled, onCheckedChange = vm::updateLearningEnabled)
                    }
                    TextButton({ confirmClearLearning = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("清空建议学习记录")
                    }
                }
            }

            SectionLabel("常用地点")
            AppCard(contentPadding = PaddingValues(16.dp)) {
                FamiliarPlacesSettings(vm)
            }

            SectionLabel("连接")
            AppCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsField(server, { server = it }, "规划服务地址")
                    SettingsField(city, { city = it }, "常用搜索城市")
                    AppPrimaryButton(
                        "保存设置",
                        onClick = {
                            val minutes = preferredDetour.toIntOrNull()
                            val walk = walkingDetour.toIntOrNull()
                            val cycle = cyclingDetour.toIntOrNull()
                            if (minutes == null || minutes !in 0..240 ||
                                walkingDetour.isNotBlank() && (walk == null || walk !in 0..240) ||
                                cyclingDetour.isNotBlank() && (cycle == null || cycle !in 0..240)
                            ) vm.message = "顺路预算请输入 0–240 分钟，步行和骑行预算也可留空"
                            else {
                                vm.savePreferredDetourMinutes(minutes)
                                vm.saveModeDetourOverride("walking", walk)
                                vm.saveModeDetourOverride("cycling", cycle)
                                vm.settings(server, city)
                            }
                        },
                        enabled = !vm.busy,
                    )
                    OutlinedButton(
                        vm::health,
                        enabled = !vm.checking && server.trim().trimEnd('/') == vm.server,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(if (vm.checking) "检查中…" else "检查已保存地址的连接") }
                }
            }

            SectionLabel("电脑数据同步")
            AppCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "使用同一个数据服务口令连接。新安装会先读取电脑记录；离线修改留在手机，联网后提交。电脑确认保存后才显示同步成功，两端发生冲突时保留双方记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        backupToken,
                        { backupToken = it },
                        label = { Text("数据服务口令") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = appTextFieldColors(),
                    )
                    AppPrimaryButton(
                        if (backupBusy) "同步中…" else "连接电脑并同步",
                        onClick = {
                            scope.launch {
                                backupBusy = true
                                try {
                                    val token = backupToken.takeIf { it.length >= 32 }
                                        ?: BackupCredentials.load(context)?.takeIf { it.server == vm.server }?.token
                                        ?: error("请输入数据服务口令")
                                    PcBackupSync.enable(context, vm.server, token)
                                    backupConfigured = true
                                    val restored = PcBackupSync.synchronizeNow(context, BackupCredential(vm.server, token))
                                    vm.message = if (restored) "电脑记录已恢复。请重新打开 App 加载恢复的对话和设置。"
                                    else if (backupPrefs.getString("sync_state", "pending") != "synced") "本次同步完成，新增修改正在排队保存。"
                                    else "记录已同步到电脑；后续修改自动同步。"
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (e: Exception) {
                                    backupPrefs.edit().putString("last_error", e.message ?: "同步失败").apply()
                                    vm.message = "同步尚未完成：" + (e.message ?: "请检查电脑连接与口令")
                                } finally {
                                    backupBusy = false
                                }
                            }
                        },
                        enabled = !backupBusy && (backupToken.length >= 32 || backupConfigured) && server.trim().trimEnd('/') == vm.server,
                    )
                    if (backupConfigured) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppStatusPill(
                                when (syncState) {
                                    "synced" -> "已同步"
                                    "syncing" -> "同步中"
                                    "conflict" -> "有冲突"
                                    else -> "等待同步"
                                },
                                container = when (syncState) {
                                    "synced" -> MaterialTheme.colorScheme.primaryContainer
                                    "conflict" -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                },
                                content = when (syncState) {
                                    "synced" -> MaterialTheme.colorScheme.onPrimaryContainer
                                    "conflict" -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                                },
                            )
                            Text(
                                if (lastBackupAt > 0) "最近成功同步：${displayTimestamp(lastBackupAt)}" else "已连接数据服务，尚未完成同步",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            when (taskProtocol) {
                                1 -> "待办由电脑保存，手机支持离线使用。"
                                0 -> "当前连接旧版数据服务，请升级电脑后端以启用待办版本校验。"
                                else -> "首次同步完成后确认待办保存状态。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when (syncState) {
                                "synced" -> "已同步到电脑"
                                "syncing" -> "正在同步"
                                "conflict" -> "存在版本冲突，双方记录均已保留"
                                else -> "本地修改等待同步"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (lastBackupError.isNotBlank()) Text(
                            "最近同步失败：$lastBackupError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            {
                                PcBackupSync.disable(context)
                                backupConfigured = false
                                vm.message = "自动同步已暂停；电脑记录仍保留。"
                            },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("暂停自动同步") }
                    }
                    if (needsReload) Text(
                        "已从电脑恢复记录，请关闭并重新打开 App，以加载恢复的对话与设置。",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "重装后使用原来的服务地址与口令连接即可恢复。手机尚未同步的修改，卸载后无法从电脑找回。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel("记录")
            AppCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        {
                            scope.launch {
                                try { history = vm.routeHistory() } catch (_: Exception) { vm.message = "行程记录读取失败" }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("行程记录") }
                    TextButton(
                        {
                            scope.launch {
                                try { feedback = vm.suggestionDecisions() } catch (_: Exception) { vm.message = "建议反馈读取失败" }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("顺路建议反馈") }
                }
            }

            InfoCard(
                R.drawable.ic_location,
                "连接你的电脑",
                "USB 调试转发后使用 http://127.0.0.1:8000。Android 模拟器访问电脑可用 http://10.0.2.2:8000。具体启动步骤见项目 README。",
            )
            InfoCard(
                R.drawable.ic_settings,
                "关于地点与隐私",
                "点击「使用当前位置」后才获取手机位置，并通过本地服务交给高德转换坐标与识别地址。室内暂时没有新定位时，会明确提示并使用最近一次的缓存位置，请核对起点。退出导航会结束本次行程，方案留在行程记录；再次出发会重新定位规划。",
            )
            Text(
                "OnMyWay 0.1 · 步行 / 骑行",
                Modifier.fillMaxWidth().padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirmClearLearning) {
        AppConfirmDialog(
            title = "清空建议学习记录？",
            message = "这会删除本机的建议选择与事件记录，不删除待办和行程。",
            confirmLabel = "清空",
            onConfirm = {
                confirmClearLearning = false
                scope.launch {
                    try { vm.clearLearningHistory(); feedback = emptyList() }
                    catch (_: Exception) { vm.message = "清空记录失败，请重试" }
                }
            },
            onDismiss = { confirmClearLearning = false },
        )
    }
    feedback?.let { events ->
        val choices = events.filter { it.decision in setOf("ACCEPTED", "ADJUSTED", "DIRECT", "REJECTED") }
        val accepted = choices.filter { it.decision in setOf("ACCEPTED", "ADJUSTED") }
        RecordDialog("顺路建议反馈", { feedback = null }) {
            Text(
                if (choices.isEmpty()) "还没有已完成的建议选择。"
                else "最近 ${choices.size} 次明确选择中，接受 ${accepted.size} 次（${accepted.size * 100 / choices.size}%）。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (accepted.isNotEmpty()) Text(
                "接受方案平均多花约 ${formatDuration(accepted.sumOf { it.selectedExtraSeconds } / accepted.size)}。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "建议选择与事件会随数据同步保存到电脑；这些事件本身不包含对话原文、任务名称或地点。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            events.take(15).forEach { entry ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                val decision = when (entry.decision) {
                    "ACCEPTED" -> "接受 ${entry.selectedTaskCount} 件事"
                    "ADJUSTED" -> "调整后选择 ${entry.selectedTaskCount} 件事"
                    "DIRECT" -> "选择直达"
                    "REJECTED" -> "取消建议"
                    else -> "改为手动调整"
                }
                Text(
                    "${displayTimestamp(entry.decidedAt)} · ${if (entry.mode == "cycling") "骑行" else "步行"} · $decision",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    history?.let { events ->
        RecordDialog("最近行程记录", { history = null }) {
            if (events.isEmpty()) Text("暂无行程记录")
            events.distinctBy { it.sessionId }.forEach { event ->
                val request = runCatching { JSONObject(event.requestJson) }.getOrNull()
                val destination = request?.optJSONObject("destination")?.let { Place.from(it).name } ?: "行程"
                val mode = if (request?.optString("mode") == "cycling") "骑行" else "步行"
                val label = when (event.state) {
                    "ARRIVED" -> "已到达"
                    "CANCELLED" -> "已结束导航"
                    "REPLANNING" -> "已重算"
                    else -> "已开始导航"
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$destination · $mode · $label", style = MaterialTheme.typography.titleSmall)
                    Text(displayTimestamp(event.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val route = runCatching { JSONObject(event.resultJson) }.getOrNull()
                    val stops = route?.optJSONArray("stops")
                    if (stops != null && stops.length() > 0) {
                        Text(
                            "原方案：" + (0 until stops.length()).joinToString(" → ") {
                                stops.getJSONObject(it).getJSONObject("place").getString("name")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (event.state in listOf("CANCELLED", "ARRIVED")) {
                        TextButton(
                            onClick = { history = null; repeatRoute(event) },
                            enabled = vm.result == null && !vm.restoringTrip && !vm.busy && !vm.updatingTrip,
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("重新规划到这里") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                }
            }
        }
    }
}
