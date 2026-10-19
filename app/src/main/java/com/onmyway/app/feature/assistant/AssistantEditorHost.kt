package com.onmyway.app.feature.assistant

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.onmyway.app.data.local.entity.Errand
import com.onmyway.app.feature.task.editorSnapshot
import com.onmyway.app.feature.task.errandFromEditorSnapshot

/**
 * 助手草稿编辑弹层的宿主身份。draftId/version 用来在恢复后向当前草稿核对，
 * 弹层输入本身仍由 TaskEditor 的 rememberSaveable 保存。
 */
internal sealed interface AssistantEditorHost {
    data object Closed : AssistantEditorHost
    data class SingleTask(val draftId: String, val snapshot: String) : AssistantEditorHost
    data class DraftItem(
        val draftId: String,
        val itemId: String,
        val version: Int,
        val snapshot: String,
    ) : AssistantEditorHost
}

internal val AssistantEditorHost.stableKey: String
    get() = when (this) {
        AssistantEditorHost.Closed -> "closed"
        is AssistantEditorHost.SingleTask -> "single:${draftId}"
        is AssistantEditorHost.DraftItem -> "item:$draftId:$itemId:$version"
    }

internal val AssistantEditorHostSaver: Saver<AssistantEditorHost, Any> = listSaver(
    save = { host ->
        when (host) {
            AssistantEditorHost.Closed -> emptyList()
            is AssistantEditorHost.SingleTask -> listOf("single", host.draftId, host.snapshot)
            is AssistantEditorHost.DraftItem ->
                listOf("item", host.draftId, host.itemId, host.version.toString(), host.snapshot)
        }
    },
    restore = { values ->
        when (values.firstOrNull()) {
            "single" -> {
                val draftId = values.getOrNull(1)
                val snapshot = values.getOrNull(2)
                if (draftId != null && snapshot != null) AssistantEditorHost.SingleTask(draftId, snapshot)
                else AssistantEditorHost.Closed
            }
            "item" -> {
                val draftId = values.getOrNull(1)
                val itemId = values.getOrNull(2)
                val version = values.getOrNull(3)?.toIntOrNull()
                val snapshot = values.getOrNull(4)
                if (draftId != null && itemId != null && version != null && snapshot != null)
                    AssistantEditorHost.DraftItem(draftId, itemId, version, snapshot)
                else AssistantEditorHost.Closed
            }
            else -> AssistantEditorHost.Closed
        }
    },
)

internal fun Errand.assistantSnapshot(): String = editorSnapshot()

internal fun errandFromAssistantSnapshot(raw: String): Errand? = errandFromEditorSnapshot(raw)
