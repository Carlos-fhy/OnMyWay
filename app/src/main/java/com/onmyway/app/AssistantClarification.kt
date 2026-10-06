package com.onmyway.app

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The question belongs to a draft, so a late answer cannot resolve a different plan. */
@Entity(tableName = "assistant_clarification")
data class AssistantClarification(
    @PrimaryKey val id: Int = 1,
    val draftId: String,
    val planId: String,
    val kind: String,
    val prompt: String,
    val destination: String,
    val mode: String,
    val savedAt: Long,
)

/** Store candidates as rows, including their display order, instead of a JSON snapshot. */
@Entity(tableName = "assistant_place_choices")
data class AssistantPlaceChoice(
    @PrimaryKey val id: String,
    val draftId: String,
    val name: String,
    val longitude: Double,
    val latitude: Double,
    val address: String,
    val position: Int,
) {
    fun toPlace() = Place(id, name, longitude, latitude, address)
}

internal data class ClarificationState(
    val question: AssistantClarification,
    val choices: List<AssistantPlaceChoice>,
)

internal fun Place.toClarificationChoice(draftId: String, position: Int) =
    AssistantPlaceChoice(id, draftId, name, longitude, latitude, address, position)

internal fun clarificationPrompt(kind: String): String = when (kind) {
    "MODE" -> "这次怎么出发？可以说步行或骑车。"
    "DESTINATION" -> "你要去哪个地点？可以说完整地点名称，也可以说“第二个”选择候选地点。"
    "CONFIRM_ROUTE" -> "按这个目的地和出行方式规划路线吗？"
    "TASK_PLACE" -> "这件事要在哪个地点完成？可以说完整地点名称，或选择候选地点。"
    "CONFIRM_TASK" -> "要保存这项待办吗？"
    else -> "请补充一下你想怎么安排。"
}

/** Only explicit, unambiguous answers select a candidate; never default to the first result. */
internal fun resolvePlaceReply(text: String, choices: List<Place>): Place? {
    val reply = text.trim().trimEnd('。', '！', '!', '？', '?', '，', ',', '；', ';').trim()
    if (reply.isEmpty() || choices.isEmpty()) return null

    val names = choices.filter { it.name.trim() == reply }
    if (names.size > 1) return null

    val number = Regex("(?:选|选择)?\\s*(?:第\\s*)?([0-9]+|[一二三四五六七八九十]+)\\s*(?:个)?")
        .matchEntire(reply)?.groupValues?.get(1)
    val position = number?.let(::placeChoiceNumber)
    val byPosition = position?.takeIf { it > 0 }?.let { choices.getOrNull(it - 1) }
    val byName = names.singleOrNull()
    // A numeric place name and an ordinal can refer to different candidates.
    if (byName != null && byPosition != null && byName != byPosition) return null
    return byName ?: byPosition
}

private fun placeChoiceNumber(text: String): Int? {
    text.toIntOrNull()?.let { return it }
    val digits = "一二三四五六七八九"
    if (text.length == 1) return if (text == "十") 10 else digits.indexOf(text).takeIf { it >= 0 }?.plus(1)
    // Recognize standard Chinese ordinals up to 99; reject malformed forms such as 一二 or 十十.
    val match = Regex("([一二三四五六七八九]?)十([一二三四五六七八九]?)").matchEntire(text) ?: return null
    val tens = match.groupValues[1].let { if (it.isEmpty()) 1 else digits.indexOf(it) + 1 }
    val units = match.groupValues[2].let { if (it.isEmpty()) 0 else digits.indexOf(it) + 1 }
    return tens * 10 + units
}
