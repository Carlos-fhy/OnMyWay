package com.onmyway.app.feature.assistant

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.feature.navigation.NavigationRequestDelivery
import com.onmyway.app.feature.planning.ConfirmedRouteSnapshot
import com.onmyway.app.feature.planning.PlanningDraft
import com.onmyway.app.feature.planning.PlanningDraftTask
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

/** Exercises the final Room write without location, transport, or the user's database. */
@RunWith(AndroidJUnit4::class)
class CardRouteCommitTest {
    private lateinit var base: Application
    private lateinit var app: Application
    private lateinit var prefix: String
    private lateinit var db: TaskDatabase
    private lateinit var vm: AppViewModel
    private lateinit var originalTrip: SavedTrip
    private lateinit var parcel: Errand
    private lateinit var print: Errand
    private val preferenceNames = mutableSetOf<String>()
    private val origin = Place("campus-gate", "校门", 120.0, 31.0)
    private val dorm = Place("dorm", "宿舍", 120.01, 31.01)

    @Before fun setUp() = runBlocking {
        base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        prefix = "card-route-commit-${UUID.randomUUID()}"
        app = object : Application() {
            init { attachBaseContext(base) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = "$prefix-$name"
                preferenceNames += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        val now = System.currentTimeMillis()
        parcel = Errand(id = "parcel", title = "取快递", status = "done", completedAt = now)
        print = Errand(id = "print", title = "打印论文", placeType = "PRINT_SHOP",
            plannedDate = "2026-10-19", deadline = "2026-10-19T17:00:00+08:00",
            reminderAt = now + 3_600_000, priority = 3)
        db.tasks().save(parcel)
        db.tasks().save(print)
        originalTrip = SavedTrip(requestJson = request().toString(), resultJson = route(600).toString(),
            sessionId = "dorm-session", planId = "dorm-plan", plannedAt = now)
        db.trips().save(originalTrip)
        vm = withContext(Dispatchers.Main) {
            AppViewModel(app, db,
                navigationDelivery = NavigationRequestDelivery(app, "$prefix-navigation"),
                reminderScheduler = {})
        }
        withTimeout(5_000) { while (withContext(Dispatchers.Main) { vm.restoringTrip }) delay(20) }
        withContext(Dispatchers.Main) { assertEquals(originalTrip, vm.planningBase()) }
    }

    @After fun tearDown() = runBlocking {
        if (::vm.isInitialized) withContext(Dispatchers.Main) {
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        if (::db.isInitialized) db.close()
        if (::prefix.isInitialized) base.deleteDatabase("$prefix-navigation")
        preferenceNames.forEach(base::deleteSharedPreferences)
    }

    private fun request() = JSONObject().put("origin", origin.json()).put("destination", dorm.json())
        .put("mode", "walking").put("preference", "fastest").put("tasks", JSONArray())
        .put("allow_partial", false).put("max_extra_minutes", 10).put("arrive_by", JSONObject.NULL)

    private fun route(seconds: Int) = JSONObject().put("stops", JSONArray())
        .put("extra_seconds", 0).put("total_seconds", seconds).put("total_meters", 800)

    private fun draft() = AssistantDraft(action = "plan_route", draftId = "route-draft", planId = originalTrip.planId,
        destination = dorm.name, mode = "walking", savedAt = System.currentTimeMillis(),
        expiresAt = System.currentTimeMillis() + 30 * 60_000)

    private fun snapshot(draft: AssistantDraft) = ConfirmedRouteSnapshot(
        versionId = "new-preview", requestJson = request().toString(), routeJson = route(540).toString(),
        previous = originalTrip, tasks = emptyList(), createdAt = System.currentTimeMillis(),
        planId = draft.planId, draftId = draft.draftId)

    private suspend fun seed(draft: AssistantDraft) {
        db.assistant().saveDraft(draft)
        db.assistant().savePlanningDraft(PlanningDraft(draftId = draft.draftId, planId = draft.planId,
            destination = dorm.name, mode = "walking", budget = 10, placeId = dorm.id,
            placeName = dorm.name, longitude = dorm.longitude, latitude = dorm.latitude,
            address = dorm.address, savedAt = draft.savedAt))
        db.assistant().savePlanningTasks(listOf(PlanningDraftTask(print.id, false)))
        db.assistant().saveClarification(AssistantClarification(draftId = draft.draftId, planId = draft.planId,
            kind = "CONFIRM_ROUTE", prompt = "请确认回宿舍路线", destination = dorm.name,
            mode = "walking", savedAt = draft.savedAt))
        db.assistant().savePlaceChoices(listOf(dorm.toClarificationChoice(draft.draftId, 0)))
    }

    private suspend fun assertRejectedAndOriginalStatePreserved(draft: AssistantDraft) {
        seed(draft)
        val planning = db.assistant().planningDraft()
        val planningTasks = db.assistant().planningTasks()
        val question = db.assistant().clarification()
        val places = db.assistant().placeChoices()
        val result = withContext(Dispatchers.Main) { runCatching { vm.confirmAssistantRoute(snapshot(draft)) } }
        assertTrue("The final transaction must reject an unavailable route card", result.exceptionOrNull() is IllegalStateException)
        assertEquals(originalTrip, db.trips().load())
        assertTrue(db.trips().history().isEmpty())
        assertEquals(draft, db.assistant().draft())
        assertEquals(planning, db.assistant().planningDraft())
        assertEquals(planningTasks, db.assistant().planningTasks())
        assertEquals(question, db.assistant().clarification())
        assertEquals(places, db.assistant().placeChoices())
        assertEquals(parcel, db.tasks().get(parcel.id))
        assertEquals(print, db.tasks().get(print.id))
        withContext(Dispatchers.Main) {
            assertEquals(originalTrip, vm.planningBase())
            assertEquals(originalTrip.resultJson, vm.result!!.toString())
            assertEquals(dorm, vm.destination)
            assertFalse(vm.updatingTrip)
            assertNull(vm.lastPlannedHistoryId)
        }
    }

    @Test fun expiredPersistedIntentCannotCommitEvenWithFreshPreviewAndMatchingIds() = runBlocking {
        assertRejectedAndOriginalStatePreserved(draft().copy(expiresAt = System.currentTimeMillis() - 1))
    }

    @Test fun taskCardCannotMasqueradeAsRouteWithMatchingDraftAndPlanIds() = runBlocking {
        assertRejectedAndOriginalStatePreserved(draft().copy(action = "create_task", title = "打印论文",
            placeType = "PRINT_SHOP"))
    }

    @Test fun supportedCurrentRouteCommitsExactPreviewAndKeepsTasksAndDormSession() = runBlocking {
        val current = draft()
        seed(current)
        val preview = snapshot(current)
        withContext(Dispatchers.Main) { vm.confirmAssistantRoute(preview) }
        val stored = db.trips().load()!!
        assertEquals(preview.requestJson, stored.requestJson)
        assertEquals(preview.routeJson, stored.resultJson)
        assertEquals(originalTrip.sessionId, stored.sessionId)
        assertEquals(originalTrip.planId, stored.planId)
        assertEquals(dorm, Place.from(JSONObject(stored.requestJson).getJSONObject("destination")))
        assertEquals(preview.versionId, db.trips().history().single().id)
        assertNull(db.assistant().draft())
        assertNull(db.assistant().planningDraft())
        assertTrue(db.assistant().planningTasks().isEmpty())
        assertNull(db.assistant().clarification())
        assertTrue(db.assistant().placeChoices().isEmpty())
        assertEquals(parcel, db.tasks().get(parcel.id))
        assertEquals(print, db.tasks().get(print.id))
        withContext(Dispatchers.Main) {
            assertEquals(stored, vm.planningBase())
            assertEquals(preview.routeJson, vm.result!!.toString())
            assertEquals(dorm, vm.destination)
        }
    }
}
