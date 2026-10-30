package com.onmyway.app.data.event

import android.content.Context
import androidx.room.withTransaction
import com.onmyway.app.data.local.TaskDatabase
import com.onmyway.app.data.remote.Api
import com.onmyway.app.data.remote.ApiHttpException
import com.onmyway.app.data.sync.BackupCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class HarnessQueueStatus(
    val pendingConfirmations: Int,
    val pendingEvents: Int,
    val terminalConfirmations: Int,
    val terminalEvents: Int,
)

internal data class HarnessStatus(
    val queue: HarnessQueueStatus,
    val remote: HarnessState? = null,
    val message: String = "",
)

/** Read-only status refresh; delivery and recovery remain owned by their existing workers. */
internal class HarnessStatusRepository(context: Context) {
    private val application = context.applicationContext
    private val database = TaskDatabase.get(application)

    suspend fun read(server: String): HarnessStatus = withContext(Dispatchers.IO) {
        val (queue, latest) = database.withTransaction {
            val confirmations = database.confirmations()
            val events = database.businessEvents()
            HarnessQueueStatus(confirmations.pendingCount(), events.pendingCount(),
                confirmations.terminalCount(), events.terminalCount()) to confirmations.latest()
        }
        val correlationId = EventCorrelation(application).peekActive() ?: latest?.correlationId
            ?: return@withContext HarnessStatus(queue, message = "尚无可查询的确认记录。")
        val credential = BackupCredentials.load(application)
            ?: return@withContext HarnessStatus(queue, message = "连接电脑数据服务后可查询确认与导航状态。")
        if (runCatching { Api.validateBase(server) }.getOrNull() != credential.server) {
            return@withContext HarnessStatus(queue, message = "请将电脑数据服务连接到已保存的规划服务地址。")
        }
        try {
            HarnessStatus(queue, Api(credential.server).harnessState(credential.token, correlationId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (http: ApiHttpException) {
            val detail = when (http.status) {
                401, 403 -> "状态查询未获授权，请检查数据服务口令与连接。"
                404 -> "电脑服务尚不支持状态查询，请更新后端。"
                409 -> "确认版本存在冲突，请重新规划并确认。"
                else -> "状态暂时无法读取，请检查电脑服务连接后刷新。"
            }
            HarnessStatus(queue, message = detail)
        } catch (_: HarnessStateProtocolException) {
            HarnessStatus(queue, message = "状态回包无法核对，请检查电脑服务版本。")
        } catch (_: Exception) {
            HarnessStatus(queue, message = "状态暂时无法读取，请检查电脑服务连接后刷新。")
        }
    }
}
