package com.onmyway.app.data.sync

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.remote.ApiHttpException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskCommandSyncTest {
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

    private class Device {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        val repository = BackupRepository(context, db)
        val journal = "task-sync-" + UUID.randomUUID() + ".db"
        val credential = BackupCredential("http://127.0.0.1:8000", "isolated-test-token")
        val scope get() = syncScope(credential.server, credential.token)
        suspend fun sync(remote: SyncTransport) = PcSyncEngine.synchronize(context, credential, repository, remote, journal)
        fun close() { db.close(); context.deleteDatabase(journal); context.clean() }

        fun createLegacyPending(kind: String, payload: JSONObject, baseline: JSONObject,
            revision: Long = 0, requestId: String = UUID.randomUUID().toString()): String {
            context.openOrCreateDatabase(journal, 0, null).use { sql ->
                sql.execSQL("CREATE TABLE bindings (scope TEXT PRIMARY KEY, store_id TEXT NOT NULL, revision INTEGER NOT NULL, baseline TEXT NOT NULL)")
                sql.execSQL("CREATE TABLE pending (scope TEXT PRIMARY KEY, kind TEXT NOT NULL, request_id TEXT NOT NULL, store_id TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL, baseline TEXT NOT NULL)")
                sql.insertOrThrow("pending", null, ContentValues().apply {
                    put("scope", scope); put("kind", kind); put("request_id", requestId)
                    put("store_id", "task-store"); put("revision", revision)
                    put("payload", payload.toString()); put("baseline", baseline.toString())
                })
                sql.version = 1
            }
            return requestId
        }
    }

    /** Strict fake task authority: stale commands or snapshot-only task writes are rejected. */
    private class Remote(var protocol: Int = 1) : SyncTransport {
        var revision = 0L
        var snapshot: JSONObject? = null
        var versions = JSONObject()
        var loseResponse = false
        var malformedVersions = false
        var commits = 0
        val writes = mutableListOf<JSONObject>()
        val receipts = mutableMapOf<String, Pair<String, JSONObject>>()
        fun upgrade() {
            protocol = 1
            for (id in taskRows(snapshot).keys) versions.put(id, 1)
        }
        override suspend fun read() = JSONObject().put("store_id", "task-store").put("revision", revision)
            .put("snapshot", snapshot ?: JSONObject.NULL).also {
                if (protocol == 1) {
                    it.put("task_protocol", 1)
                    if (!malformedVersions) it.put("task_versions", JSONObject(versions.toString()))
                }
            }
        override suspend fun write(body: JSONObject): JSONObject {
            writes.add(JSONObject(body.toString()))
            val requestId = body.getString("request_id")
            val digest = canonicalJson(body)
            val previous = receipts[requestId]
            val result: JSONObject
            if (previous != null) {
                if (previous.first != digest) throw ApiHttpException(409, "request changed")
                result = JSONObject(previous.second.toString())
            } else {
                if (body.getLong("base_revision") != revision) throw ApiHttpException(409, "revision conflict")
                val next = body.getJSONObject("snapshot")
                val nextVersions = JSONObject(versions.toString())
                val tasks = taskRows(snapshot).toMutableMap()
                if (protocol == 1) {
                    val commands = body.optJSONArray("task_commands") ?: JSONArray()
                    for (index in 0 until commands.length()) {
                        val command = commands.getJSONObject(index)
                        val id = command.getString("task_id")
                        if (command.getLong("expected_version") != nextVersions.optLong(id, 0))
                            throw ApiHttpException(409, "task conflict")
                        if (command.getString("operation") == "delete") tasks.remove(id)
                        else {
                            if (id !in tasks && nextVersions.has(id)) throw ApiHttpException(409, "tombstone")
                            tasks[id] = command.getJSONObject("task")
                        }
                        nextVersions.put(id, nextVersions.optLong(id, 0) + 1)
                    }
                    if (canonicalJson(JSONObject(tasks)) != canonicalJson(JSONObject(taskRows(next))))
                        throw ApiHttpException(409, "tasks must use commands")
                }
                snapshot = JSONObject(next.toString())
                versions = nextVersions
                revision++; commits++
                result = JSONObject().put("store_id", "task-store").put("revision", revision)
                if (protocol == 1) result.put("task_protocol", 1).put("task_versions", JSONObject(versions.toString()))
                receipts[requestId] = digest to JSONObject(result.toString())
            }
            if (loseResponse) { loseResponse = false; throw IOException("response lost after commit") }
            return result
        }
    }

    @Test fun firstUploadThenEditCompleteDeleteUsesAcknowledgedVersions() = runBlocking {
        val phone = Device(); val remote = Remote()
        try {
            val task = Errand(id = "task", title = "Buy water")
            phone.db.tasks().save(task); phone.sync(remote)
            val create = remote.writes.single().getJSONArray("task_commands").getJSONObject(0)
            assertEquals(0, create.getInt("expected_version"))
            assertEquals("upsert", create.getString("operation"))
            phone.db.tasks().save(task.copy(title = "Buy two bottles")); phone.sync(remote)
            phone.db.tasks().save(task.copy(status = "done", completedAt = 100)); phone.sync(remote)
            phone.db.tasks().delete(task); phone.sync(remote)
            assertEquals(listOf(0L, 1L, 2L, 3L), remote.writes.map {
                it.getJSONArray("task_commands").getJSONObject(0).getLong("expected_version")
            })
            assertEquals("delete", remote.writes.last().getJSONArray("task_commands").getJSONObject(0).getString("operation"))
            assertEquals(4, remote.versions.getInt("task"))
            assertTrue(taskRows(remote.snapshot).isEmpty())
            assertEquals(1, PcSyncEngine.taskProtocol(phone.context, phone.credential, phone.journal))
        } finally { phone.close() }
    }

    @Test fun lostAcknowledgementReplaysIdenticalCommandsBeforeNewerLocalEdit() = runBlocking {
        val phone = Device(); val remote = Remote()
        try {
            val task = Errand(id = "task", title = "Before")
            phone.db.tasks().save(task); remote.loseResponse = true
            try { phone.sync(remote); fail("expected network failure") } catch (_: IOException) { }
            SyncJournal(phone.context, phone.journal).use {
                assertNotNull(it.pending(phone.scope)!!.taskCommands)
            }
            phone.db.tasks().save(task.copy(title = "After")); phone.sync(remote)
            assertEquals(canonicalJson(remote.writes[0]), canonicalJson(remote.writes[1]))
            assertEquals(2, remote.commits)
            assertEquals(2, remote.versions.getInt("task"))
            assertEquals("After", taskRows(remote.snapshot).getValue("task").getString("title"))
        } finally { phone.close() }
    }

    @Test fun emptyReinstallationRestoresTasksAndVersionsThenEditsThem() = runBlocking {
        val source = Device(); val fresh = Device(); val remote = Remote()
        try {
            source.db.tasks().save(Errand(id = "task", title = "Computer copy")); source.sync(remote)
            assertTrue(fresh.sync(remote))
            fresh.db.tasks().save(fresh.db.tasks().get("task")!!.copy(title = "Restored edit")); fresh.sync(remote)
            assertEquals(1, remote.writes.last().getJSONArray("task_commands").getJSONObject(0).getInt("expected_version"))
            assertEquals(2, remote.versions.getInt("task"))
        } finally { source.close(); fresh.close() }
    }

    @Test fun v1UnsentQueueMigrationAddsCommandsWithoutChangingRequestId() = runBlocking {
        val phone = Device(); val remote = Remote()
        try {
            phone.db.tasks().save(Errand(id = "task", title = "Offline task"))
            val payload = phone.repository.snapshot()
            val id = phone.createLegacyPending("write", payload, payload)
            phone.sync(remote)
            assertEquals(id, remote.writes.single().getString("request_id"))
            assertEquals(1, remote.writes.single().getJSONArray("task_commands").length())
            SyncJournal(phone.context, phone.journal).use {
                assertEquals(2, it.readableDatabase.version)
                assertNull(it.pending(phone.scope))
                assertEquals(1, it.binding(phone.scope)!!.tasks.versions.getInt("task"))
            }
        } finally { phone.close() }
    }

    @Test fun v1CommittedQueueRecoversOldReceiptUnchangedThenRefreshesMetadata() = runBlocking {
        val phone = Device(); val remote = Remote(0)
        try {
            phone.db.tasks().save(Errand(id = "task", title = "Already committed"))
            val payload = phone.repository.snapshot()
            val id = phone.createLegacyPending("write", payload, payload)
            val original = JSONObject().put("store_id", "task-store").put("base_revision", 0)
                .put("request_id", id).put("snapshot", payload)
            remote.write(original); remote.upgrade()
            phone.sync(remote)
            assertEquals(1, remote.commits)
            assertEquals(canonicalJson(original), canonicalJson(remote.writes.last()))
            assertFalse(remote.writes.last().has("task_commands"))
            SyncJournal(phone.context, phone.journal).use {
                assertEquals(1, it.binding(phone.scope)!!.tasks.protocol)
                assertEquals(1, it.binding(phone.scope)!!.tasks.versions.getInt("task"))
            }
        } finally { phone.close() }
    }

    @Test fun v1PendingRestoreSurvivesUpgradeAndNegotiatesTaskService() = runBlocking {
        val source = Device(); val fresh = Device(); val remote = Remote()
        try {
            source.db.tasks().save(Errand(id = "task", title = "Durable restore")); source.sync(remote)
            fresh.createLegacyPending("restore", remote.snapshot!!, fresh.repository.snapshot(), remote.revision)
            assertTrue(fresh.sync(remote))
            assertEquals("Durable restore", fresh.db.tasks().get("task")!!.title)
            assertEquals(1, PcSyncEngine.taskProtocol(fresh.context, fresh.credential, fresh.journal))
            assertEquals(1, remote.commits)
        } finally { source.close(); fresh.close() }
    }

    @Test fun unchangedMigrationRefreshesVersionsAndRejectsLaterProtocolDowngrade() = runBlocking {
        val phone = Device(); val remote = Remote(0)
        try {
            val task = Errand(id = "task", title = "Existing legacy task")
            phone.db.tasks().save(task); phone.sync(remote)
            remote.upgrade(); phone.sync(remote)
            assertEquals(1, remote.commits)
            assertEquals(1, PcSyncEngine.taskProtocol(phone.context, phone.credential, phone.journal))
            remote.protocol = 0
            phone.db.tasks().save(task.copy(title = "Keep local edit"))
            try { phone.sync(remote); fail("expected downgrade conflict") } catch (_: SyncConflict) { }
            assertEquals(1, remote.commits)
            assertEquals("Keep local edit", phone.db.tasks().get("task")!!.title)
        } finally { phone.close() }
    }

    @Test fun malformedMetadataCannotCreateQueueOrAcknowledgeLocalTasks() = runBlocking {
        val phone = Device(); val remote = Remote()
        try {
            phone.db.tasks().save(Errand(id = "task", title = "Keep local"))
            remote.malformedVersions = true
            try { phone.sync(remote); fail("expected missing versions") } catch (_: SyncConflict) { }
            assertEquals(0, remote.commits)
            SyncJournal(phone.context, phone.journal).use {
                assertNull(it.binding(phone.scope)); assertNull(it.pending(phone.scope))
            }
            assertNotNull(phone.db.tasks().get("task"))
        } finally { phone.close() }
    }

    @Test fun modernPendingRequestCannotWriteIntoDowngradedLegacyServer() = runBlocking {
        val phone = Device(); val remote = Remote()
        try {
            phone.db.tasks().save(Errand(id = "task", title = "Queued operation"))
            remote.loseResponse = true
            try { phone.sync(remote); fail("expected lost response") } catch (_: IOException) { }
            remote.protocol = 0
            try { phone.sync(remote); fail("expected downgrade before PUT") } catch (_: SyncConflict) { }
            assertEquals(1, remote.writes.size)
            SyncJournal(phone.context, phone.journal).use {
                assertNotNull(it.pending(phone.scope))
                assertNull(it.binding(phone.scope))
            }
        } finally { phone.close() }
    }

    @Test fun concurrentRemoteAndOfflineChangesNeverUseNewVersionsToOverwrite() = runBlocking {
        val a = Device(); val b = Device(); val remote = Remote()
        try {
            a.db.tasks().save(Errand(id = "task", title = "Shared")); a.sync(remote); b.sync(remote)
            a.db.tasks().save(a.db.tasks().get("task")!!.copy(title = "Remote")); a.sync(remote)
            b.db.tasks().save(b.db.tasks().get("task")!!.copy(title = "Offline"))
            try { b.sync(remote); fail("expected conflict") } catch (_: SyncConflict) { }
            assertEquals("Offline", b.db.tasks().get("task")!!.title)
            assertEquals("Remote", taskRows(remote.snapshot).getValue("task").getString("title"))
            assertEquals(2, remote.commits)
        } finally { a.close(); b.close() }
    }

    @Test fun tombstoneVersionsAreRestoredAndCannotBeRecreatedBySnapshot() = runBlocking {
        val source = Device(); val fresh = Device(); val remote = Remote()
        try {
            val task = Errand(id = "task", title = "Deleted")
            source.db.tasks().save(task); source.sync(remote)
            source.db.tasks().delete(task); source.sync(remote)
            fresh.sync(remote)
            fresh.db.tasks().save(task)
            try { fresh.sync(remote); fail("expected tombstone conflict") } catch (_: SyncConflict) { }
            assertEquals(2, remote.versions.getInt("task"))
            assertTrue(taskRows(remote.snapshot).isEmpty())
            assertNotNull(fresh.db.tasks().get("task"))
        } finally { source.close(); fresh.close() }
    }
}
