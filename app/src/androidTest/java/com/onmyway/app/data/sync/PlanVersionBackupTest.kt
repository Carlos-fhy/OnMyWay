package com.onmyway.app.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.RouteHistory
import com.onmyway.app.data.local.entity.SavedTrip
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanVersionBackupTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "plan-version-backup-" + UUID.randomUUID() + "-"
        private val names = mutableSetOf<String>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }

        fun clean() { names.forEach { baseContext.deleteSharedPreferences(it) } }
    }

    private class Device {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        val repository = BackupRepository(context, database)
        val journal = "plan-version-backup-" + UUID.randomUUID() + ".db"
        val credential = BackupCredential("http://127.0.0.1:8000", "test-token")

        fun close() {
            database.close()
            context.deleteDatabase(journal)
            context.clean()
        }
    }

    private fun trip(version: Int = 2) = SavedTrip(
        requestJson = "{}", resultJson = "{}", started = true,
        sessionId = "parcel-print-dorm-session", planId = "parcel-print-dorm-plan",
        startedAt = 100, plannedAt = 50, planVersion = version,
    )

    private fun history() = RouteHistory(
        id = "parcel-print-dorm-history", sessionId = "parcel-print-dorm-session",
        planId = "parcel-print-dorm-plan", planVersion = 2,
        state = "NAVIGATING", timestamp = 100, requestJson = "{}", resultJson = "{}",
    )

    private fun assertWireSnapshot(snapshot: JSONObject) {
        assertEquals(14, snapshot.getInt("room_version"))
        for (table in listOf("saved_trip", "route_history")) {
            val rows = snapshot.getJSONObject("tables").getJSONArray(table)
            assertEquals(1, rows.length())
            assertFalse("$table must not upload the local execution version", rows.getJSONObject(0).has("planVersion"))
        }
    }

    @Test fun snapshotAndSchemaNormalizationKeepPlanVersionOutsideWire14() = runBlocking {
        val device = Device()
        try {
            device.database.trips().save(trip())
            device.database.trips().record(history())
            val snapshot = device.repository.snapshot()

            assertWireSnapshot(snapshot)
            assertWireSnapshot(device.repository.normalizedSchema(snapshot))
            assertWireSnapshot(device.repository.normalizedRestore(snapshot))
            assertEquals(2, device.database.trips().load()!!.planVersion)
            assertEquals(2, device.database.trips().history().single().planVersion)
        } finally { device.close() }
    }

    @Test fun wire14RestoreUsesUnknownLocalVersionAndRequiresReplanning() = runBlocking {
        val source = Device()
        val target = Device()
        try {
            source.database.trips().save(trip())
            source.database.trips().record(history())
            source.context.getSharedPreferences("navigation_context", 0).edit()
                .putString("snapshot", "active source navigation").commit()
            // Explicitly represent the existing wire contract, even before the snapshot fix.
            val snapshot = source.repository.snapshot()
            for (table in listOf("saved_trip", "route_history")) {
                snapshot.getJSONObject("tables").getJSONArray(table).getJSONObject(0).remove("planVersion")
            }

            target.repository.restore(snapshot)

            assertEquals(trip().copy(started = false, needsReplan = true, planVersion = 0), target.database.trips().load())
            assertEquals(history().copy(planVersion = 0), target.database.trips().history().single())
            assertFalse(target.context.getSharedPreferences("navigation_context", 0).contains("snapshot"))
            assertEquals(canonicalJson(target.repository.normalizedRestore(snapshot)), canonicalJson(target.repository.snapshot()))
        } finally {
            source.close()
            target.close()
        }
    }

    @Test fun versionOnlyLocalChangesDoNotUploadOrResetActiveNavigation() = runBlocking {
        val device = Device()
        try {
            device.database.trips().save(trip(version = 0))
            device.database.trips().record(history().copy(planVersion = 0))
            device.context.getSharedPreferences("navigation_context", 0).edit()
                .putString("snapshot", "active local navigation").commit()
            val baseline = device.repository.snapshot()
            for (table in listOf("saved_trip", "route_history")) {
                baseline.getJSONObject("tables").getJSONArray(table).getJSONObject(0).remove("planVersion")
            }
            val scope = syncScope(device.credential.server, device.credential.token)
            SyncJournal(device.context, device.journal).use {
                it.accept(scope, "version-test-store", 1, baseline)
            }
            device.database.trips().save(trip(version = 3))
            device.database.openHelper.writableDatabase.execSQL("UPDATE route_history SET planVersion = 3")
            val transport = object : SyncTransport {
                override suspend fun read() = JSONObject().put("store_id", "version-test-store")
                    .put("revision", 1).put("snapshot", JSONObject(baseline.toString()))
                override suspend fun write(body: JSONObject): JSONObject =
                    error("A local execution version must not produce a remote snapshot write")
            }

            assertFalse(PcSyncEngine.synchronize(device.context, device.credential,
                device.repository, transport, device.journal))

            assertEquals(trip(version = 3), device.database.trips().load())
            assertEquals(history().copy(planVersion = 3), device.database.trips().history().single())
            assertEquals("active local navigation", device.context.getSharedPreferences("navigation_context", 0)
                .getString("snapshot", null))
            SyncJournal(device.context, device.journal).use {
                assertNull(it.pending(scope))
                assertWireSnapshot(it.binding(scope)!!.baseline)
            }
        } finally { device.close() }
    }
}
