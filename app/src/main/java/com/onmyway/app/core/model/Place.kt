package com.onmyway.app.core.model

import androidx.room.*
import org.json.JSONObject

data class Place(val id: String, val name: String, val longitude: Double, val latitude: Double, val address: String = "") {
    fun json() = JSONObject().put("id", id).put("name", name).put("longitude", longitude).put("latitude", latitude).put("address", address)
    companion object {
        fun from(j: JSONObject) = Place(j.getString("id"), j.getString("name"), j.getDouble("longitude"), j.getDouble("latitude"), j.optString("address"))
    }
}
