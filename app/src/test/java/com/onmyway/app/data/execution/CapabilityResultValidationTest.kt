package com.onmyway.app.data.execution

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CapabilityResultValidationTest {
    private fun place(id: String) = JSONObject().put("id", id).put("name", id)
        .put("longitude", 116.3).put("latitude", 39.9).put("address", "")
    private fun parameters() = JSONObject().put("mode", "walking")
        .put("origin", place("origin")).put("destination", place("dorm"))
        .put("tasks", JSONArray().put(JSONObject().put("id", "parcel")))
    private fun plan(withStop: Boolean = true): JSONObject = JSONObject()
        .put("mode", "walking").put("origin", place("origin")).put("destination", place("dorm"))
        .put("baseline_seconds", 600).put("baseline_meters", 1000)
        .put("travel_seconds", 600).put("service_seconds", if (withStop) 60 else 0)
        .put("total_seconds", if (withStop) 660 else 600).put("extra_seconds", if (withStop) 60 else 0)
        .put("extra_travel_seconds", 0).put("total_meters", 1000).put("extra_meters", 0)
        .put("arrival", "2026-11-03T10:11:00+08:00").put("solver_status", "OPTIMAL")
        .put("stops", JSONArray().apply {
            if (withStop) put(JSONObject().put("place", place("parcel"))
                .put("task_ids", JSONArray().put("parcel")).put("task_titles", JSONArray().put("取快递"))
                .put("arrival", "2026-11-03T10:05:00+08:00").put("departure", "2026-11-03T10:06:00+08:00")
                .put("service_seconds", 60))
        })
        .put("legs", JSONArray().put(JSONObject().put("seconds", 600).put("meters", 1000).put("polyline", "")))
        .put("omitted", JSONArray()).put("warnings", JSONArray().put("注意路况"))
    private fun recommendation() = JSONObject().put("direct", plan(false)).put("suggestion", plan())
        .put("note", "").put("assessments", JSONArray().put(JSONObject().put("task_id", "parcel")
            .put("title", "取快递").put("extra_seconds", 60).put("extra_meters", 0)
            .put("within_budget", true).put("reason", "")))
    private fun rejected(result: JSONObject, parameters: JSONObject = parameters(), capability: String = "plan_route") {
        try { validateCapabilityResult(capability, parameters, result); fail("malformed capability result was accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun completePlanAndDirectRecommendationAreAccepted() {
        validateCapabilityResult("plan_route", parameters(), plan())
        validateCapabilityResult("plan_route", parameters().put("operation", "recommend"), recommendation())
        val unavailable = recommendation().put("suggestion", JSONObject.NULL)
        unavailable.getJSONArray("assessments").getJSONObject(0)
            .put("extra_seconds", JSONObject.NULL).put("within_budget", false).put("reason", "无路线").remove("extra_meters")
        validateCapabilityResult("plan_route", parameters().put("operation", "recommend"), unavailable)
    }

    @Test fun emptyPlanAndMissingRequiredFieldsAreRejected() {
        rejected(JSONObject())
        for (field in listOf("arrival", "origin", "stops", "legs", "omitted", "warnings", "total_seconds", "total_meters", "solver_status")) {
            rejected(plan().apply { remove(field) })
        }
    }

    @Test fun coercedScalarsNegativeCountsAndMalformedCoordinatesAreRejected() {
        rejected(plan().put("total_seconds", "660"))
        rejected(plan().put("total_seconds", 660.5))
        rejected(plan().put("service_seconds", -1))
        rejected(plan().put("travel_seconds", Long.MAX_VALUE))
        rejected(plan().apply { getJSONObject("destination").put("latitude", 100) })
        rejected(plan().apply { getJSONObject("origin").put("longitude", "116.3") })
        rejected(plan().apply { getJSONObject("origin").put("name", true) })
        rejected(plan().put("warnings", JSONArray().put(JSONObject())))
    }

    @Test fun localOrInvalidTimesAndIncompleteStopDetailsAreRejected() {
        rejected(plan().put("arrival", "2026-11-03T10:11:00"))
        rejected(plan().put("arrival", "tomorrow"))
        rejected(plan().apply { getJSONArray("stops").getJSONObject(0).remove("departure") })
        rejected(plan().apply { getJSONArray("stops").getJSONObject(0).put("departure", "2026-11-03T10:04:00+08:00") })
        rejected(plan().apply { getJSONArray("stops").getJSONObject(0).put("task_titles", JSONArray()) })
    }

    @Test fun invalidLegAndOmissionElementsAreRejected() {
        rejected(plan().apply { getJSONArray("legs").getJSONObject(0).put("meters", -1) })
        rejected(plan().apply { getJSONArray("legs").getJSONObject(0).put("polyline", 10) })
        rejected(plan().put("omitted", JSONArray().put(JSONObject().put("task_id", "parcel"))))
        rejected(plan().put("legs", JSONArray().put("not a leg")))
    }

    @Test fun wrongModePlaceTaskAndInconsistentTotalsAreRejected() {
        rejected(plan().put("mode", "cycling"))
        rejected(plan().put("destination", place("another-dorm")))
        rejected(plan().apply { getJSONArray("stops").getJSONObject(0).put("task_ids", JSONArray().put("unknown-task")) })
        rejected(plan().put("total_seconds", 999))
        rejected(plan().put("total_meters", 999))
    }

    @Test fun recommendationRequiresCompleteDirectSuggestionAssessmentsAndNote() {
        val params = parameters().put("operation", "recommend")
        rejected(JSONObject(), params)
        rejected(recommendation().put("direct", JSONObject()), params)
        rejected(recommendation().put("suggestion", "none"), params)
        rejected(recommendation().apply { remove("note") }, params)
        rejected(recommendation().apply { getJSONArray("assessments").getJSONObject(0).put("within_budget", "yes") }, params)
        rejected(recommendation().apply { getJSONArray("assessments").getJSONObject(0).put("extra_seconds", "60") }, params)
        rejected(recommendation().put("assessments", JSONArray().put(JSONObject())), params)
    }

    @Test fun tasksMustBeAnArrayAndUnknownCapabilityOrOperationIsRejected() {
        validateCapabilityResult("read_tasks", JSONObject(), JSONObject().put("tasks", JSONArray()))
        rejected(JSONObject().put("tasks", "[]"), JSONObject(), "read_tasks")
        rejected(JSONObject(), JSONObject(), "read_tasks")
        rejected(plan(), parameters().put("operation", "delete"))
        rejected(JSONObject(), JSONObject(), "unregistered")
    }
}
