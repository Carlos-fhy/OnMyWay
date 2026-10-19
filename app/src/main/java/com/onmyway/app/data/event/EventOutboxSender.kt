package com.onmyway.app.data.event

import com.onmyway.app.data.local.dao.BusinessEventOutboxDao
import com.onmyway.app.data.local.entity.BusinessEventOutbox
import com.onmyway.app.data.remote.ApiHttpException
import com.onmyway.app.data.sync.BackupCredential
import kotlinx.coroutines.CancellationException

internal fun interface EventTransport {
    suspend fun deliver(event: BusinessEventOutbox)
}

internal class HttpEventTransport(credential: BackupCredential) : EventTransport {
    private val api = com.onmyway.app.data.remote.Api(credential.server)
    private val token = credential.token

    override suspend fun deliver(event: BusinessEventOutbox) {
        api.sendEvent(token, event.toRequestJson())
    }
}

/**
 * Drains the outbox in observation order. A transport failure stops the pass
 * so later events cannot overtake the failed one. A permanent envelope
 * rejection is marked terminal and the following events still proceed.
 */
internal class EventOutboxSender(
    private val dao: BusinessEventOutboxDao,
    private val transport: EventTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Returns true when WorkManager should retry the remaining events. */
    suspend fun flush(limit: Int = 20): Boolean {
        val pending = dao.pending(limit)
        for (event in pending) {
            try {
                transport.deliver(event)
                dao.markDelivered(event.eventId, now())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (http: ApiHttpException) {
                if (http.status in TERMINAL_STATUSES) {
                    dao.markTerminal(event.eventId, now(), errorText(http))
                    continue
                }
                dao.markFailed(event.eventId, now(), errorText(http))
                return true
            } catch (error: Exception) {
                dao.markFailed(event.eventId, now(), errorText(error))
                return true
            }
        }
        return dao.pendingCount() > 0
    }

    private fun errorText(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_CHARS)

    private companion object {
        // Retrying the same envelope cannot repair a contract rejection.
        val TERMINAL_STATUSES = setOf(400, 409, 413, 422)
        const val MAX_ERROR_CHARS = 500
    }
}
