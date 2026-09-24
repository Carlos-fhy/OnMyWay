package com.onmyway.app
import org.junit.Assert.*
import org.junit.Test

class TravelModeChoiceTest {
    @Test fun acceptsExplicitModeAnswers() {
        assertEquals("walking", travelModeReply("步行"))
        assertEquals("cycling", travelModeReply("骑车。"))
        assertEquals("cycling", travelModeReply("改成骑行"))
    }
    @Test fun doesNotGuessFromAmbiguousAnswers() {
        assertNull(travelModeReply("不是骑车"))
        assertNull(travelModeReply("走路还是骑车"))
        assertNull(travelModeReply("都可以"))
    }
}
