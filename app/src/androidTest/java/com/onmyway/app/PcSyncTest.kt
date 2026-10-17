package com.onmyway.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PcSyncTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        val prefix = UUID.randomUUID().toString()
        val names = mutableSetOf<String>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }
        fun clean() { names.forEach { baseContext.deleteSharedPreferences(it) } }
    }
    private class Remote : SyncTransport {
        var revision = 0L
        var snapshot: JSONObject? = null
        var loseResponse = false
        var commits = 0
        val receipts = mutableMapOf<String, Pair<String, Long>>()
        override suspend fun read() = JSONObject().put("store_id", "test-store").put("revision", revision)
            .put("snapshot", snapshot ?: JSONObject.NULL)
        override suspend fun write(body: JSONObject): JSONObject {
            val id = body.getString("request_id")
            val digest = canonicalJson(body)
            val receipt = receipts[id]
            val result: Long
            if (receipt != null) {
                check(receipt.first == digest)
                result = receipt.second
            } else {
                if (body.getLong("base_revision") != revision) throw ApiHttpException(409, "conflict")
                snapshot = JSONObject(body.getJSONObject("snapshot").toString())
                revision++; commits++
                receipts[id] = digest to revision
                result = revision
            }
            if (loseResponse) { loseResponse = false; throw IOException("lost after commit") }
            return JSONObject().put("store_id", "test-store").put("revision", result)
        }
    }
    private class Device {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        val repository = BackupRepository(context, db)
        val journal = "sync-test-" + UUID.randomUUID() + ".db"
        val credential = BackupCredential("http://127.0.0.1:8000", "test-token")
        suspend fun sync(remote: Remote) = PcSyncEngine.synchronize(context, credential, repository, remote, journal)
        fun close() { db.close(); context.deleteDatabase(journal); context.clean() }
    }

    @Test fun lostResponseSurvivesJournalReopenAndUploadsNewerLocalEdits() = runBlocking {
        val phone = Device()
        val remote = Remote()
        try {
            phone.db.tasks().save(Errand(id = "task", title = "before"))
            remote.loseResponse = true
            try { phone.sync(remote); fail("expected network failure") } catch (_: IOException) { }
            assertEquals(1, remote.commits)
            SyncJournal(phone.context, phone.journal).use {
                assertNotNull(it.pending(syncScope(phone.credential.server, phone.credential.token)))
            }
            phone.db.tasks().save(Errand(id = "task", title = "after"))
            phone.sync(remote)
            assertEquals(2, remote.commits)
            assertEquals("after", remote.snapshot!!.getJSONObject("tables").getJSONArray("tasks").getJSONObject(0).getString("title"))
        } finally { phone.close() }
    }

    @Test fun reinstallPullsRemoteInsteadOfReplacingItWithEmptyDatabase() = runBlocking {
        val original = Device()
        val reinstalled = Device()
        val remote = Remote()
        try {
            original.db.tasks().save(Errand(id = "task", title = "preserved"))
            original.db.assistant().addMessage(AssistantMessage("message", "user", "history", 100, null))
            original.context.getSharedPreferences("settings", 0).edit().putString("familiar_places", "saved-place").commit()
            original.sync(remote)
            assertTrue(reinstalled.sync(remote))
            assertEquals("preserved", reinstalled.db.tasks().get("task")!!.title)
            assertEquals("history", reinstalled.db.assistant().recentMessages().single().text)
            assertEquals("saved-place", reinstalled.context.getSharedPreferences("settings", 0).getString("familiar_places", null))
            assertEquals(1, remote.commits)
        } finally { original.close(); reinstalled.close() }
    }

    @Test fun concurrentLocalAndRemoteChangesAreBothPreserved() = runBlocking {
        val a = Device()
        val b = Device()
        val remote = Remote()
        try {
            a.db.tasks().save(Errand(id = "task", title = "base"))
            a.sync(remote); b.sync(remote)
            a.db.tasks().save(Errand(id = "task", title = "remote"))
            a.sync(remote)
            b.db.tasks().save(Errand(id = "task", title = "offline"))
            try { b.sync(remote); fail("expected conflict") } catch (_: SyncConflict) { }
            assertEquals("offline", b.db.tasks().get("task")!!.title)
            assertEquals("remote", remote.snapshot!!.getJSONObject("tables").getJSONArray("tasks").getJSONObject(0).getString("title"))
        } finally { a.close(); b.close() }
    }

    @Test fun cleanDevicePullsLaterRemoteDeletion() = runBlocking {
        val a = Device()
        val b = Device()
        val remote = Remote()
        try {
            val task = Errand(id = "task", title = "base")
            a.db.tasks().save(task)
            a.sync(remote); b.sync(remote)
            a.db.tasks().delete(task)
            a.sync(remote)
            assertTrue(b.sync(remote))
            assertNull(b.db.tasks().get("task"))
        } finally { a.close(); b.close() }
    }

    @Test fun interruptedRestoreResumesWithoutOverwritingSubsequentUserEdit() = runBlocking {
        val source = Device()
        val target = Device()
        try {
            source.db.tasks().save(Errand(id = "task", title = "cloud"))
            val remote = source.repository.snapshot()
            val before = target.repository.snapshot()
            target.repository.applySyncedSnapshot(remote, before)
            target.repository.applySyncedSnapshot(remote, before)
            assertEquals("cloud", target.db.tasks().get("task")!!.title)
            target.db.tasks().save(Errand(id = "task", title = "new local edit"))
            try { target.repository.applySyncedSnapshot(remote, before); fail("expected conflict") }
            catch (_: IllegalStateException) { }
            assertEquals("new local edit", target.db.tasks().get("task")!!.title)
        } finally { source.close(); target.close() }
    }

    @Test fun numericRoundTripDoesNotCreatePhantomLocalEdits() = runBlocking {
        val source = Device()
        val restored = Device()
        val remote = Remote()
        try {
            assertEquals(canonicalJson(120L), canonicalJson(120.0))
            assertEquals(canonicalJson(0), canonicalJson(-0.0))
            assertNotEquals(canonicalJson(9007199254740992L), canonicalJson(9007199254740993L))
            source.db.assistant().saveDraft(AssistantDraft(action = "plan_route", destination = "Library",
                knownPlaceId = "library", knownPlaceName = "Library", knownLongitude = 120.0,
                knownLatitude = 31.1234567, savedAt = System.currentTimeMillis()))
            source.sync(remote)
            repeat(3) { assertFalse(source.sync(remote)) }
            assertEquals(1, remote.commits)
            assertTrue(restored.sync(remote))
            assertFalse(restored.sync(remote))
            assertEquals(source.db.assistant().draft(), restored.db.assistant().draft())
            assertEquals(1, remote.commits)
        } finally { source.close(); restored.close() }
    }

    @Test fun room11BaselineUpgradeDoesNotCreateFalseConflictWhenRemoteAdvances() = runBlocking {
        val phone = Device()
        val remote = Remote()
        try {
            phone.db.tasks().save(Errand(id = "task", title = "before", createdAt = 1, updatedAt = 1))
            val legacy = phone.repository.snapshot().put("room_version", 11)
            legacy.getJSONObject("tables").remove("assistant_task_drafts")
            legacy.getJSONObject("tables").getJSONArray("tasks").getJSONObject(0).remove("plannedDate")
            val scope = syncScope(phone.credential.server, phone.credential.token)
            SyncJournal(phone.context, phone.journal).use { it.accept(scope, "test-store", 1, legacy) }
            remote.revision = 2
            remote.snapshot = JSONObject(legacy.toString()).apply {
                getJSONObject("tables").getJSONArray("tasks").getJSONObject(0).put("title", "remote edit")
            }
            assertTrue(phone.sync(remote))
            assertEquals("remote edit", phone.db.tasks().get("task")!!.title)
            assertEquals("", phone.db.tasks().get("task")!!.plannedDate)
            assertEquals(0, remote.commits)
            assertFalse(phone.sync(remote))
        } finally { phone.close() }
    }

    @Test fun schemaOnlyUpgradeDoesNotGenerateTaskCommandsOrResetLocalNavigation() = runBlocking {
        val phone = Device()
        try {
            phone.db.tasks().save(Errand(id = "task", title = "same", createdAt = 1, updatedAt = 1))
            val current = phone.repository.snapshot()
            val legacy = JSONObject(current.toString()).put("room_version", 11)
            legacy.getJSONObject("tables").remove("assistant_task_drafts")
            legacy.getJSONObject("tables").getJSONArray("tasks").getJSONObject(0).remove("plannedDate")
            assertEquals(0, taskCommands(legacy, current, TaskAuthority(1, JSONObject().put("task", 4))).length())
            legacy.getJSONObject("preferences").getJSONObject("navigation_context").put("snapshot", "local runtime")
            assertEquals("local runtime", phone.repository.normalizedSchema(legacy).getJSONObject("preferences")
                .getJSONObject("navigation_context").getString("snapshot"))
            assertFalse(phone.repository.normalizedRestore(legacy).getJSONObject("preferences")
                .getJSONObject("navigation_context").has("snapshot"))
        } finally { phone.close() }
    }

    @Test fun httpRoundTripRestoresFreshDeviceFromComputerSqlite() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val server = args.getString("sync_test_server")
        val token = args.getString("sync_test_token")
        org.junit.Assume.assumeNotNull(server, token)
        val a = Device()
        val b = Device()
        val credential = BackupCredential(server!!, token!!)
        try {
            val api = Api(credential.server)
            assertTrue(api.readSync(credential.token).isNull("snapshot"))
            a.db.tasks().save(Errand(id = "http-task", title = "Restored over HTTP", plannedDate = "2026-10-16"))
            a.db.assistant().addMessage(AssistantMessage("http-chat", "user", "Keep my history", 10, null))
            val draft = AssistantDraft(action = "plan_route", draftId = "http-draft", planId = "http-plan",
                destination = "Library", mode = "cycling", savedAt = System.currentTimeMillis())
            val planning = PlanningDraft(draftId = draft.draftId, planId = draft.planId, destination = draft.destination,
                mode = draft.mode, budget = 12, placeId = "library", placeName = "Library",
                longitude = 120.0, latitude = 31.0, address = "Test campus", savedAt = draft.savedAt)
            val selected = listOf(PlanningDraftTask("http-task", true), PlanningDraftTask("skipped", false))
            val question = AssistantClarification(draftId = draft.draftId, planId = draft.planId,
                kind = "DESTINATION", prompt = "Which library", destination = draft.destination,
                mode = draft.mode, savedAt = draft.savedAt)
            val first = Place("z-first", "First library", 120.0, 31.0).toClarificationChoice(draft.draftId, 0)
            val second = Place("a-second", "Second library", 120.1, 31.1).toClarificationChoice(draft.draftId, 1)
            val trip = SavedTrip(requestJson = "{}", resultJson = "{}", sessionId = "http-session", planId = draft.planId)
            val event = RouteHistory(id = "http-version", sessionId = trip.sessionId, planId = draft.planId,
                state = "READY", timestamp = 100, requestJson = "{}", resultJson = "{}")
            a.db.assistant().saveDraft(draft)
            a.db.assistant().savePlanningDraft(planning)
            a.db.assistant().savePlanningTasks(selected)
            a.db.assistant().saveClarification(question)
            a.db.assistant().savePlaceChoices(listOf(second, first))
            a.db.trips().save(trip)
            a.db.trips().record(event)
            PcSyncEngine.synchronize(a.context, credential, a.repository, HttpSyncTransport(credential), a.journal)
            assertTrue(PcSyncEngine.synchronize(b.context, credential, b.repository, HttpSyncTransport(credential), b.journal))
            assertEquals("Restored over HTTP", b.db.tasks().get("http-task")!!.title)
            assertEquals("2026-10-16", b.db.tasks().get("http-task")!!.plannedDate)
            assertEquals("Keep my history", b.db.assistant().recentMessages().single().text)
            assertEquals(draft, b.db.assistant().draft())
            assertEquals(planning, b.db.assistant().planningDraft())
            assertEquals(selected, b.db.assistant().planningTasks())
            assertEquals(question, b.db.assistant().clarification())
            assertEquals(listOf(first, second), b.db.assistant().placeChoices())
            assertEquals(second.toPlace(), resolvePlaceReply("第二个", b.db.assistant().placeChoices().map { it.toPlace() }))
            assertEquals(trip.copy(needsReplan = true), b.db.trips().load())
            assertEquals(event, b.db.trips().history().single())
            val remote = api.readSync(credential.token)
            assertEquals(12, remote.getJSONObject("snapshot").getInt("room_version"))
            assertEquals(1, remote.getInt("revision"))
            assertEquals(1, remote.getInt("task_protocol"))
            assertEquals(1, remote.getJSONObject("task_versions").getInt("http-task"))
            val task = a.db.tasks().get("http-task")!!
            a.db.tasks().save(task.copy(status = "done", completedAt = System.currentTimeMillis()))
            PcSyncEngine.synchronize(a.context, credential, a.repository, HttpSyncTransport(credential), a.journal)
            repeat(2) {
                assertFalse(PcSyncEngine.synchronize(a.context, credential, a.repository, HttpSyncTransport(credential), a.journal))
            }
            val completed = api.readSync(credential.token)
            assertEquals(2, completed.getJSONObject("task_versions").getInt("http-task"))
            assertEquals("done", completed.getJSONObject("snapshot").getJSONObject("tables")
                .getJSONArray("tasks").getJSONObject(0).getString("status"))
            a.db.tasks().delete(task)
            PcSyncEngine.synchronize(a.context, credential, a.repository, HttpSyncTransport(credential), a.journal)
            val deleted = api.readSync(credential.token)
            assertEquals(3, deleted.getJSONObject("task_versions").getInt("http-task"))
            assertEquals(0, deleted.getJSONObject("snapshot").getJSONObject("tables").getJSONArray("tasks").length())
            val afterDeletion = Device()
            try {
                assertTrue(PcSyncEngine.synchronize(afterDeletion.context, credential, afterDeletion.repository,
                    HttpSyncTransport(credential), afterDeletion.journal))
                assertNull(afterDeletion.db.tasks().get("http-task"))
                SyncJournal(afterDeletion.context, afterDeletion.journal).use {
                    assertEquals(3, it.binding(syncScope(credential.server, credential.token))!!.tasks.versions.getInt("http-task"))
                }
            } finally { afterDeletion.close() }
            val store = AssistantStore(a.db, a.context.getSharedPreferences("assistant_conversation", 0))
            val multi = taskDraftReplacement(JSONObject().put("action", "create_tasks").put("tasks", org.json.JSONArray()
                .put(JSONObject().put("title", "取快递").put("place_type", "PARCEL").put("planned_date", "2026-10-15"))
                .put(JSONObject().put("title", "打印论文").put("place_type", "PRINT_SHOP").put("planned_date", "2026-10-16"))),
                null, UUID.randomUUID().toString())
            store.setDraft(multi)
            val print = taskDraftItems(multi)[1]
            assertTrue(store.confirmTasks(multi.getString("_draft_id"), mapOf(print.getString("item_id") to 1), "Saved print") {})
            PcSyncEngine.synchronize(a.context, credential, a.repository, HttpSyncTransport(credential), a.journal)
            val restoredDrafts = Device()
            try {
                assertTrue(PcSyncEngine.synchronize(restoredDrafts.context, credential, restoredDrafts.repository,
                    HttpSyncTransport(credential), restoredDrafts.journal))
                val restoredStore = AssistantStore(restoredDrafts.db, restoredDrafts.context.getSharedPreferences("assistant_conversation", 0))
                val pending = restoredStore.load().second!!
                assertEquals(store.load().second.toString(), pending.toString())
                assertEquals(1, restoredDrafts.db.assistant().draftItems().size)
                assertFalse(restoredStore.confirmTasks(multi.getString("_draft_id"), mapOf(print.getString("item_id") to 1), "Replay") {})
                val parcel = taskDraftItems(pending).single()
                restoredStore.updateDraftItem(pending.getString("_draft_id"), parcel.getString("item_id"), 1,
                    taskDraftErrand(parcel).copy(placeJson = Place("test-station", "Test pickup point", 120.0, 31.0).json().toString()))
                assertTrue(restoredStore.confirmTasks(restoredStore.load().second!!.getString("_draft_id"),
                    mapOf(parcel.getString("item_id") to 2), "Saved parcel") {})
                PcSyncEngine.synchronize(restoredDrafts.context, credential, restoredDrafts.repository,
                    HttpSyncTransport(credential), restoredDrafts.journal)
                assertNull(restoredStore.load().second)
                assertEquals(2, api.readSync(credential.token).getJSONObject("snapshot").getJSONObject("tables").getJSONArray("tasks").length())
            } finally { restoredDrafts.close() }
        } finally { a.close(); b.close() }
    }
}
