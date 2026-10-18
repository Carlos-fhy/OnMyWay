package com.onmyway.app.feature.assistant

import com.onmyway.app.core.model.isKnownPlaceType
import com.onmyway.app.data.local.entity.Errand
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** IDs are assigned by the client, never inferred from a task's title or its list position later. */
internal fun taskDraftItems(proposal: JSONObject?): List<JSONObject> {
    if (proposal?.optString("action") != "create_tasks") return emptyList()
    val items = proposal.getJSONArray("tasks")
    return (0 until items.length()).map(items::getJSONObject)
}

internal fun taskDraftErrand(item: JSONObject) = Errand(
    title = item.getString("title"), placeType = item.getString("place_type"),
    deadline = item.optString("deadline"), plannedDate = item.optString("planned_date"),
    priority = item.optInt("priority", 2), serviceMinutes = item.optInt("service_minutes", 5),
    placeJson = item.optJSONObject("place")?.toString().orEmpty(),
)

internal fun taskDraftRequestItems(proposal: JSONObject): JSONArray = JSONArray().apply {
    taskDraftItems(proposal).forEach { item ->
        put(JSONObject().apply {
            listOf("item_id", "version", "title", "place_type", "deadline", "planned_date")
                .forEach { field -> put(field, item.get(field)) }
        })
    }
}

private fun validateDraftFields(item: JSONObject) {
    require(item.getString("title").isNotBlank() && item.getString("title").length <= 100) { "任务名称无效" }
    require(isKnownPlaceType(item.getString("place_type"))) { "任务地点类型无效" }
    val date = item.getString("planned_date")
    require(date.isEmpty() || LocalDate.parse(date).toString() == date) { "计划日期无效" }
    val deadline = item.getString("deadline")
    if (deadline.isNotBlank()) OffsetDateTime.parse(deadline)
}

/** Apply only addressed fields; an omitted sibling can never be deleted by a model reply. */
internal fun taskDraftReplacement(answer: JSONObject, current: JSONObject?, requestId: String): JSONObject {
    val action = answer.getString("action")
    if (action == "update_task_drafts") {
        require(current?.optString("action") == "create_tasks") { "待修改草稿已失效" }
        val result = JSONObject(current.toString()).put("_draft_id", requestId)
        val items = taskDraftItems(result).associateBy { it.getString("item_id") }
        val updates = answer.getJSONArray("updates")
        require(updates.length() in 1..10) { "草稿修改数量无效" }
        val seen = mutableSetOf<String>()
        for (index in 0 until updates.length()) {
            val update = updates.getJSONObject(index)
            val id = update.getString("item_id")
            require(seen.add(id)) { "草稿修改编号重复" }
            val item = requireNotNull(items[id]) { "待修改草稿已失效" }
            require(update.getInt("expected_version") == item.getInt("version")) { "草稿已变化，请重新修改" }
            val fields = listOf("title", "place_type", "deadline", "planned_date")
            require(fields.any(update::has)) { "未提供需要修改的字段" }
            if (update.has("place_type") && update.getString("place_type") != item.getString("place_type"))
                item.put("place", JSONObject.NULL)
            fields.filter(update::has).forEach { key ->
                require(!update.isNull(key)) { "草稿字段无效" }
                item.put(key, update.get(key))
            }
            validateDraftFields(item)
            item.put("version", item.getInt("version") + 1)
        }
        return result
    }
    require(action == "create_tasks" || action == "create_task")
    require(taskDraftItems(current).isEmpty()) { "请先确认或取消当前草稿" }
    val source = if (action == "create_tasks") answer.getJSONArray("tasks") else JSONArray().put(answer)
    require(source.length() in 1..10) { "一次可记录 1–10 件事" }
    val items = JSONArray()
    for (index in 0 until source.length()) {
        val value = source.getJSONObject(index)
        val item = JSONObject().put("item_id", UUID.nameUUIDFromBytes("$requestId:task:$index".toByteArray(Charsets.UTF_8)).toString())
            .put("version", 1).put("title", value.getString("title")).put("place_type", value.getString("place_type"))
            .put("deadline", value.optString("deadline")).put("planned_date", value.optString("planned_date"))
            .put("priority", value.optInt("priority", 2)).put("service_minutes", 5).put("place", JSONObject.NULL)
        validateDraftFields(item)
        items.put(item)
    }
    return JSONObject().put("action", "create_tasks").put("_draft_id", requestId)
        .put("_plan_id", requestId).put("tasks", items)
}

/** Narrow confirmation grammar: ambiguous references go back to the user, never to execution. */
internal fun taskDraftConfirmation(text: String, items: List<JSONObject>): Map<String, Int>? {
    val command = text.trim().trimEnd('。', '！', '!')
    val all = command in listOf("确认全部", "全部确认", "保存全部", "全部保存", "都保存", "都记下")
    val single = command in listOf("确认", "保存", "记下") && items.size == 1
    val selected = when {
        all || single -> items
        else -> {
            val match = Regex("(?:只)?(?:确认|保存|记下)第\\s*([一二三四五六七八九十]|[1-9]|10)\\s*(?:项|条|件)").matchEntire(command)
            if (match != null) {
                val value = match.groupValues[1]
                val number = value.toIntOrNull() ?: ("一二三四五六七八九十".indexOf(value) + 1)
                listOfNotNull(items.getOrNull(number - 1))
            } else {
                val title = Regex("(?:只)?(?:确认|保存|记下)(.+)").matchEntire(command)?.groupValues?.get(1) ?: return null
                val exact = items.filter { it.getString("title") == title }
                (if (exact.isNotEmpty()) exact else items.filter { title.length >= 2 && it.getString("title").contains(title) })
                    .takeIf { it.size == 1 } ?: return null
            }
        }
    }
    return selected.takeIf { it.isNotEmpty() }?.associate { it.getString("item_id") to it.getInt("version") }
}
