package com.onmyway.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.onmyway.app.core.state.PendingPlanningRequest
import com.onmyway.app.core.state.PlanningExecution
import com.onmyway.app.core.state.PlanningRequestOwner
import com.onmyway.app.core.state.PlanningRequestStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Device-local delivery state; route confirmation and execution evidence remain authoritative in Room. */
internal class PlanningRequestJournal(context: Context, name: String = "onmyway-planning-requests.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1), PlanningRequestStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending (slot INTEGER PRIMARY KEY CHECK(slot = 1), run_id TEXT NOT NULL, envelope TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported planning journal upgrade")

    override suspend fun pending(): PendingPlanningRequest? = withContext(Dispatchers.IO) { read() }
    override suspend fun create(request: PendingPlanningRequest, replaceRunId: String?) = withContext(Dispatchers.IO) {
        require(request.operation in setOf("plan", "recommend") && request.createdAt > 0 && request.correlationId.isNotBlank())
        require(request.owner.kind in setOf("manual", "assistant", "navigation"))
        require(PlanningExecution.identity(request.server, request.operation, request.bodyJson) == request.runId)
        val encoded = encode(request)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "规划请求超出保存上限。" }
        synchronized(this@PlanningRequestJournal) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val previous = read()
                check(previous == null || previous == request ||
                    (previous.runId == replaceRunId && previous.runId != request.runId)) { "已有待核对规划，请先处理原请求。" }
                db.insertWithOnConflict("pending", null, ContentValues().apply {
                    put("slot", 1); put("run_id", request.runId); put("envelope", encoded)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "规划请求保存失败。" } }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    override suspend fun finish(runId: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@PlanningRequestJournal) {
            writableDatabase.delete("pending", "slot = 1 AND run_id = ?", arrayOf(runId)) == 1
        }
    }
    @Synchronized private fun read(): PendingPlanningRequest? = readableDatabase.rawQuery(
        "SELECT envelope FROM pending WHERE slot = 1", null,
    ).use { cursor -> if (!cursor.moveToFirst()) null else decode(cursor.getString(0)) }

    private fun encode(value: PendingPlanningRequest): String = JSONObject()
        .put("run_id", value.runId).put("server", value.server).put("body", value.bodyJson)
        .put("operation", value.operation).put("correlation_id", value.correlationId).put("created_at", value.createdAt)
        .put("owner", JSONObject().put("kind", value.owner.kind).put("conversation_id", value.owner.conversationId)
            .put("draft_id", value.owner.draftId).put("plan_id", value.owner.planId)
            .put("session_id", value.owner.sessionId).put("plan_version", value.owner.planVersion)).toString()

    private fun decode(encoded: String): PendingPlanningRequest {
        val data = JSONObject(encoded)
        val owner = data.getJSONObject("owner")
        return PendingPlanningRequest(data.getString("run_id"), data.getString("server"), data.getString("body"),
            data.getString("operation"), data.getString("correlation_id"),
            PlanningRequestOwner(owner.getString("kind"), owner.getString("conversation_id"), owner.getString("draft_id"),
                owner.getString("plan_id"), owner.getString("session_id"), owner.getInt("plan_version")), data.getLong("created_at"))
    }
}
