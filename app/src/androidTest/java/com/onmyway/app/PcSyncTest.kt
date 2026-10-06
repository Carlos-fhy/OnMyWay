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
            a.db.tasks().save(Errand(id = "http-task", title = "Restored over HTTP"))
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
            assertEquals(11, remote.getJSONObject("snapshot").getInt("room_version"))
            assertEquals(1, remote.getInt("revision"))
        } finally { a.close(); b.close() }
    }
}
