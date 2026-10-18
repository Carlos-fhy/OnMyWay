package com.onmyway.app.feature.assistant

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.model.Place
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import java.util.UUID
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
class CardVersionBoundaryTest {
    private lateinit var base: Context
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: TaskDatabase
    private lateinit var store: AssistantStore
    private val preferenceNames = mutableSetOf<String>()

    @Before fun setUp() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        name = "card-version-boundary-${UUID.randomUUID()}"
        context = object : ContextWrapper(base) {
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                val isolated = "$name-$key"
                preferenceNames += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        open()
    }

    private fun open() {
        db = Room.databaseBuilder(context, TaskDatabase::class.java, name).build()
        store = AssistantStore(db, context.getSharedPreferences("assistant_conversation", 0))
    }

    private fun reopen() {
        db.close()
        open()
    }

    @After fun tearDown() {
        db.close()
        base.deleteDatabase(name)
        preferenceNames.forEach(base::deleteSharedPreferences)
    }

    private fun proposal() = JSONObject().put("action", "create_tasks")
        .put("_draft_id", "current-draft").put("_plan_id", "current-plan")
        .put("tasks", JSONArray().put(JSONObject().put("item_id", "parcel").put("version", 1)
            .put("title", "取快递").put("place_type", "PARCEL").put("planned_date", "2026-10-19")
            .put("place", Place("east-station", "东门驿站", 120.0, 31.0).json())))

    private suspend fun assertRejectedWithoutChangingCurrent(value: JSONObject) {
        store.setDraft(proposal())
        val current = db.assistant().draft()
        val items = db.assistant().draftItems()
        val clarification = db.assistant().clarification()
        assertTrue("Unsupported raw card must be rejected before replacing the current proposal: $value",
            runCatching { store.setDraft(value) }.isFailure)
        assertEquals(current, db.assistant().draft())
        assertEquals(items, db.assistant().draftItems())
        assertEquals(clarification, db.assistant().clarification())
        assertEquals("current-draft", store.load().second!!.getString("_draft_id"))
        assertTrue(db.tasks().observe().first().isEmpty())
    }

    @Test fun unknownOrMalformedExplicitVersionsAreRejectedAndCurrentCardSurvives() = runBlocking {
        for (version in listOf<Any>(99, "1", JSONObject.NULL, 1.5)) {
            assertRejectedWithoutChangingCurrent(proposal().put("_card_version", version))
        }
    }

    @Test fun unknownActionIsRejectedAndCurrentCardSurvives() = runBlocking {
        assertRejectedWithoutChangingCurrent(proposal().put("action", "execute_arbitrary_action"))
    }

    @Test fun malformedKnownDestinationCannotBeDroppedIntoExecutableRoute() = runBlocking {
        for (invalid in listOf<Any>("broken", JSONObject(),
            Place("dorm", "宿舍", 200.0, 31.0).json())) {
            assertRejectedWithoutChangingCurrent(JSONObject().put("action", "plan_route")
                .put("_draft_id", "bad-route").put("_plan_id", "route-plan")
                .put("destination", "宿舍").put("mode", "walking").put("known_destination", invalid))
        }
    }

    @Test fun explicitlyEmptyOrWrongTypeDraftAndPlanIdsCannotBeInvented() = runBlocking {
        for (key in listOf("_draft_id", "_plan_id")) {
            for (invalid in listOf<Any>("", "   ", JSONObject.NULL, 7, false, JSONObject())) {
                assertRejectedWithoutChangingCurrent(proposal().put(key, invalid))
            }
        }
    }

    @Test fun explicitlyEmptyOrWrongTypeItemIdsCannotBeInvented() = runBlocking {
        for (invalid in listOf<Any>("", "   ", JSONObject.NULL, 7, false, JSONObject())) {
            val raw = proposal()
            raw.getJSONArray("tasks").getJSONObject(0).put("item_id", invalid)
            assertRejectedWithoutChangingCurrent(raw)
        }
    }

    @Test fun absentVersionAndIdsRemainCompatibleAndGeneratedIdentityIsStableAcrossReopen() = runBlocking {
        val authored = proposal().apply { remove("_draft_id"); remove("_plan_id") }
        authored.getJSONArray("tasks").getJSONObject(0).remove("item_id")
        store.setDraft(authored)
        val generated = store.load().second!!
        val draftId = generated.getString("_draft_id")
        val planId = generated.getString("_plan_id")
        val itemId = generated.getJSONArray("tasks").getJSONObject(0).getString("item_id")
        assertTrue(draftId.isNotBlank() && planId.isNotBlank() && itemId.isNotBlank())
        assertEquals(1, generated.optInt("_card_version", 1))
        reopen()
        val restored = store.load().second!!
        assertEquals(draftId, restored.getString("_draft_id"))
        assertEquals(planId, restored.getString("_plan_id"))
        assertEquals(itemId, restored.getJSONArray("tasks").getJSONObject(0).getString("item_id"))
        assertFalse(store.legacyWarning)

        store.setDraft(null)
        val oldSingle = JSONObject().put("action", "create_task").put("title", "打印论文")
            .put("place_type", "PRINT_SHOP").put("deadline", "")
        assertTrue(context.getSharedPreferences("assistant_conversation", 0).edit()
            .putString("proposal", oldSingle.toString()).putLong("proposal_saved_at", 1_792_379_000_000).commit())
        reopen()
        val migrated = store.load().second!!
        assertEquals("打印论文", migrated.getString("title"))
        assertTrue(migrated.getString("_draft_id").isNotBlank())
        assertTrue(migrated.getString("_plan_id").isNotBlank())
        assertFalse(store.legacyWarning)
        reopen()
        assertEquals(migrated.getString("_draft_id"), store.load().second!!.getString("_draft_id"))
    }

    @Test fun consumedSingleTaskReceiptPreventsDaoReplayFromRestoringCard() = runBlocking {
        val single = JSONObject().put("action", "create_task").put("_draft_id", "single-consumed")
            .put("_plan_id", "single-plan").put("title", "打印论文").put("place_type", "PRINT_SHOP")
        store.setDraft(single)
        val stale = db.assistant().draft()!!
        val task = Errand(title = "打印论文", placeType = "PRINT_SHOP")
        assertTrue(store.confirmTask("single-consumed", task, "已保存打印") {})
        val completed = db.tasks().observe().first().single().copy(status = "done",
            completedAt = 1_792_379_000_000, updatedAt = 1_792_379_000_000)
        db.tasks().save(completed)
        assertTrue(db.assistant().hasMessage("assistant-task-confirmed-single-consumed"))
        db.assistant().saveDraft(stale)
        reopen()
        assertNull(store.load().second)
        assertTrue("Consumed single-task recovery must be explained", store.legacyWarning)
        assertEquals(listOf(completed), db.tasks().observe().first())
        assertFalse(store.confirmTask("single-consumed", task, "重复打印") {})
        assertEquals(listOf(completed), db.tasks().observe().first())
    }
}
