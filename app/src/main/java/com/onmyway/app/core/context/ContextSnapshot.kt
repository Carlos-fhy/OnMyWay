package com.onmyway.app.core.context

import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.data.local.entity.SavedTrip
import com.onmyway.app.data.sync.canonicalJson
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Selected origins are user choices, so they do not pretend to be fresh GPS observations. */
internal data class ContextLocation(
    val source: String = "unknown",
    val observedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
)

/** Immutable decision inputs. Revalidate inside the write transaction before applying a result. */
internal class ContextSnapshot private constructor(private val payload: String) {
    fun json(): JSONObject = JSONObject(payload)

    fun requireCurrent(tasks: List<Errand>, trip: SavedTrip?, now: Long = System.currentTimeMillis()) {
        val snapshot = json()
        val capturedAt = snapshot.getLong("captured_at")
        check(now >= capturedAt) { "设备时间已变化，请重新规划。" }
        check(now - capturedAt <= SNAPSHOT_TTL_MS) { "规划上下文已超过 30 分钟，请重新规划。" }
        val location = snapshot.getJSONObject("location")
        if (location.getString("source") in setOf("device", "cached")) {
            val observedAt = location.getLong("observed_at")
            check(now >= observedAt && now - observedAt <= LOCATION_TTL_MS) {
                "定位已超过 5 分钟或设备时间已变化，请刷新定位后重新规划。"
            }
        }
        val expectedPlan = snapshot.optJSONObject("plan")
        val currentPlan = trip?.let(::planJson)
        check((expectedPlan == null && currentPlan == null) ||
            (expectedPlan != null && currentPlan != null && canonicalJson(expectedPlan) == canonicalJson(currentPlan))) {
            "当前行程或计划版本已变化，请重新规划。"
        }
        val current = tasks.associateBy { it.id }
        check(current.size == tasks.size) { "本地待办身份重复，请重新规划。" }
        val proposedIds = snapshot.getJSONArray("proposed_task_ids").strings().toSet()
        val expectedTasks = snapshot.getJSONArray("tasks")
        val storedIds = mutableSetOf<String>()
        for (index in 0 until expectedTasks.length()) {
            val expected = expectedTasks.getJSONObject(index)
            val id = expected.getString("id")
            val title = expected.getString("title")
            if (id in proposedIds) {
                check(id !in current) { "新增待办“$title”已经保存或身份冲突，请重新规划。" }
                continue
            }
            storedIds += id
            val task = current[id]
            check(task != null) { "待办“$title”已删除，请重新规划。" }
            val revision = try { taskJson(task).getString("revision") } catch (_: Exception) {
                throw IllegalStateException("待办“$title”的数据已变化，请重新规划。")
            }
            check(expected.getString("revision") == revision) { "待办“$title”已修改或完成，请重新规划。" }
        }
        if (snapshot.getString("task_scope") == "all") {
            check(current.keys == storedIds) { "待办列表已变化，请重新规划。" }
        }
    }

    companion object {
        private const val SNAPSHOT_TTL_MS = 30 * 60_000L
        private const val LOCATION_TTL_MS = 5 * 60_000L
        private const val MAX_TASKS = 100
        private val revisionPattern = Regex("[0-9a-f]{64}")

        fun capture(
            tasks: List<Errand>,
            trip: SavedTrip?,
            taskScope: String = "selected",
            location: ContextLocation = ContextLocation(),
            now: Long = System.currentTimeMillis(),
            proposedTasks: List<Errand> = emptyList(),
        ): ContextSnapshot {
            val allTasks = tasks + proposedTasks
            require(allTasks.size <= MAX_TASKS) { "规划上下文最多包含 100 条待办，请缩小选择范围。" }
            require(allTasks.map { it.id }.toSet().size == allTasks.size) { "规划上下文的待办身份重复。" }
            val data = JSONObject()
                .put("schema_version", 1)
                .put("snapshot_id", UUID.randomUUID().toString())
                .put("captured_at", now)
                .put("task_scope", taskScope)
                .put("tasks", JSONArray(allTasks.sortedBy { it.id }.map(::taskJson)))
                .put("proposed_task_ids", JSONArray(proposedTasks.map { it.id }.sorted()))
                .put("plan", trip?.let(::planJson) ?: JSONObject.NULL)
                .put("location", JSONObject().put("source", location.source)
                    .put("observed_at", location.observedAt ?: JSONObject.NULL)
                    .put("latitude", location.latitude ?: JSONObject.NULL)
                    .put("longitude", location.longitude ?: JSONObject.NULL)
                    .put("accuracy_m", location.accuracyM ?: JSONObject.NULL))
                .put("device", JSONObject().put("navigation_state", navigationState(trip)))
            return fromJson(data)
        }

        fun fromJson(value: JSONObject): ContextSnapshot = try {
            // Copy before inspecting so later mutations cannot affect validation.
            val data = JSONObject(value.toString())
            require(integer(data, "schema_version", 1, 1) == 1L) { "不支持的规划上下文版本。" }
            val snapshotId = string(data, "snapshot_id")
            require(UUID.fromString(snapshotId).toString() == snapshotId.lowercase()) { "规划上下文身份无效。" }
            val capturedAt = integer(data, "captured_at", 1)
            require(string(data, "task_scope") in setOf("all", "selected")) { "规划上下文范围无效。" }
            val tasks = data.getJSONArray("tasks")
            require(tasks.length() <= MAX_TASKS) { "规划上下文最多包含 100 条待办。" }
            val ids = mutableSetOf<String>()
            for (index in 0 until tasks.length()) {
                val task = tasks.getJSONObject(index)
                val id = string(task, "id")
                require(ids.add(id)) { "规划上下文的待办身份重复。" }
                string(task, "title")
                integer(task, "priority", 0, Int.MAX_VALUE.toLong())
                integer(task, "service_minutes", 0, Int.MAX_VALUE.toLong())
                string(task, "deadline", allowBlank = true)
                string(task, "place_type", allowBlank = true)
                string(task, "status")
                string(task, "planned_date", allowBlank = true)
                integer(task, "reminder_at", 0)
                require(task.has("place")) { "规划上下文缺少任务地点。" }
                if (!task.isNull("place")) validatePlace(task.getJSONObject("place"))
                val revision = revision(task)
                val content = JSONObject(task.toString()).apply { remove("revision") }
                require(hash(content) == revision) { "规划上下文的待办版本与内容不一致。" }
            }
            val proposed = data.getJSONArray("proposed_task_ids").strings()
            require(proposed.toSet().size == proposed.size && ids.containsAll(proposed)) {
                "规划上下文的新增待办身份无效。"
            }
            require(data.has("plan")) { "规划上下文缺少行程状态。" }
            val plan = if (data.isNull("plan")) null else data.getJSONObject("plan").also {
                string(it, "plan_id", allowBlank = true)
                integer(it, "plan_version", 0, Int.MAX_VALUE.toLong())
                string(it, "session_id", allowBlank = true)
                revision(it)
                boolean(it, "started")
                boolean(it, "needs_replan")
            }
            validateLocation(data.getJSONObject("location"), capturedAt)
            val expectedState = when {
                plan == null -> "idle"
                plan.getBoolean("needs_replan") -> "needs_replan"
                plan.getBoolean("started") -> "started"
                else -> "ready"
            }
            require(string(data.getJSONObject("device"), "navigation_state") == expectedState) {
                "规划上下文的设备状态与行程不一致。"
            }
            ContextSnapshot(data.toString())
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalArgumentException("规划上下文字段无效，请重新规划。", failure)
        }

        private fun taskJson(task: Errand): JSONObject {
            val content = JSONObject().put("id", task.id).put("title", task.title)
                .put("priority", task.priority).put("service_minutes", task.serviceMinutes)
                .put("deadline", task.deadline).put("place", task.place()?.json() ?: JSONObject.NULL)
                .put("place_type", task.placeType).put("status", task.status)
                .put("planned_date", task.plannedDate).put("reminder_at", task.reminderAt)
            return content.put("revision", hash(content))
        }

        private fun planJson(trip: SavedTrip): JSONObject {
            val content = JSONObject().put("session_id", trip.sessionId).put("plan_id", trip.planId)
                .put("plan_version", trip.planVersion).put("request", trip.requestJson)
                .put("result", trip.resultJson).put("started", trip.started)
                .put("started_at", trip.startedAt).put("planned_at", trip.plannedAt)
                .put("needs_replan", trip.needsReplan)
            return JSONObject().put("plan_id", trip.planId).put("plan_version", trip.planVersion)
                .put("session_id", trip.sessionId).put("revision", hash(content))
                .put("started", trip.started).put("needs_replan", trip.needsReplan)
        }

        private fun navigationState(trip: SavedTrip?): String = when {
            trip == null -> "idle"
            trip.needsReplan -> "needs_replan"
            trip.started -> "started"
            else -> "ready"
        }

        private fun validatePlace(place: JSONObject) {
            string(place, "id", allowBlank = true)
            string(place, "name", allowBlank = true)
            string(place, "address", allowBlank = true)
            number(place, "latitude", -90.0, 90.0)
            number(place, "longitude", -180.0, 180.0)
        }

        private fun validateLocation(location: JSONObject, capturedAt: Long) {
            val source = string(location, "source")
            require(source in setOf("device", "cached", "selected", "unknown")) { "定位来源无效。" }
            listOf("observed_at", "latitude", "longitude", "accuracy_m").forEach {
                require(location.has(it)) { "定位上下文缺少字段：$it。" }
            }
            if (source == "unknown") {
                require(listOf("observed_at", "latitude", "longitude", "accuracy_m").all(location::isNull)) {
                    "未知来源不能携带定位数据。"
                }
                return
            }
            number(location, "latitude", -90.0, 90.0)
            number(location, "longitude", -180.0, 180.0)
            if (source == "selected") {
                require(location.isNull("observed_at") && location.isNull("accuracy_m")) {
                    "手动选择的起点不能携带设备定位时间或精度。"
                }
            } else {
                integer(location, "observed_at", 1, capturedAt)
                if (!location.isNull("accuracy_m")) number(location, "accuracy_m", 0.0, Double.MAX_VALUE)
            }
        }

        private fun string(data: JSONObject, key: String, allowBlank: Boolean = false): String {
            val value = data.get(key)
            require(value is String && (allowBlank || value.isNotBlank())) { "规划上下文字段无效：$key。" }
            return value
        }

        private fun integer(data: JSONObject, key: String, min: Long, max: Long = Long.MAX_VALUE): Long {
            val value = data.get(key)
            require(value is Int || value is Long) { "规划上下文字段必须为整数：$key。" }
            val number = (value as Number).toLong()
            require(number in min..max) { "规划上下文字段超出范围：$key。" }
            return number
        }

        private fun number(data: JSONObject, key: String, min: Double, max: Double): Double {
            val value = data.get(key)
            require(value is Number) { "规划上下文字段必须为数值：$key。" }
            val number = value.toDouble()
            require(number.isFinite() && number in min..max) { "规划上下文字段超出范围：$key。" }
            return number
        }

        private fun boolean(data: JSONObject, key: String): Boolean {
            val value = data.get(key)
            require(value is Boolean) { "规划上下文字段必须为布尔值：$key。" }
            return value
        }

        private fun revision(data: JSONObject): String = string(data, "revision").also {
            require(revisionPattern.matches(it)) { "规划上下文版本指纹无效。" }
        }

        private fun JSONArray.strings(): List<String> = (0 until length()).map {
            val value = get(it)
            require(value is String && value.isNotBlank()) { "规划上下文身份必须为非空字符串。" }
            value
        }

        private fun hash(value: JSONObject): String = MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
