package com.onmyway.app.feature.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.onmyway.app.core.model.Place
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantFamiliarPlaceFillTest {
    private val origin = Place("origin", "当前位置", 120.0, 31.0)
    private val pickup = Place("pickup", "菜鸟驿站", 120.001, 31.0)
    private val print = Place("print", "文印中心", 120.002, 31.0)

    private fun proposal(vararg items: JSONObject) = JSONObject()
        .put("action", "create_tasks")
        .put("_draft_id", "draft")
        .put("_plan_id", "plan")
        .put("_card_version", 1)
        .put("tasks", JSONArray(items.toList()))

    private fun item(id: String, title: String, type: String, place: Place? = null) = JSONObject()
        .put("item_id", id).put("version", 1).put("title", title).put("place_type", type)
        .put("deadline", "").put("planned_date", "2026-10-19").put("priority", 2)
        .put("service_minutes", 5)
        .put("place", place?.json() ?: JSONObject.NULL)

    @Test fun fillsOnlyMissingOutdoorItemsWithSameTypeCandidates() {
        val draft = proposal(
            item("parcel", "取快递", "PARCEL"),
            item("print", "打印论文", "PRINT_SHOP"),
            item("indoor", "写作业", "INDOOR"),
        )
        val requests = placeFillRequests(draft)
        // PRINT_SHOP 同样是可自动填入的室外类型，只要缺地点就会进入请求；
        // 这里不提供打印类常用地点，验证没有候选的条目保持缺失。
        assertEquals(listOf("parcel", "print"), requests.map { it.itemId })

        val summary = applyFamiliarPlaceFills(
            draft, requests, origin,
            mapOf("快递站" to pickup),
            emptyMap(),
        )

        assertEquals(1, summary.filledCount)
        assertEquals(1, summary.missingCount)
        assertEquals("pickup", draft.getJSONArray("tasks").getJSONObject(0)
            .getJSONObject("place").getString("id"))
        assertTrue(draft.getJSONArray("tasks").getJSONObject(1).isNull("place"))
        assertTrue(draft.getJSONArray("tasks").getJSONObject(2).isNull("place"))
    }

    @Test fun existingPlaceIsNeverOverwritten() {
        val existing = Place("existing", "用户选的点", 120.0005, 31.0)
        val draft = proposal(item("parcel", "取快递", "PARCEL", existing))
        assertEquals(emptyList<PlaceFillRequest>(), placeFillRequests(draft))

        val summary = applyFamiliarPlaceFills(
            draft, placeFillRequests(draft), origin,
            mapOf("快递站" to pickup), emptyMap(),
        )

        assertEquals(0, summary.filledCount)
        assertEquals("existing", draft.getJSONArray("tasks").getJSONObject(0)
            .getJSONObject("place").getString("id"))
    }

    @Test fun placesBeyondThreeKilometersStayMissing() {
        val far = Place("far", "远处理发店", 120.0, 31.05)
        val draft = proposal(item("parcel", "取快递", "PARCEL"))

        val summary = applyFamiliarPlaceFills(
            draft, placeFillRequests(draft), origin,
            mapOf("快递站" to far), emptyMap(),
        )

        assertEquals(0, summary.filledCount)
        assertEquals(1, summary.missingCount)
        assertTrue(summary.outOfRange)
        assertTrue(draft.getJSONArray("tasks").getJSONObject(0).isNull("place"))
    }

    @Test fun missingOriginLeavesItemsMissingAndSaysSo() {
        val draft = proposal(item("parcel", "取快递", "PARCEL"))

        val summary = applyFamiliarPlaceFills(
            draft, placeFillRequests(draft), null,
            mapOf("快递站" to pickup), emptyMap(),
        )

        assertEquals(0, summary.filledCount)
        assertTrue(summary.locationUnavailable)
        assertTrue(placeFillNote(summary).contains("未获取当前位置"))
    }

    @Test fun placeTypeTagsBeatNameKeywords() {
        val draft = proposal(item("print", "打印论文", "PRINT_SHOP"))

        val summary = applyFamiliarPlaceFills(
            draft, placeFillRequests(draft), origin,
            mapOf("快递站" to pickup, "打印店" to print),
            mapOf("打印店" to setOf("PRINT_SHOP")),
        )

        assertEquals(1, summary.filledCount)
        assertEquals("print", draft.getJSONArray("tasks").getJSONObject(0)
            .getJSONObject("place").getString("id"))
        assertTrue(placeFillNote(summary).contains("已按常用地点填入"))
    }

    @Test fun malformedProposalDegradesWithoutThrowing() {
        val draft = JSONObject().put("action", "create_tasks").put("tasks", JSONArray().put("bad"))
        val summary = applyFamiliarPlaceFills(
            draft, listOf(PlaceFillRequest("missing", "PARCEL")),
            origin, mapOf("快递站" to pickup), emptyMap(),
        )
        assertEquals(0, summary.filledCount)
    }
}
