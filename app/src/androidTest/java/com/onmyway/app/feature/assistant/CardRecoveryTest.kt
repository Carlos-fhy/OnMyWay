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
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.sync.BackupRepository
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

/** Recovery uses disposable databases and preferences, never the installed user's records. */
@RunWith(AndroidJUnit4::class)
class CardRecoveryTest {
    private lateinit var base: Context
    private lateinit var context: Context
    private lateinit var prefix: String
    private lateinit var db: TaskDatabase
    private lateinit var store: AssistantStore
    private val databaseNames = mutableSetOf<String>()
    private val databases = mutableListOf<TaskDatabase>()
    private val preferenceNames = mutableSetOf<String>()

    @Before fun setUp() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        prefix = "card-recovery-${UUID.randomUUID()}"
        context = isolatedContext("source")
        open()
    }

    private fun isolatedContext(label: String): Context = object : ContextWrapper(base) {
        override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$label-$key"
            preferenceNames += isolatedName
            return base.getSharedPreferences(isolatedName, mode)
        }
    }

    private fun database(label: String, owner: Context): TaskDatabase {
        val name = "$prefix-$label.db"
        databaseNames += name
        return Room.databaseBuilder(owner, TaskDatabase::class.java, name).build().also(databases::add)
    }

    private fun open() {
        db = database("source", context)
        store = AssistantStore(db, context.getSharedPreferences("assistant_conversation", 0))
    }

    private fun reopen() {
        db.close()
        open()
    }

    @After fun tearDown() {
        databases.forEach { if (it.isOpen) it.close() }
        databaseNames.forEach(base::deleteDatabase)
        preferenceNames.forEach(base::deleteSharedPreferences)
    }

    private fun parcel() = AssistantDraftItem.fromTask("parcel", "errands-plan", 0, 1,
        Errand(title = "取快递", placeType = "PARCEL", plannedDate = "2026-10-19",
            placeJson = Place("east-station", "东门驿站", 120.0, 31.0).json().toString()))

    private fun print() = AssistantDraftItem.fromTask("print", "errands-plan", 1, 1,
        Errand(title = "打印论文", placeType = "PRINT_SHOP", plannedDate = "2026-10-19",
            deadline = "2026-10-19T17:00:00+08:00"))

    private fun draft(vararg items: AssistantDraftItem) = JSONObject()
        .put("action", "create_tasks").put("_draft_id", "draft-initial").put("_plan_id", "errands-plan")
        .put("tasks", JSONArray(items.map(AssistantDraftItem::json)))

    private fun itemIds(proposal: JSONObject?): List<String> {
        val items = proposal?.optJSONArray("tasks") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it).getString("item_id") }
    }

    private fun itemReceipt(item: AssistantDraftItem): String {
        val identity = UUID.nameUUIDFromBytes("assistant-task-item:${item.planId}:${item.id}"
            .toByteArray(Charsets.UTF_8)).toString()
        return "assistant-task-item-confirmed-$identity"
    }

    private suspend fun assertRejectedStoredItem(item: AssistantDraftItem) {
        db.assistant().saveDraft(AssistantDraft(action = "create_tasks", draftId = "stored-draft",
            planId = item.planId, savedAt = 1))
        db.assistant().saveDraftItems(listOf(item))
        reopen()
        assertNull("A damaged saved row must not become an executable task card", store.load().second)
        assertTrue("Recovery must explain why the saved card is unavailable", store.legacyWarning)
        assertTrue(db.tasks().observe().first().isEmpty())
        assertTrue("Recovery must not invent a replacement task item ID",
            db.assistant().draftItems().all { it.id == item.id })
    }

    @Test fun blankStoredDraftAndPlanIdsDoNotRecoverAsExecutableCard() = runBlocking {
        db.assistant().saveDraft(AssistantDraft(action = "create_tasks", draftId = "", planId = "", savedAt = 1))
        db.assistant().saveDraftItems(listOf(parcel().copy(planId = "")))
        reopen()
        assertNull(store.load().second)
        assertTrue("An invalid persisted card identity needs a visible recovery warning", store.legacyWarning)
        assertTrue(db.tasks().observe().first().isEmpty())
    }

    @Test fun zeroVersionStoredItemIsRejectedWithoutReplacementIdentity() = runBlocking {
        assertRejectedStoredItem(parcel().copy(version = 0))
    }

    @Test fun malformedDeadlineStoredItemIsRejectedWithoutReplacementIdentity() = runBlocking {
        assertRejectedStoredItem(print().copy(deadline = "tomorrow afternoon"))
    }

    @Test fun blankStoredItemIdIsRejectedInsteadOfRegenerated() = runBlocking {
        assertRejectedStoredItem(parcel().copy(id = ""))
    }

    @Test fun malformedStoredPlaceIsReportedInsteadOfCrashingRecovery() = runBlocking {
        assertRejectedStoredItem(parcel().copy(placeJson = "{broken-place"))
    }

    @Test fun consumedParcelRowCannotReviveAndValidPrintDraftSurvivesReopen() = runBlocking {
        val consumed = parcel()
        store.setDraft(draft(consumed, print()))
        assertTrue(store.confirmTasks("draft-initial", mapOf(consumed.id to consumed.version), "已保存快递") {})
        val saved = db.tasks().observe().first().single()
        val completed = saved.copy(status = "done", completedAt = 1_792_379_000_000,
            updatedAt = 1_792_379_000_000, reminderAt = 0)
        db.tasks().save(completed)
        val remaining = db.assistant().draftItems().single()
        val revision = db.assistant().draft()!!.draftId
        assertTrue(db.assistant().hasMessage(itemReceipt(consumed)))

        // A stale database write must not give an already consumed identity another confirmation.
        db.assistant().saveDraftItems(listOf(consumed))
        reopen()
        val restored = store.load().second
        assertEquals(listOf("print"), itemIds(restored))
        assertEquals(revision, restored!!.getString("_draft_id"))
        assertTrue(store.legacyWarning)
        assertEquals(remaining, db.assistant().draftItems().single())
        assertEquals(listOf(completed), db.tasks().observe().first())
        assertTrue(db.assistant().hasMessage(itemReceipt(consumed)))
        assertTrue(runCatching {
            store.confirmTasks(revision, mapOf(consumed.id to consumed.version), "重复快递") {}
        }.isFailure)
    }

    @Test fun staleBackupRowsCannotReviveConsumedParcelWithDurableReceipt() = runBlocking {
        val consumed = parcel()
        store.setDraft(draft(consumed, print()))
        assertTrue(store.confirmTasks("draft-initial", mapOf("parcel" to 1), "已保存快递") {})
        val saved = db.tasks().observe().first().single().copy(status = "done", completedAt = 1_792_379_000_000)
        db.tasks().save(saved)
        val expectedPrint = db.assistant().draftItems().single()
        val snapshot = BackupRepository(context, db).snapshot()
        val rows = snapshot.getJSONObject("tables").getJSONArray("assistant_task_drafts")
        rows.put(JSONObject().put("id", consumed.id).put("planId", consumed.planId)
            .put("position", consumed.position).put("version", consumed.version).put("title", consumed.title)
            .put("placeType", consumed.placeType).put("deadline", consumed.deadline)
            .put("plannedDate", consumed.plannedDate).put("priority", consumed.priority)
            .put("serviceMinutes", consumed.serviceMinutes).put("placeJson", consumed.placeJson))

        val targetContext = isolatedContext("restore")
        val target = database("restore", targetContext)
        BackupRepository(targetContext, target).restore(snapshot)
        target.close()
        val reopened = database("restore", targetContext)
        val restoredStore = AssistantStore(reopened, targetContext.getSharedPreferences("assistant_conversation", 0))
        assertEquals(listOf("print"), itemIds(restoredStore.load().second))
        assertTrue(restoredStore.legacyWarning)
        assertEquals(expectedPrint, reopened.assistant().draftItems().single())
        assertEquals(listOf(saved), reopened.tasks().observe().first())
        assertTrue(reopened.assistant().hasMessage(itemReceipt(consumed)))
    }

    @Test fun validRemainingCardAndCompletedTaskReminderAndDormTripPersistAcrossReopenAndRestore() = runBlocking {
        val extra = AssistantDraftItem.fromTask("water", "errands-plan", 2, 1,
            Errand(title = "买瓶水", placeType = "SUPERMARKET", plannedDate = "2026-10-19"))
        store.setDraft(draft(parcel(), print(), extra))
        assertTrue(store.confirmTasks("draft-initial", mapOf("parcel" to 1, "print" to 1), "已保存快递和打印") {})
        val created = db.tasks().observe().first()
        val completedParcel = created.single { it.title == "取快递" }.copy(status = "done",
            completedAt = 1_792_379_000_000, updatedAt = 1_792_379_000_000)
        val remindedPrint = created.single { it.title == "打印论文" }.copy(reminderAt = 1_792_382_600_000,
            updatedAt = 1_792_379_000_000)
        db.tasks().save(completedParcel)
        db.tasks().save(remindedPrint)
        val dormTrip = SavedTrip(requestJson = JSONObject().put("destination", "宿舍").put("mode", "walking").toString(),
            resultJson = JSONObject().put("destination", "宿舍").put("route_id", "dorm-route").toString(),
            started = true, sessionId = "dorm-session", startedAt = 1_792_379_000_000,
            plannedAt = 1_792_378_000_000, planId = "dorm-plan")
        db.trips().save(dormTrip)
        val remainingCard = store.load().second!!.toString()
        val remainingItem = db.assistant().draftItems().single()

        reopen()
        assertEquals(remainingCard, store.load().second!!.toString())
        assertFalse(store.legacyWarning)
        assertEquals(remainingItem, db.assistant().draftItems().single())
        assertEquals(completedParcel, db.tasks().get(completedParcel.id))
        assertEquals(remindedPrint, db.tasks().get(remindedPrint.id))
        assertEquals(dormTrip, db.trips().load())

        val targetContext = isolatedContext("valid-restore")
        val target = database("valid-restore", targetContext)
        BackupRepository(targetContext, target).restore(BackupRepository(context, db).snapshot())
        target.close()
        val reopened = database("valid-restore", targetContext)
        val restored = AssistantStore(reopened, targetContext.getSharedPreferences("assistant_conversation", 0))
        assertEquals(remainingCard, restored.load().second!!.toString())
        assertFalse(restored.legacyWarning)
        assertEquals(remainingItem, reopened.assistant().draftItems().single())
        assertEquals(completedParcel, reopened.tasks().get(completedParcel.id))
        assertEquals(remindedPrint, reopened.tasks().get(remindedPrint.id))
        assertEquals(dormTrip.copy(started = false, needsReplan = true), reopened.trips().load())
        assertTrue(reopened.assistant().hasMessage(itemReceipt(parcel())))
        assertTrue(reopened.assistant().hasMessage(itemReceipt(print())))
    }
}
