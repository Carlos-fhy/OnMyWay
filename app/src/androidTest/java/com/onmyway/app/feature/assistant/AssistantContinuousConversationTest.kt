package com.onmyway.app.feature.assistant

import android.app.Application
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.TaskDatabase
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantContinuousConversationTest {
    private fun fixture(test: suspend (AssistantStore, TaskDatabase, android.content.SharedPreferences) -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val name = "continuous-chat-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, 0)
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        try { test(AssistantStore(db, prefs), db, prefs) }
        finally { db.close(); app.deleteSharedPreferences(name) }
    }

    @Test fun midnightAndProcessRecreationKeepMessagesAndTaskDraft() = fixture { store, db, prefs ->
        store.load()
        val id = store.activeConversationId
        val midnight = LocalDate.of(2026, 11, 8).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        store.add(ChatLine("user", "取快递、打印论文，然后回宿舍", createdAt = midnight - 30_000))
        store.add(ChatLine("assistant", "请核对草稿", createdAt = midnight + 30_000))
        store.setDraft(JSONObject().put("action", "create_task").put("title", "取快递"), savedAt = midnight - 30_000)
        val reopened = AssistantStore(db, prefs)
        val restored = reopened.load()
        assertEquals(id, reopened.activeConversationId)
        assertEquals(2, restored.first.size)
        assertEquals("取快递", restored.second!!.getString("title"))
        assertEquals(id, db.assistant().draft()!!.conversationId)
        assertTrue(db.assistant().recentMessages().all { it.conversationId == id })
    }

    @Test fun explicitSwitchKeepsArchiveAndRejectsLateResponse() = fixture { store, db, prefs ->
        store.load()
        val first = store.activeConversationId
        val session = store.memorySessionId
        store.add(ChatLine("user", "先取快递"))
        val second = "chat-${UUID.randomUUID()}"
        store.activateConversation(second)
        assertTrue(store.load().first.isEmpty())
        assertEquals(1, store.conversation(first).size)
        assertFalse(store.applyResponse(UUID.randomUUID().toString(), null,
            ChatLine("user", "旧请求"), ChatLine("assistant", "旧回复"), null, false,
            expectedConversationId = first))
        store.activateConversation(first)
        assertEquals("先取快递", store.load().first.single().text)
        assertNotEquals(session, store.memorySessionId)
        val reopened = AssistantStore(db, prefs)
        reopened.load()
        assertEquals(first, reopened.activeConversationId)
        assertEquals(store.memorySessionId, reopened.memorySessionId)
    }

    @Test fun pendingDraftBlocksSwitchAndIndependentConfirmationUsesItsConversation() = fixture { store, db, prefs ->
        store.load()
        val id = store.activeConversationId
        store.setDraft(JSONObject().put("action", "create_task").put("title", "打印论文").put("place_type", "indoor"))
        val draft = db.assistant().draft()!!
        assertTrue(runCatching { store.activateConversation("chat-other") }.isFailure)
        assertEquals(id, store.activeConversationId)
        assertTrue(AssistantStore(db, prefs).confirmTask(draft.draftId,
            com.onmyway.app.data.local.entity.Errand(title = "打印论文", placeType = "indoor"), "已保存打印论文") {})
        assertEquals(id, db.assistant().recentMessages().single().conversationId)
    }

    @Test fun legacyDateHistoryRestoresWithoutChangingItsIdentity() = fixture { store, db, _ ->
        val time = 1_000L
        val legacyId = conversationKey(time)
        db.assistant().addMessage(AssistantMessage("legacy-message", "user", "回宿舍", time, null, legacyId))
        assertEquals("回宿舍", store.load().first.single().text)
        assertEquals(legacyId, store.activeConversationId)
        assertEquals(legacyId, store.conversations().single().id)
    }

    @Test fun oldMessagesRemainAvailableThroughExplicitPagination() = fixture { store, db, _ ->
        store.load()
        val id = store.activeConversationId
        db.assistant().addMessages((1..1_001).map {
            AssistantMessage("line-$it", "user", "message-$it", it.toLong(), null, id)
        })
        val recent = store.conversation(id)
        assertEquals(500, recent.size)
        assertEquals("message-502", recent.first().text)
        assertEquals("message-1001", recent.last().text)
        assertEquals(1_001, store.conversation(id, 1_500).size)
    }

    @Test fun expiredRouteCannotExecuteButCanBeExplicitlyDismissed() = fixture { store, db, prefs ->
        store.load()
        store.setDraft(JSONObject().put("action", "plan_route").put("destination", "宿舍")
            .put("mode", "walking").put("_expires_at", System.currentTimeMillis() - 1_000))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val journal = "expired-chat-${UUID.randomUUID()}.db"
        val model = withContext(Dispatchers.Main) { AssistantViewModel(app, prefs, db, requestJournalName = journal) }
        try {
            withTimeout(5_000) { while (!withContext(Dispatchers.Main) { model.ready }) delay(20) }
            withContext(Dispatchers.Main) {
                val draft = model.proposal!!
                assertNotNull(assistantCardIssue(draft))
                assertTrue(model.dismissDraft(draft.getString("_draft_id"), draft.getString("_plan_id")))
            }
            withTimeout(5_000) { while (db.assistant().draft() != null || withContext(Dispatchers.Main) { model.busy }) delay(20) }
            store.activateConversation("chat-next")
            assertEquals("chat-next", store.activeConversationId)
        } finally {
            withContext(Dispatchers.Main) { model.viewModelScope.cancel() }
            app.deleteDatabase(journal)
        }
    }

    @Test fun failedPreferenceCommitRestoresIdentityBeforeNextMessageAndResponse() = fixture { store, db, prefs ->
        store.load()
        val id = store.activeConversationId
        val session = store.memorySessionId
        val start = store.memorySessionStartedAt
        var failNext = true
        val failing = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor {
                        editor.putLong(key, value); return this
                    }
                    override fun remove(key: String?): android.content.SharedPreferences.Editor {
                        editor.remove(key); return this
                    }
                    override fun commit(): Boolean {
                        val saved = editor.commit()
                        if (failNext) { failNext = false; return false }
                        return saved
                    }
                }
            }
        }
        val affected = AssistantStore(db, failing)
        assertTrue(runCatching { affected.activateConversation("chat-failed") }.isFailure)
        assertEquals(id, affected.activeConversationId)
        assertEquals(session, affected.memorySessionId)
        assertEquals(start, affected.memorySessionStartedAt)
        val user = ChatLine("user", "取快递后回宿舍")
        affected.add(user, id)
        assertTrue(affected.applyResponse(UUID.randomUUID().toString(), null, user,
            ChatLine("assistant", "请确认路线"), null, false, expectedConversationId = id))
        assertTrue(db.assistant().recentMessages().all { it.conversationId == id })
    }
}
