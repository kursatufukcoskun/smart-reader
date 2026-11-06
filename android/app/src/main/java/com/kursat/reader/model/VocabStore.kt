package com.kursat.reader.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class VocabDraft(
    val word: String,
    val paragraph: String,
    val bookID: String,
    val bookTitle: String
) {
    companion object {
        fun make(word: String, paragraph: String, book: Book): VocabDraft? {
            val w = word.trim()
            if (w.isEmpty() || w.split(Regex("\\s+")).size > 6) return null
            return VocabDraft(word = w, paragraph = paragraph, bookID = book.id, bookTitle = book.title)
        }
    }
}

@Serializable
data class VocabEntry(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val word: String,
    val gloss: String,
    val explanation: String,
    val paragraph: String,
    val bookID: String? = null,
    val bookTitle: String,
    val addedAt: String = IsoDate.now(),
    val box: Int = 0,
    val dueAt: String = IsoDate.now(),
    val reviews: Int = 0,
    val updatedAt: String? = null
) {
    val stamp: Instant get() = IsoDate.parseOrNull(updatedAt) ?: IsoDate.parse(addedAt)

    val isDue: Boolean get() = IsoDate.parse(dueAt) <= Instant.now()

    val snippet: String
        get() {
            val text = paragraph.replace("\n", " ")
            val idx = text.indexOf(word, ignoreCase = true)
            if (idx < 0) return text.take(220)
            val lo = max(0, idx - 140)
            val hi = min(text.length, idx + word.length + 140)
            var s = text.substring(lo, hi)
            if (lo > 0) s = "…$s"
            if (hi < text.length) s = "${s}…"
            return s
        }
}

enum class ReviewGrade { again, hard, good, easy }

class VocabStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _entries = MutableStateFlow<List<VocabEntry>>(emptyList())
    val entries: StateFlow<List<VocabEntry>> = _entries.asStateFlow()

    var deleted: MutableMap<String, String> = mutableMapOf()
        private set

    private var saveJob: Job? = null
    private val file: File get() = File(Paths.base, "vocab.json")
    private val deletedFile: File get() = File(Paths.base, "vocab-deleted.json")

    companion object {
        private val intervals = doubleArrayOf(0.0, 1.0, 3.0, 7.0, 16.0, 35.0)

        fun gloss(fromExplanation: String): String {
            val first = fromExplanation.lineSequence().firstOrNull { it.isNotBlank() } ?: fromExplanation
            return first.replace("*", "").trim().take(160)
        }
    }

    init {
        load()
    }

    val dueCount: Int get() = _entries.value.count { it.isDue }

    fun contains(d: VocabDraft): Boolean =
        _entries.value.any {
            it.bookID?.equals(d.bookID, ignoreCase = true) == true &&
                it.word.equals(d.word, ignoreCase = true)
        }

    fun add(d: VocabDraft, explanation: String) {
        val gloss = gloss(explanation)
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst {
            it.bookID?.equals(d.bookID, ignoreCase = true) == true &&
                it.word.equals(d.word, ignoreCase = true)
        }
        val now = IsoDate.now()
        if (i >= 0) {
            list[i] = list[i].copy(
                explanation = explanation,
                gloss = gloss,
                paragraph = d.paragraph,
                updatedAt = now
            )
        } else {
            val e = VocabEntry(
                word = d.word,
                gloss = gloss,
                explanation = explanation,
                paragraph = d.paragraph,
                bookID = d.bookID.uppercase(),
                bookTitle = d.bookTitle,
                updatedAt = now
            )
            list.add(0, e)
        }
        _entries.value = list
        scheduleSave()
    }

    fun delete(ids: List<String>) {
        val now = IsoDate.now()
        val upperIds = ids.map { it.uppercase() }.toSet()
        for (id in upperIds) deleted[id] = now
        _entries.value = _entries.value.filterNot { upperIds.contains(it.id.uppercase()) }
        scheduleSave()
    }

    fun deleteBook(bookID: String) {
        delete(_entries.value.filter { it.bookID?.equals(bookID, ignoreCase = true) == true }.map { it.id })
    }

    fun replaceFromSync(entries: List<VocabEntry>, deletedMap: Map<String, String>) {
        _entries.value = entries
        deleted = deletedMap.toMutableMap()
        save()
    }

    fun review(id: String, grade: ReviewGrade) {
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (i < 0) return
        val e = list[i]
        val nextBox = when (grade) {
            ReviewGrade.again -> 0
            ReviewGrade.hard -> e.box
            ReviewGrade.good -> min(e.box + 1, intervals.size - 1)
            ReviewGrade.easy -> min(e.box + 2, intervals.size - 1)
        }
        var days = intervals[nextBox]
        if (grade == ReviewGrade.hard) days = max(days * 0.5, 0.0)
        val nextDueSeconds = if (days == 0.0) 10L * 60L else (days * 86_400.0).toLong()
        val nextDue = Instant.ofEpochSecond(Instant.now().epochSecond + nextDueSeconds).toString()
        list[i] = e.copy(
            box = nextBox,
            dueAt = nextDue,
            reviews = e.reviews + 1,
            updatedAt = IsoDate.now()
        )
        _entries.value = list
        scheduleSave()
    }

    fun exportText(): String {
        fun clean(s: String): String =
            s.replace("\t", " ").replace("\r", "").replace("\n", "<br>")
        return _entries.value.joinToString("\n") { e ->
            val front = "<b>${clean(e.word)}</b><br><i>${clean(e.snippet)}</i>"
            val back = clean(e.explanation.replace("**", ""))
            "$front\t$back\t${clean(e.bookTitle)}"
        }
    }

    private fun load() {
        try {
            if (file.exists()) {
                _entries.value = json.decodeFromString<List<VocabEntry>>(file.readText(Charsets.UTF_8))
            }
            if (deletedFile.exists()) {
                deleted = json.decodeFromString<Map<String, String>>(deletedFile.readText(Charsets.UTF_8))
                    .mapKeys { it.key.uppercase() }.toMutableMap()
            }
        } catch (_: Exception) {
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            save()
        }
    }

    fun save() {
        try {
            file.writeText(json.encodeToString(_entries.value), Charsets.UTF_8)
            deletedFile.writeText(json.encodeToString(deleted), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}

