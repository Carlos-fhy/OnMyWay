package com.onmyway.app.feature.planning

internal fun travelModeReply(text: String): String? = when (text.trim().trimEnd('。', '！', '!', '，')) {
    "步行", "走路", "走过去", "我走路", "改成步行", "切换步行" -> "walking"
    "骑行", "骑车", "骑自行车", "我骑车", "改成骑行", "切换骑行" -> "cycling"
    else -> null
}
