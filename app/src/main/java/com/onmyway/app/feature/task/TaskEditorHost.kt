package com.onmyway.app.feature.task

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.onmyway.app.data.local.entity.Errand
import org.json.JSONObject

/**
 * 编辑弹层的宿主身份。弹层自身的输入由 TaskEditor 的 rememberSaveable 保存，
 * 这里只负责让"是否打开、编辑哪一条"在 Activity 重建后仍然成立。
 */
internal sealed interface TaskEditorHost {
    data object Closed : TaskEditorHost
    data object New : TaskEditorHost
    data class Edit(val taskId: String, val snapshot: String) : TaskEditorHost
}

internal val TaskEditorHost.stableKey: String
    get() = when (this) {
        TaskEditorHost.Closed -> "closed"
        TaskEditorHost.New -> "new"
        is TaskEditorHost.Edit -> "edit:$taskId"
    }

internal val TaskEditorHostSaver: Saver<TaskEditorHost, Any> = listSaver(
    save = { host ->
        when (host) {
            TaskEditorHost.Closed -> emptyList()
            TaskEditorHost.New -> listOf("new")
            is TaskEditorHost.Edit -> listOf("edit", host.taskId, host.snapshot)
        }
    },
    restore = { values ->
        when (values.firstOrNull()) {
            "new" -> TaskEditorHost.New
            "edit" -> {
                val id = values.getOrNull(1)
                val snapshot = values.getOrNull(2)
                if (id != null && snapshot != null) TaskEditorHost.Edit(id, snapshot) else TaskEditorHost.Closed
            }
            else -> TaskEditorHost.Closed
        }
    },
)

/**
 * 编辑宿主的完整快照。TaskEditor 保存时会以原任务为底做 copy，
 * 因此这里必须覆盖 Errand 的全部字段，避免恢复后保存把提醒等状态抹掉。
 */
internal fun Errand.editorSnapshot(): String = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("priority", priority)
    .put("serviceMinutes", serviceMinutes)
    .put("deadline", deadline)
    .put("plannedDate", plannedDate)
    .put("placeJson", placeJson)
    .put("placeType", placeType)
    .put("status", status)
    .put("createdAt", createdAt)
    .put("updatedAt", updatedAt)
    .put("completedAt", completedAt)
    .put("lastRemindedAt", lastRemindedAt)
    .put("reminderCount", reminderCount)
    .put("reminderPending", reminderPending)
    .put("reminderAt", reminderAt)
    .toString()

internal fun errandFromEditorSnapshot(raw: String): Errand? = runCatching {
    val value = JSONObject(raw)
    val id = value.getString("id")
    require(id.isNotBlank())
    Errand(
        id = id,
        title = value.getString("title"),
        priority = value.getInt("priority"),
        serviceMinutes = value.getInt("serviceMinutes"),
        deadline = value.optString("deadline"),
        plannedDate = value.optString("plannedDate"),
        placeJson = value.optString("placeJson"),
        placeType = value.getString("placeType"),
        status = value.optString("status", "pending"),
        createdAt = value.optLong("createdAt"),
        updatedAt = value.optLong("updatedAt"),
        completedAt = value.optLong("completedAt"),
        lastRemindedAt = value.optLong("lastRemindedAt"),
        reminderCount = value.optInt("reminderCount"),
        reminderPending = value.optBoolean("reminderPending"),
        reminderAt = value.optLong("reminderAt"),
    )
}.getOrNull()

/** 删除确认只需要身份和当时的标题；任务本身始终从 Room 重新解析。 */
internal data class DeleteHost(val taskId: String, val title: String)

internal val DeleteHostSaver: Saver<DeleteHost?, Any> = listSaver(
    save = { host -> host?.let { listOf(it.taskId, it.title) } ?: emptyList() },
    restore = { values ->
        values.getOrNull(0)?.let { id -> DeleteHost(id, values.getOrNull(1).orEmpty()) }
    },
)
