package com.onmyway.app

import androidx.room.*

/** Intent and user choices survive restart; live routes are always recalculated. */
@Entity(tableName = "planning_draft")
data class PlanningDraft(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(defaultValue = "'legacy-draft'") val draftId: String = "legacy-draft",
    @ColumnInfo(defaultValue = "''") val planId: String = draftId,
    val destination: String,
    val mode: String,
    val budget: Int,
    val placeId: String,
    val placeName: String,
    val longitude: Double,
    val latitude: Double,
    val address: String,
    val savedAt: Long,
) {
    fun place() = Place(placeId, placeName, longitude, latitude, address)
}
@Entity(tableName = "planning_draft_tasks")
data class PlanningDraftTask(@PrimaryKey val id: String, val selected: Boolean)

internal data class PlanningChoices(val draft: PlanningDraft, val tasks: List<PlanningDraftTask>)
