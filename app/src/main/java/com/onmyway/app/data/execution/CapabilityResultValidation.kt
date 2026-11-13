package com.onmyway.app.data.execution

import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

internal fun validateCapabilityResult(capabilityId: String, parameters: JSONObject, result: JSONObject) {
    when (capabilityId) {
        "read_tasks" -> result.array("tasks").objects().forEach {
            it.text("id", nonEmpty = true)
            it.text("title", nonEmpty = true)
        }
        "plan_route" -> when (val operation = if (parameters.has("operation")) parameters.opt("operation") else "plan") {
            "plan" -> validatePlan(parameters, result)
            "recommend" -> validateRecommendation(parameters, result)
            else -> throw IllegalArgumentException("规划操作未登记：$operation")
        }
        else -> throw IllegalArgumentException("能力结果契约未登记")
    }
}

private fun validateRecommendation(parameters: JSONObject, result: JSONObject) {
    val taskIds = parameters.array("tasks").objects().map { it.text("id", nonEmpty = true) }.toSet()
    validatePlan(JSONObject(parameters.toString()).put("tasks", JSONArray()), result.obj("direct"))
    require(result.has("suggestion")) { "建议路线字段缺失" }
    if (result.opt("suggestion") !== JSONObject.NULL) validatePlan(parameters, result.obj("suggestion"))
    result.text("note")
    val assessed = mutableSetOf<String>()
    result.array("assessments").objects().forEach { assessment ->
        val id = assessment.text("task_id", nonEmpty = true)
        require(id in taskIds && assessed.add(id)) { "任务评估身份不一致" }
        assessment.text("title", nonEmpty = true)
        assessment.text("reason")
        val withinBudget = assessment.opt("within_budget")
        require(withinBudget is Boolean) { "预算判断必须是布尔值" }
        require(assessment.has("extra_seconds")) { "任务评估缺少额外时间" }
        if (assessment.opt("extra_seconds") === JSONObject.NULL) {
            require(!withinBudget) { "无法规划的任务不能标记为预算内" }
            require(!assessment.has("extra_meters") || assessment.opt("extra_meters") === JSONObject.NULL) {
                "无法规划的任务不能返回额外距离"
            }
        } else {
            assessment.integer("extra_seconds", nonNegative = false)
            assessment.integer("extra_meters", nonNegative = false)
        }
    }
    require(assessed == taskIds) { "任务评估不完整" }
}

private fun validatePlan(parameters: JSONObject, result: JSONObject) {
    val mode = result.text("mode")
    require(mode in setOf("walking", "cycling") && mode == parameters.opt("mode")) { "路线出行方式不一致" }
    for (key in listOf("origin", "destination")) {
        val actual = result.obj(key)
        validatePlace(actual)
        val expected = parameters.obj(key)
        require(actual.opt("id") == expected.opt("id") &&
            actual.coordinate("longitude", 180.0) == expected.coordinate("longitude", 180.0) &&
            actual.coordinate("latitude", 90.0) == expected.coordinate("latitude", 90.0)) { "路线起终点不一致" }
    }
    val baselineSeconds = result.integer("baseline_seconds")
    val baselineMeters = result.integer("baseline_meters")
    val travelSeconds = result.integer("travel_seconds")
    val serviceSeconds = result.integer("service_seconds")
    val totalSeconds = result.integer("total_seconds")
    val totalMeters = result.integer("total_meters")
    require(totalSeconds == travelSeconds + serviceSeconds &&
        result.integer("extra_seconds", false) == totalSeconds - baselineSeconds &&
        result.integer("extra_travel_seconds", false) == travelSeconds - baselineSeconds &&
        result.integer("extra_meters", false) == totalMeters - baselineMeters) { "路线汇总数值不一致" }
    val finalArrival = result.time("arrival")
    result.text("solver_status", nonEmpty = true)
    result.array("warnings").strings()
    val requestedIds = parameters.array("tasks").objects().map { it.text("id", nonEmpty = true) }.toSet()
    val accountedIds = mutableSetOf<String>()
    var services = 0L
    var previousDeparture: OffsetDateTime? = null
    result.array("stops").objects().forEach { stop ->
        validatePlace(stop.obj("place"))
        val ids = stop.array("task_ids").strings(nonEmpty = true)
        val titles = stop.array("task_titles").strings(nonEmpty = true)
        require(ids.isNotEmpty() && ids.size == titles.size) { "路线站点的任务信息不完整" }
        require(ids.all { it in requestedIds && accountedIds.add(it) }) { "路线包含重复或未请求的任务" }
        val arrival = stop.time("arrival")
        val departure = stop.time("departure")
        require(!departure.isBefore(arrival) && !departure.isAfter(finalArrival) &&
            (previousDeparture == null || !arrival.isBefore(previousDeparture))) { "站点到离时间无效" }
        previousDeparture = departure
        services += stop.integer("service_seconds")
    }
    require(services == serviceSeconds) { "办事总时长不一致" }
    var meters = 0L
    var seconds = 0L
    val legs = result.array("legs").objects()
    require(legs.isNotEmpty()) { "路线缺少路段" }
    legs.forEach { leg ->
        meters += leg.integer("meters")
        seconds += leg.integer("seconds")
        leg.text("polyline")
    }
    require(meters == totalMeters && seconds == travelSeconds) { "路段总距离或总时长不一致" }
    result.array("omitted").objects().forEach { omission ->
        val id = omission.text("task_id", nonEmpty = true)
        require(id in requestedIds && accountedIds.add(id)) { "未安排任务身份不一致" }
        omission.text("title", nonEmpty = true)
        omission.text("reason", nonEmpty = true)
    }
    require(accountedIds == requestedIds) { "路线任务结果不完整" }
}

private fun validatePlace(place: JSONObject) {
    place.text("id", nonEmpty = true)
    place.text("name", nonEmpty = true)
    place.coordinate("longitude", 180.0)
    place.coordinate("latitude", 90.0)
    if (place.has("address")) place.text("address")
}

private fun JSONObject.obj(key: String): JSONObject =
    requireNotNull(opt(key) as? JSONObject) { "$key 必须是对象" }

private fun JSONObject.array(key: String): JSONArray =
    requireNotNull(opt(key) as? JSONArray) { "$key 必须是数组" }

private fun JSONObject.text(key: String, nonEmpty: Boolean = false): String {
    val value = requireNotNull(opt(key) as? String) { "$key 必须是文本" }
    require(!nonEmpty || value.isNotBlank()) { "$key 不能为空" }
    return value
}

private fun JSONObject.integer(key: String, nonNegative: Boolean = true): Long {
    val value = opt(key)
    require(value is Int || value is Long) { "$key 必须是整数" }
    val number = (value as Number).toLong()
    require(number in (if (nonNegative) 0L else Int.MIN_VALUE.toLong())..Int.MAX_VALUE.toLong()) { "$key 超出范围" }
    return number
}

private fun JSONObject.coordinate(key: String, limit: Double): Double {
    val value = opt(key)
    require(value is Number) { "$key 必须是数值" }
    val number = value.toDouble()
    require(number.isFinite() && number in -limit..limit) { "$key 超出范围" }
    return number
}

private fun JSONObject.time(key: String): OffsetDateTime = try {
    OffsetDateTime.parse(text(key))
} catch (error: java.time.format.DateTimeParseException) {
    throw IllegalArgumentException("$key 必须是带时区的有效时间", error)
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map {
    requireNotNull(opt(it) as? JSONObject) { "数组元素必须是对象" }
}

private fun JSONArray.strings(nonEmpty: Boolean = false): List<String> = (0 until length()).map {
    val value = requireNotNull(opt(it) as? String) { "数组元素必须是文本" }
    require(!nonEmpty || value.isNotBlank()) { "数组文本不能为空" }
    value
}
