package com.onmyway.app.data.local.entity

import androidx.room.*
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.onmyway.app.core.model.Place
import java.util.UUID
import org.json.JSONObject

@Entity(tableName = "tasks")
data class Errand(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val priority: Int = 2,
    val serviceMinutes: Int = 5,
    val deadline: String = "",
    val placeJson: String = "",
    val placeType: String = "SUPERMARKET",
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = createdAt,
    @ColumnInfo(defaultValue = "0") val completedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastRemindedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val reminderCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val reminderPending: Boolean = false,
    @ColumnInfo(defaultValue = "''") val plannedDate: String = "",
    @ColumnInfo(defaultValue = "0") val reminderAt: Long = 0,
) {
    fun place(): Place? = if (placeJson.isBlank()) null else Place.from(JSONObject(placeJson))
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title).put("priority", priority)
        .put("service_minutes", serviceMinutes).put("deadline", if (deadline.isBlank()) JSONObject.NULL else deadline)
        .put("place", place()?.json() ?: JSONObject.NULL)
        .put("place_type", if (placeJson.isBlank()) placeType else JSONObject.NULL)

    /** Eligibility input; task-specific fields are intentionally absent from this contract. */
    fun candidateJson(): JSONObject = JSONObject().put("id", id).put("title", title)
        .put("place_type", placeType).put("place", place()?.json() ?: JSONObject.NULL)
        .put("deadline", if (deadline.isBlank()) JSONObject.NULL else deadline)
        .put("planned_date", plannedDate)
}
