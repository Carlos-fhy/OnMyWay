package com.onmyway.app.feature.assistant

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.model.Place
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.AgentEvent
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.sync.BackupRepository
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantMultiDraftStoreTest {
    private lateinit var context: Context
    private lateinit var base: Context
    private lateinit var name: String
    private lateinit var db: TaskDatabase
    private lateinit var store: AssistantStore
    private val preferences = mutableSetOf<String>()

    @Before fun setUp() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        name = "multi-draft-store-${UUID.randomUUID()}"
        context = object : ContextWrapper(base) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                val isolated = "$name-$key"
                preferences += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        open()
    }

    private fun open() {
        db = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
        store = AssistantStore(db, context.getSharedPreferences("assistant_conversation", 0))
    }

    @After fun tearDown() {
        db.close()
        base.deleteDatabase(name)
        preferences.forEach(base::deleteSharedPreferences)
    }

    private fun draft(id: String = "draft-initial", includePlace: Boolean = false) = JSONObject()
        .put("action", "create_tasks").put("_draft_id", id).put("_plan_id", "plan-errands")
        .put("tasks", JSONArray().put(JSONObject().put("item_id", "parcel").put("version", 1)
            .put("title", "取快递").put("place_type", "PARCEL").put("planned_date", "2026-10-15")
            .put("priority", 2).put("service_minutes", 5)
            .put("place", if (includePlace) Place("station", "东门驿站", 120.0, 31.0).json() else JSONObject.NULL))
            .put(JSONObject().put("item_id", "print").put("version", 1).put("title", "打印论文")
                .put("place_type", "PRINT_SHOP").put("planned_date", "2026-10-15")))

    private suspend fun confirm(id: String, selection: Map<String, Int>): Boolean =
        store.confirmTasks(id, selection, "已保存选中的待办") { created ->
            db.agentEvents().append(AgentEvent(type = "ACTION_CREATED", source = "ASSISTANT", actionId = created.id))
        }

    @Test fun emptySelectionAndConcurrentRepeatCannotPartiallyConsumeDrafts() = runBlocking {
        store.setDraft(draft(includePlace = true))
        val before = store.load().second.toString()
        assertTrue(runCatching { confirm("draft-initial", emptyMap()) }.isFailure)
        assertEquals(before, store.load().second.toString())
        assertTrue(db.tasks().observe().first().isEmpty())
        assertTrue(db.assistant().recentMessages().isEmpty())
        val outcomes = awaitAll(
            async { confirm("draft-initial", mapOf("parcel" to 1)) },
            async { confirm("draft-initial", mapOf("parcel" to 1)) },
        )
        assertEquals(1, outcomes.count { it })
        assertEquals("取快递", db.tasks().observe().first().single().title)
        assertEquals("print", db.assistant().draftItems().single().id)
        assertEquals(1, db.assistant().draftItems().single().version)
        assertEquals(1, db.agentEvents().recent().size)
    }

    @Test fun partialConfirmationAndReopenKeepUnselectedItemAndPlan() = runBlocking {
        store.setDraft(draft())
        assertTrue(confirm("draft-initial", mapOf("print" to 1)))
        val saved = db.tasks().observe().first().single()
        assertEquals("打印论文", saved.title)
        assertEquals("2026-10-15", saved.plannedDate)
        assertEquals("", saved.deadline)
        assertEquals("pending", saved.status)
        val remaining = store.load().second!!
        assertNotEquals("draft-initial", remaining.getString("_draft_id"))
        assertEquals("plan-errands", remaining.getString("_plan_id"))
        assertEquals("parcel", remaining.getJSONArray("tasks").getJSONObject(0).getString("item_id"))
        assertEquals(1, remaining.getJSONArray("tasks").getJSONObject(0).getInt("version"))
        assertEquals(1, store.load().first.size)
        db.close()
        open()
        assertEquals(remaining.toString(), store.load().second.toString())
        assertFalse(confirm("draft-initial", mapOf("print" to 1)))
        assertEquals(listOf(saved), db.tasks().observe().first())
        assertEquals(1, db.agentEvents().recent().size)
    }

    @Test fun editingIncompleteParcelChangesOnlyItsVersionAndNeverCreatesTasks() = runBlocking {
        store.setDraft(draft())
        val print = db.assistant().draftItems().single { it.id == "print" }
        assertTrue(store.updateDraftItem("draft-initial", "parcel", 1,
            Errand(title = "取两件快递", placeType = "PARCEL", plannedDate = "2026-10-16", priority = 3)))
        val current = db.assistant().draft()!!
        assertNotEquals("draft-initial", current.draftId)
        assertEquals("plan-errands", current.planId)
        assertEquals(print, db.assistant().draftItems().single { it.id == "print" })
        val changed = db.assistant().draftItems().single { it.id == "parcel" }
        assertEquals(2, changed.version)
        assertEquals("2026-10-16", changed.plannedDate)
        assertEquals(3, changed.priority)
        assertTrue(db.tasks().observe().first().isEmpty())
        assertTrue(runCatching { confirm(current.draftId, mapOf("parcel" to 2)) }.isFailure)
        assertEquals(current, db.assistant().draft())
        assertTrue(runCatching { store.updateDraftItem("draft-initial", "parcel", 1, changed.task()) }.isFailure)
        assertTrue(runCatching { confirm(current.draftId, mapOf("parcel" to 1)) }.isFailure)
        assertTrue(runCatching { confirm(current.draftId, mapOf("print" to 1, "missing" to 1)) }.isFailure)
        assertTrue(db.tasks().observe().first().isEmpty())
    }

    @Test fun selectedItemsValidateTogetherAndCallbackFailureRollsBackEveryWrite() = runBlocking {
        store.setDraft(draft())
        assertTrue(runCatching { confirm("draft-initial", mapOf("print" to 1, "parcel" to 1)) }.isFailure)
        assertTrue(db.tasks().observe().first().isEmpty())
        store.setDraft(draft(includePlace = true))
        val before = store.load().second.toString()
        var calls = 0
        assertTrue(runCatching {
            store.confirmTasks("draft-initial", mapOf("print" to 1, "parcel" to 1), "Saved") { created ->
                db.agentEvents().append(AgentEvent(type = "ACTION_CREATED", source = "ASSISTANT", actionId = created.id))
                if (++calls == 2) error("Injected transaction failure")
            }
        }.isFailure)
        assertEquals(before, store.load().second.toString())
        assertTrue(db.tasks().observe().first().isEmpty())
        assertTrue(db.agentEvents().recent().isEmpty())
        assertTrue(db.assistant().recentMessages().isEmpty())
        assertTrue(confirm("draft-initial", mapOf("print" to 1, "parcel" to 1)))
        assertEquals(2, db.tasks().observe().first().size)
        assertTrue(db.assistant().draftItems().isEmpty())
        assertNull(db.assistant().draft())
        assertNull(db.assistant().clarification())
        assertEquals(1, store.load().first.size)
    }

    @Test fun deletionAndSnapshotRestoreKeepReceiptsAndCannotRecreateConsumedItem() = runBlocking {
        store.setDraft(draft())
        confirm("draft-initial", mapOf("print" to 1))
        db.tasks().delete(db.tasks().observe().first().single())
        val snapshot = BackupRepository(context, db).snapshot()
        val target = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        try {
            BackupRepository(context, target).restore(snapshot)
            val restored = AssistantStore(target, context.getSharedPreferences("assistant_conversation", 0))
            assertFalse(restored.confirmTasks("draft-initial", mapOf("print" to 1), "Duplicate") {})
            assertEquals("parcel", target.assistant().draftItems().single().id)
            restored.setDraft(draft("reintroduced"))
            assertTrue(runCatching {
                restored.confirmTasks("reintroduced", mapOf("print" to 1), "Duplicate") {}
            }.isFailure)
            assertTrue(target.tasks().observe().first().isEmpty())
            assertEquals(1, restored.load().first.size)
        } finally { target.close() }
    }

    @Test fun cancellingAndReplacingDraftRemoveChildrenAndStaleConfirmCannotConsumeNewDraft() = runBlocking {
        store.setDraft(draft())
        store.setDraft(null)
        assertTrue(db.assistant().draftItems().isEmpty())
        assertTrue(runCatching { confirm("draft-initial", mapOf("print" to 1)) }.isFailure)
        store.setDraft(draft("replacement"))
        assertTrue(runCatching { confirm("draft-initial", mapOf("print" to 1)) }.isFailure)
        assertEquals("replacement", db.assistant().draft()!!.draftId)
        store.setDraft(JSONObject().put("action", "plan_route").put("destination", "宿舍")
            .put("mode", "walking").put("_draft_id", "route"))
        assertTrue(db.assistant().draftItems().isEmpty())
        assertEquals("plan_route", store.load().second!!.getString("action"))
    }

    @Test fun invalidReplacementRollsBackResponseReceiptAndExistingDraft() = runBlocking {
        store.setDraft(draft())
        val bad = draft("bad")
        bad.getJSONArray("tasks").getJSONObject(1).put("item_id", "parcel")
        val request = UUID.randomUUID().toString()
        assertTrue(runCatching {
            store.applyResponse(request, "draft-initial", ChatLine("user", "改成明天"),
                ChatLine("assistant", "已修改草稿"), bad, true)
        }.isFailure)
        assertFalse(store.responseApplied(request))
        assertEquals("draft-initial", db.assistant().draft()!!.draftId)
        assertEquals(2, db.assistant().draftItems().size)
        assertTrue(db.assistant().recentMessages().isEmpty())
    }
}
