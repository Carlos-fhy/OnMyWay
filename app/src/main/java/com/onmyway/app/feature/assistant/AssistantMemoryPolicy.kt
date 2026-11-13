package com.onmyway.app.feature.assistant

internal const val MEMORY_LIMIT = 40
internal const val MEMORY_TEXT_LIMIT = 120
internal val MEMORY_SCOPES = setOf("general", "printing", "travel", "food", "errands")

data class MemoryUpdate(
    val op: String,
    val id: String = "",
    val text: String = "",
    val quote: String = "",
    val kind: String = "preference",
    val scope: String = "general",
)

data class AssistantMemoryReference(
    val id: String,
    val text: String,
    val revision: Long,
    val kind: String = "preference",
    val scope: String = "general",
)
data class MemorySnapshot(val state: AssistantMemoryState, val items: List<AssistantMemory>)

private val temporary = Regex("这次|这一次|此次|本次|今天|今日|明天|临时|暂时|这趟|先不|先别|this time|today|temporar", RegexOption.IGNORE_CASE)
private val stable = Regex("以后|长期|习惯|喜欢|偏好|总是|通常|每次|默认|记住")
private val forgetIntent = Regex("忘掉|忘记|删除|删掉|删了|移除|清除|别记|不要记|不用记|不要再记|别再记|取消记忆|\\b(?:forget|delete|remove)\\b", RegexOption.IGNORE_CASE)
private val negatedForget = Regex(
    "(?:不|别|不要|不用|无需|无须|不必|不能|不准|禁止)\\s*(?:再|去)?\\s*(?:忘掉|忘记|删除|删掉|删了|移除|清除)|" +
        "\\b(?:do not|don't|never|no need to)\\s+(?:forget|delete|remove)\\b", RegexOption.IGNORE_CASE,
)
private val noRemember = Regex("(?:别|不要|不用|无需|无须|不必|不能|不准|禁止)\\s*(?:再|去)?\\s*记", RegexOption.IGNORE_CASE)
private val clauseBoundary = Regex("[，,。.!！？?；;\\n\\r]+")
private val sensitive = Regex(
    "密码|口令|密钥|令牌|验证码|校验码|动态码|安全码|身份证|银行卡|" +
        "api[_ -]?key|authorization|bearer|access[_ -]?token|secret|\\b(?:token|password|passwd|passcode|otp|cvv|cvc)\\b|" +
        "sk-[A-Za-z0-9_-]{8,}|[A-Za-z0-9_+/=-]{24,}|[0-9]{13,}", RegexOption.IGNORE_CASE,
)

internal fun memoryTextAllowed(value: String): Boolean =
    value.isNotBlank() && value.length <= MEMORY_TEXT_LIMIT && !value.startsWith("deleted:") && !sensitive.containsMatchIn(value) &&
        value.none { it.isISOControl() && it != '\n' }

internal fun memoryUpdateGenerationMatches(state: AssistantMemoryState, expectedRevision: Long): Boolean =
    expectedRevision >= 0 && state.revision == expectedRevision && state.useMemory && state.autoRemember

internal fun memoryTargetMatches(item: AssistantMemory, reference: AssistantMemoryReference): Boolean =
    item.status == "active" && item.id == reference.id && item.revision == reference.revision && item.text == reference.text &&
        item.kind == reference.kind && item.scope == reference.scope

/** A model proposes changes; current user words and the exact recalled targets authorize them. */
internal fun validateMemoryUpdate(update: MemoryUpdate, sourceText: String, allowedItems: List<AssistantMemoryReference>): Boolean {
    if (update.op !in setOf("add", "update", "forget") || update.kind != "preference" || update.scope !in MEMORY_SCOPES) return false
    val quote = update.quote.trim()
    val completeQuote = quote == sourceText.trim() || sourceText.split(clauseBoundary).any { it.trim() == quote }
    if (quote.isBlank() || quote.length > 400 || !completeQuote || sensitive.containsMatchIn(quote)) return false
    val target = allowedItems.singleOrNull { it.id == update.id }
    if (update.op != "add") {
        if (target == null || update.kind != target.kind || update.scope != target.scope) return false
        val mentioned = allowedItems.filter { item ->
            quote.contains(item.text) || Regex("(?<![A-Za-z0-9_-])${Regex.escape(item.id)}(?![A-Za-z0-9_-])").containsMatchIn(quote)
        }
        if (mentioned.size != 1 || mentioned.single().id != target.id) return false
    }
    if (update.op == "forget") {
        return forgetIntent.containsMatchIn(quote) && !negatedForget.containsMatchIn(sourceText) &&
            quote.contains(checkNotNull(target).text)
    }
    if (update.op == "add" && update.id.isNotBlank()) return false
    val text = update.text.trim()
    return memoryTextAllowed(text) && text == quote && !temporary.containsMatchIn(sourceText) &&
        !noRemember.containsMatchIn(sourceText) && !forgetIntent.containsMatchIn(sourceText) && !sensitive.containsMatchIn(sourceText) &&
        stable.containsMatchIn(quote)
}

private val scopeWords = mapOf(
    "printing" to listOf("打印", "论文", "复印", "装订"),
    "travel" to listOf("出行", "旅游", "旅行", "行程", "景点", "交通", "步行", "骑行", "走路", "路线", "导航", "宿舍", "回家"),
    "food" to listOf("吃", "饮食", "餐", "外卖", "咖啡", "忌口"),
    "errands" to listOf("快递", "包裹", "取件", "买", "购物", "超市", "待办"),
)

private fun lexicalTerms(text: String): Set<String> = buildSet {
    Regex("[\\p{IsHan}]+").findAll(text).forEach { match ->
        match.value.windowed(2).forEach(::add)
    }
    Regex("[a-z0-9]{2,}").findAll(text.lowercase()).forEach { add(it.value) }
}

/** Bounded local retrieval: valid facts, matching task scope and lexical evidence before recency. */
internal fun selectMemories(
    items: List<AssistantMemory>,
    query: String,
    now: Long = System.currentTimeMillis(),
    limit: Int = 12,
): List<AssistantMemory> {
    val queryTerms = lexicalTerms(query)
    val scopes = scopeWords.filterValues { words -> words.any(query::contains) }.keys
    return items.asSequence()
        .filter { it.status == "active" && (it.expiresAt == 0L || it.expiresAt > now) && memoryTextAllowed(it.text) }
        .map { item ->
            val overlap = lexicalTerms(item.text).count(queryTerms::contains)
            val score = (if (item.scope in scopes) 100 else 0) + (if (item.scope == "general") 2 else 0) + overlap * 10
            item to score
        }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<AssistantMemory, Int>> { it.second }
            .thenByDescending { it.first.updatedAt }.thenBy { it.first.id })
        .take(limit.coerceIn(0, 12)).map { it.first }.toList()
}
