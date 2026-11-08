package com.kursat.reader.ai

import com.kursat.reader.model.Book
import com.kursat.reader.model.LLMConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

data class StudyConcept(
    val id: String = UUID.randomUUID().toString(),
    val term: String,
    val definition: String
)

data class QuizQuestion(
    val id: String = UUID.randomUUID().toString(),
    val question: String,
    val options: List<String>,
    val correct: Int,
    val explanation: String
)

data class QAItem(
    val id: String = UUID.randomUUID().toString(),
    val question: String,
    val answer: String
)

data class StudyData(
    val summary: List<String> = emptyList(),
    val concepts: List<StudyConcept> = emptyList(),
    val quiz: List<QuizQuestion> = emptyList(),
    val qa: List<QAItem> = emptyList()
)

/** Çalışma setindeki maddeleri "içgörü" olarak vurgulara eklerken kullanılan konum bilgisi. */
data class InsightTarget(val chapter: Int?, val page: Int?, val location: String)

class ChapterStudyModel(
    val title: String,
    text: String,
    book: Book,
    private val config: LLMConfig,
    private val scopeKey: String
) {
    private val clippedText = text.take(80_000)
    val bookTitle = book.title
    val bookID = book.id
    /** Okuyucu doldurur: içgörülerin ekleneceği bölüm/sayfa */
    var insightTarget: InsightTarget? = null

    private val _data = MutableStateFlow<StudyData?>(null)
    val data: StateFlow<StudyData?> = _data.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _language = MutableStateFlow("Türkçe")
    val language: StateFlow<String> = _language.asStateFlow()

    private val cacheKey: String
        get() = ResponseCache.key("study", config.cacheID, bookID, scopeKey, _language.value)

    fun setLanguage(lang: String) {
        if (_language.value == lang) return
        _language.value = lang
        _data.value = null
    }

    suspend fun load(force: Boolean = false) {
        if (_isLoading.value) return
        if (!force && _data.value != null) return
        _isLoading.value = true
        _error.value = null
        try {
            val raw = if (!force) {
                ResponseCache.get(cacheKey) ?: LLMClient.complete(
                    config,
                    system = Prompts.assistantSystem,
                    user = Prompts.study(clippedText, bookTitle, title, _language.value),
                    maxTokens = 7000
                )
            } else {
                LLMClient.complete(
                    config,
                    system = Prompts.assistantSystem,
                    user = Prompts.study(clippedText, bookTitle, title, _language.value),
                    maxTokens = 7000
                )
            }
            val parsed = parse(raw)
            if (parsed == null) {
                _error.value = "Yanıt okunamadı (biçim bozuk). Tekrar deneyin."
                return
            }
            ResponseCache.set(cacheKey, raw)
            _data.value = parsed
        } catch (e: Exception) {
            _error.value = e.message ?: "Bilinmeyen hata"
        } finally {
            _isLoading.value = false
        }
    }

    companion object {
        fun parse(raw: String): StudyData? {
            val a = raw.indexOf('{')
            val b = raw.lastIndexOf('}')
            if (a < 0 || b <= a) return null
            return try {
                val obj = JSONObject(raw.substring(a, b + 1))

                val summaryList = mutableListOf<String>()
                obj.optJSONArray("summary")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i).trim()
                        if (s.isNotEmpty()) summaryList.add(s)
                    }
                }

                val conceptsList = mutableListOf<StudyConcept>()
                obj.optJSONArray("concepts")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val term = c.optString("term").trim()
                        val def = c.optString("definition").trim()
                        if (term.isNotEmpty() && def.isNotEmpty()) {
                            conceptsList.add(StudyConcept(term = term, definition = def))
                        }
                    }
                }

                val quizList = mutableListOf<QuizQuestion>()
                obj.optJSONArray("quiz")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val q = arr.optJSONObject(i) ?: continue
                        val question = q.optString("question").trim()
                        val optsArr = q.optJSONArray("options") ?: continue
                        val opts = (0 until optsArr.length()).map { optsArr.optString(it) }
                        val ans = q.optInt("answer", -1)
                        val expl = q.optString("explanation")
                        if (question.isNotEmpty() && opts.size >= 2 && ans in opts.indices) {
                            val order = opts.indices.shuffled()
                            quizList.add(
                                QuizQuestion(
                                    question = question,
                                    options = order.map { opts[it] },
                                    correct = order.indexOf(ans).coerceAtLeast(0),
                                    explanation = expl
                                )
                            )
                        }
                    }
                }

                val qaList = mutableListOf<QAItem>()
                obj.optJSONArray("qa")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val q = item.optString("question").trim()
                        val ans = item.optString("answer").trim()
                        if (q.isNotEmpty() && ans.isNotEmpty()) {
                            qaList.add(QAItem(question = q, answer = ans))
                        }
                    }
                }

                if (summaryList.isEmpty() && quizList.isEmpty() && qaList.isEmpty()) null
                else StudyData(
                    summary = summaryList,
                    concepts = conceptsList,
                    quiz = quizList,
                    qa = qaList
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

