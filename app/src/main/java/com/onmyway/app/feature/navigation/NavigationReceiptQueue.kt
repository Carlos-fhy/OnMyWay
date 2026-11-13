package com.onmyway.app.feature.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Process-owned writes remain independent of Activity and controller lifetimes. */
internal class NavigationReceiptQueue(
    private val scope: CoroutineScope,
    private val retryDelay: suspend () -> Unit = { delay(1_000) },
) {
    private val pending = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { pending.cancel() }
        scope.launch {
            for (operation in pending) {
                while (isActive) {
                    try {
                        operation()
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Retry the same captured observation; never restart SDK navigation here.
                        retryDelay()
                    }
                }
            }
        }
    }

    fun submit(operation: suspend () -> Unit) {
        check(pending.trySend(operation).isSuccess) { "导航回执队列已关闭" }
    }

    suspend fun drain() {
        val done = CompletableDeferred<Unit>()
        submit { done.complete(Unit) }
        done.await()
    }
}
