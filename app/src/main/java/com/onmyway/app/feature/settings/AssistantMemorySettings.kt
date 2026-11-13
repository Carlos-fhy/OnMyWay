package com.onmyway.app.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.onmyway.app.core.ui.AppConfirmDialog
import com.onmyway.app.core.ui.AppDialogCard
import com.onmyway.app.core.ui.AppPrimaryButton
import com.onmyway.app.core.ui.appTextFieldColors
import com.onmyway.app.feature.assistant.AssistantViewModel
import com.onmyway.app.data.remote.Api
import com.onmyway.app.data.sync.BackupCredentials
import com.onmyway.app.feature.assistant.personalMemoryCacheKey
import com.onmyway.app.feature.assistant.PersonalMemoryItem
import com.onmyway.app.feature.assistant.PersonalMemorySnapshot
import java.time.LocalDate
import java.time.ZoneId

private val memoryTopicLabels = linkedMapOf(
    "general" to "通用", "printing" to "打印", "travel" to "出行", "food" to "饮食", "errand" to "办事",
)
private val memoryKindLabels = linkedMapOf(
    "preference" to "偏好", "context" to "情境", "episode" to "经历", "insight" to "归纳",
)
private fun expiresAfter(days: Long): String = LocalDate.now().plusDays(days)
    .atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime().toString()

private fun memoryConnectionKey(context: Context, server: String): String = runCatching {
    val base = Api.validateBase(server)
    BackupCredentials.load(context)?.takeIf { it.server == base }
        ?.let { personalMemoryCacheKey(base, it.token) }.orEmpty()
}.getOrDefault("")

internal data class MemorySettingsUiState(
    val ready: Boolean,
    val busy: Boolean,
    val connected: Boolean,
    val pending: Boolean,
    val referenceAllowed: Boolean,
    val learningAllowed: Boolean,
    val snapshot: PersonalMemorySnapshot?,
    val evidence: List<PersonalMemoryItem> = emptyList(),
    val legacyCount: Int = 0,
    val message: String = "",
)

@Composable
internal fun AssistantMemorySettings(chat: AssistantViewModel, server: String) {
    val context = LocalContext.current
    LaunchedEffect(server) { chat.refreshPersonalMemory(server) }
    PersonalMemorySettingsContent(
        MemorySettingsUiState(chat.ready, chat.memoryBusy, chat.personalMemoryConnected, chat.personalMemoryPending,
            chat.personalReferenceAllowed, chat.personalLearningAllowed, chat.personalMemory,
            chat.personalMemoryEvidence, chat.memories.size, chat.memoryMessage),
        server = server,
        connectionKey = { memoryConnectionKey(context, server) },
        refresh = { chat.refreshPersonalMemory(server) },
        retry = { recovered -> chat.retryPersonalMemory(server, recovered) },
        controls = { reference, learning -> chat.setPersonalMemorySettings(server, reference, learning) },
        save = { previous, text, kind, topic, expiry, saved ->
            chat.savePersonalMemory(server, previous, text, kind, topic, expiry, saved)
        },
        forget = { chat.forgetPersonalMemory(server, it) },
        importLegacy = { chat.importLegacyPersonalMemory(server) },
        reflect = { chat.reflectPersonalMemory(server) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonalMemorySettingsContent(
    state: MemorySettingsUiState,
    server: String,
    connectionKey: () -> String,
    refresh: () -> Unit,
    retry: (() -> Unit) -> Unit,
    controls: (Boolean, Boolean) -> Unit,
    save: (PersonalMemoryItem?, String, String, String, String?, () -> Unit) -> Unit,
    forget: (PersonalMemoryItem) -> Unit,
    importLegacy: () -> Unit,
    reflect: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var displayLimit by rememberSaveable(filter, server) { mutableIntStateOf(30) }
    var editingId by rememberSaveable { mutableStateOf("") }
    var editingRevision by rememberSaveable { mutableLongStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("preference") }
    var topic by rememberSaveable { mutableStateOf("general") }
    var expiry by rememberSaveable { mutableStateOf("") }
    var deletingId by rememberSaveable { mutableStateOf("") }
    var editingStore by rememberSaveable { mutableStateOf("") }
    var editingConnection by rememberSaveable { mutableStateOf("") }
    var originalContent by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var editorError by rememberSaveable { mutableStateOf("") }
    var editorSubmitted by rememberSaveable { mutableStateOf(false) }
    var evidenceOpen by rememberSaveable { mutableStateOf(false) }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var deletingStore by rememberSaveable { mutableStateOf("") }
    var deletingConnection by rememberSaveable { mutableStateOf("") }
    var deletingRevision by rememberSaveable { mutableLongStateOf(0) }
    var actionError by rememberSaveable { mutableStateOf("") }
    val snapshot = state.snapshot
    val items = snapshot?.items.orEmpty().filter { it.status == "active" }
    val filteredItems = items.filter { filter == "all" || it.kind == filter }
    val available = state.ready && state.connected && !state.busy && !state.pending

    fun contentKey() = listOf(draft, kind, topic, expiry).joinToString("\u0000")
    fun closeEditor() { editingId = ""; confirmDiscard = false; editorError = ""; editorSubmitted = false }
    fun requestCloseEditor() {
        if (!state.busy) {
            if (contentKey() != originalContent) confirmDiscard = true else closeEditor()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MemorySwitch("参考个人记忆", "让助手参考适用的偏好、临时情境和经历。",
            state.referenceAllowed && snapshot?.referenceEnabled == true,
            state.ready && !state.busy && !state.pending) {
            controls(it, state.learningAllowed && snapshot?.learningEnabled == true)
        }
        MemorySwitch("自动保存个人记忆", "保存对话中的偏好、临时情境，以及实际任务和出行经历。",
            state.learningAllowed && snapshot?.learningEnabled == true,
            state.ready && !state.busy && !state.pending && state.referenceAllowed && snapshot?.referenceEnabled == true) {
            controls(state.referenceAllowed, it)
        }
        Text("预算自动调整在「出行偏好」中单独控制。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text(when {
            state.busy -> "正在更新记忆…"
            state.pending -> "上次更新结果待确认"
            state.connected -> "已连接 · ${items.size} 条记忆"
            snapshot != null -> "离线查看 · ${items.size} 条缓存记录"
            else -> "尚未连接个人记忆服务"
        }, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { refresh() }, enabled = !state.busy) { Text("刷新") }
            if (state.pending) OutlinedButton(onClick = { retry({}) }, enabled = !state.busy) {
                Text("获取上次更新结果")
            }
        }
        if (state.message.isNotBlank()) {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionError.isNotBlank()) {
            Text(actionError, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        if (state.evidence.isNotEmpty()) {
            TextButton(onClick = { evidenceOpen = !evidenceOpen }, contentPadding = PaddingValues(0.dp)) {
                Text(if (evidenceOpen) "收起本轮依据" else "查看本轮依据")
            }
            if (evidenceOpen) {
                state.evidence.forEach { item -> Text("• ${item.text}", style = MaterialTheme.typography.bodySmall) }
                Text("曾提供这些信息，不代表每条都被采用。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("记忆记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                editingId = "new"; editingRevision = 0; draft = ""; kind = "preference"; topic = "general"; expiry = ""
                editingStore = snapshot?.storeId.orEmpty(); editingConnection = connectionKey()
                originalContent = contentKey(); editorError = ""; editorSubmitted = false
            }, enabled = available) { Text("添加") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (linkedMapOf("all" to "全部") + memoryKindLabels).forEach { (value, label) ->
                FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) })
            }
        }
        if (filteredItems.isEmpty()) {
            Text(if (items.isEmpty()) "还没有记忆。可以添加一项偏好，或描述需要考虑的情况。" else "这个分类下还没有记录。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        filteredItems.take(displayLimit).forEach { item ->
            key(item.id) {
                val origin = when (item.sourceKind) {
                    "manual" -> "手动记录"
                    "conversation" -> "对话原话"
                    "event" -> "实际事件"
                    "derived" -> "根据经历整理"
                    else -> "从本机旧记忆导入"
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${memoryKindLabels[item.kind]} · ${memoryTopicLabels[item.topic] ?: "通用"} · $origin",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.text, style = MaterialTheme.typography.bodyMedium)
                    if (item.quote.isNotBlank() && item.quote != item.text) {
                        Text("原话：${item.quote}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val range = when (item.scope) { "session" -> "指定会话"; "trip" -> "指定行程"; else -> "跨会话" }
                    Text(range + (item.validUntil?.let { " · 有效至 ${it.take(16).replace('T', ' ')}" } ?: "") +
                        (if (item.activeAt()) "" else " · 当前不生效"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.derivedFrom.isNotEmpty()) {
                        Text("关联 ${item.derivedFrom.size} 条来源；来源被纠正或忘记后，这条归纳会失效。",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (item.editable) TextButton(onClick = {
                            editingId = item.id; editingRevision = item.revision; draft = item.text
                            kind = item.kind; topic = item.topic; expiry = item.validUntil.orEmpty()
                            editingStore = snapshot?.storeId.orEmpty(); editingConnection = connectionKey()
                            originalContent = contentKey(); editorError = ""; editorSubmitted = false
                        }, enabled = available) { Text("纠正") }
                        TextButton(onClick = {
                            deletingId = item.id; deletingRevision = item.revision
                            deletingStore = snapshot?.storeId.orEmpty(); deletingConnection = connectionKey()
                        }, enabled = available) { Text("忘记") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        if (filteredItems.size > displayLimit) TextButton(onClick = { displayLimit += 30 }) {
            Text("查看更多（还有 ${filteredItems.size - displayLimit} 条）")
        }
        if (state.legacyCount > 0 || items.any { it.kind == "episode" }) {
            TextButton(onClick = { toolsOpen = !toolsOpen }, contentPadding = PaddingValues(0.dp)) {
                Text(if (toolsOpen) "收起整理与导入" else "整理与导入")
            }
            if (toolsOpen) {
                if (items.any { it.kind == "episode" }) OutlinedButton(onClick = { reflect() }, enabled = available) {
                    Text("整理已有经历")
                }
                if (state.legacyCount > 0) OutlinedButton(onClick = { importLegacy() }, enabled = available) {
                    Text("导入本机的 ${state.legacyCount} 条旧偏好")
                }
            }
        }
        Text("记忆保存在个人服务，手机副本供离线查看。忘记会停止使用相关归纳，聊天原文仍保留。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (editingId.isNotBlank()) {
        val previous = items.firstOrNull { it.id == editingId }
        val target = MemoryEditTarget(editingConnection, editingStore, editingId.takeUnless { it == "new" }.orEmpty(), editingRevision)
        val issue = memoryEditIssue(target, connectionKey(), snapshot)
        AppDialogCard(if (editingId == "new") "添加记忆" else "纠正记忆", ::requestCloseEditor) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (issue != null) Text(issue, color = MaterialTheme.colorScheme.error)
                if (editorError.isNotBlank()) Text(editorError, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(value = draft, onValueChange = { draft = it.take(1000) }, label = { Text("需要记住的内容") },
                    minLines = 2, maxLines = 5, enabled = !state.busy && !state.pending, modifier = Modifier.fillMaxWidth(),
                    colors = appTextFieldColors(), supportingText = { Text("${draft.length} / 1000") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = kind == "preference", onClick = { kind = "preference"; expiry = "" },
                        label = { Text("长期偏好") }, enabled = available)
                    FilterChip(selected = kind == "context", onClick = { kind = "context"; expiry = expiresAfter(1) },
                        label = { Text("临时情境") }, enabled = available)
                }
                if (kind == "context") {
                    Text("情境会在期限结束后退出当前召回。", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { expiry = expiresAfter(1) }, enabled = available) { Text("今天有效") }
                        OutlinedButton(onClick = { expiry = expiresAfter(7) }, enabled = available) { Text("未来七天") }
                    }
                    Text("有效至 ${expiry.take(16).replace('T', ' ')}", style = MaterialTheme.typography.labelSmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    memoryTopicLabels.forEach { (value, label) ->
                        FilterChip(selected = topic == value, onClick = { topic = value }, label = { Text(label) }, enabled = available)
                    }
                }
                AppPrimaryButton("保存记忆", onClick = {
                    val latestIssue = memoryEditIssue(target, connectionKey(), state.snapshot)
                    if (latestIssue != null) editorError = latestIssue else {
                        editorSubmitted = true
                        save(previous, draft, kind, topic,
                            expiry.takeIf { kind == "context" && it.isNotBlank() }, ::closeEditor)
                    }
                }, enabled = available && issue == null && draft.isNotBlank() && (kind != "context" || expiry.isNotBlank()))
                if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.pending) OutlinedButton(onClick = {
                    if (editingConnection != connectionKey() || editingStore != state.snapshot?.storeId) {
                        editorError = "连接已变化，请回到原服务核对上次更新。"
                    } else {
                        val recoveringId = editingId
                        retry({
                            if (editorSubmitted && editingId == recoveringId && editingConnection == target.connectionKey &&
                                editingStore == target.storeId) closeEditor()
                        })
                    }
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("获取上次更新结果") }
                TextButton(onClick = ::requestCloseEditor, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
    if (confirmDiscard) AppConfirmDialog("放弃未保存的编辑？",
        if (state.pending) "上次更新可能已经保存，关闭编辑不会取消服务端操作；请在记忆页获取结果。"
        else "尚未提交的修改将被丢弃，原来的记忆保留。",
        "放弃编辑", onConfirm = ::closeEditor, onDismiss = { confirmDiscard = false }, dismissLabel = "继续编辑")
    items.firstOrNull { it.id == deletingId }?.let { item ->
        AppConfirmDialog(title = "忘记这条记忆？", message = item.text + "\n相关归纳也会停止使用。聊天原文仍保留。",
            confirmLabel = "忘记", destructive = true,
            onConfirm = {
                val target = MemoryEditTarget(deletingConnection, deletingStore, deletingId, deletingRevision)
                val issue = memoryEditIssue(target, connectionKey(), state.snapshot)
                if (issue == null && available) forget(item)
                else actionError = issue ?: "请先恢复连接或获取上次更新结果。"
                deletingId = ""
            }, onDismiss = { deletingId = "" })
    }
}

@Composable
private fun MemorySwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = change, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title })
    }
}
