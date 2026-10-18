package com.onmyway.app.feature.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.onmyway.app.R
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.ui.AppCard
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.data.local.entity.Errand
import org.json.JSONObject

/** Missing metadata is the known legacy format; an explicit future version is never downgraded. */
internal fun assistantCardVersionSupported(value: JSONObject): Boolean {
    if (!value.has("_card_version")) return true
    return strictCardInteger(value.opt("_card_version")) == 1L
}

internal fun strictCardInteger(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    else -> null
}

internal fun cardIdentity(value: JSONObject, key: String): String? =
    (value.opt(key) as? String)?.takeIf { it.isNotBlank() }

/** Validate before rendering or dispatching an action, including keyboard/voice shortcuts. */
internal fun assistantCardIssue(value: JSONObject, now: Long = System.currentTimeMillis()): String? {
    if (!assistantCardVersionSupported(value)) return "建议版本暂不支持，请重新输入需求。"
    val draftId = cardIdentity(value, "_draft_id") ?: return "建议标识已失效，请重新输入需求。"
    val planId = cardIdentity(value, "_plan_id") ?: return "建议标识已失效，请重新输入需求。"
    return runCatching {
        when (value.optString("action")) {
            "create_tasks" -> {
                val items = value.getJSONArray("tasks")
                require(items.length() in 1..10)
                val ids = mutableSetOf<String>()
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val id = cardIdentity(item, "item_id") ?: error("Missing item identity")
                    require(ids.add(id))
                    val version = strictCardInteger(item.opt("version")) ?: error("Invalid item version")
                    require(version in 1..Int.MAX_VALUE.toLong())
                    require(item.opt("title") is String && item.opt("place_type") is String)
                    require(!item.has("place") || item.isNull("place") || item.opt("place") is JSONObject)
                    AssistantDraftItem.from(item, planId, index)
                }
            }
            "create_task" -> {
                require(value.opt("title") is String && value.opt("place_type") is String)
                AssistantDraftItem.fromTask(draftId, planId, 0, 1,
                    Errand(title = value.getString("title"), placeType = value.getString("place_type"),
                        deadline = value.optString("deadline")))
            }
            "plan_route" -> {
                require((value.opt("destination") as? String)?.isNotBlank() == true)
                require(value.optString("mode") in listOf("", "walking", "cycling"))
                if (value.has("_expires_at")) {
                    val expiry = strictCardInteger(value.opt("_expires_at")) ?: error("Invalid expiry")
                    if (expiry <= now) return "这条路线建议已过期，请重新告诉我目的地。"
                }
                if (value.has("known_destination") && !value.isNull("known_destination")) {
                    val place = Place.from(value.getJSONObject("known_destination"))
                    require(place.id.isNotBlank() && place.name.isNotBlank() && place.longitude.isFinite() &&
                        place.latitude.isFinite() && place.longitude in -180.0..180.0 && place.latitude in -90.0..90.0)
                }
            }
            else -> return "建议类型暂不支持，请重新输入需求。"
        }
        null
    }.getOrElse { "建议内容不完整或已损坏，请重新输入需求。" }
}

@Composable
internal fun UnavailableAssistantCard(reason: String) {
    AppCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBadge(
                R.drawable.ic_compass,
                size = 36.dp,
                tint = MaterialTheme.colorScheme.tertiary,
                container = MaterialTheme.colorScheme.tertiaryContainer,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("这张建议暂时无法操作", style = MaterialTheme.typography.titleMedium)
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "已保存的待办和行程保留。可在下方输入新指令继续。",
            Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
