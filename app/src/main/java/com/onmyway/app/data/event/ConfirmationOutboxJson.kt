package com.onmyway.app.data.event

import com.onmyway.app.data.local.entity.ConfirmationOutbox
import java.time.ZoneId
import org.json.JSONObject

/** The exact body sent to `POST /confirmations`; persisted columns are the source of truth. */
internal fun ConfirmationOutbox.toRequestJson(zone: ZoneId = ZoneId.systemDefault()): JSONObject =
    JSONObject()
        .put("schema_version", ConfirmationFactory.SCHEMA_VERSION)
        .put("receipt_id", receiptId)
        .put("subject_type", subjectType)
        .put("subject_id", subjectId)
        .put("subject_version", subjectVersion)
        .put("correlation_id", correlationId)
        .put("confirmed_at", formatEventTime(confirmedAt, zone))
        .apply { validUntil?.let { put("valid_until", formatEventTime(it, zone)) } }
        .put("snapshot", runCatching { JSONObject(snapshotJson) }.getOrDefault(JSONObject()))
