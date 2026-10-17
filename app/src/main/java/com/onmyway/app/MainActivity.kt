package com.onmyway.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlin.math.ceil

private val LightPalette = lightColorScheme(
    primary = Color(0xFF1E5A44),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E8DD),
    onPrimaryContainer = Color(0xFF0D3323),
    secondaryContainer = Color(0xFFE5ECE6),
    onSecondaryContainer = Color(0xFF223026),
    tertiary = Color(0xFFC86C1D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC4),
    onTertiaryContainer = Color(0xFF3E1A00),
    background = Color(0xFFF8F9F5),
    surface = Color(0xFFF8F9F5),
    surfaceContainerLowest = Color.White,
    surfaceContainer = Color(0xFFEFF2EC),
    onSurface = Color(0xFF1B241F),
    onSurfaceVariant = Color(0xFF5A665E),
    outline = Color(0xFF75837A),
    outlineVariant = Color(0xFFDCE2DA),
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF56C691),
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF1E5A44),
    onPrimaryContainer = Color(0xFFB5E4CC),
    secondaryContainer = Color(0xFF233129),
    onSecondaryContainer = Color(0xFFD4E2D8),
    tertiary = Color(0xFFFDBA74),
    onTertiary = Color(0xFF4A2400),
    tertiaryContainer = Color(0xFF6B3705),
    onTertiaryContainer = Color(0xFFFFDCC4),
    background = Color(0xFF121815),
    surface = Color(0xFF121815),
    surfaceContainerLowest = Color(0xFF1A221E),
    surfaceContainer = Color(0xFF202A24),
    onSurface = Color(0xFFE0E6E1),
    onSurfaceVariant = Color(0xFFA0ACA3),
    outline = Color(0xFF859388),
    outlineVariant = Color(0xFF2C3931),
)

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        scheduleReminders(this)
        PcBackupSync.schedule(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null)
            getSharedPreferences("pc_backup", 0).edit().remove("needs_reload").apply()
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) DarkPalette else LightPalette) { OnMyWay() }
        }
    }
}

@Composable
private fun OnMyWay(vm: AppViewModel = viewModel()) {
    val chat: AssistantViewModel = viewModel()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Errand?>(null) }
    var deleting by remember { mutableStateOf<Errand?>(null) }
    var navigationRequested by rememberSaveable { mutableStateOf(NavigationRuntime.current != null) }
    val navigationVisible = navigationRequested && vm.result != null && !vm.editingPlan && !vm.restoringTrip
    LaunchedEffect(vm.result, vm.navigationState) {
        if (vm.result == null) {
            chat.routeResult = null
            if (vm.navigationState in listOf(NavigationState.CANCELLED, NavigationState.ARRIVED)) chat.routeEnded()
        }
    }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message, navigationVisible) {
        val message = vm.message
        if (message.isNotBlank() && !navigationVisible) {
            snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
            if (vm.message == message) vm.message = ""
        }
    }
    Scaffold(modifier = Modifier.imePadding(), snackbarHost = { if (!navigationVisible) SnackbarHost(snackbar) }, bottomBar = {
        if (!navigationVisible && !keyboardVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("待办" to R.drawable.ic_tasks, "助手" to R.drawable.ic_assistant, "顺路" to R.drawable.ic_route).forEachIndexed { i, (label, icon) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                    icon = { Icon(painterResource(icon), contentDescription = null) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!navigationVisible && tab != 3) SyncStatusNotice { tab = 3 }
            when (if (navigationVisible) 2 else tab) {
                0 -> TaskScreen(tasks, { adding = true }, { editing = it }, { deleting = it }, vm::save, vm::acknowledgeReminders)
                1 -> AssistantScreen(vm, tasks, { tab = 3 }, { tab = 2 }, { navigationRequested = true }, chat)
                2 -> PlanScreen(vm, tasks, navigationVisible) { navigationRequested = it }
                else -> SettingsScreen(vm) { event ->
                    if (vm.result == null && chat.repeatRoute(event, vm)) tab = 1
                }
            }
        }
    }
    if (adding || editing != null) TaskEditor(vm, editing, { adding = false; editing = null }) {
        vm.save(it); adding = false; editing = null
    }
    deleting?.let { task ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这件事？") },
            text = { Text(task.title) }, confirmButton = { TextButton(onClick = { vm.delete(task); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun SyncStatusNotice(openSettings: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pc_backup", 0) }
    var connected by remember { mutableStateOf(BackupCredentials.load(context) != null) }
    var state by remember { mutableStateOf(prefs.getString("sync_state", "pending")) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            connected = BackupCredentials.load(context) != null
            state = prefs.getString("sync_state", "pending")
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    if (!connected || state != "synced") {
        TextButton(onClick = openSettings, modifier = Modifier.fillMaxWidth()) {
            Text(when {
                !connected -> "尚未连接电脑，记录目前仅保存在手机 · 去连接"
                state == "conflict" -> "数据同步存在冲突，双方记录已保留 · 查看"
                state == "syncing" -> "正在同步到电脑"
                else -> "本地记录等待同步到电脑 · 查看"
            }, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Header(kicker: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kicker, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ChoiceRow(options: List<Pair<String, String>>, selected: String, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) -> FilterChip(selected == key, { change(key) }, label = { Text(label) }) }
    }
}

@Composable
private fun InfoCard(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun TaskEditor(vm: AppViewModel, task: Errand?, dismiss: () -> Unit,
    allowIncompleteDraft: Boolean = false, saveLabel: String = "保存任务", save: (Errand) -> Unit) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf(task?.title ?: "") }
    var duration by rememberSaveable { mutableStateOf(task?.serviceMinutes?.toString() ?: "5") }
    var deadline by rememberSaveable { mutableStateOf(task?.deadline?.takeIf { it.isNotBlank() }?.let(::displayTime) ?: "") }
    var priority by rememberSaveable { mutableIntStateOf(task?.priority ?: 2) }
    var plannedDate by rememberSaveable { mutableStateOf(task?.plannedDate.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(task?.placeType ?: "SUPERMARKET") }
    var fixed by rememberSaveable { mutableStateOf(task?.place() != null) }
    var selectedPlaceJson by rememberSaveable { mutableStateOf(task?.placeJson.orEmpty()) }
    val place = selectedPlaceJson.takeIf { it.isNotBlank() }?.let { Place.from(JSONObject(it)) }
    var search by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var more by rememberSaveable { mutableStateOf(task?.deadline?.isNotBlank() == true) }
    val mustSpecify = requiresFixedPlace(type)
    val storedTasks by vm.tasks.collectAsStateWithLifecycle()
    val recentPlaces = storedTasks.filter { it.placeType == type }.mapNotNull { it.place() }.distinctBy { it.id }.take(3)
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.92f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (task == null) "记下一件事" else "编辑任务", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("要办什么") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    val current = runCatching { java.time.LocalDate.parse(plannedDate) }.getOrElse { java.time.LocalDate.now() }
                    DatePickerDialog(context, { _, year, month, day ->
                        plannedDate = java.time.LocalDate.of(year, month + 1, day).toString()
                    }, current.year, current.monthValue - 1, current.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "计划日期" }) { Text(plannedDate.ifBlank { "设置计划日期（可选）" }) }
                if (plannedDate.isNotBlank()) TextButton({ plannedDate = "" }) { Text("清除计划日期") }
                Text("计划日期用于安排待办；有必须完成的时限时，再设置截止时间。", style = MaterialTheme.typography.bodySmall)
                Text("优先级", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(priorities.map { it.key.toString() to it.value }, priority.toString()) { priority = it.toInt() }
                Text("去哪办", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(placeTypes.toList(), type) { if (type != it) { type = it; selectedPlaceJson = "" } }
                if (!mustSpecify) ChoiceRow(listOf("type" to "系统选顺路门店", "fixed" to "指定门店"), if (fixed) "fixed" else "type") { fixed = it == "fixed" }
                if (fixed || mustSpecify) {
                    if (mustSpecify) Text("按取件/取餐通知选择实际领取点", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { search = true }, modifier = Modifier.fillMaxWidth()) { Text(place?.name ?: "选择领取地点") }
                    if (vm.familiarPlaces.isNotEmpty()) {
                        Text("从常用地点选择本次实际领取点", style = MaterialTheme.typography.bodySmall)
                        ChoiceRow(vm.familiarPlaces.map { (alias, saved) -> alias to "$alias · ${saved.name}" },
                            vm.familiarPlaces.entries.firstOrNull { it.value.id == place?.id }?.key ?: "") { alias ->
                            selectedPlaceJson = vm.familiarPlaces[alias]?.json()?.toString().orEmpty()
                        }
                    }
                    if (recentPlaces.isNotEmpty()) ChoiceRow(recentPlaces.map { it.id to it.name }, place?.id ?: "") { id ->
                        selectedPlaceJson = recentPlaces.first { it.id == id }.json().toString()
                    }
                } else {
                    Text("规划时选店，方案中可换一家。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("预计办事时间", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(listOf("5" to "5分钟", "10" to "10分钟", "15" to "15分钟", "20" to "20分钟", "30" to "30分钟"), duration) { duration = it }
                OutlinedTextField(duration, { duration = it }, label = { Text("自定义办事分钟数（1–240）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton({ more = !more }) { Text(if (more) "收起截止时间" else "设置截止时间（可选）") }
                if (more) {
                    OutlinedButton(onClick = {
                        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                        val current = runCatching { LocalDateTime.parse(deadline, format) }.getOrElse { LocalDateTime.now().plusHours(1) }
                        DatePickerDialog(context, { _, year, month, day ->
                            TimePickerDialog(context, { _, hour, minute ->
                                deadline = LocalDateTime.of(year, month + 1, day, hour, minute).format(format)
                            }, current.hour, current.minute, true).show()
                        }, current.year, current.monthValue - 1, current.dayOfMonth).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text(deadline.ifBlank { "选择截止时间 · 当前不限" }) }
                    if (deadline.isNotBlank()) TextButton({ deadline = "" }) { Text("清除截止时间") }
                }
                if (task != null && more) {
                    Text("创建 ${displayTimestamp(task.createdAt)}\n修改 ${displayTimestamp(task.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                    if (task.completedAt > 0) Text("完成 ${displayTimestamp(task.completedAt)}", style = MaterialTheme.typography.bodySmall)
                    if (task.lastRemindedAt > 0) Text("上次提醒 ${displayTimestamp(task.lastRemindedAt)}", style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    try {
                        require(title.isNotBlank()) { "请填写任务名称" }
                        val minutes = duration.toIntOrNull()
                        require(minutes != null && minutes in 1..240) { "办事时长应为 1–240 分钟" }
                        require(allowIncompleteDraft || !(fixed || mustSpecify) || place != null) { "请选择实际领取地点" }
                        val originalDeadline = task?.deadline.orEmpty()
                        val originalDisplay = originalDeadline.takeIf { it.isNotBlank() }?.let(::displayTime).orEmpty()
                        val date = if (deadline == originalDisplay) originalDeadline else try {
                            parseTime(deadline) ?: ""
                        } catch (e: Exception) { throw IllegalArgumentException("截止时间格式应为 yyyy-MM-dd HH:mm") }
                        val updated = (task ?: Errand(title = title.trim())).copy(title = title.trim(), priority = priority,
                            serviceMinutes = minutes, deadline = date, plannedDate = plannedDate,
                            placeJson = if (fixed || mustSpecify) place?.json()?.toString().orEmpty() else "", placeType = type)
                        save(updated)
                    } catch (e: Exception) { error = e.message ?: "请检查输入" }
                }, modifier = Modifier.fillMaxWidth()) { Text(saveLabel) }
                TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
    if (search) PlacePicker(vm, { search = false }) { selectedPlaceJson = it.json().toString(); search = false }
}

@Composable
internal fun PlacePicker(vm: AppViewModel, dismiss: () -> Unit, initialKeyword: String = "", choose: (Place) -> Unit) {
    var keyword by rememberSaveable(initialKeyword) { mutableStateOf(initialKeyword) }
    var city by rememberSaveable { mutableStateOf(vm.city) }
    var items by remember { mutableStateOf(emptyList<Place>()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun doSearch() {
        if (loading || keyword.isBlank() || city.isBlank()) return
        loading = true; items = emptyList(); message = ""
        scope.launch {
            try { items = vm.search(keyword.trim(), city.trim()); if (items.isEmpty()) message = "没有找到地点，请换个关键词。" }
            catch (e: Exception) { message = networkMessage(e) }
            finally { loading = false }
        }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.88f), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择地点", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(city, { city = it; items = emptyList() }, enabled = !loading, label = { Text("所在城市") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keyword, { keyword = it; items = emptyList() }, enabled = !loading, label = { Text("地点名称 / 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { doSearch() }))
                Button(onClick = { doSearch() }, enabled = !loading && keyword.isNotBlank() && city.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (loading) "搜索中…" else "搜索高德地点") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                if (items.isEmpty() && !loading && vm.familiarPlaces.isNotEmpty()) {
                    Text("常用地点快捷选择：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.familiarPlaces.forEach { (alias, place) ->
                            AssistChip(onClick = { choose(place) }, label = { Text("$alias · ${place.name}") })
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id }) { place ->
                        OutlinedCard(onClick = { choose(place) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(place.name, fontWeight = FontWeight.SemiBold)
                                Text(place.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: AppViewModel, repeatRoute: (RouteHistory) -> Unit) {
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
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header("ON MY WAY · 设置", "准备好，再出发。", "连接电脑数据服务后，记录自动同步；手机保留离线数据。")
        FamiliarPlacesSettings(vm)
        OutlinedTextField(preferredDetour, { preferredDetour = it.filter(Char::isDigit).take(3) },
            label = { Text("默认顺路预算（分钟）") },
            supportingText = { Text("没有足够历史选择时使用此预算；0 表示不额外绕路。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(walkingDetour, { walkingDetour = it.filter(Char::isDigit).take(3) },
            label = { Text("步行顺路预算（可留空）") },
            supportingText = { Text("填写后优先采用你的设定；留空时参考步行历史选择。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(cyclingDetour, { cyclingDetour = it.filter(Char::isDigit).take(3) },
            label = { Text("骑行顺路预算（可留空）") },
            supportingText = { Text("填写后优先采用你的设定；留空时参考骑行历史选择。") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("根据建议选择调整预算", modifier = Modifier.weight(1f))
            Switch(checked = vm.learningEnabled, onCheckedChange = vm::updateLearningEnabled)
        }
        Text("学习记录包含出行方式、时间段、事项数量与额外耗时，开启同步后会保存在电脑。建议仍需你确认。", style = MaterialTheme.typography.bodySmall)
        TextButton({ confirmClearLearning = true }) { Text("清空建议学习记录") }
        OutlinedTextField(server, { server = it }, label = { Text("规划服务地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(city, { city = it }, label = { Text("常用搜索城市") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
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
        }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
        OutlinedButton(vm::health, enabled = !vm.checking && server.trim().trimEnd('/') == vm.server, modifier = Modifier.fillMaxWidth()) { Text(if (vm.checking) "检查中…" else "检查已保存地址的连接") }
        HorizontalDivider()
        Text("电脑数据同步", style = MaterialTheme.typography.titleMedium)
        Text("使用同一个数据服务口令连接。新安装会先读取电脑记录；离线修改留在手机，联网后提交。电脑确认保存后才显示同步成功，两端发生冲突时保留双方记录。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(backupToken, { backupToken = it }, label = { Text("数据服务口令") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { scope.launch {
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
            }
            finally { backupBusy = false }
        } }, enabled = !backupBusy && (backupToken.length >= 32 || backupConfigured) && server.trim().trimEnd('/') == vm.server,
            modifier = Modifier.fillMaxWidth()) { Text(if (backupBusy) "同步中…" else "连接电脑并同步") }
        if (backupConfigured) {
            Text(when (taskProtocol) {
                1 -> "待办由电脑保存，手机支持离线使用。"
                0 -> "当前连接旧版数据服务，请升级电脑后端以启用待办版本校验。"
                else -> "首次同步完成后确认待办保存状态。"
            }, style = MaterialTheme.typography.bodySmall)
            Text(when (syncState) {
                "synced" -> "已同步到电脑"
                "syncing" -> "正在同步"
                "conflict" -> "存在版本冲突，双方记录均已保留"
                else -> "本地修改等待同步"
            }, style = MaterialTheme.typography.bodySmall)
            Text(if (lastBackupAt > 0) "最近成功同步：${displayTimestamp(lastBackupAt)}" else "已连接数据服务，尚未完成同步",
                style = MaterialTheme.typography.bodySmall)
            if (lastBackupError.isNotBlank()) Text("最近同步失败：$lastBackupError",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (backupConfigured) TextButton({
            PcBackupSync.disable(context)
            backupConfigured = false
            vm.message = "自动同步已暂停；电脑记录仍保留。"
        }) { Text("暂停自动同步") }
        if (needsReload) Text("已从电脑恢复记录，请关闭并重新打开 App，以加载恢复的对话与设置。",
            color = MaterialTheme.colorScheme.primary)
        Text("重装后使用原来的服务地址与口令连接即可恢复。手机尚未同步的修改，卸载后无法从电脑找回。", style = MaterialTheme.typography.bodySmall)
        TextButton({ scope.launch {
            try { history = vm.routeHistory() } catch (_: Exception) { vm.message = "行程记录读取失败" }
        } }) { Text("行程记录") }
        TextButton({ scope.launch {
            try { feedback = vm.suggestionDecisions() } catch (_: Exception) { vm.message = "建议反馈读取失败" }
        } }) { Text("顺路建议反馈") }
        InfoCard("连接你的电脑", "USB 调试转发后使用 http://127.0.0.1:8000。Android 模拟器访问电脑可用 http://10.0.2.2:8000。具体启动步骤见项目 README。")
        InfoCard("关于地点与隐私", "点击「使用当前位置」后才获取手机位置，并通过本地服务交给高德转换坐标与识别地址。退出导航会结束本次行程，方案留在行程记录；再次出发会重新定位规划。")
        Text("OnMyWay 0.1 · 步行 / 骑行", style = MaterialTheme.typography.labelMedium)
    }
    if (confirmClearLearning) AlertDialog(onDismissRequest = { confirmClearLearning = false },
        title = { Text("清空建议学习记录？") },
        text = { Text("这会删除本机的建议选择与事件记录，不删除待办和行程。") },
        confirmButton = { TextButton({
            confirmClearLearning = false
            scope.launch {
                try { vm.clearLearningHistory(); feedback = emptyList() }
                catch (_: Exception) { vm.message = "清空记录失败，请重试" }
            }
        }) { Text("清空") } },
        dismissButton = { TextButton({ confirmClearLearning = false }) { Text("取消") } })
    feedback?.let { events ->
        val choices = events.filter { it.decision in setOf("ACCEPTED", "ADJUSTED", "DIRECT", "REJECTED") }
        val accepted = choices.filter { it.decision in setOf("ACCEPTED", "ADJUSTED") }
        AlertDialog(onDismissRequest = { feedback = null }, title = { Text("顺路建议反馈") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (choices.isEmpty()) "还没有已完成的建议选择。" else
                    "最近 ${choices.size} 次明确选择中，接受 ${accepted.size} 次（${accepted.size * 100 / choices.size}%）。")
                if (accepted.isNotEmpty()) Text("接受方案平均多花约 ${formatDuration(accepted.sumOf { it.selectedExtraSeconds } / accepted.size)}。")
                Text("建议选择与事件会随数据同步保存到电脑；这些事件本身不包含对话原文、任务名称或地点。", style = MaterialTheme.typography.bodySmall)
                events.take(15).forEach { entry ->
                    HorizontalDivider()
                    val decision = when (entry.decision) {
                        "ACCEPTED" -> "接受 ${entry.selectedTaskCount} 件事"
                        "ADJUSTED" -> "调整后选择 ${entry.selectedTaskCount} 件事"
                        "DIRECT" -> "选择直达"
                        "REJECTED" -> "取消建议"
                        else -> "改为手动调整"
                    }
                    Text("${displayTimestamp(entry.decidedAt)} · ${if (entry.mode == "cycling") "骑行" else "步行"} · $decision", style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton({ feedback = null }) { Text("关闭") } })
    }
    history?.let { events -> AlertDialog(onDismissRequest = { history = null }, title = { Text("最近行程记录") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (events.isEmpty()) Text("暂无行程记录")
            events.distinctBy { it.sessionId }.forEach { event ->
                val request = runCatching { JSONObject(event.requestJson) }.getOrNull()
                val destination = request?.optJSONObject("destination")?.let { Place.from(it).name } ?: "行程"
                val mode = if (request?.optString("mode") == "cycling") "骑行" else "步行"
                val label = when (event.state) { "ARRIVED" -> "已到达"; "CANCELLED" -> "已结束导航"; "REPLANNING" -> "已重算"; else -> "已开始导航" }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$destination · $mode · $label", style = MaterialTheme.typography.titleSmall)
                    Text(displayTimestamp(event.timestamp), style = MaterialTheme.typography.bodySmall)
                    val route = runCatching { JSONObject(event.resultJson) }.getOrNull()
                    val stops = route?.optJSONArray("stops")
                    if (stops != null && stops.length() > 0) {
                        Text("原方案：" + (0 until stops.length()).joinToString(" → ") {
                            stops.getJSONObject(it).getJSONObject("place").getString("name")
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    if (event.state in listOf("CANCELLED", "ARRIVED")) {
                        TextButton(onClick = { history = null; repeatRoute(event) },
                            enabled = vm.result == null && !vm.restoringTrip && !vm.busy && !vm.updatingTrip) { Text("重新规划到这里") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }, confirmButton = { TextButton({ history = null }) { Text("关闭") } }) }
}
