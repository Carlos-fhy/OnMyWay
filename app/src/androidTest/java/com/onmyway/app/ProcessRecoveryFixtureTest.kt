package com.onmyway.app

import android.app.Application
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.onmyway.app.core.context.ContextLocation
import com.onmyway.app.core.context.ContextSnapshot
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.PendingPlanningRequest
import com.onmyway.app.data.local.AssistantRequestJournal
import com.onmyway.app.data.local.PendingAssistantRequest
import com.onmyway.app.data.local.PlanningRequestJournal
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.remote.Api
import com.onmyway.app.data.sync.BackupCredentials
import com.onmyway.app.data.sync.canonicalJson
import com.onmyway.app.feature.assistant.AssistantStore
import com.onmyway.app.feature.assistant.PersonalMemoryRepository
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host-controlled phases; never launches an Activity and deliberately preserves data on exit. */
@RunWith(AndroidJUnit4::class)
class ProcessRecoveryFixtureTest {
    @Test fun runPhase() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Host-only fixture requires isolatedProcessRecovery=true",
            args.getString("isolatedProcessRecovery") == "true")
        check(Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone") ||
            Build.HARDWARE in setOf("ranchu", "goldfish")) { "Only an isolated Android emulator is supported" }
        val app = instrumentation.targetContext.applicationContext as Application
        val database = TaskDatabase.get(app)
        val baselineFile = File(app.filesDir, "process-recovery-baseline.json")
        val observationFile = File(app.filesDir, "process-recovery-observation.json")
        val phase = args.getString("phase") ?: "observe"
        check(phase in setOf("seed", "observe", "assert")) { "Unknown fixture phase" }

        if (phase == "seed") {
            val server = Api.validateBase(args.getString("server") ?: "http://127.0.0.1:8000")
            check(URI(server).host in setOf("127.0.0.1", "localhost", "10.0.2.2")) { "Fixture backend must be local" }
            val mode = args.getString("mode") ?: "none"
            check(mode in setOf("none", "print_draft", "planning")) { "Unknown seed mode" }
            WorkManager.getInstance(app).cancelAllWork().result.get(10, TimeUnit.SECONDS)
            BackupCredentials.clear(app)
            check(app.getSharedPreferences("pc_backup", 0).edit().clear().commit())
            database.clearAllTables()
            for (name in listOf("settings", "assistant_conversation", "navigation_context")) {
                check(app.getSharedPreferences(name, 0).edit().clear().commit())
            }
            app.deleteDatabase("onmyway-assistant-requests.db")
            app.deleteDatabase("onmyway-navigation-requests.db")
            app.deleteDatabase("onmyway-planning-requests.db")
            check(app.getSharedPreferences("settings", 0).edit().putString("server", server).putString("city", "测试城").commit())
            PersonalMemoryRepository(app).setLocalControls(server, reference = false, learning = false)

            val now = System.currentTimeMillis()
            val departure = OffsetDateTime.now()
            val origin = Place("process-origin", "学校", 110.0, 30.0)
            val pickup = Place("process-parcel-stop", "快递站", 110.004, 30.0)
            val dorm = Place("process-dorm", "宿舍", 110.01, 30.0)
            val parcel = Errand(id = "process-fixture-parcel", title = "取快递", placeType = "PARCEL",
                placeJson = pickup.json().toString(), createdAt = now, updatedAt = now)
            database.tasks().save(parcel)
            val request = JSONObject().put("origin", origin.json()).put("destination", dorm.json())
                .put("mode", "walking").put("preference", "most_tasks").put("allow_partial", false)
                .put("max_extra_minutes", JSONObject.NULL).put("arrive_by", JSONObject.NULL)
                .put("departure", departure.toString()).put("tasks", JSONArray().put(parcel.json()))
                .put("context_snapshot", ContextSnapshot.capture(listOf(parcel), null,
                    location = ContextLocation("selected", latitude = origin.latitude, longitude = origin.longitude), now = now).json())
            val result = JSONObject().put("origin", origin.json()).put("destination", dorm.json()).put("mode", "walking")
                .put("baseline_seconds", 600).put("baseline_meters", 600).put("travel_seconds", 600)
                .put("service_seconds", 300).put("total_seconds", 900).put("total_meters", 600)
                .put("extra_seconds", 300).put("extra_travel_seconds", 0).put("extra_meters", 0)
                .put("arrival", departure.plusMinutes(15).toString()).put("solver_status", "OPTIMAL")
                .put("stops", JSONArray().put(JSONObject().put("place", pickup.json())
                    .put("task_ids", JSONArray().put(parcel.id)).put("task_titles", JSONArray().put(parcel.title))
                    .put("arrival", departure.plusMinutes(4).toString()).put("departure", departure.plusMinutes(9).toString())
                    .put("service_seconds", 300)))
                .put("legs", JSONArray().put(JSONObject().put("seconds", 240).put("meters", 240).put("polyline", ""))
                    .put(JSONObject().put("seconds", 360).put("meters", 360).put("polyline", "")))
                .put("omitted", JSONArray()).put("warnings", JSONArray())
            database.trips().save(SavedTrip(requestJson = request.toString(), resultJson = result.toString(),
                started = false, sessionId = "process-fixture-session", planId = "process-fixture-plan",
                plannedAt = now, planVersion = 0))
            val store = AssistantStore(database, app.getSharedPreferences("assistant_conversation", 0))
            store.load()
            store.updateMemorySettings(useMemory = false, autoRemember = false)
            if (mode == "print_draft") store.setDraft(JSONObject().put("action", "create_task")
                .put("title", "打印论文").put("place_type", "PRINT_SHOP")
                .put("place", Place("process-print-stop", "打印店", 110.007, 30.0).json())
                .put("_draft_id", "process-fixture-print-draft").put("_plan_id", "process-fixture-print-plan")
                .put("_card_version", 1))
            val baseline = JSONObject().put("fixture", FIXTURE).put("run_id", UUID.randomUUID().toString())
                .put("seed_mode", mode).put("server", server).put("conversation_id", store.activeConversationId)
                .put("fingerprints", fingerprints(database)).put("original_pending", JSONObject.NULL)
            baselineFile.writeText(baseline.toString(2), Charsets.UTF_8)
        }

        check(baselineFile.isFile) { "Seed this isolated fixture before observing it" }
        val baseline = JSONObject(baselineFile.readText(Charsets.UTF_8))
        check(baseline.getString("fixture") == FIXTURE) { "Unknown fixture baseline" }
        val pending = AssistantRequestJournal(app).use { it.pending() }
        val planningPending = PlanningRequestJournal(app).use { it.pending() }
        if (pending != null && baseline.isNull("original_pending")) {
            check(pending.server == baseline.getString("server")) { "Pending request does not belong to the synthetic fixture server" }
            baseline.put("original_pending", pendingJson(pending))
            baselineFile.writeText(baseline.toString(2), Charsets.UTF_8)
        }
        val messages = database.assistant().recentMessages()
        val draft = database.assistant().draft()
        val prefs = app.getSharedPreferences("assistant_conversation", 0)
        val original = baseline.optJSONObject("original_pending")
        val requestId = args.getString("requestId") ?: original?.optString("request_id").orEmpty()
        val originalUserId = original?.optString("user_message_id")?.takeIf(String::isNotBlank)
        val responseId = "agent-response-$requestId"
        val responseApplied = requestId.isNotBlank() && AssistantStore(database, prefs).responseApplied(requestId)
        val planningRunId = args.getString("runId") ?: planningPending?.runId.orEmpty()
        val trip = database.trips().load()
        val history = database.trips().history()
        val fingerprint = fingerprints(database)
        val observation = JSONObject().put("fixture", FIXTURE).put("run_id", baseline.getString("run_id"))
            .put("phase", phase).put("pid", Process.myPid()).put("observed_at", System.currentTimeMillis())
            .put("conversation_id", prefs.getString("active_conversation_id", ""))
            .put("memory_session_id", prefs.getString("memory_session_id", ""))
            .put("pending", pending?.let(::pendingJson) ?: JSONObject.NULL)
            .put("planning_pending", planningPending?.let(::planningJson) ?: JSONObject.NULL)
            .put("planning_run_id", planningRunId)
            .put("route_history_count", count(database, "route_history"))
            .put("matching_route_history_count", history.count { it.id == planningRunId })
            .put("route_history", JSONArray(history.map { JSONObject().put("id", it.id).put("state", it.state)
                .put("session_id", it.sessionId).put("plan_id", it.planId).put("plan_version", it.planVersion) }))
            .put("trip", trip?.let { JSONObject().put("session_id", it.sessionId).put("plan_id", it.planId)
                .put("plan_version", it.planVersion).put("started", it.started).put("started_at", it.startedAt)
                .put("request", JSONObject(it.requestJson)).put("result", JSONObject(it.resultJson)) } ?: JSONObject.NULL)
            .put("original_pending", original ?: JSONObject.NULL)
            .put("request_id", requestId).put("message_count", messages.size)
            .put("response_count", messages.count { it.id == responseId })
            .put("response_applied", responseApplied)
            .put("all_response_count", messages.count { it.id.startsWith("agent-response-") })
            // Host-only SIGKILL checks cannot run instrumentation while the original process lives.
            // Their backend capture supplies requestId; this fixture starts with no user messages.
            .put("user_message_count", messages.count { if (originalUserId == null) it.role == "user" else it.id == originalUserId })
            .put("messages", JSONArray(messages.map { JSONObject().put("id", it.id).put("role", it.role)
                .put("text", it.text).put("conversation_id", it.conversationId).put("created_at", it.createdAt) }))
            .put("draft", draft?.let { JSONObject().put("draft_id", it.draftId).put("plan_id", it.planId)
                .put("action", it.action).put("title", it.title).put("conversation_id", it.conversationId)
                .put("proposal", it.proposal()) } ?: JSONObject.NULL)
            .put("fingerprints", fingerprint).put("baseline_fingerprints", baseline.getJSONObject("fingerprints"))
            .put("confirmation_count", count(database, "confirmation_outbox"))
            .put("task_count", count(database, "tasks"))
        observationFile.writeText(observation.toString(2), Charsets.UTF_8)

        if (phase == "assert") {
            val expected = args.getString("expected") ?: "completed"
            if (expected == "planning_recovered")
                assertEquals("Planning must not change the parcel task", baseline.getJSONObject("fingerprints").getString("tasks"), fingerprint.getString("tasks"))
            else assertEquals("Parcel and dorm data must stay unchanged", canonicalJson(baseline.getJSONObject("fingerprints")), canonicalJson(fingerprint))
            assertEquals(1, count(database, "tasks"))
            assertEquals(if (expected == "planning_recovered") 1 else 0, count(database, "confirmation_outbox"))
            val activeConversation = prefs.getString("active_conversation_id", "").orEmpty()
            assertTrue("Active conversation must survive restart", activeConversation.isNotBlank())
            if (args.getString("changedConversation") == "true")
                assertNotEquals("The new conversation must stay active", baseline.getString("conversation_id"), activeConversation)
            else assertEquals(baseline.getString("conversation_id"), activeConversation)
            when (expected) {
                "planning_pending" -> {
                    assertNotNull("Planning request must survive process death", planningPending)
                    assertEquals(planningRunId, planningPending?.runId)
                    assertEquals("manual", planningPending?.owner?.kind)
                    assertEquals(0, observation.getInt("route_history_count"))
                    assertNull(pending)
                    assertNull(draft)
                }
                "planning_recovered" -> {
                    assertTrue("Host must provide the original planning runId", planningRunId.isNotBlank())
                    assertNull("Confirmed planning request must be consumed", planningPending)
                    assertNull(pending)
                    assertNull(draft)
                    val restored = checkNotNull(trip) { "Recovered route must be saved" }
                    assertFalse("Recovery must not start navigation", restored.started)
                    assertEquals(0L, restored.startedAt)
                    assertFalse(restored.needsReplan)
                    assertEquals("process-fixture-session", restored.sessionId)
                    // The seed is an unversioned legacy plan; its first confirmation gets a new identity.
                    assertNotEquals("process-fixture-plan", restored.planId)
                    assertEquals(restored.planId, UUID.fromString(restored.planId).toString())
                    assertEquals(1, restored.planVersion)
                    val request = JSONObject(restored.requestJson)
                    val result = JSONObject(restored.resultJson)
                    assertEquals("process-dorm", request.getJSONObject("destination").getString("id"))
                    assertEquals("宿舍", result.getJSONObject("destination").getString("name"))
                    assertEquals("process-dorm", result.getJSONObject("destination").getString("id"))
                    assertEquals(1, request.getJSONArray("tasks").length())
                    assertEquals("process-fixture-parcel", request.getJSONArray("tasks").getJSONObject(0).getString("id"))
                    val routedIds = result.getJSONArray("stops").let { stops -> (0 until stops.length()).flatMap { index ->
                        stops.getJSONObject(index).getJSONArray("task_ids").let { ids -> (0 until ids.length()).map(ids::getString) }
                    } }
                    assertEquals(listOf("process-fixture-parcel"), routedIds)
                    assertEquals(0, result.getJSONArray("omitted").length())
                    assertEquals(1, observation.getInt("matching_route_history_count"))
                    assertEquals(1, history.count { it.state == "READY" })
                    assertEquals(1, observation.getInt("route_history_count"))
                    val receipt = checkNotNull(database.trips().historyById(planningRunId))
                    assertEquals("READY", receipt.state)
                    assertEquals(restored.sessionId, receipt.sessionId)
                    assertEquals(restored.planId, receipt.planId)
                    assertEquals(restored.planVersion, receipt.planVersion)
                    assertEquals(canonicalJson(request), canonicalJson(JSONObject(receipt.requestJson)))
                    assertEquals(canonicalJson(result), canonicalJson(JSONObject(receipt.resultJson)))
                }
                "pending" -> {
                    assertNotNull("Original request must remain recoverable", pending)
                    assertNotNull("Capture the original request with observe before restarting", original)
                    val actual = pendingJson(checkNotNull(pending)).apply { remove("result_json") }
                    val saved = JSONObject(checkNotNull(original).toString()).apply { remove("result_json") }
                    assertEquals(canonicalJson(saved), canonicalJson(actual))
                    assertEquals(0, observation.getInt("response_count"))
                    assertFalse(responseApplied)
                }
                "completed", "cancelled" -> {
                    assertTrue("Expected original request identity", requestId.isNotBlank())
                    assertNull("Consumed request journal must be cleared", pending)
                    assertEquals(1, observation.getInt("response_count"))
                    assertEquals(1, observation.getInt("all_response_count"))
                    assertTrue(responseApplied)
                    assertEquals(1, observation.getInt("user_message_count"))
                    if (expected == "cancelled") assertNull("Cancelled printing draft must not revive", draft)
                    else args.getString("expectedDraftTitle")?.let { assertEquals(it, draft?.title) }
                }
                "idle" -> {
                    assertNull("Abandoned request must remain cleared", pending)
                    assertNull("Abandoned printing draft must not revive", draft)
                    assertEquals(0, observation.getInt("response_count"))
                    assertEquals(0, observation.getInt("all_response_count"))
                    assertFalse("Abandoned response must not be applied", responseApplied)
                    if (requestId.isNotBlank()) assertEquals(1, observation.getInt("user_message_count"))
                }
                else -> error("Unknown expected state")
            }
            assertTrue(messages.all { it.conversationId == baseline.getString("conversation_id") })
        }
    }

    private fun pendingJson(request: PendingAssistantRequest) = JSONObject().put("request_id", request.requestId)
        .put("server", request.server).put("body_json", request.bodyJson).put("context_key", request.contextKey)
        .put("user_text", request.userText).put("user_message_id", request.userMessageId)
        .put("created_at", request.createdAt).put("result_json", request.resultJson ?: JSONObject.NULL)

    private fun planningJson(request: PendingPlanningRequest) = JSONObject().put("run_id", request.runId)
        .put("server", request.server).put("body_json", request.bodyJson).put("operation", request.operation)
        .put("correlation_id", request.correlationId).put("created_at", request.createdAt)
        .put("owner", JSONObject().put("kind", request.owner.kind).put("conversation_id", request.owner.conversationId)
            .put("draft_id", request.owner.draftId).put("plan_id", request.owner.planId)
            .put("session_id", request.owner.sessionId).put("plan_version", request.owner.planVersion))

    private fun count(database: TaskDatabase, table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst()); cursor.getInt(0)
        }

    private fun fingerprints(database: TaskDatabase): JSONObject = JSONObject().apply {
        for (table in listOf("tasks", "saved_trip", "route_history", "confirmation_outbox")) {
            val rows = JSONArray()
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) rows.put(JSONObject().apply {
                    cursor.columnNames.forEachIndexed { index, name ->
                        put(name, if (cursor.isNull(index)) JSONObject.NULL else cursor.getString(index))
                    }
                })
            }
            val canonical = canonicalJson(JSONObject().put("rows", rows))
            put(table, MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) })
        }
    }

    companion object { private const val FIXTURE = "onmyway-isolated-process-recovery-v1" }
}
