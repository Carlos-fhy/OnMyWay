package com.onmyway.app.feature.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
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
internal fun AssistantChatHeader(historyLabel: String?, history: () -> Unit, routes: () -> Unit, settings: () -> Unit,
    newConversation: () -> Unit = {}, compact: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val iconActions = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(history) { Icon(painterResource(R.drawable.ic_history_menu), "打开历史对话", Modifier.size(22.dp)) }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("助手", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    if (!compact) Text(
                        historyLabel ?: "OnMyWay",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (iconActions) {
                    IconButton(newConversation) {
                        Icon(painterResource(R.drawable.ic_add), "新对话", Modifier.size(22.dp))
                    }
                } else {
                    TextButton(newConversation, Modifier.semantics { contentDescription = "新对话" }) { Text("新对话") }
                }
                IconButton(routes) { Icon(painterResource(R.drawable.ic_route), "路线记录", Modifier.size(22.dp)) }
                IconButton(settings) { Icon(painterResource(R.drawable.ic_settings), "设置", Modifier.size(22.dp)) }
            }
        }
    }
}

@Composable
internal fun ChatBubble(line: ChatLine, allowCopy: Boolean = true, showAvatar: Boolean = true) {
    val user = line.role == "user"
    val clipboard = LocalClipboardManager.current
    var copied by remember(line.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2_000); copied = false }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = if (user) maxWidth * 0.86f else maxWidth
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        ) {
            if (!user && showAvatar) {
                Row(
                    Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_assistant), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("OnMyWay", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (user) {
                Surface(
                    modifier = Modifier.widthIn(max = bubbleWidth),
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                ) {
                    SelectionContainer {
                        Text(
                            line.text,
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                SelectionContainer {
                    Text(
                        line.text,
                        Modifier.widthIn(max = bubbleWidth),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (allowCopy) {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(line.text)); copied = true },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
                            contentDescription = "复制回复",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AssistantWelcome(onPrompt: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIconBadge(R.drawable.ic_assistant, size = 48.dp)
            Text("你好，今天有什么安排？", style = MaterialTheme.typography.headlineMedium)
            Text(
                "告诉我待办和目的地，我来帮你理一理。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("试试这样说", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("取快递、打印论文后回宿舍", "今天有哪些待办？", "明天提醒我打印论文").forEach { prompt ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPrompt(prompt) }
                        .heightIn(min = 52.dp)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(prompt, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun AssistantTypingIndicator() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThinkingDots()
        Text("正在处理…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onVoiceActivity: (Boolean) -> Unit = {},
) {
    // 同一个外壳容纳输入和操作，文字、语音切换沿用原状态。
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(toggleVoice, enabled = ready && !busy, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(if (showVoice) R.drawable.ic_keyboard else R.drawable.ic_mic),
                    if (showVoice) "切换文字输入" else "切换语音输入", Modifier.size(22.dp))
            }
            if (showVoice) {
                Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                    CloudVoiceButton(server, enabled = canSend, result = onVoice, failure = onVoiceError,
                        activityChanged = onVoiceActivity)
                }
            } else {
                BasicTextField(
                    value = input,
                    onValueChange = { onInput(it.take(4000)) },
                    modifier = Modifier.weight(1f).testTag("assistant-input")
                        .semantics { contentDescription = "消息输入框" }.heightIn(min = 48.dp)
                        .padding(horizontal = 4.dp, vertical = 11.dp),
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
                IconButton(
                    onClick = send,
                    enabled = canSend && input.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (canSend && input.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.ic_send),
                                "发送",
                                Modifier.size(19.dp),
                                tint = if (canSend && input.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
