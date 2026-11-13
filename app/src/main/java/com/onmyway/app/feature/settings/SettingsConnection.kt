package com.onmyway.app.feature.settings

import com.onmyway.app.data.remote.Api
import com.onmyway.app.data.sync.BackupCredential

/** An edited destination or incomplete replacement must never reuse the old secret. */
internal fun resolveSettingsCredential(
    draftServer: String,
    savedServer: String,
    enteredToken: String,
    existing: BackupCredential?,
): BackupCredential {
    val destination = Api.validateBase(savedServer)
    require(Api.validateBase(draftServer) == destination) { "请先保存当前服务地址。" }
    val token = if (enteredToken.isNotEmpty()) {
        require(enteredToken.length >= 32) { "口令至少需要 32 个字符。" }
        enteredToken
    } else {
        requireNotNull(existing?.takeIf { it.server == destination }) { "请输入当前数据服务的口令。" }.token
    }
    return BackupCredential(destination, token)
}
