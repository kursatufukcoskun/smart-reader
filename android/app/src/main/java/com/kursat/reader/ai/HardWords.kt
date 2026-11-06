package com.kursat.reader.ai

import com.kursat.reader.model.Book
import com.kursat.reader.model.LLMConfig
import com.kursat.reader.model.VocabDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class HardWord(
    val id: String = UUID.randomUUID().toString(),
    val word: String,
    val pos: String,
    val meaning: String,
    val context: String
)

class HardWordsModel(
    val title: String,
    text: String,
    book: Book,
    private val config: LLMConfig,
    scopeKey: String
) {
    private val clippedText = text.take(60_000)
    val bookID = book.id
    val bookTitle = book.title
    private val cacheKey = ResponseCache.key("hardwords", config.cacheID, book.id, scopeKey)

    private val _items = MutableStateFlow<List<HardWord>>(emptyList())
    val items: StateFlow<List<HardWord>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun load() {
        if (_items.value.isNotEmpty()) return
        _isLoading.value = true
        _error.value = null
        try {
            val raw = ResponseCache.get(cacheKey) ?: run {
                val res = LLMClient.complete(
                    config,
                    system = Prompts.assistantSystem,
                    user = Prompts.hardWords(clippedText, bookTitle),
                    maxTokens = 3000
                )
                ResponseCache.set(cacheKey, res)
                res
            }
            val parsed = parse(raw)
            _items.value = parsed
            if (parsed.isEmpty()) {
                _error.value = "Kelime listesi çıkarılamadı, tekrar deneyin."
            }
        } catch (e: Exception) {
            _error.value = e.message ?: "Bilinmeyen hata"
        } finally {
            _isLoading.value = false
        }
    }

    fun draft(w: HardWord): VocabDraft =
        VocabDraft(word = w.word, paragraph = w.context, bookID = bookID, bookTitle = bookTitle)

    fun explanation(w: HardWord): String {
        var s = "**${w.word}** — ${w.pos}; ${w.meaning}"
        if (w.context.isNotEmpty()) s += "\n**Bağlam:** ${w.context}"
        return s
    }

    companion object {
        private val bulletChars = charArrayOf(' ', '\t', '•', '-', '*')

        fun parse(raw: String): List<HardWord> {
            return raw.lines().mapNotNull { line ->
                val parts = line.split("|").map { it.trim(*bulletChars) }
                if (parts.size < 3 || parts[0].isEmpty() || parts[2].isEmpty()) return@mapNotNull null
                HardWord(
                    word = parts[0],
                    pos = parts[1],
                    meaning = parts[2],
                    context = if (parts.size > 3) parts[3] else ""
                )
            }
        }
    }
}

