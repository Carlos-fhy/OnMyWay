package com.onmyway.app.core.state

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.context.ContextSnapshot
import com.onmyway.app.data.local.PlanningRequestJournal
import com.onmyway.app.data.local.TaskDatabase
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanningRequestJournalTest {
    @Test fun changingServerWhileResultIsInFlightRejectsTheOldResult() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        val name = "planning-server-change-${UUID.randomUUID()}.db"
        val journal = PlanningRequestJournal(app, name)
        val settings = app.getSharedPreferences("settings", 0)
        val originalServer = settings.getString("server", null)
        val originalCity = settings.getString("city", null)
        val vm = withContext(Dispatchers.Main) { AppViewModel(app, database, planningRequests = journal) }
        try {
            withTimeout(10_000) { while (vm.restoringTrip) delay(20) }
            withContext(Dispatchers.Main) { vm.settings("http://127.0.0.1:8000", vm.city) }
            val body = JSONObject("""{"origin":{"id":"start","name":"学校","longitude":121.0,"latitude":31.0},"destination":{"id":"dorm","name":"宿舍","longitude":121.01,"latitude":31.01},"mode":"walking","tasks":[],"departure":"2026-11-08T09:00:00+08:00"}""")
                .put("context_snapshot", ContextSnapshot.capture(emptyList(), null).json())
            val route = JSONObject("""{"mode":"walking","baseline_seconds":600,"baseline_meters":500,"travel_seconds":600,"service_seconds":0,"total_seconds":600,"total_meters":500,"extra_seconds":0,"extra_travel_seconds":0,"extra_meters":0,"arrival":"2026-11-08T09:10:00+08:00","stops":[],"legs":[{"seconds":600,"meters":500,"polyline":""}],"omitted":[],"warnings":[],"solver_status":"direct"}""")
                .put("origin", body.getJSONObject("origin")).put("destination", body.getJSONObject("destination"))
            var rejected = false
            try {
                vm.executeRoutePlan(body) {
                    withContext(Dispatchers.Main) { vm.settings("http://127.0.0.1:8001", vm.city) }
                    route
                }
            } catch (error: IllegalStateException) { rejected = error.message.orEmpty().contains("服务地址已变化") }
            assertTrue("A result from the old service must not be accepted", rejected)
            assertNotEquals("completed", database.capabilityRuns().recent().single().state)
            assertNull(database.trips().load())
        } finally {
            withContext(Dispatchers.Main) { vm.viewModelScope.cancel() }
            journal.close(); database.close(); app.deleteDatabase(name)
            settings.edit().apply {
                if (originalServer == null) remove("server") else putString("server", originalServer)
                if (originalCity == null) remove("city") else putString("city", originalCity)
            }.commit()
        }
    }

    @Test fun reopenedJournalRetainsExactRequestAndRejectsLateFinish() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "planning-journal-${UUID.randomUUID()}.db"
        var journal = PlanningRequestJournal(context, name)
        val body = "{\"context_snapshot\":{\"snapshot_id\":\"original\"}}"
        val request = PendingPlanningRequest(PlanningExecution.identity("http://127.0.0.1:8000", "plan", body),
            "http://127.0.0.1:8000", body, "plan", "trace", PlanningRequestOwner("manual", draftId = "draft"), 1L)
        try {
            journal.create(request)
            journal.close()
            journal = PlanningRequestJournal(context, name)
            assertEquals(request, journal.pending())
            assertFalse(journal.finish(UUID.randomUUID().toString()))
            assertEquals(request, journal.pending())
            assertTrue(journal.finish(request.runId))
            assertNull(journal.pending())
        } finally { journal.close(); context.deleteDatabase(name) }
    }
}
