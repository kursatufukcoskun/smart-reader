package com.kursat.reader.ai

import com.kursat.reader.model.LLMTier
import com.kursat.reader.model.LLMProvider
import com.kursat.reader.model.ReaderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class BookContext(
    val text: String,
    val words: Int,
    val truncated: Boolean,
    val label: String
) {
    companion object {
        val empty = BookContext(text = "", words = 0, truncated = false, label = "")

        fun assemble(sections: List<Pair<String, String>>, budget: Int, label: String): BookContext {
            val sb = StringBuilder()
            for ((title, body) in sections) {
                if (body.isNotEmpty()) {
                    sb.append("\n\n=== $title ===\n$body")
                }
            }
            var out = sb.toString()
            var truncated = false
            if (out.length > budget) {
                truncated = true
                out = "[… önceki kısımlar uzunluk nedeniyle çıkarıldı …]\n" + out.takeLast(budget)
            }
            val words = out.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            return BookContext(text = out, words = words, truncated = truncated, label = label)
        }
    }
}

class ChatModel(
    val settings: ReaderSettings,
    val bookTitle: String
) {
    data class Message(
        val id: String = UUID.randomUUID().toString(),
        val role: ChatTurn.Role,
        val text: String
    )

    enum class Tier(val title: String) {
        fast("Hızlı"),
        smart("Güçlü")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Bir yanıtı okuma konumuna not olarak kaydeder (okuyucu bağlar). */
    var saveNote: ((String) -> Unit)? = null

    /** Okuyucudan gelen hazır soru: sohbet açılıp bağlam hazır olunca otomatik gönderilir. */
    var pendingPrompt: String? = null

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isLoadingContext = MutableStateFlow(false)
    val isLoadingContext: StateFlow<Boolean> = _isLoadingContext.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _info = MutableStateFlow(BookContext.empty)
    val info: StateFlow<BookContext> = _info.asStateFlow()

    private val _tier = MutableStateFlow(Tier.smart)
    val tier: StateFlow<Tier> = _tier.asStateFlow()

    var contextProvider: (suspend (Int) -> BookContext)? = null
    private var job: Job? = null
    private var lastFailedQuestion: String? = null

    private val budget: Int
        get() = if (settings.current.provider == LLMProvider.gemini) 1_800_000 else 450_000

    fun setTier(t: Tier) {
        _tier.value = t
    }

    suspend fun refreshContext() {
        if (_isStreaming.value) return
        _isLoadingContext.value = true
        _info.value = contextProvider?.invoke(budget) ?: BookContext.empty
        _isLoadingContext.value = false
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _isStreaming.value) return
        val currentInfo = _info.value
        if (currentInfo.text.isEmpty()) {
            _error.value = "Okunan kısımdan metin çıkarılamadı (taranmış PDF olabilir)."
            return
        }
        _error.value = null
        val userMsg = Message(role = ChatTurn.Role.user, text = text)
        val replyMsg = Message(role = ChatTurn.Role.assistant, text = "")
        val nextList = _messages.value + userMsg + replyMsg
        _messages.value = nextList

        val replyID = replyMsg.id
        val turns = nextList.dropLast(1).map { ChatTurn(role = it.role, text = it.text) }
        val config = settings.current.config(if (_tier.value == Tier.fast) LLMTier.fast else LLMTier.smart)
        val contextStr = "Aşağıda kitabın, kullanıcının şu ana kadar okuduğu kısmı var:\n<kitap>${currentInfo.text}\n</kitap>"
        val system = Prompts.chatSystem(bookTitle)

        _isStreaming.value = true
        job = scope.launch {
            val acc = StringBuilder()
            try {
                LLMClient.streamChat(config, system = system, context = contextStr, turns = turns)
                    .collect { chunk ->
                        acc.append(chunk)
                        setReply(replyID, acc.toString())
                    }
            } catch (e: Exception) {
                if (job?.isCancelled != true) {
                    _error.value = e.message ?: "Bilinmeyen hata"
                }
            } finally {
                // Boş kalan yanıtı sil ki bir sonraki istek geçersiz mesaj dizisi göndermesin.
                if (acc.isEmpty()) {
                    _messages.value = _messages.value.filterNot { it.id == replyID }
                    dropDanglingUser()
                }
                _isStreaming.value = false
            }
        }
    }

    private fun setReply(id: String, text: String) {
        _messages.value = _messages.value.map { if (it.id == id) it.copy(text = text) else it }
    }

    private fun dropDanglingUser() {
        val cur = _messages.value
        if (cur.lastOrNull()?.role == ChatTurn.Role.user) {
            lastFailedQuestion = cur.last().text
            _messages.value = cur.dropLast(1)
        }
    }

    fun retry() {
        val q = lastFailedQuestion ?: return
        lastFailedQuestion = null
        send(q)
    }

    fun stop() {
        job?.cancel()
        _isStreaming.value = false
    }

    fun clear() {
        job?.cancel()
        _isStreaming.value = false
        _messages.value = emptyList()
        _error.value = null
    }
}

