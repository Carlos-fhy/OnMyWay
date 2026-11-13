package com.onmyway.app.feature.navigation

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NavigationReceiptQueueTest {
    @Test fun immediateArrivalIsRetainedWhileStartPersistenceIsBlocked() = runBlocking {
        val runtime = CoroutineScope(coroutineContext + Job())
        try {
            var phase = NavigationPhase.STARTING
            val startedWriting = CompletableDeferred<Unit>()
            val releaseStart = CompletableDeferred<Unit>()
            val records = mutableListOf<String>()
            val queue = NavigationReceiptQueue(runtime)
            queue.submit {
                startedWriting.complete(Unit)
                releaseStart.await()
                records += "started"
                if (shouldPauseRecordedNavigationStart(phase, ownsAttempt = true, uncertain = false)) records += "paused"
                if (phase == NavigationPhase.STARTING) phase = NavigationPhase.NAVIGATING
            }
            withTimeout(2_000) { startedWriting.await() }
            assertTrue(acceptsNavigationArrival(phase, startObserved = true))
            phase = NavigationPhase.ARRIVED
            queue.submit { records += "arrived" }
            releaseStart.complete(Unit)
            withTimeout(2_000) { queue.drain() }
            assertEquals(listOf("started", "arrived"), records)
            assertEquals(NavigationPhase.ARRIVED, phase)
        } finally { runtime.cancel() }
    }

    @Test fun receiptOutlivesPageAndControllerScope() = runBlocking {
        val page = CoroutineScope(coroutineContext + Job())
        val runtime = CoroutineScope(coroutineContext + Job())
        try {
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val saved = CompletableDeferred<Unit>()
            val queue = NavigationReceiptQueue(runtime)
            queue.submit { writing.complete(Unit); release.await(); saved.complete(Unit) }
            withTimeout(2_000) { writing.await() }
            page.cancel()
            assertFalse(saved.isCompleted)
            release.complete(Unit)
            withTimeout(2_000) { saved.await() }
        } finally { page.cancel(); runtime.cancel() }
    }

    @Test fun failedWriteRetriesBeforeLaterReceiptWithoutReexecutingNavigation() = runBlocking {
        val runtime = CoroutineScope(coroutineContext + Job())
        try {
            val observed = mutableListOf<String>()
            val saved = CompletableDeferred<Unit>()
            var attempts = 0
            val queue = NavigationReceiptQueue(runtime, retryDelay = {})
            queue.submit {
                attempts++
                if (attempts == 1) throw IOException("storage busy")
                observed += "failure"
            }
            queue.submit { observed += "arrival"; saved.complete(Unit) }
            withTimeout(2_000) { saved.await() }
            assertEquals(2, attempts)
            assertEquals(listOf("failure", "arrival"), observed)
        } finally { runtime.cancel() }
    }
}
