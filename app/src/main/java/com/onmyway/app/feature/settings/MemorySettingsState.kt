package com.onmyway.app.feature.settings

import com.onmyway.app.feature.assistant.PersonalMemorySnapshot

internal data class MemoryEditTarget(
    val connectionKey: String,
    val storeId: String,
    val itemId: String,
    val itemRevision: Long,
)

/** A long-lived editor must not apply to a different account, store or item revision. */
internal fun memoryEditIssue(
    target: MemoryEditTarget, connectionKey: String, snapshot: PersonalMemorySnapshot?,
): String? = when {
    target.connectionKey.isBlank() || target.connectionKey != connectionKey ->
        "服务连接已变化，编辑内容已保留；请关闭后在当前服务重新编辑。"
    snapshot == null -> "暂时无法核对记忆，请恢复连接后保存。"
    target.storeId.isBlank() || target.storeId != snapshot.storeId ->
        "记忆库已变化，编辑内容已保留；请关闭后重新查看。"
    target.itemId.isNotBlank() && snapshot.items.none {
        it.id == target.itemId && it.revision == target.itemRevision && it.status == "active" && it.editable
    } -> "这条记忆已被纠正或忘记，请关闭后重新查看。"
    else -> null
}
