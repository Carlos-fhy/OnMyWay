package com.onmyway.app.data.event

import com.onmyway.app.data.local.entity.ConfirmationOutbox
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

internal class ConfirmationProtocolException : IOException("确认回包与本地记录不一致，请核对服务版本并重新确认。")

internal data class ConfirmationAcknowledgement(
    val receiptId: String,
    val subjectType: String,
    val subjectId: String,
    val subjectVersion: Int,
    val correlationId: String,
    val status: String,
) {
    fun requireMatches(receipt: ConfirmationOutbox) {
        if (subjectType != receipt.subjectType || subjectId != receipt.subjectId ||
            subjectVersion != receipt.subjectVersion || correlationId != receipt.correlationId ||
            (status == "accepted" && receiptId != receipt.receiptId)) throw ConfirmationProtocolException()
        // A semantic duplicate can name the server's first receipt for this exact subject version.
    }

    companion object {
        fun fromJson(value: JSONObject): ConfirmationAcknowledgement = try {
            require(value.opt("recorded") == true && value.opt("recorded_is_not_executed") == true)
            ConfirmationAcknowledgement(
                receiptId = value.requiredUuid("receipt_id"),
                subjectType = value.requiredText("subject_type").also { require(it in setOf("proposal", "plan")) },
                subjectId = value.requiredText("subject_id"),
                subjectVersion = value.requiredPositiveInt("subject_version"),
                correlationId = value.requiredUuid("correlation_id"),
                status = value.requiredText("status").also { require(it in setOf("accepted", "duplicate")) },
            )
        } catch (_: Exception) {
            throw ConfirmationProtocolException()
        }
    }
}

internal fun JSONObject.requiredText(key: String): String = (get(key) as? String)
    ?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Invalid $key")

internal fun JSONObject.requiredUuid(key: String): String = requiredText(key).also {
    require(UUID.fromString(it).toString() == it.lowercase())
}

internal fun JSONObject.requiredPositiveInt(key: String): Int {
    val value = get(key)
    require(value is Int || value is Long)
    return (value as Number).toLong().also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
}
