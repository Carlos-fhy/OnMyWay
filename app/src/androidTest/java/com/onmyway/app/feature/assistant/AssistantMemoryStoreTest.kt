package com.onmyway.app.feature.assistant

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onmyway.app.data.local.TaskDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class AssistantMemoryStoreTest {
    private lateinit var database: TaskDatabase
    private lateinit var store: AssistantStore
    private val preferencesName = "memory-${UUID.randomUUID()}"

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().context
        database = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java).build()
        store = AssistantStore(database, context.getSharedPreferences(preferencesName, 0))
    }

    @After fun close() {
        database.close()
        InstrumentationRegistry.getInstrumentation().context.deleteSharedPreferences(preferencesName)
    }

    @Test fun deletionErasesEvidenceAndRejectsDelayedResponseButAllowsManualReaddition() = runBlocking {
        val quote = "我通常去图书馆打印"
        assertTrue(store.addManualMemory(quote, "printing"))
        val before = store.memorySnapshot()
        val item = before.items.single()
        assertTrue(store.forget(item.id))
        assertEquals(0, store.applyMemoryUpdates(listOf(MemoryUpdate("add", text = quote, quote = quote)),
            before.state.revision, "message-old", quote, before.items.map { AssistantMemoryReference(it.id, it.text, it.revision, it.kind, it.scope) }))
        assertTrue(store.memories().isEmpty())
        val erased = database.assistant().memoryById(item.id)!!
        assertEquals("deleted", erased.status)
        assertFalse(erased.text.contains("打印"))
        assertTrue(erased.sourceQuote.isEmpty())
        assertTrue(erased.sourceMessageId.isEmpty())
        assertTrue(store.addManualMemory(quote, "printing"))
        assertNotEquals(item.id, store.memories().single().id)
    }

    @Test fun settingsAndTargetChangesRejectOlderUpdates() = runBlocking {
        store.addManualMemory("我喜欢步行", "travel")
        val initial = store.memorySnapshot()
        val item = initial.items.single()
        store.updateMemorySettings(true, false)
        val text = "把${item.text}改为以后默认骑行"
        assertEquals(0, store.applyMemoryUpdates(listOf(MemoryUpdate("update", item.id, text, text, scope = "travel")),
            initial.state.revision, "source", text, listOf(AssistantMemoryReference(item.id, item.text, item.revision, item.kind, item.scope))))
        assertEquals(item.text, store.memories().single().text)
        assertTrue(store.editMemory(item.id, item.revision, text, "travel"))
        assertFalse(store.editMemory(item.id, item.revision, "以后默认坐车", "travel"))
    }

    @Test fun acceptedQuoteIsAppliedOnceAndUnrelatedTasksAreUnchanged() = runBlocking {
        val source = "我通常去图书馆打印"
        val snapshot = store.memorySnapshot()
        val update = MemoryUpdate("add", text = source, quote = source, scope = "printing")
        assertEquals(1, store.applyMemoryUpdates(listOf(update), snapshot.state.revision, "user-1", source, emptyList()))
        assertEquals(0, store.applyMemoryUpdates(listOf(update), snapshot.state.revision, "user-1", source, emptyList()))
        val stored = store.memories().single()
        assertEquals("user-1", stored.sourceMessageId)
        assertEquals(source, stored.sourceQuote)
        assertEquals("conversation", stored.sourceType)
        assertTrue(database.tasks().all().isEmpty())
    }

    @Test fun fullCapacityDoesNotSilentlyEvictOlderPreferences() = runBlocking {
        repeat(40) { assertTrue(store.addManualMemory("偏好 $it")) }
        val before = store.memorySnapshot()
        assertFalse(store.addManualMemory("第 41 条"))
        val after = store.memorySnapshot()
        assertEquals(before, after)
        assertTrue(after.items.any { it.text == "偏好 0" })
    }

    @Test fun automaticMutationRechecksTargetRevisionInsideTransaction() = runBlocking {
        store.addManualMemory("我通常去图书馆打印", "printing")
        val before = store.memorySnapshot()
        val item = before.items.single()
        store.editMemory(item.id, item.revision, "以后默认去北门打印店", "printing")
        val current = store.memorySnapshot()
        val text = "把${item.text}改为以后默认去南门打印店"
        assertEquals(0, store.applyMemoryUpdates(listOf(MemoryUpdate("update", item.id, text, text, scope = "printing")),
            current.state.revision, "new-message", text, listOf(AssistantMemoryReference(item.id, item.text, item.revision, item.kind, item.scope))))
        assertEquals("以后默认去北门打印店", store.memories().single().text)
    }

    @Test fun frozenWireTargetsKeepScopeAndForgetCannotBeReaddedInTheSameReply() = runBlocking {
        store.addManualMemory("我通常去图书馆打印", "printing")
        val snapshot = store.memorySnapshot()
        val item = snapshot.items.single()
        val source = "忘掉${item.text}"
        val body = memoryRequestFields(snapshot, source)
        val response = JSONObject().put("memory_updates", JSONArray()
            .put(JSONObject().put("op", "forget").put("id", item.id).put("quote", source)
                .put("kind", item.kind).put("scope", item.scope))
            .put(JSONObject().put("op", "add").put("text", source).put("quote", source)
                .put("kind", item.kind).put("scope", item.scope)))
        val batch = checkNotNull(memoryResponseBatch(body, response))
        assertEquals("printing", batch.references.single().scope)
        assertEquals(1, store.applyMemoryUpdates(batch.updates, batch.revision, "forget-source", source, batch.references))
        assertTrue(store.memories().isEmpty())
        assertEquals(0, store.applyMemoryUpdates(batch.updates, batch.revision, "forget-source", source, batch.references))
    }
}
