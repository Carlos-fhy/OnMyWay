package com.onmyway.app.data.event

internal data class PlanRevision(val planId: String, val version: Int)

internal fun nextPlanRevision(planId: String, localVersion: Int, receiptVersion: Int,
    hasUnversionedHistory: Boolean, newPlanId: () -> String): PlanRevision {
    val latest = maxOf(localVersion, receiptVersion)
    // A restored snapshot deliberately has no device receipt/version. The old
    // server namespace may already contain v1 or later; do not reuse it.
    if (latest == 0 && hasUnversionedHistory) return PlanRevision(newPlanId(), 1)
    check(latest < Int.MAX_VALUE - 1) { "计划版本已达上限，请创建新方案。" }
    return PlanRevision(planId, latest + 1)
}
