package com.onmyway.app.feature.assistant

import com.onmyway.app.core.model.isIndoor
import com.onmyway.app.data.local.entity.Errand

internal data class TripCompletionTask(
    val id: String,
    val title: String,
    val placeLabel: String,
    val done: Boolean,
)

internal data class TripCompletionPrompt(
    val eventId: String,
    val arrived: Boolean,
    val destination: String,
    val tasks: List<TripCompletionTask>,
)

internal fun tripCompletionSummary(
    arrived: Boolean,
    destination: String,
    completed: List<String>,
    pending: List<String>,
    confirmed: Boolean = true,
): String {
    val place = destination.ifBlank { "终点" }
    val opening = if (arrived) "已到达「$place」附近。" else "已结束本次导航。"
    val record = "本次路线已存入行程记录。"
    if (completed.isEmpty() && pending.isEmpty()) {
        return opening + record + "本次没有顺路待办。接下来想做什么？"
    }
    val done = if (completed.isEmpty()) {
        "没有已完成事项"
    } else {
        "已完成：" + completed.joinToString("、") { "「$it」" }
    }
    val left = if (pending.isEmpty()) {
        "没有未完成事项"
    } else {
        "未完成：" + pending.joinToString("、") { "「$it」" }
    }
    val status = if (confirmed) "" else "待办状态未更新。"
    val tail = if (pending.isEmpty()) {
        "本次顺路的待办都完成了。"
    } else {
        "未完成项继续留在待办中。"
    }
    return "$opening$record$status$done；$left。$tail"
}

internal fun tripCompletionPlaceLabel(task: Errand): String = when {
    isIndoor(task.placeType) -> "不用出门"
    task.place() != null -> task.place()!!.name
    else -> "待补办理地点"
}
