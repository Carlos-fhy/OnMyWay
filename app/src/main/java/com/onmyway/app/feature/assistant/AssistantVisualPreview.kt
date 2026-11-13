package com.onmyway.app.feature.assistant

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.onmyway.app.core.ui.OnMyWayTheme

/** 使用实际组件的设计预览；不读取业务数据或发起请求。 */
@Preview(name = "对话 · 浅色", widthDp = 360, heightDp = 760)
@Preview(name = "对话 · 深色", widthDp = 360, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "对话 · 大字体", widthDp = 320, heightDp = 760, fontScale = 1.3f)
@Composable
private fun AssistantConversationPreview() {
    AssistantPreviewFrame(welcome = false)
}

@Preview(name = "助手 · 空状态", widthDp = 360, heightDp = 760)
@Composable
private fun AssistantWelcomePreview() {
    AssistantPreviewFrame(welcome = true)
}

@Composable
private fun AssistantPreviewFrame(welcome: Boolean) {
    OnMyWayTheme {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AssistantChatHeader(null, {}, {}, {})
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (welcome) item { AssistantWelcome {} }
                else {
                    item { ChatBubble(ChatLine("user", "取快递、打印论文后回宿舍")) }
                    item { ChatBubble(ChatLine("assistant", "先核对快递领取点和打印店，再选择步行或骑行。我会给出沿途安排，确认后再开始导航。")) }
                    item { ChatBubble(ChatLine("user", "先不打印了，只取快递。")) }
                    item { AssistantTypingIndicator() }
                }
            }
            AssistantComposer(
                input = "", onInput = {}, ready = true, busy = false, canSend = true,
                showVoice = false, toggleVoice = {}, server = "", onVoice = {}, onVoiceError = {}, send = {},
            )
        }
    }
}
