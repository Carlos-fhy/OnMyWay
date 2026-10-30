package com.onmyway.app.data.event

import com.onmyway.app.data.local.dao.ConfirmationOutboxDao
import com.onmyway.app.data.local.entity.ConfirmationOutbox
import com.onmyway.app.data.remote.ApiHttpException
import com.onmyway.app.data.sync.BackupCredential
import kotlinx.coroutines.CancellationException

internal fun interface ConfirmationTransport {
    suspend fun deliver(receipt: ConfirmationOutbox)
}

internal class HttpConfirmationTransport(credential: BackupCredential) : ConfirmationTransport {
    private val api = com.onmyway.app.data.remote.Api(credential.server)
    private val token = credential.token

    override suspend fun deliver(receipt: ConfirmationOutbox) {
        api.sendConfirmation(token, receipt)
    }
}

/**
 * Drains confirmation receipts in confirmation order. Conflicting or stale
 * versions are permanent rejections; network failures stop the pass so later
 * confirmations cannot overtake a failed one.
 */
internal class ConfirmationOutboxSender(
    private val dao: ConfirmationOutboxDao,
    private val transport: ConfirmationTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Returns true when WorkManager should retry the remaining receipts. */
    suspend fun flush(limit: Int = 20): Boolean {
        val pending = dao.pending(limit)
        for (receipt in pending) {
            try {
                transport.deliver(receipt)
                dao.markDelivered(receipt.receiptId, now())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: ConfirmationProtocolException) {
                dao.markTerminal(receipt.receiptId, now(), errorText(invalid))
            } catch (http: ApiHttpException) {
                if (http.status in TERMINAL_STATUSES) {
                    dao.markTerminal(receipt.receiptId, now(), errorText(http))
                    continue
                }
                dao.markFailed(receipt.receiptId, now(), errorText(http))
                return true
            } catch (error: Exception) {
                dao.markFailed(receipt.receiptId, now(), errorText(error))
                return true
            }
        }
        return dao.pendingCount() > 0
    }

    private fun errorText(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)

    private companion object {
        // A changed/stale receipt cannot be repaired by resending the same bytes.
        val TERMINAL_STATUSES = setOf(400, 409, 413, 422)
        const val MAX_ERROR_CHARS = 500
    }
}
