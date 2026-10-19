package com.onmyway.app.data.event

import com.onmyway.app.data.local.entity.BusinessEventOutbox
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

private val eventTimeFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

internal fun formatEventTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).format(eventTimeFormatter)

/** The exact body sent to `POST /events`; persisted columns are the source of truth. */
internal fun BusinessEventOutbox.toRequestJson(zone: ZoneId = ZoneId.systemDefault()): JSONObject =
    JSONObject()
        .put("schema_version", schemaVersion)
        .put("event_id", eventId)
        .put("correlation_id", correlationId)
        .put("source", source)
        .put("kind", kind)
        .put("occurred_at", formatEventTime(occurredAt, zone))
        .apply { validUntil?.let { put("valid_until", formatEventTime(it, zone)) } }
        .put("evidence", runCatching { JSONArray(evidenceJson) }.getOrDefault(JSONArray()))
        .put("payload", runCatching { JSONObject(payloadJson) }.getOrDefault(JSONObject()))
