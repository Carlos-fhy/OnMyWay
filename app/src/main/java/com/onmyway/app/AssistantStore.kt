package com.onmyway.app

import android.content.SharedPreferences
import androidx.room.*
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
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
            action == "create_tasks" ||
            (action == "plan_route" && destination.isNotBlank() && savedAt > 0)
    fun proposal(): JSONObject {
        val result = JSONObject().put("action", action).put("_draft_id", draftId).put("_plan_id", planId.ifBlank { draftId })
        if (action == "create_task") {
            result.put("title", title).put("place_type", placeType).put("deadline", deadline)
        } else if (action == "plan_route") {
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
            if (action !in listOf("create_task", "create_tasks", "plan_route")) return null
            val known = value.optJSONObject("known_destination")
            val place = runCatching { known?.let(Place::from) }.getOrNull()
            val draftId = value.optString("_draft_id").ifBlank { UUID.randomUUID().toString() }
            val draft = AssistantDraft(action = action, draftId = draftId, planId = value.optString("_plan_id").ifBlank { draftId }, title = value.optString("title"),
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
    @Query("SELECT * FROM assistant_task_drafts ORDER BY position, id") suspend fun draftItems(): List<AssistantDraftItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveDraftItems(items: List<AssistantDraftItem>)
    @Query("DELETE FROM assistant_task_drafts") suspend fun clearDraftItems()
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
    @Query("SELECT strftime('%Y-%m-%d', createdAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count FROM assistant_messages WHERE role IN ('user', 'assistant') GROUP BY day ORDER BY day DESC")
    suspend fun days(): List<AssistantDay>
    @Query("SELECT * FROM assistant_messages WHERE createdAt >= :start AND createdAt < :end AND role IN ('user', 'assistant') ORDER BY createdAt, rowid")
    suspend fun messagesInDay(start: Long, end: Long): List<AssistantMessage>

    @Query("SELECT * FROM assistant_messages WHERE role IN ('user', 'assistant') ORDER BY createdAt DESC, rowid DESC LIMIT 500")
    suspend fun recentMessages(): List<AssistantMessage>
    @Query("SELECT EXISTS(SELECT 1 FROM assistant_messages WHERE id = :id)")
    suspend fun hasMessage(id: String): Boolean
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
            if (dao.draft() == null && legacyDraft != null) {
                if (legacyDraft.action == "create_tasks") setDraft(parsedDraft) else dao.saveDraft(legacyDraft)
            }
        }
        if (legacyLines != null || parsedDraft != null) {
            val editor = legacy.edit()
            if (completeLegacyLines) editor.remove("lines")
            if (parsedDraft != null) editor.remove("proposal").remove("proposal_saved_at")
            check(editor.commit()) { "旧对话记录迁移后清理失败" }
        }
        return database.withTransaction {
            val dao = database.assistant()
            val messages = dao.recentMessages().asReversed().map(AssistantMessage::line)
            val draft = dao.draft()?.takeIf { it.valid() }
            val storedItems = dao.draftItems()
            val items = storedItems.filter { it.planId == draft?.planId }
            if (draft == null || draft.action == "create_tasks" && items.isEmpty()) {
                dao.clearDraft(); dao.clearDraftItems()
                dao.clearClarification(); dao.clearPlaceChoices()
                dao.clearPlanningDraft(); dao.clearPlanningTasks()
                messages to null
            } else {
                if (draft.action != "create_tasks" || storedItems.size != items.size) {
                    dao.clearDraftItems()
                    if (draft.action == "create_tasks") dao.saveDraftItems(items)
                }
                messages to draft.proposal().also {
                    if (draft.action == "create_tasks") it.put("tasks", JSONArray(items.map(AssistantDraftItem::json)))
                }
            }
        }
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
    suspend fun pendingTasks(): List<Errand> = database.tasks().observe().first().filter { it.status == "pending" }
    suspend fun responseApplied(requestId: String): Boolean =
        database.assistant().hasMessage(responseMessageId(requestId))

    /** The confirmation message is the durable receipt, including after task deletion. */
    suspend fun confirmTask(
        expectedDraftId: String,
        task: Errand,
        successText: String,
        onCreated: suspend (Errand) -> Unit,
    ): Boolean {
        require(expectedDraftId.isNotBlank()) { "待确认任务已失效，请重新确认。" }
        val receiptId = "assistant-task-confirmed-$expectedDraftId"
        val taskId = UUID.nameUUIDFromBytes("assistant-task:$expectedDraftId".toByteArray(Charsets.UTF_8)).toString()
        return database.withTransaction {
            val dao = database.assistant()
            // A replay must neither recreate a deleted task nor consume a newer draft.
            if (dao.hasMessage(receiptId)) return@withTransaction false
            val draft = dao.draft()
            check(draft?.draftId == expectedDraftId && draft.action == "create_task") {
                "待确认任务已变化，请关闭旧编辑页并重新确认。"
            }
            require(task.title.isNotBlank()) { "请填写任务名称。" }
            require(!requiresFixedPlace(task.placeType) || task.place() != null) { "请指定实际领取地点" }
            check(database.tasks().get(taskId) == null) { "任务确认记录不完整，请重新打开助手。" }
            val now = System.currentTimeMillis()
            val created = task.copy(id = taskId, status = "pending", createdAt = now, updatedAt = now,
                completedAt = 0, lastRemindedAt = 0, reminderCount = 0, reminderPending = false)
            database.tasks().save(created)
            onCreated(created)
            dao.clearPlanningTasks(); dao.clearPlanningDraft()
            dao.clearPlaceChoices(); dao.clearClarification(); dao.clearDraftItems(); dao.clearDraft()
            dao.addMessage(AssistantMessage(receiptId, "assistant", successText, now, null))
            true
        }
    }

    /** All selected versions are checked before any task is created; unselected rows stay editable. */
    suspend fun confirmTasks(
        expectedDraftId: String,
        selections: Map<String, Int>,
        successText: String,
        onCreated: suspend (Errand) -> Unit,
    ): Boolean {
        require(expectedDraftId.isNotBlank() && selections.isNotEmpty()) { "请选择要确认的待办" }
        val receiptId = "assistant-tasks-confirmed-$expectedDraftId"
        return database.withTransaction {
            val dao = database.assistant()
            if (dao.hasMessage(receiptId)) return@withTransaction false
            val draft = dao.draft()
            check(draft?.draftId == expectedDraftId && draft.action == "create_tasks") {
                "待确认任务已变化，请重新选择后确认。"
            }
            val items = dao.draftItems().filter { it.planId == draft.planId }
            val selected = selections.map { (id, version) ->
                val item = items.singleOrNull { it.id == id }
                check(item != null && item.version == version) { "任务条目已变化，请重新选择后确认。" }
                AssistantDraftItem.fromTask(item.id, item.planId, item.position, item.version, item.task())
                require(!requiresFixedPlace(item.placeType) || item.task().place() != null) {
                    "请先为“${item.title}”指定实际领取地点"
                }
                val identity = taskItemIdentity(item)
                check(!dao.hasMessage("assistant-task-item-confirmed-$identity")) { "这项待办已确认，请刷新草稿。" }
                check(database.tasks().get(identity) == null) { "任务确认记录不完整，请重新打开助手。" }
                item
            }
            val now = System.currentTimeMillis()
            selected.forEach { item ->
                val taskId = taskItemIdentity(item)
                val created = item.task().copy(id = taskId, createdAt = now, updatedAt = now)
                database.tasks().save(created)
                onCreated(created)
                // Internal receipts survive deletion and backup, but never enter the conversation.
                dao.addMessage(AssistantMessage("assistant-task-item-confirmed-$taskId", "receipt", "", now, null))
            }
            val remaining = items.filterNot { it.id in selections }
            val replacement = if (remaining.isEmpty()) null else draft.proposal()
                .put("_draft_id", UUID.randomUUID().toString())
                .put("tasks", JSONArray(remaining.map(AssistantDraftItem::json)))
            setDraft(replacement)
            dao.addMessage(AssistantMessage(receiptId, "assistant", successText, now, null))
            true
        }
    }

    /** Editing only changes the proposal. Even a fixed-place task may remain incomplete here. */
    suspend fun updateDraftItem(
        expectedDraftId: String,
        itemId: String,
        expectedVersion: Int,
        task: Errand,
    ): Boolean = database.withTransaction {
        val dao = database.assistant()
        val draft = dao.draft()
        check(draft?.draftId == expectedDraftId && draft.action == "create_tasks") {
            "待确认任务已变化，请关闭旧编辑页并重新编辑。"
        }
        val items = dao.draftItems().filter { it.planId == draft.planId }
        val item = items.singleOrNull { it.id == itemId }
        check(item != null && item.version == expectedVersion && expectedVersion < Int.MAX_VALUE) {
            "任务条目已变化，请关闭旧编辑页并重新编辑。"
        }
        val changed = AssistantDraftItem.fromTask(item.id, item.planId, item.position, item.version + 1, task)
        setDraft(draft.proposal().put("_draft_id", UUID.randomUUID().toString())
            .put("tasks", JSONArray(items.map { if (it.id == itemId) changed.json() else it.json() })))
        true
    }

    private fun taskItemIdentity(item: AssistantDraftItem): String =
        UUID.nameUUIDFromBytes("assistant-task-item:${item.planId}:${item.id}".toByteArray(Charsets.UTF_8)).toString()

    /** The reply is the durable receipt; messages and its resulting draft commit together. */
    suspend fun applyResponse(
        requestId: String,
        expectedDraftId: String?,
        userLine: ChatLine,
        replyLine: ChatLine,
        replacement: JSONObject?,
        replaceDraft: Boolean,
        expectedTripContext: String? = null,
    ): Boolean {
        val responseId = responseMessageId(requestId)
        return database.withTransaction {
            val dao = database.assistant()
            // Check the receipt first: replaying an old response must never clear a newer draft.
            if (dao.hasMessage(responseId)) return@withTransaction false
            if (dao.draft()?.draftId != expectedDraftId) return@withTransaction false
            if (expectedTripContext != null && assistantTripContext(database.trips().load()) != expectedTripContext)
                return@withTransaction false
            dao.addMessages(listOf(AssistantMessage.from(userLine)))
            dao.addMessage(AssistantMessage.from(replyLine.copy(id = responseId, role = "assistant")))
            if (replaceDraft) setDraft(replacement)
            true
        }
    }

    private fun responseMessageId(requestId: String): String {
        require(runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)) {
            "Request ID must be a canonical UUID"
        }
        return "agent-response-$requestId"
    }

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
        val items = if (draft?.action == "create_tasks") {
            val array = value!!.getJSONArray("tasks")
            require(array.length() in 1..10) { "待确认任务数量无效" }
            (0 until array.length()).map { AssistantDraftItem.from(array.getJSONObject(it), draft.planId, it) }
                .also { rows -> require(rows.map { it.id }.distinct().size == rows.size) { "待办条目标识重复" } }
        } else emptyList()
        database.withTransaction {
            val dao = database.assistant()
            val old = dao.draft()
            if (draft == null || draft.action != "plan_route" || old?.draftId != draft.draftId || old.destination != draft.destination || old.mode != draft.mode) {
                dao.clearPlanningDraft(); dao.clearPlanningTasks()
            }
            dao.clearClarification(); dao.clearPlaceChoices()
            dao.clearDraftItems()
            if (draft == null) dao.clearDraft() else {
                dao.saveDraft(draft)
                dao.saveDraftItems(items)
                val kind = when {
                    draft.action == "create_task" -> if (requiresFixedPlace(draft.placeType)) "TASK_PLACE" else "CONFIRM_TASK"
                    draft.action == "create_tasks" -> "CONFIRM_TASKS"
                    draft.mode.isBlank() -> "MODE"
                    else -> "CONFIRM_ROUTE"
                }
                dao.saveClarification(AssistantClarification(draftId = draft.draftId, planId = draft.planId,
                    kind = kind, prompt = if (kind == "CONFIRM_TASKS") "请逐项检查待办，选择要保存的事项。" else clarificationPrompt(kind), destination = draft.destination,
                    mode = draft.mode, savedAt = draft.savedAt))
            }
        }
    }
}
