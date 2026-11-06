package com.kursat.reader.ai

import com.kursat.reader.model.LLMConfig
import com.kursat.reader.model.VocabDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AIRequest(
    val title: String,
    val system: String,
    val user: String,
    val config: LLMConfig,
    val maxTokens: Int = 2048,
    val cacheKey: String? = null,
    val vocab: VocabDraft? = null
)

class AIAssistant {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isPresented = MutableStateFlow(false)
    val isPresented: StateFlow<Boolean> = _isPresented.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _vocabDraft = MutableStateFlow<VocabDraft?>(null)
    val vocabDraft: StateFlow<VocabDraft?> = _vocabDraft.asStateFlow()

    private var job: Job? = null
    private var lastRequest: AIRequest? = null

    fun start(req: AIRequest) {
        job?.cancel()
        lastRequest = req
        _vocabDraft.value = req.vocab
        _title.value = req.title
        _text.value = ""
        _error.value = null
        _isPresented.value = true

        val key = req.cacheKey
        if (key != null) {
            val cached = ResponseCache.get(key)
            if (cached != null) {
                _text.value = cached
                _isStreaming.value = false
                return
            }
        }

        _isStreaming.value = true
        job = scope.launch {
            val sb = StringBuilder()
            try {
                LLMClient.stream(req.config, system = req.system, user = req.user, maxTokens = req.maxTokens)
                    .collect { chunk ->
                        sb.append(chunk)
                        _text.value = sb.toString()
                    }
                val finalStr = sb.toString()
                if (key != null && finalStr.isNotEmpty()) {
                    ResponseCache.set(key, finalStr)
                }
            } catch (e: Exception) {
                if (job?.isCancelled != true) {
                    _error.value = e.message ?: "Bilinmeyen hata"
                }
            } finally {
                _isStreaming.value = false
            }
        }
    }

    fun showError(title: String, message: String) {
        job?.cancel()
        _vocabDraft.value = null
        _title.value = title
        _text.value = ""
        _error.value = message
        _isStreaming.value = false
        _isPresented.value = true
    }

    fun retry() {
        lastRequest?.let { start(it) }
    }

    fun dismiss() {
        _isPresented.value = false
        cancel()
    }

    fun cancel() {
        job?.cancel()
        job = null
        _isStreaming.value = false
    }
}

