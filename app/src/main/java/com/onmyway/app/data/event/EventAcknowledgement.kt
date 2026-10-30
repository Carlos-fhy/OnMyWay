package com.onmyway.app.data.event

import com.onmyway.app.data.local.entity.BusinessEventOutbox
import java.io.IOException
import org.json.JSONObject

internal class EventProtocolException : IOException("事件回包与本地记录不一致，请核对服务版本。")

internal data class EventAcknowledgement(
    val eventId: String,
    val correlationId: String,
    val status: String,
) {
    fun requireMatches(event: BusinessEventOutbox) {
        // Unlike confirmation receipts, duplicate events retain the exact original event id.
        if (eventId != event.eventId || correlationId != event.correlationId) throw EventProtocolException()
    }

    companion object {
        fun fromJson(value: JSONObject): EventAcknowledgement = try {
            require(value.opt("recorded") == true)
            EventAcknowledgement(
                eventId = value.requiredUuid("event_id"),
                correlationId = value.requiredUuid("correlation_id"),
                status = value.requiredText("status").also { require(it in setOf("accepted", "duplicate")) },
            )
        } catch (_: Exception) {
            throw EventProtocolException()
        }
    }
}
