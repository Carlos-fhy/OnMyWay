package com.onmyway.app

import org.junit.Assert.*
import org.junit.Test

class AssistantContextTest {
    private fun line(text: String, time: Long, history: String? = null) =
        ChatLine("user", text, id = time.toString(), createdAt = time, routeHistoryId = history)

    @Test fun excludesPreviousSessionButKeepsShortFollowUpsAcrossMidnight() {
        val lines = listOf(line("old", 1), line("go", 2_000_000), line("cycling", 2_060_000))
        assertEquals(listOf("go", "cycling"), assistantContextLines(lines).map { it.text })
    }

    @Test fun newestInstructionSurvivesCharacterBudget() {
        val lines = (1L..20L).map { line("x".repeat(2_000), it) } + line("latest", 21)
        val selected = assistantContextLines(lines)
        assertEquals("latest", selected.last().text)
        assertTrue(selected.sumOf { it.text.length } <= 6_000)
        assertTrue(selected.size <= 12)
    }

    @Test fun archivedRouteDoesNotBecomeCurrentInstruction() {
        val lines = listOf(line("old route", 1, "route-id"), line("new destination", 2))
        assertEquals(listOf("new destination"), assistantContextLines(lines).map { it.text })
        assertEquals(2, lines.size)
    }

    @Test fun shortMessagesAlsoHaveTurnLimit() {
        assertEquals(12, assistantContextLines((1L..30L).map { line("yes", it) }).size)
    }
    @Test fun routeEditsOnlyShowLatestEntryForSameTrip() {
        val first = line("first", 1, "r1").copy(routeSessionId = "trip")
        val other = line("other trip", 2, "r2").copy(routeSessionId = "other")
        val text = line("change route", 3)
        val latest = line("updated", 4, "r3").copy(routeSessionId = "trip")
        assertEquals(listOf(other, text, latest), latestRouteEntries(listOf(first, other, text, latest)))
    }
    @Test fun unknownTripLinksAreNotIncorrectlyMerged() {
        val lines = listOf(line("a", 1, "a"), line("b", 2, "b"))
        assertEquals(lines, latestRouteEntries(lines))
    }
    @Test fun abandonedAttemptsAcrossSessionIdsOnlyKeepFinalEntry() {
        val first = line("first", 1, "a").copy(routeSessionId = "s1", routeAbandoned = true)
        val second = line("second", 2, "b").copy(routeSessionId = "s2", routeAbandoned = true)
        val latest = line("final", 3, "c").copy(routeSessionId = "s3", routeAbandoned = true)
        assertEquals(listOf(latest), latestRouteEntries(listOf(first, second, latest)))
    }
    @Test fun completedTripsSurviveLaterReplanning() {
        val completed = line("completed", 1, "a").copy(routeSessionId = "s1")
        val abandoned = line("abandoned", 2, "b").copy(routeSessionId = "s2", routeAbandoned = true)
        val latest = line("final", 3, "c").copy(routeSessionId = "s3")
        assertEquals(listOf(completed, latest), latestRouteEntries(listOf(completed, abandoned, latest)))
    }
    @Test fun revisionsAcrossDifferentSessionsGroupByPlanIdentity() {
        val first = line("first", 1, "r1").copy(routeSessionId = "s1", routePlanId = "plan")
        val instruction = line("change destination", 2)
        val revised = line("revised", 3, "r2").copy(routeSessionId = "s2", routePlanId = "plan")
        assertEquals(listOf(instruction, revised), latestRouteEntries(listOf(first, instruction, revised)))
    }

    @Test fun independentCancelledPlansAreNotHiddenByAnotherPlan() {
        val first = line("cancelled trip", 1, "r1").copy(routeSessionId = "s", routePlanId = "first", routeAbandoned = true)
        val other = line("other trip", 2, "r2").copy(routeSessionId = "s", routePlanId = "second", routeAbandoned = true)
        assertEquals(listOf(first, other), latestRouteEntries(listOf(first, other)))
    }

}
