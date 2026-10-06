package com.onmyway.app

import android.app.Application
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanEditTest {
    @Test fun confirmationCommitsExactPreviewAndRejectsReplay() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        try {
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            val place = Place("preview", "Test destination", 121.0, 31.0)
            val request = JSONObject().put("origin", place.json()).put("destination", place.json())
                .put("mode", "walking").put("preference", "fastest").put("tasks", JSONArray())
                .put("max_extra_minutes", 10).put("arrive_by", JSONObject.NULL).toString()
            val route = JSONObject().put("stops", JSONArray()).put("extra_seconds", 0).toString()
            val draft = AssistantDraft(action = "plan_route", draftId = "current-draft", planId = "test-plan",
                destination = place.name, mode = "walking", savedAt = System.currentTimeMillis())
            val planning = PlanningDraft(draftId = draft.draftId, planId = draft.planId, destination = place.name,
                mode = "walking", budget = 10, placeId = place.id, placeName = place.name,
                longitude = place.longitude, latitude = place.latitude, address = place.address, savedAt = draft.savedAt)
            val question = AssistantClarification(draftId = draft.draftId, planId = draft.planId,
                kind = "CONFIRM_ROUTE", prompt = "Confirm this route", destination = place.name,
                mode = "walking", savedAt = draft.savedAt)
            db.assistant().saveDraft(draft)
            db.assistant().savePlanningDraft(planning)
            db.assistant().savePlanningTasks(listOf(PlanningDraftTask("not-selected", false)))
            db.assistant().saveClarification(question)
            db.assistant().savePlaceChoices(listOf(place.toClarificationChoice(draft.draftId, 0)))
            val snapshot = ConfirmedRouteSnapshot("preview-version", request, route, null, emptyList(),
                System.currentTimeMillis(), planId = draft.planId, draftId = draft.draftId)
            // Neither a superseded draft nor a different plan may consume the current proposal.
            for (stale in listOf(snapshot.copy(draftId = "old-draft"), snapshot.copy(planId = "another-plan"))) {
                withContext(Dispatchers.Main) {
                    try { vm.confirmAssistantRoute(stale); fail("Stale identity must fail") }
                    catch (_: IllegalStateException) { }
                }
                assertNull(db.trips().load())
                assertTrue(db.trips().history().isEmpty())
                assertEquals(draft, db.assistant().draft())
                assertEquals(planning, db.assistant().planningDraft())
                assertEquals(question, db.assistant().clarification())
            }
            // Simulate a failure after the route/history inserts: the whole confirmation must roll back.
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_draft_consume BEFORE DELETE ON assistant_draft BEGIN SELECT RAISE(ABORT, 'simulated consume failure'); END")
            withContext(Dispatchers.Main) {
                try { vm.confirmAssistantRoute(snapshot); fail("A failed consume must roll back confirmation") }
                catch (_: android.database.SQLException) { }
                assertNull(vm.result)
            }
            assertNull(db.trips().load())
            assertTrue(db.trips().history().isEmpty())
            assertEquals(draft, db.assistant().draft())
            assertEquals(planning, db.assistant().planningDraft())
            assertEquals(1, db.assistant().planningTasks().size)
            assertEquals(question, db.assistant().clarification())
            assertEquals(1, db.assistant().placeChoices().size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_draft_consume")
            withContext(Dispatchers.Main) { vm.confirmAssistantRoute(snapshot) }
            assertNull(db.assistant().draft())
            assertNull(db.assistant().planningDraft())
            assertTrue(db.assistant().planningTasks().isEmpty())
            assertNull(db.assistant().clarification())
            assertTrue(db.assistant().placeChoices().isEmpty())
            assertEquals(draft.planId, db.trips().load()!!.planId)
            assertEquals(draft.planId, db.trips().history().single().planId)
            assertEquals(route, db.trips().load()!!.resultJson)
            assertEquals(request, db.trips().load()!!.requestJson)
            assertEquals("preview-version", db.trips().history().single().id)
            withContext(Dispatchers.Main) {
                try { vm.confirmAssistantRoute(snapshot); fail("Repeated confirmation must fail") }
                catch (_: IllegalStateException) { }
            }
            assertEquals(1, db.trips().history().size)
            val previous = db.trips().load()!!
            val revisedDraft = draft.copy(draftId = "revised-draft")
            db.assistant().saveDraft(revisedDraft)
            val replacement = snapshot.copy(versionId = "next-version", previous = previous, draftId = revisedDraft.draftId)
            withContext(Dispatchers.Main) { vm.confirmAssistantRoute(replacement) }
            assertNull(db.assistant().draft())
            assertEquals(previous.planId, db.trips().load()!!.planId)
            assertEquals(2, db.trips().historyForPlan(previous.planId).size)
            assertEquals(previous.sessionId, db.trips().load()!!.sessionId)
            assertEquals(2, db.trips().history().size)
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }

    @Test fun liveNavigationSurvivesViewModelRecreationAndEndsAfterCommit() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        val place = Place("live-end", "测试终点", 121.0, 31.0)
        val request = JSONObject().put("origin", place.json()).put("destination", place.json())
            .put("mode", "walking").put("preference", "fastest").put("allow_partial", false)
            .put("max_extra_minutes", 10).put("arrive_by", JSONObject.NULL).put("tasks", JSONArray())
        val stored = SavedTrip(requestJson = request.toString(), resultJson = JSONObject().put("stops", JSONArray()).toString(), started = true)
        try {
            db.trips().save(stored)
            withContext(Dispatchers.Main) {
                val original = NavigationRuntime.obtain(app, stored.sessionId, "walking", place, emptyList())
                original.detach()
                assertSame(original, NavigationRuntime.obtain(app, stored.sessionId, "walking", place, emptyList()))
            }
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            assertEquals(stored, db.trips().load())
            assertTrue(db.trips().history().isEmpty())
            var ended = false
            withContext(Dispatchers.Main) {
                assertTrue(vm.tripStarted)
                assertTrue(vm.finishTrip(false) { ended = true })
                assertTrue(NavigationRuntime.owns(stored.sessionId))
            }
            withTimeout(5000) { while (!withContext(Dispatchers.Main) { ended }) delay(20) }
            assertNull(db.trips().load())
            withContext(Dispatchers.Main) { assertFalse(NavigationRuntime.owns(stored.sessionId)) }
            assertEquals("CANCELLED", db.trips().history().single().state)
        } finally {
            withContext(Dispatchers.Main) {
                model?.viewModelScope?.cancel()
                NavigationRuntime.finish(stored.sessionId)
            }
            db.close()
        }
    }

    @Test fun interruptedNavigationIsArchivedInsteadOfRestored() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        try {
            val place = Place("end", "宿舍", 121.0, 31.0)
            val request = JSONObject().put("origin", place.json()).put("destination", place.json())
                .put("mode", "cycling").put("preference", "fastest").put("allow_partial", false)
                .put("max_extra_minutes", 10).put("arrive_by", JSONObject.NULL).put("tasks", JSONArray())
            db.trips().save(SavedTrip(requestJson = request.toString(), resultJson = "{\"stops\":[]}", started = true))
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            assertNull(db.trips().load())
            val archived = db.trips().history().single()
            assertEquals("CANCELLED", archived.state)
            assertEquals("宿舍", Place.from(JSONObject(archived.requestJson).getJSONObject("destination")).name)
            withContext(Dispatchers.Main) {
                assertNull(vm.result)
                assertFalse(vm.tripStarted)
                assertEquals(NavigationState.CANCELLED, vm.navigationState)
            }
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }

    @Test fun cancelAndInvalidReplanKeepPersistedTrip() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        try {
            val place = Place("test", "测试终点", 121.0, 31.0)
            val request = JSONObject().put("origin", place.json()).put("destination", place.json())
                .put("mode", "walking").put("preference", "fastest").put("allow_partial", false)
                .put("max_extra_minutes", 20).put("arrive_by", JSONObject.NULL)
                .put("tasks", JSONArray().put(JSONObject().put("id", "a")))
            val stored = SavedTrip(requestJson = request.toString(), resultJson = "{\"stops\":[]}", started = false)
            db.trips().save(stored)
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            withContext(Dispatchers.Main) {
                val task = Errand(id = "a", title = "买东西")
                val replacement = place.copy(id = "replacement", name = "另一家门店")
                vm.chooseTripPlace(task.id, replacement)
                assertEquals(replacement, vm.taskForTrip(task).place())
                assertNull(task.place()) // A trip override must not change the task default.
                assertNotNull(vm.result)
                vm.cancelPlanEdit()
                assertTrue(vm.tripPlaces.isEmpty())
                vm.chooseTripPlace(task.id, replacement)
                vm.resetTripPlace(task.id)
                assertNull(vm.taskForTrip(task).place())
                vm.cancelPlanEdit()
                vm.beginPlanEdit()
                vm.destination = place.copy(name = "新终点")
                vm.mode = "cycling"; vm.extra = "50"; vm.partial = true; vm.selected = emptySet()
                vm.inputsChanged()
                vm.arriveBy = "invalid"
                var assistantReceivedResult = false
                vm.planForAssistant { assistantReceivedResult = true }
                assertFalse(assistantReceivedResult) // A rejected plan cannot produce a success card.
                assertNotNull(vm.result)
                assertFalse(vm.tripStarted)
                assertTrue(vm.editingPlan)
                vm.cancelPlanEdit()
                assertEquals(place, vm.destination)
                assertEquals("walking", vm.mode)
                assertEquals("20", vm.extra)
                assertEquals(setOf("a"), vm.selected)
                assertFalse(vm.partial)
                assertFalse(vm.editingPlan)
            }
            assertEquals(stored, db.trips().load())
            withContext(Dispatchers.Main) { vm.launchTrip { true } }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.updatingTrip }) delay(20) }
            var callbackObservedCommittedState = false
            withContext(Dispatchers.Main) {
                assertEquals(NavigationState.NAVIGATION_OPENED, vm.navigationState)
                assertTrue(vm.finishTrip(false) {
                    assertNull(vm.result)
                    callbackObservedCommittedState = true
                })
                assertFalse(callbackObservedCommittedState)
            }
            withTimeout(5000) { while (!withContext(Dispatchers.Main) { callbackObservedCommittedState }) delay(20) }
            assertNull(db.trips().load())
            assertEquals("CANCELLED", db.trips().history().first().state)
            withContext(Dispatchers.Main) {
                assertEquals(NavigationState.CANCELLED, vm.navigationState)
                assertNull(vm.result)
                assertNull(vm.destination)
                assertTrue(vm.selected.isEmpty())
            }
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }
    @Test fun taskChangesKeepTheRouteButRequireReplanning() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        try {
            val place = Place("test", "测试终点", 121.0, 31.0)
            val plannedTask = Errand(id = "planned-task", title = "买水")
            db.tasks().save(plannedTask)
            val request = JSONObject().put("origin", place.json()).put("destination", place.json())
                .put("mode", "walking").put("preference", "fastest").put("allow_partial", false)
                .put("max_extra_minutes", 20).put("arrive_by", JSONObject.NULL)
                .put("tasks", JSONArray().put(plannedTask.json()))
            db.trips().save(SavedTrip(requestJson = request.toString(), resultJson = JSONObject().put("stops", JSONArray()).toString()))
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            val newTask = Errand(id = "new-task", title = "买水")
            withContext(Dispatchers.Main) {
                vm.navigationConversation.record("user", "继续去宿舍")
                vm.save(newTask)
            }
            withTimeout(5000) { while (db.tasks().get(newTask.id) == null) delay(20) }
            withContext(Dispatchers.Main) {
                assertNotNull(vm.result)
                assertFalse(vm.planNeedsRefresh)
                vm.save(plannedTask.copy(title = "买饮料"))
            }
            withTimeout(5000) { while (db.tasks().get(plannedTask.id)?.title != "买饮料" || db.trips().load()?.needsReplan != true || !withContext(Dispatchers.Main) { vm.planNeedsRefresh }) delay(20) }
            withContext(Dispatchers.Main) {
                assertNotNull(vm.result)
                assertTrue(vm.planNeedsRefresh)
                assertEquals("继续去宿舍", vm.navigationConversation.history.last().second)
                var launched = false
                vm.launchTrip { launched = true; true }
                assertFalse(launched)
                assertFalse(vm.tripStarted)
                vm.settings(vm.server, vm.city)
                assertNotNull(vm.result)
                vm.delete(plannedTask)
            }
            withTimeout(5000) { while (db.tasks().get(plannedTask.id) != null) delay(20) }
            assertNotNull(db.tasks().get(newTask.id))
            assertNotNull(db.trips().load())
            assertTrue(db.trips().load()!!.needsReplan)
            withContext(Dispatchers.Main) { assertNotNull(vm.result) }
            withContext(Dispatchers.Main) { vm.viewModelScope.cancel() }
            val restored = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { restored.restoringTrip }) delay(20) }
            withContext(Dispatchers.Main) {
                assertNotNull(restored.result)
                assertTrue(restored.planNeedsRefresh)
            }
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }

    @Test fun failedTaskWriteDoesNotEraseCurrentRoute() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        var model: AppViewModel? = null
        try {
            val place = Place("end", "终点", 121.0, 31.0)
            val request = JSONObject().put("origin", place.json()).put("destination", place.json())
                .put("mode", "walking").put("preference", "fastest").put("allow_partial", false)
                .put("max_extra_minutes", 10).put("arrive_by", JSONObject.NULL).put("tasks", JSONArray())
            db.trips().save(SavedTrip(requestJson = request.toString(),
                resultJson = JSONObject().put("stops", JSONArray()).toString()))
            val vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }.also { model = it }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_task_insert BEFORE INSERT ON tasks BEGIN SELECT RAISE(FAIL, 'simulated write failure'); END")
            withContext(Dispatchers.Main) { vm.save(Errand(title = "会失败的写入")) }
            withTimeout(5000) { while (withContext(Dispatchers.Main) { "任务保存失败" !in vm.message }) delay(20) }
            withContext(Dispatchers.Main) {
                assertNotNull(vm.result)
                assertFalse(vm.planNeedsRefresh)
                assertEquals("终点", vm.destination?.name)
            }
            assertNotNull(db.trips().load())
        } finally {
            withContext(Dispatchers.Main) { model?.viewModelScope?.cancel() }
            db.close()
        }
    }

}
