package com.onmyway.app.feature.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantConversationPolicyTest {
    @Test fun newRequestIdentityDoesNotDependOnCalendarDay() {
        val key = JSONObject().put("conversation_id", "chat-example").toString()
        assertEquals("chat-example", requestConversationId(key, 1L))
        assertEquals("chat-example", requestConversationId(key, 100_000_000L))
    }

    @Test fun olderRequestsRetainTheirOriginalConversation() {
        assertEquals(conversationKey(1L), requestConversationId("{}", 1L))
    }

    @Test fun switchingRequiresOutstandingWorkAndInputToBeHandled() {
        assertNull(conversationSwitchIssue(true, false, false, false, ""))
        assertNotNull(conversationSwitchIssue(false, false, false, false, ""))
        assertTrue(conversationSwitchIssue(true, true, false, false, "")!!.contains("尚未完成"))
        assertTrue(conversationSwitchIssue(true, false, true, false, "")!!.contains("取回"))
        assertTrue(conversationSwitchIssue(true, false, false, true, "")!!.contains("保存或取消"))
        assertTrue(conversationSwitchIssue(true, false, false, false, "取快递")!!.contains("未发送"))
    }

    @Test fun businessReplyIncludesActualMemoryReceipt() {
        listOf("已保存：以后优先步行。", "本轮记忆未保存。", "本轮记忆更新未成功保存。").forEach { feedback ->
            val answer = JSONObject().put("memory_context", JSONObject().put("feedback", feedback))
            assertEquals("请确认任务。 $feedback", appendMemoryFeedback("请确认任务。", "create_task", answer))
            assertEquals(feedback, personalMemoryWriteMessage(answer.getJSONObject("memory_context")))
        }
    }

    @Test fun plainReplyDoesNotRepeatServerMemoryReceipt() {
        val answer = JSONObject().put("memory_context", JSONObject().put("feedback", "已保存。"))
        assertEquals("好的。已保存。", appendMemoryFeedback("好的。已保存。", "reply", answer))
        assertEquals("请确认。", appendMemoryFeedback("请确认。", "create_task", JSONObject()))
    }

    @Test fun resumedHistoryMarksOldTemporaryConditionsEvenOnSameDay() {
        val old = ChatLine("user", "这次骑车", createdAt = 1_000L)
        val marked = assistantContextText(old, false, 3_000L, 2_000L)
        assertTrue(marked.contains("历史对话背景"))
        assertTrue(marked.contains("不代表当前状态"))
        assertTrue(marked.contains("这次骑车"))
        assertEquals("这次骑车", assistantContextText(old, true, 3_000L, 2_000L))
    }

    @Test fun historyAnnotationsRemainWithinContextBudget() {
        val marked = (1L..20L).map { ChatLine("user", "x".repeat(1_000), createdAt = it) }
            .map { it.copy(text = assistantContextText(it, false, 3_000, 2_000)) }
        val selected = assistantContextLines(marked)
        assertTrue(selected.sumOf { it.text.length } <= 6_000)
        assertTrue(selected.size <= 12)
    }
}
