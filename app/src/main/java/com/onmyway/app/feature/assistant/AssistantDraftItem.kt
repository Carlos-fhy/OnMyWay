package com.onmyway.app.feature.assistant

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.model.isKnownPlaceType
import com.onmyway.app.data.local.entity.Errand
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.json.JSONObject

/** Stable identity belongs to one proposed task, independent of the card revision. */
@Entity(tableName = "assistant_task_drafts")
data class AssistantDraftItem(
    @PrimaryKey val id: String,
    val planId: String,
    val position: Int,
    val version: Int,
    val title: String,
    val placeType: String,
    val deadline: String,
    val plannedDate: String,
    val priority: Int,
    val serviceMinutes: Int,
    val placeJson: String,
    @ColumnInfo(defaultValue = "0") val reminderAt: Long = 0,
) {
    fun task() = Errand(id = id, title = title, placeType = placeType, deadline = deadline,
        plannedDate = plannedDate, priority = priority, serviceMinutes = serviceMinutes, placeJson = placeJson,
        reminderAt = reminderAt)

    fun json(): JSONObject = JSONObject().put("item_id", id).put("version", version)
        .put("title", title).put("place_type", placeType).put("deadline", deadline)
        .put("planned_date", plannedDate).put("priority", priority).put("service_minutes", serviceMinutes)
        .put("reminder_at", reminderAtIso(reminderAt))
        .put("place", if (placeJson.isBlank()) JSONObject.NULL else JSONObject(placeJson))

    companion object {
        fun from(value: JSONObject, planId: String, position: Int): AssistantDraftItem {
            fun text(key: String) = if (value.isNull(key)) "" else value.optString(key).trim()
            val place = value.optJSONObject("place")?.let(Place::from)
            return fromTask(value.optString("item_id").ifBlank { UUID.randomUUID().toString() },
                planId, position, value.optInt("version", 1),
                Errand(title = text("title"), placeType = text("place_type"), deadline = text("deadline"),
                    plannedDate = text("planned_date"), priority = value.optInt("priority", 2),
                    serviceMinutes = value.optInt("service_minutes", 5),
                    reminderAt = reminderAtMillis(text("reminder_at")),
                    placeJson = place?.json()?.toString().orEmpty()))
        }

        fun fromTask(id: String, planId: String, position: Int, version: Int, task: Errand): AssistantDraftItem {
            require(id.isNotBlank() && planId.isNotBlank() && version > 0) { "待办条目版本无效" }
            require(task.title.isNotBlank() && task.title.trim().length <= 100) { "请填写 100 字以内的任务名称" }
            require(task.priority in 1..3 && task.serviceMinutes in 1..240) { "任务办事时间无效" }
            require(task.reminderAt >= 0) { "提醒时间无效" }
            require(isKnownPlaceType(task.placeType) || task.place() != null) { "请选择任务地点类型" }
            if (task.plannedDate.isNotBlank()) LocalDate.parse(task.plannedDate)
            if (task.deadline.isNotBlank()) OffsetDateTime.parse(task.deadline)
            task.place()?.let {
                require(it.id.isNotBlank() && it.name.isNotBlank() && it.longitude.isFinite() && it.latitude.isFinite() &&
                    it.longitude in -180.0..180.0 && it.latitude in -90.0..90.0) { "任务地点无效" }
            }
            return AssistantDraftItem(id, planId, position, version, task.title.trim(), task.placeType,
                task.deadline, task.plannedDate, task.priority, task.serviceMinutes, task.placeJson, task.reminderAt)
        }
    }
}
