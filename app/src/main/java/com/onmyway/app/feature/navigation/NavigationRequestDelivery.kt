package com.onmyway.app.feature.navigation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.onmyway.app.data.local.AssistantRequestJournal
import com.onmyway.app.data.local.PendingAssistantRequest
import com.onmyway.app.data.remote.Api
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal interface NavigationRequestTransport {
    suspend fun submit(server: String, body: JSONObject): JSONObject
    suspend fun status(server: String, body: JSONObject): JSONObject
}

internal object HttpNavigationRequestTransport : NavigationRequestTransport {
    override suspend fun submit(server: String, body: JSONObject) = Api(server).navigationRequest(body)
    override suspend fun status(server: String, body: JSONObject) = Api(server).navigationStatus(body)
}

/** A delivered proposal still requires context validation and explicit local consumption. */
internal sealed interface NavigationDeliveryResult {
    data class Ready(val result: JSONObject) : NavigationDeliveryResult
    data class Waiting(val state: String, val errorStatus: Int? = null) : NavigationDeliveryResult
}

/**
 * Device-local delivery and navigation context, independent of the chat request slot.
 * The caller validates proposals; consuming one commits its context and clears delivery atomically.
 */
internal class NavigationRequestDelivery(
    context: Context,
    name: String = "onmyway-navigation-requests.db",
    private val transport: NavigationRequestTransport = HttpNavigationRequestTransport,
) : Closeable {
    private val journal = AssistantRequestJournal(context, name)
    private val dbMutex = Mutex()
    private val deliveryMutex = Mutex()

    suspend fun pending(): PendingAssistantRequest? = withContext(Dispatchers.IO) {
        dbMutex.withLock { journal.pending() }
    }

    suspend fun create(request: PendingAssistantRequest) = withContext(Dispatchers.IO) {
        dbMutex.withLock { journal.create(request) }
    }

    suspend fun storeResult(requestId: String, result: JSONObject): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            dbMutex.withLock { journal.saveResult(requestId, result.toString()) }
        }

    /** Clear a request after explicit cancellation or discarding a proposal for obsolete context. */
    suspend fun finish(requestId: String): Boolean = withContext(Dispatchers.IO) {
        dbMutex.withLock { journal.finish(requestId) }
    }

    suspend fun loadContext(): JSONObject? = withContext(Dispatchers.IO) {
        dbMutex.withLock {
            contextDatabase().rawQuery("SELECT snapshot FROM navigation_context WHERE slot = 1", null).use { cursor ->
                if (cursor.moveToFirst()) JSONObject(cursor.getString(0)) else null
            }
        }
    }

    /** Persist ordinary navigation changes without waiting for an in-flight network request. */
    suspend fun saveContext(snapshot: JSONObject?) = withContext(Dispatchers.IO) {
        dbMutex.withLock { writeContext(contextDatabase(), snapshot) }
    }

    /** Commit a validated proposal's resulting context and its consumption in one transaction. */
    suspend fun consume(requestId: String, snapshot: JSONObject): Boolean = withContext(Dispatchers.IO) {
        dbMutex.withLock {
            val db = contextDatabase()
            db.beginTransaction()
            try {
                if (journal.pending()?.requestId != requestId) return@withLock false
                writeContext(db, snapshot)
                check(journal.finish(requestId)) { "导航请求已被替换，无法保存上下文。" }
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun contextDatabase(): SQLiteDatabase = journal.writableDatabase.apply {
        // Also initializes the table for databases created by earlier navigation delivery builds.
        execSQL("""
            CREATE TABLE IF NOT EXISTS navigation_context (
                slot INTEGER PRIMARY KEY CHECK(slot = 1),
                snapshot TEXT NOT NULL
            )
        """.trimIndent())
    }

    private fun writeContext(db: SQLiteDatabase, snapshot: JSONObject?) {
        if (snapshot == null) {
            db.delete("navigation_context", "slot = 1", null)
        } else {
            db.insertWithOnConflict("navigation_context", null, ContentValues().apply {
                put("slot", 1)
                put("snapshot", snapshot.toString())
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "无法保存导航上下文。" } }
        }
    }

    /** Persist before the first network call; repeated submissions recover the same request. */
    suspend fun submit(request: PendingAssistantRequest): NavigationDeliveryResult = deliveryMutex.withLock {
        val current = pending()
        if (current == null) {
            create(request)
            request.resultJson?.let { return@withLock NavigationDeliveryResult.Ready(JSONObject(it)) }
            send(request)
        } else {
            check(sameRequest(current, request)) { "已有待处理的导航请求，请先恢复或取消。" }
            obtainCurrent(current)
        }
    }

    /** Check server status before resending a request whose delivery may already have succeeded. */
    suspend fun obtain(request: PendingAssistantRequest): NavigationDeliveryResult = deliveryMutex.withLock {
        val current = pending()
        if (current == null || !sameRequest(current, request)) {
            NavigationDeliveryResult.Waiting("superseded")
        } else {
            obtainCurrent(current)
        }
    }

    private suspend fun obtainCurrent(request: PendingAssistantRequest): NavigationDeliveryResult {
        request.resultJson?.let { return NavigationDeliveryResult.Ready(JSONObject(it)) }
        val status = transport.status(request.server, JSONObject(request.bodyJson))
        check(status.opt("request_id") is String && status.optString("request_id") == request.requestId &&
            status.opt("operation") == "navigation") { "导航请求状态与原请求不一致。" }
        return when (val state = status.optString("state")) {
            "completed" -> {
                val result = status.optJSONObject("result")
                    ?: return NavigationDeliveryResult.Waiting("unresolved")
                saveDelivered(request, result)
            }
            "not_found" -> {
                // Cancellation or replacement while status was in flight must not start a submission.
                if (pending()?.let { sameRequest(it, request) } == true) send(request)
                else NavigationDeliveryResult.Waiting("superseded")
            }
            "running", "failed", "unresolved" -> NavigationDeliveryResult.Waiting(
                state,
                (status.opt("error_status") as? Number)?.toInt()?.takeIf { it in 400..599 },
            )
            else -> NavigationDeliveryResult.Waiting("unresolved")
        }
    }

    private suspend fun send(request: PendingAssistantRequest): NavigationDeliveryResult =
        saveDelivered(request, transport.submit(request.server, JSONObject(request.bodyJson)))

    private suspend fun saveDelivered(request: PendingAssistantRequest, result: JSONObject): NavigationDeliveryResult =
        if (storeResult(request.requestId, result)) NavigationDeliveryResult.Ready(result)
        else NavigationDeliveryResult.Waiting("superseded")

    private fun sameRequest(first: PendingAssistantRequest, second: PendingAssistantRequest): Boolean =
        first.copy(resultJson = null) == second.copy(resultJson = null)

    override fun close() = journal.close()
}
