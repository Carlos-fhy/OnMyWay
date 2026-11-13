package com.onmyway.app.feature.assistant

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AssistantConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val count: Int,
)

/** Legacy requests have no explicit conversation key; their original local date is stable. */
internal fun requestConversationId(contextKey: String, createdAt: Long): String =
    JSONObject(contextKey).optString("conversation_id").takeIf { it.isNotBlank() }
        ?: conversationKey(createdAt)

internal fun conversationSwitchIssue(
    ready: Boolean, working: Boolean, pending: Boolean, draft: Boolean, input: String,
): String? = when {
    !ready -> "正在读取对话，请稍后再切换。"
    pending -> "请先取回上一条请求的结果，或重新编辑并处理后再切换对话。"
    working -> "当前操作尚未完成，请稍后再切换对话。"
    draft -> "请先保存或取消当前待确认建议，再切换对话。"
    input.isNotBlank() -> "输入框还有未发送内容，请先发送或清空，再切换对话。"
    else -> null
}

/** Business replies are generated locally; the server's memory receipt must remain visible. */
internal fun appendMemoryFeedback(reply: String, action: String, answer: JSONObject): String {
    if (action == "reply") return reply
    val feedback = answer.optJSONObject("memory_context")?.optString("feedback").orEmpty()
    return listOf(reply, feedback).filter(String::isNotBlank).joinToString(" ")
}

internal fun assistantContextText(line: ChatLine, latest: Boolean, now: Long, sessionStartedAt: Long): String {
    if (latest) return line.text.take(4_000)
    val time = Instant.ofEpochMilli(line.createdAt).atZone(ZoneId.systemDefault())
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val prefix = when {
        sessionStartedAt > 0 && line.createdAt < sessionStartedAt ->
            "[历史对话背景 ${time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}；当时的临时条件、建议和执行状态不代表当前状态] "
        time.toLocalDate() != today -> "[${time.toLocalDate()}] "
        else -> ""
    }
    return (prefix + line.text).take(4_000)
}
