package com.onmyway.app

import org.junit.Assert.*
import org.junit.Test

class NavigationMemoryTest {
    @Test fun newRequestInvalidatesChoicesButKeepsConversation() {
        val memory = NavigationConversationState()
        memory.record("user", "改去图书馆")
        memory.pendingInstruction = "改去图书馆"
        memory.pendingIds = setOf("parcel")
        memory.places = listOf(Place("library", "图书馆", 120.0, 30.0))
        memory.invalidatePreview()
        assertTrue(memory.places.isEmpty())
        assertNull(memory.pendingIds)
        assertEquals("", memory.pendingInstruction)
        assertEquals("改去图书馆", memory.history.single().second)
    }
    @Test fun boundsHistoryAndExplicitCancelClearsReferences() {
        val memory = NavigationConversationState()
        repeat(20) { memory.record("user", "message $it") }
        assertEquals(12, memory.history.size)
        assertEquals("message 8", memory.history.first().second)
        memory.pendingIds = setOf("parcel")
        memory.pendingInstruction = "取完快递回宿舍"
        memory.clear()
        assertTrue(memory.history.isEmpty())
        assertNull(memory.pendingIds)
        assertEquals("", memory.pendingInstruction)
    }
}
