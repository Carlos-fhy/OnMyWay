package com.onmyway.app.feature.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantMemoryPolicyTest {
    private val now = 10_000L
    private val printing = AssistantMemory(id = "print", text = "我通常去图书馆打印", scope = "printing", createdAt = 1)
    private val reference = AssistantMemoryReference(printing.id, printing.text, printing.revision, printing.kind, printing.scope)

    @Test fun temporaryOrInventedPreferencesAreNotLearned() {
        assertFalse(validateMemoryUpdate(MemoryUpdate("add", text = "今天喜欢少走路", quote = "今天喜欢少走路"), "今天喜欢少走路", emptyList()))
        assertFalse(validateMemoryUpdate(MemoryUpdate("add", text = "以后喜欢坐车", quote = "以后喜欢坐车"), "取快递后回宿舍", emptyList()))
        assertFalse(validateMemoryUpdate(MemoryUpdate("add", text = "喜欢坐车", quote = "以后喜欢步行"), "以后喜欢步行", emptyList()))
        assertFalse(validateMemoryUpdate(MemoryUpdate("add", text = "去打印", quote = "去打印"), "去打印", emptyList()))
        assertFalse(validateMemoryUpdate(MemoryUpdate("add", text = "喜欢步行", quote = "我不喜欢步行"), "我不喜欢步行", emptyList()))
        assertFalse(validateMemoryUpdate(MemoryUpdate("add", text = "喜欢步行", quote = "喜欢步行"), "我不喜欢步行", emptyList()))
        assertFalse(validateMemoryUpdate(MemoryUpdate("add", text = "喜欢步行", quote = "喜欢步行"), "不 喜欢步行", emptyList()))
        assertFalse(validateMemoryUpdate(MemoryUpdate("update", id = "print", text = "喜欢步行", quote = "我不喜欢步行"), "我不喜欢步行", listOf(reference)))
        assertFalse(validateMemoryUpdate(MemoryUpdate("add", text = "默认去图书馆", quote = "默认去图书馆", kind = "fact"), "默认去图书馆", emptyList()))
    }

    @Test fun explicitStableQuoteCanBeLearnedButTokensCannot() {
        val quote = "我通常去图书馆打印"
        assertTrue(validateMemoryUpdate(MemoryUpdate("add", text = quote, quote = quote, scope = "printing"), quote, emptyList()))
        assertTrue(validateMemoryUpdate(MemoryUpdate("add", text = quote, quote = quote, scope = "printing"), "取快递。$quote。", emptyList()))
        val secret = "记住我的密钥 sk-1234567890abcdefghijklmnop"
        assertFalse(validateMemoryUpdate(MemoryUpdate("add", text = secret, quote = secret), secret, emptyList()))
    }

    @Test fun changingOrForgettingRequiresProvidedTargetAndExplicitEvidence() {
        val corrected = "把print这条偏好改为以后默认去北门打印店"
        assertTrue(validateMemoryUpdate(MemoryUpdate("update", id = "print", text = corrected, quote = corrected, scope = "printing"), corrected, listOf(reference)))
        assertFalse(validateMemoryUpdate(MemoryUpdate("update", id = "unknown", text = corrected, quote = corrected), corrected, listOf(reference)))
        val forget = "忘记${printing.text}这条偏好"
        assertTrue(validateMemoryUpdate(MemoryUpdate("forget", id = "print", quote = forget, scope = "printing"), forget, listOf(reference)))
        val keep = "不要忘记${printing.text}"
        assertFalse(validateMemoryUpdate(MemoryUpdate("forget", id = "print", quote = keep, scope = "printing"), keep, listOf(reference)))
    }

    @Test fun negatedForgetAndRefusalNeverBecomeNewMemories() {
        for (prefix in listOf("不要记", "不用记", "无需记", "不必记", "禁止记", "忘掉", "移除", "清除", "删掉", "删了", "别记", "取消记忆")) {
            val source = "$prefix：以后喜欢步行"
            assertFalse(prefix, validateMemoryUpdate(MemoryUpdate("add", text = source, quote = source), source, emptyList()))
        }
        for (prefix in listOf("不用忘记", "无需删除", "不必移除", "禁止清除", "不要忘掉")) {
            val source = "$prefix${printing.text}"
            assertFalse(prefix, validateMemoryUpdate(MemoryUpdate("forget", id = printing.id, quote = source, scope = printing.scope), source, listOf(reference)))
        }
        for (prefix in listOf("忘掉", "移除", "清除", "删掉", "删了", "别记", "不要记", "不用记", "取消记忆")) {
            val source = "$prefix${printing.text}"
            assertTrue(prefix, validateMemoryUpdate(MemoryUpdate("forget", id = printing.id, quote = source, scope = printing.scope), source, listOf(reference)))
        }
        for (prefix in listOf("明天", "这一次", "此次", "今日", "这趟")) {
            val source = "${prefix}喜欢步行"
            assertFalse(prefix, validateMemoryUpdate(MemoryUpdate("add", text = source, quote = source), source, emptyList()))
        }
    }

    @Test fun evenSingleTargetRequiresExplicitReferenceAndKeepsItsScope() {
        val unrelated = "以后午饭清淡"
        assertFalse(validateMemoryUpdate(MemoryUpdate("update", printing.id, unrelated, unrelated, scope = printing.scope), unrelated, listOf(reference)))
        val targeted = "把print这条改为以后默认去北门打印店"
        assertFalse(validateMemoryUpdate(MemoryUpdate("update", printing.id, targeted, targeted, scope = "travel"), targeted, listOf(reference)))
        val other = AssistantMemoryReference("other", "以后默认步行", 1, scope = "travel")
        val ambiguous = "把print和other改为以后默认骑行"
        assertFalse(validateMemoryUpdate(MemoryUpdate("update", printing.id, ambiguous, ambiguous, scope = printing.scope), ambiguous, listOf(reference, other)))
        val idSubstring = "把footprint这条改为以后默认去北门打印店"
        assertFalse(validateMemoryUpdate(MemoryUpdate("update", printing.id, idSubstring, idSubstring, scope = printing.scope), idSubstring, listOf(reference)))
    }

    @Test fun recallUsesRelevantScopeAndExcludesDeletedExpiredOrUnrelatedEntries() {
        val memories = listOf(
            AssistantMemory(id = "food", text = "我喜欢清淡饮食", scope = "food", createdAt = now),
            AssistantMemory(id = "general", text = "默认使用中文", createdAt = 5),
            printing,
            printing.copy(id = "old", expiresAt = now),
            printing.copy(id = "deleted", status = "deleted"),
        )
        assertEquals(listOf("print", "general"), selectMemories(memories, "打印论文后回宿舍", now).map { it.id })
        assertEquals(listOf("general"), selectMemories(memories, "你好", now).map { it.id })
        assertEquals(1, selectMemories(memories, "打印论文", now, limit = 1).size)
    }

    @Test fun deletionGenerationAndTargetRevisionPreventStaleChanges() {
        val snapshot = MemorySnapshot(AssistantMemoryState(revision = 4), listOf(printing))
        assertTrue(memoryUpdateGenerationMatches(snapshot.state, 4))
        assertFalse(memoryUpdateGenerationMatches(snapshot.state.copy(revision = 5), 4))
        assertFalse(memoryUpdateGenerationMatches(snapshot.state.copy(autoRemember = false), 4))
        assertFalse(memoryUpdateGenerationMatches(snapshot.state.copy(useMemory = false), 4))
        assertFalse(memoryTargetMatches(printing.copy(revision = 2), reference))
        assertFalse(memoryTargetMatches(printing.copy(status = "deleted"), reference))
    }
}
