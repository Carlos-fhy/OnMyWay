package com.onmyway.app.feature.navigation

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.PendingAssistantRequest
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only on the isolated test emulator: no location, map, model or sync network is used. */
@RunWith(AndroidJUnit4::class)
class NavigationRecoveryTest {
    private lateinit var app: Application
    private lateinit var isolatedApp: Application
    private lateinit var databaseName: String
    private lateinit var journalName: String
    private lateinit var db: TaskDatabase
    private var model: AppViewModel? = null
    private var delivery: NavigationRequestDelivery? = null
    private val preferenceNames = mutableSetOf<String>()
    private val transport = FakeTransport()
    private val maps = FakeMaps()
    private val origin = Place("origin", "Teaching building", 120.576, 31.249, "苏州市")
    private val destination = Place("dorm", "Dormitory", 120.579, 31.253)
    private val printShop = Place("print-shop", "Campus printing", 120.577, 31.250)
    private val alternative = Place("library", "Campus library", 120.581, 31.251)
    private lateinit var task: Errand
    private lateinit var initial: SavedTrip

    @Before fun setUp() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        databaseName = "navigation_recovery_" + UUID.randomUUID()
        // Do not use Room's reserved '<database>-journal' rollback sidecar filename.
        journalName = "$databaseName-navigation.db"
        isolatedApp = object : Application() {
            init { attachBaseContext(app) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$databaseName-$name"
                preferenceNames += isolatedName
                return app.getSharedPreferences(isolatedName, mode)
            }
        }
        isolatedApp.getSharedPreferences("settings", 0).edit()
            .putString("server", "http://127.0.0.1:8000").putString("city", "苏州").commit()
        db = Room.databaseBuilder(app, TaskDatabase::class.java, databaseName).build()
        task = Errand(id = "print-task", title = "Print thesis", placeType = "PRINT_SHOP",
            placeJson = printShop.json().toString(), createdAt = 123L)
        db.tasks().save(task)
        val body = JSONObject().put("origin", origin.json()).put("destination", destination.json())
            .put("mode", "cycling").put("preference", "shortest").put("allow_partial", false)
            .put("max_extra_minutes", 15).put("arrive_by", JSONObject.NULL)
            .put("tasks", JSONArray().put(task.json()))
        val result = JSONObject().put("destination", destination.json())
            .put("stops", JSONArray().put(JSONObject().put("place", printShop.json())
                .put("task_ids", JSONArray().put(task.id))))
            .put("total_seconds", 600).put("total_meters", 1000)
        // An unstarted plan survives ViewModel recreation without reviving a stopped navigation.
        initial = SavedTrip(requestJson = body.toString(), resultJson = result.toString(), started = false)
        db.trips().save(initial)
        maps.currentOrigin = origin
        maps.choices = listOf(alternative)
        transport.answer = adjustment()
    }

    @After fun tearDown() = runBlocking {
        stopModel()
        db.close()
        app.deleteDatabase(databaseName)
        app.deleteDatabase(journalName)
        preferenceNames.forEach(app::deleteSharedPreferences)
    }

    private fun adjustment() = JSONObject().put("action", "adjust")
        .put("keep_task_ids", JSONArray()).put("destination", "")

    private fun addStop(destinationKeyword: String = "") = JSONObject().put("action", "add_stop")
        .put("keep_task_ids", JSONArray().put(task.id)).put("destination", destinationKeyword)
        .put("new_place_type", "SUPERMARKET").put("new_task_title", "Buy water")

    private suspend fun startModel(): AppViewModel {
        check(model == null)
        val requestDelivery = NavigationRequestDelivery(app, journalName, transport).also { delivery = it }
        val vm = withContext(Dispatchers.Main) { AppViewModel(isolatedApp, db, requestDelivery, maps) }
            .also { model = it }
        withTimeout(5_000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
        assertNotNull("Saved test trip must be restored", withContext(Dispatchers.Main) { vm.planningBase() })
        return vm
    }

    private suspend fun stopModel() {
        val previous = model
        model = null
        withContext(Dispatchers.Main) { previous?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
        delivery?.close()
        delivery = null
    }

    private suspend fun restartModel(): AppViewModel {
        stopModel()
        db.close()
        db = Room.databaseBuilder(app, TaskDatabase::class.java, databaseName).build()
        return startModel()
    }

    private suspend fun interrupt(vm: AppViewModel, text: String): PendingAssistantRequest {
        transport.failSubmissions = 1
        val failure = withContext(Dispatchers.Main) { runCatching { vm.previewNavigationChange(text) }.exceptionOrNull() }
        assertTrue("Injected model connection loss should remain recoverable: $failure", failure is IOException)
        val pending = checkNotNull(delivery!!.pending())
        assertEquals(text, pending.userText)
        assertNull(pending.resultJson)
        assertEquals(pending.requestId, withContext(Dispatchers.Main) { vm.pendingNavigationRequest?.requestId })
        return pending
    }

    private suspend fun assertNoAcceptedChanges() {
        assertEquals(initial, db.trips().load())
        assertEquals(listOf(task), db.tasks().observe().first())
        assertTrue(db.trips().history().isEmpty())
    }

    @Test fun interruptedSubmissionRecoversOriginalRequestAfterRestartWithoutConfirmingRoute() = runBlocking {
        val instruction = "Keep this route but skip the printing stop"
        val pending = interrupt(startModel(), instruction)
        val vm = restartModel()
        val change = withContext(Dispatchers.Main) { vm.recoverNavigationRequest() }
        assertNotNull(change)
        assertEquals(1, transport.submitted.size)
        assertEquals(listOf(pending.bodyJson), transport.statusBodies)
        assertEquals(pending.bodyJson, transport.submitted.single())
        assertEquals(1, maps.planned.size)
        assertEquals(0, change!!.body.getJSONArray("tasks").length())
        assertEquals("cycling", change.body.getString("mode"))
        assertNull(delivery!!.pending())
        assertNotNull(delivery!!.loadContext())
        withContext(Dispatchers.Main) {
            assertEquals(1, vm.navigationConversation.history.count { it == ("user" to instruction) })
            assertEquals(change.versionId, vm.navigationConversation.draft?.versionId)
            assertNull(vm.recoverNavigationRequest())
        }
        assertNoAcceptedChanges()

        val restored = restartModel()
        withContext(Dispatchers.Main) {
            val memory = restored.navigationConversation
            assertNull(memory.draft)
            assertNotNull(memory.proposedBody)
            assertEquals(1, memory.history.count { it == ("user" to instruction) })
            restored.recalculateNavigationProposal()
        }
        assertEquals(2, maps.planned.size)
        assertEquals(1, transport.submitted.size)
        assertEquals(1, transport.statusBodies.size)
        assertNoAcceptedChanges()
    }

    @Test fun routingFailureReusesCachedIntentAndStableNewTaskIdAcrossRestart() = runBlocking {
        transport.answer = addStop()
        maps.failPlans = 1
        val vm = startModel()
        val failure = withContext(Dispatchers.Main) {
            runCatching { vm.previewNavigationChange("Add a supermarket stop for water") }.exceptionOrNull()
        }
        assertTrue(failure is IOException)
        val pending = checkNotNull(delivery!!.pending())
        assertNotNull(pending.resultJson)
        val failedBody = JSONObject(maps.planned.single())
        val newId = failedBody.getJSONArray("tasks").getJSONObject(1).getString("id")
        assertNoAcceptedChanges()

        maps.currentOrigin = origin.copy(longitude = 120.5765)
        val restored = restartModel()
        val change = withContext(Dispatchers.Main) { checkNotNull(restored.recoverNavigationRequest()) }
        assertEquals(1, transport.submitted.size)
        assertTrue("A cached result must not ask the model status again", transport.statusBodies.isEmpty())
        assertEquals(2, maps.planned.size)
        assertEquals(newId, change.addedTask?.id)
        assertEquals(newId, change.body.getJSONArray("tasks").getJSONObject(1).getString("id"))
        assertEquals(maps.currentOrigin, Place.from(change.body.getJSONObject("origin")))
        assertNotNull(change.addedTask?.place())
        assertNull(delivery!!.pending())
        assertNoAcceptedChanges()

        withContext(Dispatchers.Main) { restored.confirmNavigationChange(change) }
        assertEquals(2, db.tasks().observe().first().size)
        assertEquals(change.addedTask, db.tasks().get(newId))
        val route = db.trips().load()
        withContext(Dispatchers.Main) {
            assertTrue(runCatching { restored.confirmNavigationChange(change) }.isFailure)
        }
        assertEquals(route, db.trips().load())
        assertEquals(2, db.tasks().observe().first().size)
    }

    @Test fun changedTaskRejectsInterruptedIntentBeforeStatusOrMapRequests() = runBlocking {
        val pending = interrupt(startModel(), "Skip the printing stop and continue")
        val changed = task.copy(status = "done", completedAt = System.currentTimeMillis())
        db.tasks().save(changed)
        val restored = restartModel()
        withContext(Dispatchers.Main) {
            assertNull(restored.recoverNavigationRequest())
            assertTrue(restored.navigationRecoveryMessage.contains("已变化"))
            assertNull(restored.navigationConversation.draft)
        }
        assertTrue(transport.statusBodies.isEmpty())
        assertTrue(maps.planned.isEmpty())
        assertEquals(pending.requestId, delivery!!.pending()?.requestId)
        assertEquals(initial, db.trips().load())
        assertEquals(changed, db.tasks().get(task.id))
    }

    @Test fun replacedTripRejectsInterruptedIntentBeforeStatusOrMapRequests() = runBlocking {
        interrupt(startModel(), "Skip printing and take me home")
        val replacement = initial.copy(sessionId = "replacement-session", planId = "replacement-plan")
        db.trips().save(replacement)
        val restored = restartModel()
        withContext(Dispatchers.Main) {
            assertNull(restored.recoverNavigationRequest())
            assertTrue(restored.navigationRecoveryMessage.contains("已变化"))
        }
        assertTrue(transport.statusBodies.isEmpty())
        assertTrue(maps.planned.isEmpty())
        assertEquals(replacement, db.trips().load())
        assertEquals(listOf(task), db.tasks().observe().first())
    }

    @Test fun recoveredDestinationChoicePersistsUntilSelectionAndDoesNotDuplicateNewTask() = runBlocking {
        val instruction = "Buy water and change the destination to the library"
        transport.answer = addStop("Campus library")
        interrupt(startModel(), instruction)
        val vm = restartModel()
        val choice = withContext(Dispatchers.Main) {
            runCatching { vm.recoverNavigationRequest() }.exceptionOrNull() as? NavigationDestinationChoice
        }
        assertNotNull("Recovery should ask for the searched destination, not choose one", choice)
        val taskId = checkNotNull(choice!!.newTask).id
        assertEquals(listOf(alternative), choice.places)
        assertNull(delivery!!.pending())
        assertTrue(maps.planned.isEmpty())
        assertNoAcceptedChanges()

        val restored = restartModel()
        val change = withContext(Dispatchers.Main) {
            val memory = restored.navigationConversation
            assertEquals(instruction, memory.pendingInstruction)
            assertEquals(listOf(alternative), memory.places)
            assertEquals(taskId, memory.pendingChoiceNewTask?.id)
            assertEquals(1, memory.history.count { it == ("user" to instruction) })
            restored.previewNavigationChange(memory.pendingInstruction, memory.places.single())
        }
        assertEquals(taskId, change.addedTask?.id)
        assertEquals(alternative, Place.from(change.body.getJSONObject("destination")))
        assertEquals(2, change.body.getJSONArray("tasks").length())
        assertEquals(1, transport.submitted.size)
        assertEquals(1, transport.statusBodies.size)
        assertEquals(1, maps.searches.size)
        assertNoAcceptedChanges()

        // Reopening the resulting preview requires recalculation, not another model turn or task.
        val reopened = restartModel()
        val fresh = withContext(Dispatchers.Main) {
            assertNull(reopened.navigationConversation.draft)
            assertEquals(taskId, reopened.navigationConversation.pendingNewTask?.id)
            reopened.recalculateNavigationProposal()
        }
        assertEquals(taskId, fresh.addedTask?.id)
        assertEquals(2, fresh.body.getJSONArray("tasks").length())
        assertEquals(1, transport.submitted.size)
        withContext(Dispatchers.Main) { reopened.confirmNavigationChange(fresh) }
        assertEquals(2, db.tasks().observe().first().size)
        assertEquals(taskId, db.tasks().observe().first().single { it.id != task.id }.id)
    }

    @Test fun taskChangedDuringDestinationSearchCannotCommitOldChoices() = runBlocking {
        transport.answer = addStop("Campus library")
        maps.beforeSearchReturn = { db.tasks().save(task.copy(status = "done", completedAt = 456L)) }
        val vm = startModel()
        val failure = withContext(Dispatchers.Main) {
            runCatching { vm.previewNavigationChange("Buy water and change to the library") }.exceptionOrNull()
        }
        assertTrue("A search result for obsolete task context must be rejected", failure is IllegalStateException)
        assertFalse(failure is NavigationDestinationChoice)
        withContext(Dispatchers.Main) {
            assertTrue(vm.navigationConversation.places.isEmpty())
            assertNull(vm.navigationConversation.draft)
        }
        assertNotNull("Keep the delivered intent available for explicit editing", delivery!!.pending()?.resultJson)
        assertTrue(maps.planned.isEmpty())
        assertEquals(initial, db.trips().load())
        assertEquals("done", db.tasks().get(task.id)?.status)
    }

    @Test fun overlappingAdjustmentsCannotReplaceAnInFlightProposal() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        maps.beforePlanReturn = { entered.complete(Unit); release.await() }
        val vm = startModel()
        val original = async(Dispatchers.Main) { vm.previewNavigationChange("Skip printing this time") }
        try {
            withTimeout(5_000) { entered.await() }
            withContext(Dispatchers.Main) {
                assertTrue(runCatching { vm.previewNavigationChange("Change to another destination") }.isFailure)
                assertTrue(runCatching { vm.recalculateNavigationProposal() }.isFailure)
                assertTrue(runCatching { vm.editNavigationRequest() }.isFailure)
                assertTrue(runCatching { vm.cancelNavigationAdjustment() }.isFailure)
                val stale = NavigationChange(initial, JSONObject(initial.requestJson), JSONObject(initial.resultJson), listOf(task))
                assertTrue(runCatching { vm.confirmNavigationChange(stale) }.isFailure)
            }
        } finally { release.complete(Unit) }
        val change = original.await()
        assertEquals(1, transport.submitted.size)
        assertEquals(1, maps.planned.size)
        withContext(Dispatchers.Main) { assertEquals(change.versionId, vm.navigationConversation.draft?.versionId) }
        assertNoAcceptedChanges()
    }

    @Test fun contextCheckpointFailureDoesNotReportCommittedRouteAsFailed() = runBlocking {
        val vm = startModel()
        val change = withContext(Dispatchers.Main) { vm.previewNavigationChange("Skip printing this time") }
        app.openOrCreateDatabase(journalName, 0, null).use { journal ->
            journal.execSQL("CREATE TRIGGER reject_context BEFORE INSERT ON navigation_context BEGIN SELECT RAISE(ABORT, 'injected checkpoint failure'); END")
        }
        withContext(Dispatchers.Main) {
            vm.confirmNavigationChange(change)
            assertEquals(change.bodyJson, vm.planningBase()?.requestJson)
            assertEquals(Place.from(change.body.getJSONObject("destination")), vm.destination)
            assertTrue(vm.selected.isEmpty())
            assertNull(vm.navigationConversation.draft)
            assertTrue(vm.message.contains("路线已更新"))
        }
        assertEquals(change.bodyJson, db.trips().load()?.requestJson)
        assertNotNull(db.trips().historyById(change.versionId))
        assertEquals(listOf(task), db.tasks().observe().first())
    }

    private class FakeTransport : NavigationRequestTransport {
        var answer = JSONObject()
        var failSubmissions = 0
        val submitted = mutableListOf<String>()
        val statusBodies = mutableListOf<String>()

        override suspend fun submit(server: String, body: JSONObject): JSONObject {
            submitted += body.toString()
            if (failSubmissions > 0) {
                failSubmissions--
                throw IOException("Injected disconnection after server accepted request")
            }
            return JSONObject(answer.toString())
        }

        override suspend fun status(server: String, body: JSONObject): JSONObject {
            statusBodies += body.toString()
            return JSONObject().put("request_id", body.getString("request_id"))
                .put("operation", "navigation").put("state", "completed")
                .put("result", JSONObject(answer.toString()))
        }
    }

    private class FakeMaps : NavigationRouteTools {
        lateinit var currentOrigin: Place
        var choices = emptyList<Place>()
        var failPlans = 0
        var beforeSearchReturn: (suspend () -> Unit)? = null
        var beforePlanReturn: (suspend () -> Unit)? = null
        val planned = mutableListOf<String>()
        val searches = mutableListOf<Pair<String, String>>()
        private val supermarket = Place("campus-market", "Campus supermarket", 120.580, 31.251)

        override suspend fun origin() = currentOrigin

        override suspend fun search(server: String, keyword: String, city: String): List<Place> {
            searches += keyword to city
            beforeSearchReturn?.invoke()
            return choices
        }

        override suspend fun plan(server: String, body: JSONObject): JSONObject {
            planned += body.toString()
            beforePlanReturn?.invoke()
            if (failPlans > 0) {
                failPlans--
                throw IOException("Injected map timeout")
            }
            val tasks = body.getJSONArray("tasks")
            val stops = JSONArray()
            for (index in 0 until tasks.length()) {
                val task = tasks.getJSONObject(index)
                stops.put(JSONObject().put("place", task.optJSONObject("place") ?: supermarket.json())
                    .put("task_ids", JSONArray().put(task.getString("id"))))
            }
            return JSONObject().put("origin", body.getJSONObject("origin"))
                .put("destination", body.getJSONObject("destination"))
                .put("stops", stops).put("total_seconds", 600).put("total_meters", 1000)
                .put("extra_seconds", 60).put("extra_meters", 100)
        }
    }
}
