package com.onmyway.app.feature.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.ThinkingDots
import com.onmyway.app.core.voice.CloudVoiceButton
import kotlinx.coroutines.delay

@Composable
internal fun AssistantChatHeader(historyLabel: String?, history: () -> Unit, routes: () -> Unit, settings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(history) { Icon(painterResource(R.drawable.ic_history_menu), "打开历史对话") }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text("助手", style = MaterialTheme.typography.titleMedium)
                    Text(
                        historyLabel ?: "OnMyWay",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(routes) { Icon(painterResource(R.drawable.ic_route), "路线记录", Modifier.size(22.dp)) }
                IconButton(settings) { Icon(painterResource(R.drawable.ic_settings), "设置", Modifier.size(22.dp)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
internal fun ChatBubble(line: ChatLine, allowCopy: Boolean = true) {
    val user = line.role == "user"
    val clipboard = LocalClipboardManager.current
    var copied by remember(line.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2_000); copied = false }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = maxWidth * 0.76f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (user) Spacer(Modifier.weight(1f)) else {
                AppIconBadge(R.drawable.ic_assistant, size = 34.dp)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.widthIn(max = bubbleWidth)) {
                Surface(
                    color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = if (user) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                        else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                    border = if (user) null else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                ) {
                    SelectionContainer {
                        Text(
                            line.text,
                            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            color = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (!user && allowCopy) {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(line.text)); copied = true },
                        modifier = Modifier.semantics { contentDescription = "复制回复" },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Icon(painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy), null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (user) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Text("我", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun AssistantWelcome(onPrompt: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val greeting = remember { ChatLine("assistant", "你好，今天有什么安排？\n告诉我待办和目的地，我来帮你理一理。") }
        ChatBubble(greeting, allowCopy = false)
        Column(Modifier.padding(start = 42.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("试试这样说", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("取快递、打印论文后回宿舍", "今天有哪些待办？", "明天提醒我打印论文").forEach { prompt ->
                Surface(
                    onClick = { onPrompt(prompt) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(prompt, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium)
                        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AssistantTypingIndicator() {
    Row(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconBadge(R.drawable.ic_assistant, size = 34.dp)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThinkingDots()
                Text("正在处理…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun AssistantComposer(
    input: String,
    onInput: (String) -> Unit,
    ready: Boolean,
    busy: Boolean,
    canSend: Boolean,
    showVoice: Boolean,
    toggleVoice: () -> Unit,
    server: String,
    onVoice: (String) -> Unit,
    onVoiceError: (String) -> Unit,
    send: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(toggleVoice, enabled = ready && !busy, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(if (showVoice) R.drawable.ic_keyboard else R.drawable.ic_mic),
                        if (showVoice) "切换文字输入" else "切换语音输入", Modifier.size(24.dp))
                }
                if (showVoice) {
                    Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        CloudVoiceButton(server, enabled = canSend, result = onVoice, failure = onVoiceError)
                    }
                } else {
                    Surface(
                        Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                    ) {
                        BasicTextField(
                            value = input,
                            onValueChange = { onInput(it.take(4000)) },
                            modifier = Modifier.testTag("assistant-input").semantics { contentDescription = "消息输入框" }.heightIn(min = 48.dp)
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            enabled = ready,
                            readOnly = busy,
                            maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (canSend && input.isNotBlank()) send() }),
                            decorationBox = { field ->
                                Box {
                                    if (input.isEmpty()) Text("说点什么…", style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    field()
                                }
                            },
                        )
                    }
                    FilledIconButton(
                        onClick = send,
                        enabled = canSend && input.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_send), "发送", Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}
