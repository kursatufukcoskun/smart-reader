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

@Serializable
data class Highlight(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val bookID: String,
    val bookTitle: String,
    val text: String,
    val note: String = "",
    val color: HighlightColor = HighlightColor.yellow,
    /** Boşsa klasik dolgulu vurgu (iOS ile aynı alan; eski kayıtlar etkilenmez) */
    val style: HighlightStyle? = null,
    /** null = kullanıcının kendi vurgusu; "insight" = yapay zekâ içgörüsü (metinde işaretlenmez, yalnızca listede durur). */
    val kind: String? = null,
    val createdAt: String = IsoDate.now(),
    val chapter: Int? = null,
    val start: Int? = null,
    val end: Int? = null,
    val page: Int? = null,
    val rects: List<List<Double>>? = null,
    val location: String = "",
    val updatedAt: String? = null
) {
    val effectiveStyle: HighlightStyle get() = style ?: HighlightStyle.highlight
    val isInsight: Boolean get() = kind == "insight"
    val stamp: Instant get() = IsoDate.parseOrNull(updatedAt) ?: IsoDate.parse(createdAt)

    val sortKey: Pair<Int, Int>
        get() {
            if (chapter != null) return chapter to (start ?: 0)
            if (page != null) return page to -((rects?.firstOrNull()?.getOrNull(1) ?: 0.0).toInt())
            return 0 to 0
        }
}

data class EditingHighlight(
    val highlight: Highlight,
    val focusNote: Boolean = false
)

class HighlightStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _items = MutableStateFlow<List<Highlight>>(emptyList())
    val items: StateFlow<List<Highlight>> = _items.asStateFlow()

    var deleted: MutableMap<String, String> = mutableMapOf()
        private set

    var pendingJump: Highlight? = null
    private var saveJob: Job? = null

    private val file: File get() = File(Paths.base, "highlights.json")
    private val deletedFile: File get() = File(Paths.base, "highlights-deleted.json")

    init {
        load()
    }

    /** Kullanıcının kendi vurguları (yapay zekâ içgörüleri hariç). */
    val userItems: List<Highlight> get() = _items.value.filter { !it.isInsight }

    /** İçgörüleri bölüme/sayfaya ekler; aynı kitapta aynı metin varsa atlar. Eklenen sayıyı döner. */
    fun addInsights(texts: List<String>, bookID: String, bookTitle: String, chapter: Int?, page: Int?, location: String): Int {
        var n = 0
        for (raw in texts) {
            val t = raw.replace("**", "").trim()
            if (t.isEmpty()) continue
            if (_items.value.any { it.isInsight && it.bookID.equals(bookID, ignoreCase = true) && it.text.equals(t, ignoreCase = true) }) continue
            add(Highlight(bookID = bookID, bookTitle = bookTitle, text = t, kind = "insight", chapter = chapter, page = page, location = location))
            n++
        }
        return n
    }

    fun forBook(bookID: String): List<Highlight> =
        _items.value.filter { it.bookID.equals(bookID, ignoreCase = true) }
            .sortedWith(compareBy({ it.sortKey.first }, { it.sortKey.second }))

    fun highlight(id: String): Highlight? =
        _items.value.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun add(h: Highlight) {
        val norm = h.copy(id = h.id.uppercase(), bookID = h.bookID.uppercase(), updatedAt = IsoDate.now())
        _items.value = _items.value + norm
        scheduleSave()
    }

    fun update(h: Highlight) {
        val list = _items.value.toMutableList()
        val i = list.indexOfFirst { it.id.equals(h.id, ignoreCase = true) }
        if (i < 0) return
        list[i] = h.copy(updatedAt = IsoDate.now())
        _items.value = list
        scheduleSave()
    }

    fun delete(ids: List<String>) {
        val now = IsoDate.now()
        val upperIds = ids.map { it.uppercase() }.toSet()
        for (id in upperIds) deleted[id] = now
        _items.value = _items.value.filterNot { upperIds.contains(it.id.uppercase()) }
        scheduleSave()
    }

    fun deleteBook(bookID: String) {
        val ids = _items.value.filter { it.bookID.equals(bookID, ignoreCase = true) }.map { it.id }
        delete(ids)
    }

    fun replaceFromSync(items: List<Highlight>, deletedMap: Map<String, String>) {
        _items.value = items
        deleted = deletedMap.toMutableMap()
        save()
    }

    fun exportMarkdown(): String {
        val out = StringBuilder("# Vurgular ve notlar\n")
        val grouped = _items.value.groupBy { it.bookID.uppercase() }
        val sortedGroups = grouped.values.sortedBy { it.firstOrNull()?.bookTitle.orEmpty() }
        for (list in sortedGroups) {
            out.append("\n## ${list.firstOrNull()?.bookTitle.orEmpty()}\n")
            for (h in list.sortedWith(compareBy({ it.sortKey.first }, { it.sortKey.second }))) {
                val quote = h.text.replace("\n", " ")
                out.append(if (h.isInsight) "\n> ✨ *Yapay zekâ içgörüsü:* $quote\n" else "\n> $quote\n")
                if (h.location.isNotEmpty()) out.append("\n*${h.location}*\n")
                if (h.note.isNotEmpty()) out.append("\n**Not:** ${h.note}\n")
            }
        }
        return out.toString()
    }

    private fun load() {
        try {
            if (file.exists()) {
                _items.value = json.decodeFromString<List<Highlight>>(file.readText(Charsets.UTF_8))
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
            file.writeText(json.encodeToString(_items.value), Charsets.UTF_8)
            deletedFile.writeText(json.encodeToString(deleted), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}

