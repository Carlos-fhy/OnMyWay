package com.onmyway.app

import android.content.SharedPreferences
import androidx.room.*
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val MAIN_CONVERSATION = "primary"
private const val ROUTE_DRAFT_LIFETIME = 30 * 60_000L

@Entity(tableName = "assistant_messages", indices = [Index("createdAt")])
data class AssistantMessage(
    @PrimaryKey val id: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val routeHistoryId: String?,
    val conversationId: String = MAIN_CONVERSATION,
) {
    internal fun line() = ChatLine(role, text, id, createdAt, routeHistoryId)
    companion object {
        internal fun from(line: ChatLine) = AssistantMessage(line.id, line.role, line.text, line.createdAt, line.routeHistoryId)
    }
}

@Entity(tableName = "assistant_draft")
data class AssistantDraft(
    @PrimaryKey val id: Int = 1,
    val action: String,
    @ColumnInfo(defaultValue = "'legacy-draft'") val draftId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "''") val planId: String = draftId,
    val title: String = "",
    val placeType: String = "",
    val deadline: String = "",
    val destination: String = "",
    val mode: String = "",
    val knownPlaceId: String = "",
    val knownPlaceName: String = "",
    val knownLongitude: Double? = null,
    val knownLatitude: Double? = null,
    val knownAddress: String = "",
    val savedAt: Long,
    val expiresAt: Long = 0,
    val conversationId: String = MAIN_CONVERSATION,
) {
    fun valid(now: Long = System.currentTimeMillis()) =
        (action == "create_task" && title.isNotBlank()) ||
            (action == "plan_route" && destination.isNotBlank() && savedAt > 0)
    fun proposal(): JSONObject {
        val result = JSONObject().put("action", action).put("_draft_id", draftId).put("_plan_id", planId.ifBlank { draftId })
        if (action == "create_task") {
            result.put("title", title).put("place_type", placeType).put("deadline", deadline)
        } else {
            result.put("destination", destination).put("mode", mode)
            if (knownPlaceId.isNotBlank() && knownLongitude != null && knownLatitude != null) {
                result.put("known_destination", Place(knownPlaceId, knownPlaceName,
                    knownLongitude, knownLatitude, knownAddress).json())
            }
        }
        return result
    }
    companion object {
        fun from(value: JSONObject, savedAt: Long = System.currentTimeMillis()): AssistantDraft? {
            val action = value.optString("action")
            if (action !in listOf("create_task", "plan_route")) return null
            val known = value.optJSONObject("known_destination")
            val place = runCatching { known?.let(Place::from) }.getOrNull()
            val draft = AssistantDraft(action = action, draftId = value.optString("_draft_id").ifBlank { UUID.randomUUID().toString() }, planId = value.optString("_plan_id").ifBlank { value.optString("_draft_id") }.ifBlank { UUID.randomUUID().toString() }, title = value.optString("title"),
                placeType = value.optString("place_type"), deadline = value.optString("deadline"),
                destination = value.optString("destination"), mode = value.optString("mode"),
                knownPlaceId = place?.id.orEmpty(), knownPlaceName = place?.name.orEmpty(),
                knownLongitude = place?.longitude, knownLatitude = place?.latitude,
                knownAddress = place?.address.orEmpty(), savedAt = savedAt,
                expiresAt = if (action == "plan_route") savedAt + ROUTE_DRAFT_LIFETIME else 0)
            return draft.takeIf { it.valid() }
        }
    }
}

data class AssistantDay(val day: String, val count: Int)

@Dao
interface AssistantDao {
    @Query("SELECT * FROM assistant_clarification WHERE id = 1") suspend fun clarification(): AssistantClarification?
    @Query("SELECT * FROM assistant_place_choices ORDER BY position") suspend fun placeChoices(): List<AssistantPlaceChoice>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveClarification(value: AssistantClarification)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePlaceChoices(value: List<AssistantPlaceChoice>)
    @Query("DELETE FROM assistant_clarification") suspend fun clearClarification()
    @Query("DELETE FROM assistant_place_choices") suspend fun clearPlaceChoices()
    @Query("SELECT * FROM planning_draft WHERE id = 1") suspend fun planningDraft(): PlanningDraft?
    @Query("SELECT * FROM planning_draft_tasks ORDER BY id") suspend fun planningTasks(): List<PlanningDraftTask>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePlanningDraft(value: PlanningDraft)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePlanningTasks(value: List<PlanningDraftTask>)
    @Query("DELETE FROM planning_draft") suspend fun clearPlanningDraft()
    @Query("DELETE FROM planning_draft_tasks") suspend fun clearPlanningTasks()
    @Query("SELECT strftime('%Y-%m-%d', createdAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count FROM assistant_messages GROUP BY day ORDER BY day DESC")
    suspend fun days(): List<AssistantDay>
    @Query("SELECT * FROM assistant_messages WHERE createdAt >= :start AND createdAt < :end ORDER BY createdAt, rowid")
    suspend fun messagesInDay(start: Long, end: Long): List<AssistantMessage>

    @Query("SELECT * FROM assistant_messages ORDER BY createdAt DESC, rowid DESC LIMIT 500")
    suspend fun recentMessages(): List<AssistantMessage>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addMessages(messages: List<AssistantMessage>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addMessage(message: AssistantMessage)
    @Query("SELECT * FROM assistant_draft WHERE id = 1") suspend fun draft(): AssistantDraft?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDraft(draft: AssistantDraft)
    @Query("DELETE FROM assistant_draft WHERE id = 1") suspend fun clearDraft()
}

/** Imports the old preference records exactly once; SQLite is authoritative afterwards. */
internal class AssistantStore(private val database: TaskDatabase, private val legacy: SharedPreferences) {
    var legacyWarning: Boolean = false
        private set
    suspend fun load(): Pair<List<ChatLine>, JSONObject?> {
        val rawLines = runCatching { legacy.getString("lines", null) }.getOrNull()
        val legacyLines = rawLines?.let { raw -> runCatching {
            val saved = JSONArray(raw)
            (0 until saved.length()).mapNotNull { index ->
                val item = saved.optJSONObject(index) ?: return@mapNotNull null
                val role = item.optString("role")
                val text = item.optString("text")
                if (role !in listOf("user", "assistant") || text.isBlank()) null
                else {
                    val rawStamp = item.optLong("created_at", 0)
                    val stamp = rawStamp.takeIf { it > 0 } ?: System.currentTimeMillis()
                    val id = item.optString("id").ifBlank {
                        UUID.nameUUIDFromBytes("$index|$role|$rawStamp|$text".toByteArray(Charsets.UTF_8)).toString()
                    }
                    ChatLine(role, text, id, stamp,
                        item.optString("route_history_id").takeUnless { it.isBlank() || it == "null" })
                }
            }
        }.getOrNull() }
        val legacyLineCount = rawLines?.let { runCatching { JSONArray(it).length() }.getOrNull() }
        val completeLegacyLines = legacyLines != null && legacyLines.size == legacyLineCount
        val rawDraft = runCatching { legacy.getString("proposal", null) }.getOrNull()
        val parsedDraft = rawDraft?.let { runCatching { JSONObject(it) }.getOrNull() }
        val legacyDraft = parsedDraft?.let {
            AssistantDraft.from(it, runCatching { legacy.getLong("proposal_saved_at", 0) }.getOrDefault(0L))
        }
        legacyWarning = (legacy.contains("lines") && !completeLegacyLines) ||
            (legacy.contains("proposal") && parsedDraft == null)
        database.withTransaction {
            val dao = database.assistant()
            if (legacyLines != null) dao.addMessages(legacyLines.map(AssistantMessage::from))
            if (dao.draft() == null && legacyDraft != null) dao.saveDraft(legacyDraft)
        }
        if (legacyLines != null || parsedDraft != null) {
            val editor = legacy.edit()
            if (completeLegacyLines) editor.remove("lines")
            if (parsedDraft != null) editor.remove("proposal").remove("proposal_saved_at")
            check(editor.commit()) { "旧对话记录迁移后清理失败" }
        }
        val messages = database.assistant().recentMessages().asReversed().map(AssistantMessage::line)
        val draft = database.assistant().draft()?.takeIf { it.valid() }
        if (draft == null) database.assistant().clearDraft()
        return messages to draft?.proposal()
    }
    suspend fun days() = database.assistant().days()
    suspend fun day(day: java.time.LocalDate): List<ChatLine> {
        val zone = java.time.ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return database.withTransaction {
            val sessions = mutableMapOf<String, String?>()
            val outcomes = mutableMapOf<String, String?>()
            database.assistant().messagesInDay(start, end).map { message ->
                val id = message.routeHistoryId
                val session = if (id == null) null else sessions.getOrPut(id) {
                    database.trips().historyById(id)?.sessionId
                }
                val state = session?.let { outcomes.getOrPut(it) { database.trips().latestStateForSession(it) } }
                message.line().copy(routeSessionId = session, routeAbandoned = state == "CANCELLED",
                    routePlanId = id?.let { database.trips().historyById(it)?.planId?.takeIf(String::isNotBlank) })
            }
        }
    }
    suspend fun add(line: ChatLine) = database.assistant().addMessage(AssistantMessage.from(line))
    suspend fun planningChoices(): PlanningChoices? = database.withTransaction {
        val dao = database.assistant()
        val value = dao.planningDraft() ?: return@withTransaction null
        val intent = dao.draft()
        if (intent?.action != "plan_route" || intent.draftId != value.draftId || intent.planId != value.planId || intent.destination != value.destination || intent.mode != value.mode)
            return@withTransaction null
        PlanningChoices(value, dao.planningTasks())
    }
    suspend fun saveChoices(value: PlanningChoices) = database.withTransaction {
        val dao = database.assistant()
        val intent = dao.draft()
        if (intent?.action == "plan_route" && intent.draftId == value.draft.draftId && intent.planId == value.draft.planId && intent.destination == value.draft.destination && intent.mode == value.draft.mode) {
            dao.savePlanningDraft(value.draft)
            dao.clearPlanningTasks()
            dao.savePlanningTasks(value.tasks)
        }
    }
    suspend fun clarification(): ClarificationState? = database.withTransaction {
        val dao = database.assistant()
        val intent = dao.draft() ?: return@withTransaction null
        val question = dao.clarification() ?: return@withTransaction null
        if (question.draftId != intent.draftId || question.planId != intent.planId) return@withTransaction null
        ClarificationState(question, dao.placeChoices().filter { it.draftId == intent.draftId })
    }
    suspend fun saveClarification(value: ClarificationState) = database.withTransaction {
        val dao = database.assistant()
        val intent = dao.draft()
        if (intent?.draftId == value.question.draftId && intent.planId == value.question.planId) {
            dao.saveClarification(value.question)
            dao.clearPlaceChoices()
            dao.savePlaceChoices(value.choices)
        }
    }
    suspend fun setDraft(value: JSONObject?) {
        val draft = value?.let(AssistantDraft::from)
        if (value != null && draft == null) error("无效的待确认方案")
        database.withTransaction {
            val dao = database.assistant()
            val old = dao.draft()
            if (draft == null || draft.action != "plan_route" || old?.draftId != draft.draftId || old.destination != draft.destination || old.mode != draft.mode) {
                dao.clearPlanningDraft(); dao.clearPlanningTasks()
            }
            dao.clearClarification(); dao.clearPlaceChoices()
            if (draft == null) dao.clearDraft() else {
                dao.saveDraft(draft)
                val kind = when {
                    draft.action == "create_task" -> if (requiresFixedPlace(draft.placeType)) "TASK_PLACE" else "CONFIRM_TASK"
                    draft.mode.isBlank() -> "MODE"
                    else -> "CONFIRM_ROUTE"
                }
                dao.saveClarification(AssistantClarification(draftId = draft.draftId, planId = draft.planId,
                    kind = kind, prompt = clarificationPrompt(kind), destination = draft.destination,
                    mode = draft.mode, savedAt = draft.savedAt))
            }
        }
    }
}
