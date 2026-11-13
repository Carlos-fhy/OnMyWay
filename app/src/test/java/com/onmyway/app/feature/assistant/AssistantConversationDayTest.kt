package com.onmyway.app.feature.assistant

import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Legacy rows retain the date IDs written by MIGRATION_18_19. New sessions bind an explicit ID. */
class AssistantConversationDayTest {
    private val zone = ZoneId.systemDefault()
    private fun millis(day: LocalDate, hour: Int = 12) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun legacyDateIdsRemainReadable() {
        val day = LocalDate.of(2026, 11, 5)
        assertEquals("day-2026-11-05", conversationKey(millis(day)))
        assertEquals("day-2026-11-05", conversationKey(day))
        // 零点整属于新的一天，前一晚 23:59 仍留在旧对话。
        assertNotEquals(conversationKey(millis(day, 23)), conversationKey(millis(day.plusDays(1), 0)))
        assertEquals("day-2026-11-06", conversationKey(millis(day.plusDays(1), 0)))
    }

    @Test
    fun savedDraftBelongsToTheDayItWasSaved() {
        val value = JSONObject().put("action", "create_task").put("title", "取快递")
            .put("_draft_id", "draft-1").put("_plan_id", "plan-1")
        val savedAt = millis(LocalDate.of(2026, 11, 5))
        val draft = AssistantDraft.from(value, savedAt)
        assertNotNull(draft)
        assertEquals("day-2026-11-05", draft!!.conversationId)
    }

    @Test
    fun messageConversationFollowsItsOwnTimestamp() {
        val line = ChatLine("user", "去图书馆", createdAt = millis(LocalDate.of(2026, 11, 5)))
        assertEquals("day-2026-11-05", AssistantMessage.from(line).conversationId)
    }
}
