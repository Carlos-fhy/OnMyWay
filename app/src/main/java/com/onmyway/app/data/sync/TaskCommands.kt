package com.onmyway.app.data.sync

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Metadata acknowledged by the task service, including versions of deleted task IDs. */
internal data class TaskAuthority(val protocol: Int = 0, val versions: JSONObject = JSONObject()) {
    val active: Boolean get() = protocol == 1

    fun checkSnapshot(snapshot: JSONObject?) {
        if (!active) return
        taskRows(snapshot).keys.forEach { id ->
            if (!versions.has(id)) throw SyncConflict("任务版本不完整；已保留本机记录，请更新数据服务后重试")
        }
    }

    companion object {
        fun from(response: JSONObject): TaskAuthority {
            if (!response.has("task_protocol")) {
                if (response.has("task_versions")) throw SyncConflict("任务服务协议标识缺失，已暂停提交")
                return TaskAuthority()
            }
            if (response.opt("task_protocol") !is Number || canonicalJson(response.get("task_protocol")) != "1")
                throw SyncConflict("任务服务协议不兼容，请更新 App 或数据服务")
            val versions = response.optJSONObject("task_versions")
                ?: throw SyncConflict("任务版本信息缺失，已暂停提交")
            for (id in versions.keys()) {
                val value = versions.get(id)
                if (id.isBlank() || value !is Number || value.toLong() <= 0 ||
                    canonicalJson(value) != value.toLong().toString())
                    throw SyncConflict("任务版本信息无效，已暂停提交")
            }
            return TaskAuthority(1, JSONObject(versions.toString()))
        }
    }
}

internal fun taskRows(snapshot: JSONObject?): Map<String, JSONObject> {
    val rows = snapshot?.getJSONObject("tables")?.getJSONArray("tasks") ?: return emptyMap()
    return buildMap {
        for (index in 0 until rows.length()) {
            val task = rows.getJSONObject(index)
            val id = task.getString("id")
            check(id.isNotBlank() && put(id, task) == null) { "任务记录标识无效或重复" }
        }
    }
}

/** Room is the offline working copy. Persist the derived commands before any network write. */
internal fun taskCommands(baseline: JSONObject?, edited: JSONObject, authority: TaskAuthority): JSONArray {
    check(authority.active)
    authority.checkSnapshot(baseline)
    val before = taskRows(baseline)
    val after = taskRows(edited)
    val commands = JSONArray()
    fun comparable(task: JSONObject?): String = canonicalJson(task?.let {
        JSONObject(it.toString()).apply {
            if (!has("plannedDate")) put("plannedDate", "")
            if (!has("reminderAt")) put("reminderAt", 0L)
        }
    })
    for (id in (before.keys + after.keys).sorted()) {
        if (comparable(before[id]) == comparable(after[id])) continue
        if (id !in before && authority.versions.has(id))
            throw SyncConflict("任务已在电脑端删除；已保留本机记录，请处理冲突后重试")
        commands.put(JSONObject().put("command_id", UUID.randomUUID().toString())
            .put("operation", if (id in after) "upsert" else "delete")
            .put("task_id", id).put("expected_version", authority.versions.optLong(id, 0))
            .also { command -> after[id]?.let { command.put("task", JSONObject(it.toString())) } })
    }
    return commands
}
