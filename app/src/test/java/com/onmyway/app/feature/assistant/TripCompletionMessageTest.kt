package com.onmyway.app.feature.assistant

import com.onmyway.app.core.model.TripMetrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCompletionMessageTest {
    @Test fun summaryListsCompletedAndPendingTasks() {
        val text = tripCompletionSummary(arrived = true, destination = "宿舍",
            completed = listOf("取快递"), pending = listOf("打印论文"))
        assertTrue(text.contains("已到达「宿舍」附近"))
        assertTrue(text.contains("已完成：「取快递」"))
        assertTrue(text.contains("未完成：「打印论文」"))
        assertTrue(text.contains("继续留在待办中"))
    }

    @Test fun allCompletedSummaryDoesNotAskAgain() {
        val text = tripCompletionSummary(arrived = true, destination = "宿舍",
            completed = listOf("取快递", "打印论文"), pending = emptyList())
        assertTrue(text.contains("没有未完成事项"))
        assertTrue(text.contains("待办都完成了"))
        assertFalse(text.contains("继续留在待办中"))
    }

    @Test fun allPendingSummaryDoesNotClaimCompletion() {
        val text = tripCompletionSummary(arrived = false, destination = "宿舍",
            completed = emptyList(), pending = listOf("取快递", "打印论文"))
        assertTrue(text.contains("已结束本次导航"))
        assertTrue(text.contains("没有已完成事项"))
        assertTrue(text.contains("未完成：「取快递」、「打印论文」"))
        assertTrue(text.contains("继续留在待办中"))
    }

    @Test fun unconfirmedSummarySaysStatusWasNotUpdated() {
        val text = tripCompletionSummary(arrived = true, destination = "宿舍",
            completed = listOf("取快递"), pending = listOf("打印论文"), confirmed = false)
        assertTrue(text.contains("待办状态未更新"))
        assertTrue(text.contains("未完成：「打印论文」"))
    }

    @Test fun tripWithoutTasksDoesNotClaimTasksWereCompleted() {
        val text = tripCompletionSummary(arrived = true, destination = "宿舍",
            completed = emptyList(), pending = emptyList())
        assertTrue(text.contains("本次没有顺路待办"))
        assertFalse(text.contains("待办都完成了"))
    }

    @Test fun missingDestinationFallsBackToGenericLabel() {
        val text = tripCompletionSummary(arrived = true, destination = "",
            completed = emptyList(), pending = listOf("取快递"))
        assertTrue(text.contains("已到达「终点」附近"))
    }

    @Test fun summaryIncludesMeasuredTripValuesWithoutReplacingPrediction() {
        val text = tripCompletionSummary(arrived = true, destination = "宿舍",
            completed = listOf("取快递"), pending = listOf("打印论文"),
            metrics = TripMetrics(
                startedAt = 1_000, endedAt = 601_000, actualSeconds = 600,
                actualMeters = 930, predictedSeconds = 540, predictedMeters = 800,
            ))
        assertTrue(text.contains("实际用时 10 分钟 · 930 米（当时预计 9 分钟 · 800 米）"))
        assertTrue(text.contains("已完成：「取快递」"))
    }

    @Test fun tripWithoutTasksStillReportsMeasuredValues() {
        val text = tripCompletionSummary(arrived = true, destination = "宿舍",
            completed = emptyList(), pending = emptyList(),
            metrics = TripMetrics(startedAt = 1_000, endedAt = 301_000, actualSeconds = 300))
        assertTrue(text.contains("实际用时 5 分钟"))
        assertFalse(text.contains("当时预计"))
    }
}
