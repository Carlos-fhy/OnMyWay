package com.onmyway.app.core.model

import androidx.room.*
import com.onmyway.app.R

/** Place types shown when an errand requires going out. INDOOR is a separate state, not a shop type. */
val placeTypes = linkedMapOf("SUPERMARKET" to "超市", "PRINT_SHOP" to "打印店", "PHARMACY" to "药店", "PARCEL" to "取快递", "TAKEOUT" to "取外卖")
const val INDOOR_PLACE_TYPE = "INDOOR"
fun placeTypeIcon(type: String): Int = when (type) {
    "SUPERMARKET" -> R.drawable.ic_supermarket
    "PARCEL" -> R.drawable.ic_parcel
    "TAKEOUT" -> R.drawable.ic_takeout
    "PHARMACY" -> R.drawable.ic_pharmacy
    "PRINT_SHOP" -> R.drawable.ic_print
    else -> R.drawable.ic_tasks
}
fun requiresFixedPlace(type: String) = type == "PARCEL" || type == "TAKEOUT"
fun isIndoor(type: String) = type == INDOOR_PLACE_TYPE
fun isKnownPlaceType(type: String) = isIndoor(type) || type in placeTypes
fun placeTypeLabel(type: String) = if (isIndoor(type)) "不用出门" else placeTypes[type] ?: "其他地点"
