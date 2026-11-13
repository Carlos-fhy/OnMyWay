package com.onmyway.app.core.state

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.context.ContextSnapshot
import com.onmyway.app.data.execution.RoomCapabilityRunStore
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.remote.Api
import com.onmyway.app.data.sync.canonicalJson
import java.io.IOException
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an isolated emulator and the synthetic Maps backend on adb-reversed port 8000. */
@RunWith(AndroidJUnit4::class)
class PlanningHttpRecoveryTest {
    private val server = "http://127.0.0.1:8000"

    private fun place(id: String, name: String, longitude: Double) = JSONObject()
        .put("id", id).put("name", name).put("longitude", longitude).put("latitude", 30.0).put("address", "")

    private fun tasks() = listOf(
        Errand(id = "parcel", title = "取快递", serviceMinutes = 2,
            placeJson = place("a", "快递站", 110.004).toString(), placeType = "PARCEL"),
        Errand(id = "print", title = "打印论文", serviceMinutes = 2,
            placeJson = place("b", "打印店", 110.007).toString(), placeType = "PRINT_SHOP"),
    )

    private fun request(tasks: List<Errand>, snapshot: ContextSnapshot) = JSONObject()
        .put("origin", place("start", "学校", 110.0)).put("destination", place("end", "宿舍", 110.01))
        .put("tasks", JSONArray(tasks.map { it.json() })).put("context_snapshot", snapshot.json())
        .put("mode", "walking").put("preference", "most_tasks").put("departure", OffsetDateTime.now().toString())
        .put("allow_partial", true).put("max_extra_minutes", 10)

    @Test fun lostPlanResponseIsRecoveredAndCachedAfterRoomReopens() = runBlocking {
        recoverAndReopen("plan")
    }

    @Test fun lostRecommendationResponseIsRecoveredAndCachedAfterRoomReopens() = runBlocking {
        recoverAndReopen("recommend")
    }

    private suspend fun recoverAndReopen(operation: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "h2-http-recovery-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, TaskDatabase::class.java, databaseName).build()
        var database = open()
        try {
            val tasks = tasks()
            tasks.forEach { database.tasks().save(it) }
            val snapshot = ContextSnapshot.capture(tasks, null, taskScope = "all")
            val body = request(tasks, snapshot)
            val current: suspend () -> Unit = { snapshot.requireCurrent(database.tasks().all(), database.trips().load()) }
            var submitted = 0
            var lookedUp = 0
            var serverResult: JSONObject? = null
            val execution = PlanningExecution(RoomCapabilityRunStore(database), submit = { endpoint, payload, id, op ->
                submitted++
                serverResult = if (op == "plan") Api(endpoint).plan(payload, id) else Api(endpoint).recommend(payload, id)
                throw IOException("synthetic response loss after the server completed")
            }, status = { endpoint, payload, id, op ->
                lookedUp++
                Api(endpoint).planningStatus(payload, id, op)
            })
            val recovered = execution.execute(server, body, operation, "original-correlation", current)
            assertEquals(1, submitted)
            assertEquals(1, lookedUp)
            assertEquals(canonicalJson(checkNotNull(serverResult)), canonicalJson(recovered))
            val route = if (operation == "plan") recovered else recovered.getJSONObject("suggestion")
            assertEquals("宿舍", route.getJSONObject("destination").getString("name"))
            val selected = route.getJSONArray("stops").let { stops ->
                buildSet {
                    for (i in 0 until stops.length()) {
                        val ids = stops.getJSONObject(i).getJSONArray("task_ids")
                        for (j in 0 until ids.length()) add(ids.getString(j))
                    }
                }
            }
            assertEquals(setOf("parcel", "print"), selected)
            val saved = database.capabilityRuns().recent().single()
            assertEquals("completed", saved.state)
            database.close()
            database = open()
            val reopened = PlanningExecution(RoomCapabilityRunStore(database), submit = { _, _, _, _ ->
                error("reopening Room must not submit another plan")
            }, status = { _, _, _, _ -> error("completed local evidence must not query the server again") })
            val cached = reopened.execute(server, body, operation, "new-screen-correlation", current)
            assertEquals(canonicalJson(recovered), canonicalJson(cached))
            val persisted = database.capabilityRuns().recent().single()
            assertEquals(saved.runId, persisted.runId)
            assertEquals("original-correlation", persisted.correlationId)
            assertEquals("completed", persisted.state)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun cancellingPrintDuringHttpRecoveryRejectsStaleRouteAndKeepsParcelAndDorm() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "h2-http-stale-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, TaskDatabase::class.java, databaseName).build()
        try {
            val tasks = tasks()
            tasks.forEach { database.tasks().save(it) }
            val snapshot = ContextSnapshot.capture(tasks, null, taskScope = "all")
            val body = request(tasks, snapshot)
            var submitted = 0
            var lookedUp = 0
            val execution = PlanningExecution(RoomCapabilityRunStore(database), submit = { endpoint, payload, id, _ ->
                submitted++
                Api(endpoint).plan(payload, id)
                throw IOException("synthetic response loss")
            }, status = { endpoint, payload, id, operation ->
                lookedUp++
                Api(endpoint).planningStatus(payload, id, operation).also {
                    database.tasks().delete(tasks.single { task -> task.id == "print" })
                }
            })
            try {
                execution.execute(server, body, "plan", "cancel-print") {
                    snapshot.requireCurrent(database.tasks().all(), database.trips().load())
                }
                fail("recovered route used the cancelled print task")
            } catch (_: IllegalStateException) { }
            assertEquals(1, submitted)
            assertEquals(1, lookedUp)
            assertEquals(listOf("parcel"), database.tasks().all().map { it.id })
            assertEquals("宿舍", body.getJSONObject("destination").getString("name"))
            assertEquals("failed", database.capabilityRuns().recent().single().state)
            assertNull(database.trips().load())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
