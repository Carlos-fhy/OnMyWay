package com.onmyway.app.feature.assistant

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.data.local.AssistantRequestJournal
import com.onmyway.app.data.local.PendingAssistantRequest
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.remote.AssistantRequestTransport
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantRecoveryTest {
    private lateinit var app: Application
    private lateinit var db: TaskDatabase
    private lateinit var vm: AppViewModel
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: SharedPreferences
    private lateinit var prefsName: String
    private lateinit var journalName: String
    private var originalServer: String? = null
    private var originalCity: String? = null
    private val chats = mutableListOf<AssistantViewModel>()
    private val transport = FakeTransport()
    private val text = "请带我去测试图书馆"
    private val server = "http://127.0.0.1:18001"

    private data class Call(val server: String, val bodyJson: String)

    private class FakeTransport : AssistantRequestTransport {
        val submissions = CopyOnWriteArrayList<Call>()
        val statusQueries = CopyOnWriteArrayList<Call>()
        var onSubmit: suspend (String, JSONObject) -> JSONObject = { _, _ ->
            throw IOException("Injected interrupted connection")
        }
        var onStatus: suspend (String, JSONObject) -> JSONObject = { _, _ ->
            error("Unexpected status query")
        }

        override suspend fun submit(server: String, body: JSONObject): JSONObject {
            submissions.add(Call(server, body.toString()))
            return onSubmit(server, body)
        }

        override suspend fun status(server: String, body: JSONObject): JSONObject {
            statusQueries.add(Call(server, body.toString()))
            return onStatus(server, body)
        }
    }

    @Before fun setUp() = runBlocking {
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        prefsName = "assistant_recovery_" + UUID.randomUUID()
        journalName = "$prefsName.db"
        prefs = app.getSharedPreferences(prefsName, 0)
        settings = app.getSharedPreferences("settings", 0)
        originalServer = settings.getString("server", null)
        originalCity = settings.getString("city", null)
        check(settings.edit().putString("server", server).putString("city", "Test city").commit())
        db = Room.inMemoryDatabaseBuilder(app, TaskDatabase::class.java).build()
        vm = withContext(Dispatchers.Main) { AppViewModel(app, db) }
        waitUntil { !vm.restoringTrip }
    }

    @After fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) {
            chats.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        db.close()
        app.deleteDatabase(journalName)
        app.deleteSharedPreferences(prefsName)
        val edit = settings.edit()
        if (originalServer == null) edit.remove("server") else edit.putString("server", originalServer)
        if (originalCity == null) edit.remove("city") else edit.putString("city", originalCity)
        check(edit.commit())
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(5_000) { while (!withContext(Dispatchers.Main) { predicate() }) delay(20) }
    }

    private suspend fun openChat(): AssistantViewModel {
        val chat = withContext(Dispatchers.Main) {
            AssistantViewModel(app, prefs, db, requestTransport = transport, requestJournalName = journalName)
        }.also(chats::add)
        waitUntil { chat.ready }
        return chat
    }

    private suspend fun stop(chat: AssistantViewModel) = withContext(Dispatchers.Main) {
        chat.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
    }

    private suspend fun interruptedSend(chat: AssistantViewModel, userText: String = text): PendingAssistantRequest {
        withContext(Dispatchers.Main) { chat.input = userText; chat.send(vm) }
        waitUntil { transport.submissions.size == 1 && !chat.busy && chat.pendingRequest != null }
        return withContext(Dispatchers.Main) { chat.pendingRequest!! }
    }

    private fun routeReply() = JSONObject().put("action", "plan_route")
        .put("destination", "测试图书馆").put("mode", "cycling").put("reply", "请确认路线")

    private fun status(body: JSONObject, state: String, result: JSONObject? = null) = JSONObject()
        .put("request_id", body.getString("request_id")).put("operation", "chat")
        .put("state", state).put("result", result ?: JSONObject.NULL)

    private suspend fun recover(chat: AssistantViewModel) {
        withContext(Dispatchers.Main) { chat.recoverRequest(vm) }
        waitUntil { !chat.busy }
    }

    private suspend fun commitRoomResponseOnly(request: PendingAssistantRequest): AssistantStore {
        val store = AssistantStore(db, prefs)
        assertTrue(store.applyResponse(request.requestId, null,
            ChatLine("user", request.userText, request.userMessageId, request.createdAt),
            ChatLine("assistant", "Cached route response"),
            routeReply().put("_draft_id", request.requestId).put("_plan_id", request.requestId), true))
        // Simulate process death between the Room commit and journal deletion.
        AssistantRequestJournal(app, journalName).use {
            assertTrue(it.saveResult(request.requestId, routeReply().toString()))
            assertNotNull(it.pending())
        }
        return store
    }

    @Test fun reopenedCompletedRequestConsumesCachedResultOnceWithoutResubmitting() = runBlocking {
        val original = openChat()
        val request = interruptedSend(original)
        stop(original)
        transport.onStatus = { _, body -> status(body, "completed", routeReply()) }
        val reopened = openChat()
        assertEquals(request, reopened.pendingRequest)
        assertTrue(transport.statusQueries.isEmpty()) // Init must never replay network work.
        recover(reopened)

        assertNull(reopened.pendingRequest)
        assertEquals(1, transport.submissions.size)
        assertEquals(listOf(Call(server, request.bodyJson)), transport.statusQueries.toList())
        val messages = db.assistant().recentMessages()
        assertEquals(1, messages.count { it.id == request.userMessageId })
        assertEquals(1, messages.count { it.id == "agent-response-${request.requestId}" })
        val draft = db.assistant().draft()!!
        assertEquals("测试图书馆", draft.destination)
        assertEquals(draft.draftId, reopened.proposal?.optString("_draft_id"))
        assertEquals(draft.planId, reopened.proposal?.optString("_plan_id"))
        assertEquals("CONFIRM_ROUTE", db.assistant().clarification()?.kind)

        recover(reopened)
        stop(reopened)
        val third = openChat()
        assertNull(third.pendingRequest)
        assertEquals(draft, db.assistant().draft())
        assertEquals(messages, db.assistant().recentMessages())
        assertEquals(1, transport.submissions.size)
        assertEquals(1, transport.statusQueries.size)
    }

    @Test fun notFoundResubmitsOriginalBodyIncludingTimeAndRequestId() = runBlocking {
        val original = openChat()
        val request = interruptedSend(original)
        val submitted = transport.submissions.single()
        val body = JSONObject(submitted.bodyJson)
        assertEquals(request.requestId, body.getString("request_id"))
        assertEquals(request.requestId, UUID.fromString(request.requestId).toString())
        assertTrue(body.getString("now").isNotBlank())
        stop(original)
        transport.onStatus = { _, pending -> status(pending, "not_found") }
        transport.onSubmit = { _, _ -> routeReply() }
        val reopened = openChat()
        recover(reopened)

        assertNull(reopened.pendingRequest)
        assertEquals(listOf(submitted, submitted), transport.submissions.toList())
        assertEquals(listOf(submitted), transport.statusQueries.toList())
        assertEquals(1, db.assistant().recentMessages().count { it.id == "agent-response-${request.requestId}" })
    }

    @Test fun changedDraftCannotBeReplacedByInterruptedResponse() = runBlocking {
        val original = openChat()
        val request = interruptedSend(original)
        stop(original)
        val reopened = openChat()
        withContext(Dispatchers.Main) {
            reopened.proposal = JSONObject().put("action", "create_task").put("title", "New task")
                .put("place_type", "SUPERMARKET")
        }
        withTimeout(5_000) { while (db.assistant().draft()?.title != "New task") delay(20) }
        val current = db.assistant().draft()!!
        transport.onStatus = { _, body -> status(body, "completed", routeReply()) }
        recover(reopened)

        assertEquals(current, db.assistant().draft())
        assertFalse(db.assistant().hasMessage("agent-response-${request.requestId}"))
        assertEquals(request.requestId, reopened.pendingRequest?.requestId)
        assertEquals(1, transport.submissions.size)
        assertTrue(reopened.recoveryMessage.isNotBlank() || reopened.error.isNotBlank())
    }

    @Test fun changedServerCannotReceiveOriginalRequestOrItsStatusQuery() = runBlocking {
        val original = openChat()
        val request = interruptedSend(original)
        stop(original)
        val reopened = openChat()
        withContext(Dispatchers.Main) { vm.settings("http://127.0.0.1:18002", "Other test city") }
        transport.onStatus = { _, body -> status(body, "completed", routeReply()) }
        recover(reopened)

        assertEquals(request.requestId, reopened.pendingRequest?.requestId)
        assertEquals(listOf(Call(server, request.bodyJson)), transport.submissions.toList())
        assertTrue(transport.statusQueries.isEmpty())
        assertNull(db.assistant().draft())
        assertFalse(db.assistant().hasMessage("agent-response-${request.requestId}"))
        assertTrue(reopened.recoveryMessage.isNotBlank() || reopened.error.isNotBlank())
    }

    @Test fun unfinishedStatesNeverResubmitAndUserCanExplicitlyEditAgain() = runBlocking {
        val original = openChat()
        val request = interruptedSend(original)
        stop(original)
        val reopened = openChat()
        for (state in listOf("running", "unresolved", "failed")) {
            transport.onStatus = { _, body -> status(body, state).put("error_status", 502) }
            recover(reopened)
            assertEquals(request.requestId, reopened.pendingRequest?.requestId)
            assertEquals(1, transport.submissions.size)
            assertFalse(db.assistant().hasMessage("agent-response-${request.requestId}"))
            assertTrue(reopened.recoveryMessage.isNotBlank())
        }
        assertEquals(3, transport.statusQueries.size)

        withContext(Dispatchers.Main) { reopened.editInterruptedRequest() }
        waitUntil { reopened.pendingRequest == null && !reopened.busy }
        assertEquals(text, reopened.input)
        AssistantRequestJournal(app, journalName).use { assertNull(it.pending()) }
        assertEquals(1, transport.submissions.size)
        assertEquals(3, transport.statusQueries.size)
    }

    @Test fun locallySavedResponseIsConsumedBeforeAnyNetworkRecovery() = runBlocking {
        val original = openChat()
        val request = interruptedSend(original)
        stop(original)
        AssistantRequestJournal(app, journalName).use {
            assertTrue(it.saveResult(request.requestId, routeReply().toString()))
        }
        val reopened = openChat()
        assertNotNull(reopened.pendingRequest?.resultJson)
        recover(reopened)

        assertNull(reopened.pendingRequest)
        assertTrue(transport.statusQueries.isEmpty())
        assertEquals(1, transport.submissions.size)
        assertEquals("测试图书馆", db.assistant().draft()?.destination)
        assertEquals(1, db.assistant().recentMessages().count { it.id == "agent-response-${request.requestId}" })
    }

    @Test fun committedReceiptClearsLeftoverJournalWithoutResurrectingConsumedDraft() = runBlocking {
        val original = openChat()
        val request = interruptedSend(original)
        stop(original)
        val store = commitRoomResponseOnly(request)
        store.setDraft(null) // The user has already dealt with this proposal.
        val messages = db.assistant().recentMessages()
        val reopened = openChat()

        assertNull(reopened.pendingRequest)
        assertNull(reopened.proposal)
        assertNull(db.assistant().draft())
        AssistantRequestJournal(app, journalName).use { assertNull(it.pending()) }
        recover(reopened)
        assertNull(db.assistant().draft())
        assertEquals(messages, db.assistant().recentMessages())
        assertEquals(1, messages.count { it.id == "agent-response-${request.requestId}" })
        assertEquals(1, transport.submissions.size)
        assertTrue(transport.statusQueries.isEmpty())
    }

    @Test fun committedReceiptNeverOverwritesNewerDraftDuringInitialization() = runBlocking {
        val original = openChat()
        val request = interruptedSend(original)
        stop(original)
        val store = commitRoomResponseOnly(request)
        store.setDraft(JSONObject().put("action", "create_task").put("title", "Keep the newer task")
            .put("place_type", "SUPERMARKET").put("_draft_id", "new-draft").put("_plan_id", "new-plan"))
        val newer = db.assistant().draft()!!
        val question = store.clarification()
        val messages = db.assistant().recentMessages()
        val reopened = openChat()

        assertNull(reopened.pendingRequest)
        assertEquals("new-draft", reopened.proposal?.optString("_draft_id"))
        assertEquals("Keep the newer task", reopened.proposal?.optString("title"))
        assertEquals(newer, db.assistant().draft())
        assertEquals(question, reopened.clarification)
        AssistantRequestJournal(app, journalName).use { assertNull(it.pending()) }
        recover(reopened)
        assertEquals(newer, db.assistant().draft())
        assertEquals(messages, db.assistant().recentMessages())
        assertEquals(1, transport.submissions.size)
        assertTrue(transport.statusQueries.isEmpty())
    }

    @Test fun cachedTaskListReadsPendingRowsWithoutTaskFlowSubscription() = runBlocking {
        val pending = Errand(title = "Pending parcel for recovery", status = "pending")
        val completed = Errand(title = "Completed shopping for recovery", status = "done",
            completedAt = System.currentTimeMillis())
        db.tasks().save(pending)
        db.tasks().save(completed)
        // No collector subscribes to vm.tasks: its WhileSubscribed initial value stays empty.
        assertTrue(withContext(Dispatchers.Main) { vm.tasks.value.isEmpty() })
        val original = openChat()
        val request = interruptedSend(original, "我有哪些待办")
        stop(original)
        AssistantRequestJournal(app, journalName).use {
            assertTrue(it.saveResult(request.requestId, JSONObject().put("action", "list_tasks").toString()))
        }
        val reopened = openChat()
        recover(reopened)

        assertNull(reopened.pendingRequest)
        val reply = db.assistant().recentMessages().single { it.id == "agent-response-${request.requestId}" }
        assertTrue(reply.text.contains(pending.title))
        assertFalse(reply.text.contains(completed.title))
        assertTrue(withContext(Dispatchers.Main) { vm.tasks.value.isEmpty() })
        assertEquals(pending, db.tasks().get(pending.id))
        assertEquals(completed, db.tasks().get(completed.id))
        assertNull(db.assistant().draft())
        assertEquals(1, transport.submissions.size)
        assertTrue(transport.statusQueries.isEmpty())
    }
}
