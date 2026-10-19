package com.onmyway.app

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.onmyway.app.core.reminder.scheduleReminders
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.AppConfirmDialog
import com.onmyway.app.core.ui.OnMyWayTheme
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.sync.BackupCredentials
import com.onmyway.app.data.sync.PcBackupSync
import com.onmyway.app.feature.assistant.AssistantScreen
import com.onmyway.app.feature.assistant.AssistantViewModel
import com.onmyway.app.feature.navigation.NavigationRuntime
import com.onmyway.app.feature.navigation.NavigationState
import com.onmyway.app.feature.planning.PlanScreen
import com.onmyway.app.feature.settings.SettingsScreen
import com.onmyway.app.feature.task.DeleteHost
import com.onmyway.app.feature.task.DeleteHostSaver
import com.onmyway.app.feature.task.TaskEditor
import com.onmyway.app.feature.task.TaskEditorHost
import com.onmyway.app.feature.task.TaskEditorHostSaver
import com.onmyway.app.feature.task.TaskScreen
import com.onmyway.app.feature.task.editorSnapshot
import com.onmyway.app.feature.task.errandFromEditorSnapshot
import com.onmyway.app.feature.task.stableKey

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
            OnMyWayTheme { OnMyWay() }
        }
    }
}

private data class HomeTab(val label: String, val icon: Int)

private val homeTabs = listOf(
    HomeTab("待办", R.drawable.ic_tasks),
    HomeTab("助手", R.drawable.ic_assistant),
    HomeTab("顺路", R.drawable.ic_route),
)

@Composable
private fun OnMyWay(vm: AppViewModel = viewModel()) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val chat: AssistantViewModel = viewModel { AssistantViewModel(app, vm) }
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(1) }
    var editor by rememberSaveable(stateSaver = TaskEditorHostSaver) {
        mutableStateOf<TaskEditorHost>(TaskEditorHost.Closed)
    }
    var deleting by rememberSaveable(stateSaver = DeleteHostSaver) { mutableStateOf<DeleteHost?>(null) }
    var navigationRequested by rememberSaveable { mutableStateOf(NavigationRuntime.current != null) }
    val navigationVisible = navigationRequested && vm.result != null && !vm.editingPlan && !vm.restoringTrip
    LaunchedEffect(vm.result, vm.navigationState, vm.navigationEndEvent?.id) {
        if (vm.result == null) {
            chat.routeResult = null
            if (vm.navigationState in listOf(NavigationState.CANCELLED, NavigationState.ARRIVED)) {
                chat.routeEnded(vm.navigationEndEvent, tasks)
            }
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
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { if (!navigationVisible) SnackbarHost(snackbar) },
        bottomBar = {
            if (!navigationVisible && !keyboardVisible) {
                AppBottomBar(tab) { tab = it }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            // 页面顶部一层极淡的绿色晨雾，给素色背景一点空间感，不干扰阅读。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            ),
                        ),
                    ),
            )
            Column(Modifier.fillMaxSize()) {
                if (!navigationVisible && tab != 3) SyncStatusNotice { tab = 3 }
                Crossfade(
                    targetState = if (navigationVisible) 2 else tab,
                    animationSpec = tween(220),
                    label = "main-tab",
                ) { current ->
                    when (current) {
                        0 -> TaskScreen(tasks, { editor = TaskEditorHost.New },
                            { editor = TaskEditorHost.Edit(it.id, it.editorSnapshot()) },
                            { deleting = DeleteHost(it.id, it.title) }, vm::save, vm::snoozeReminder, vm::dismissReminder)
                        1 -> AssistantScreen(vm, tasks, { tab = 3 }, { tab = 2 }, { navigationRequested = true }, chat)
                        2 -> PlanScreen(vm, tasks, navigationVisible) { navigationRequested = it }
                        else -> SettingsScreen(vm) { event ->
                            if (vm.result == null && chat.repeatRoute(event, vm)) tab = 1
                        }
                    }
                }
            }
        }
    }
    val host = editor
    if (host !is TaskEditorHost.Closed) key(host.stableKey) {
        val initial = when (host) {
            TaskEditorHost.New -> null
            is TaskEditorHost.Edit -> errandFromEditorSnapshot(host.snapshot)
            TaskEditorHost.Closed -> null
        }
        LaunchedEffect(host) {
            if (host is TaskEditorHost.Edit && vm.taskOrNull(host.taskId) == null) {
                editor = TaskEditorHost.Closed
                vm.message = "这条待办已不存在，编辑已关闭。"
            }
        }
        if (host is TaskEditorHost.Edit && initial == null) {
            LaunchedEffect(Unit) {
                editor = TaskEditorHost.Closed
                vm.message = "编辑内容无法恢复，请重新打开任务。"
            }
        } else TaskEditor(vm, initial, { editor = TaskEditorHost.Closed }, autoSaveOnDismiss = true) { updated ->
            if (host is TaskEditorHost.Edit) vm.saveExisting(updated) else vm.save(updated)
            editor = TaskEditorHost.Closed
        }
    }
    deleting?.let { host ->
        AppConfirmDialog(
            title = "删除这件事？",
            message = host.title,
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                val task = tasks.firstOrNull { it.id == host.taskId }
                if (task != null) vm.delete(task) else vm.message = "这条待办已不存在，无需删除。"
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun AppBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
                shadowElevation = 8.dp,
            ) {
                Row(Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 6.dp)) {
                    homeTabs.forEachIndexed { index, item ->
                        AppNavItem(
                            label = item.label,
                            icon = item.icon,
                            selected = selected == index,
                            center = index == 1,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.AppNavItem(
    label: String,
    icon: Int,
    selected: Boolean,
    center: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val indicator by animateColorAsState(
        when {
            selected && center -> MaterialTheme.colorScheme.primary
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        },
        animationSpec = tween(220),
        label = "nav-indicator",
    )
    val tint by animateColorAsState(
        when {
            selected && center -> MaterialTheme.colorScheme.onPrimary
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(220),
        label = "nav-tint",
    )
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .height(28.dp)
                .widthIn(min = 46.dp)
                .clip(CircleShape)
                .background(indicator),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, Modifier.size(21.dp), tint = tint)
        }
        Spacer(Modifier.height(1.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
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
        val text = when {
            !connected -> "尚未连接电脑，记录目前仅保存在手机"
            state == "conflict" -> "数据同步存在冲突，双方记录已保留"
            state == "syncing" -> "正在同步到电脑"
            else -> "本地记录等待同步到电脑"
        }
        Surface(
            onClick = openSettings,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Text(
                    text,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    if (!connected) "去连接" else "查看",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
