package com.onmyway.app

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AssistantConversationTest {
    @Test fun unfinishedProposalAndConversationSurviveScreenRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefsName = "assistant_conversation_test_" + UUID.randomUUID()
        try {
            instrumentation.runOnMainSync {
                val prefs = app.getSharedPreferences(prefsName, 0)
                val original = AssistantViewModel(app, prefs)
                original.record("先看看顺路事项")
                original.recordRoute("路线已算好", "test-route-id")
                original.proposal = JSONObject().put("action", "plan_route")
                    .put("destination", "图书馆").put("mode", "cycling")

                val reopened = AssistantViewModel(app, prefs)
                assertTrue(reopened.lines.any { it.text == "先看看顺路事项" })
                assertTrue(reopened.lines.first { it.text == "先看看顺路事项" }.createdAt > 0)
                assertEquals("图书馆", reopened.proposal?.optString("destination"))
                assertEquals("cycling", reopened.proposal?.optString("mode"))
                assertEquals("test-route-id", reopened.lines.first { it.routeHistoryId != null }.routeHistoryId)

                reopened.routeEnded()
                val afterTrip = AssistantViewModel(app, prefs)
                assertNull(afterTrip.proposal)
                assertTrue(afterTrip.lines.any { it.text == "先看看顺路事项" })
            }
        } finally {
            app.deleteSharedPreferences(prefsName)
        }
    }

    @Test fun yesterdayRouteIntentIsArchivedButNotExecutable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val prefsName = "assistant_conversation_test_" + UUID.randomUUID()
        try {
            instrumentation.runOnMainSync {
                val prefs = app.getSharedPreferences(prefsName, 0)
                val original = AssistantViewModel(app, prefs)
                original.record("去图书馆")
                original.proposal = JSONObject().put("action", "plan_route")
                    .put("destination", "图书馆").put("mode", "walking")
                prefs.edit().putLong("proposal_saved_at", System.currentTimeMillis() - 31 * 60_000L).commit()
                val reopened = AssistantViewModel(app, prefs)
                assertNull(reopened.proposal)
                assertTrue(reopened.lines.any { it.text == "去图书馆" })
            }
        } finally {
            app.deleteSharedPreferences(prefsName)
        }
    }
}
