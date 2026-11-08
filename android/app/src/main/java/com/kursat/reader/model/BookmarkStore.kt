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
data class Bookmark(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val bookID: String,
    val bookTitle: String,
    val snippet: String,
    val chapter: Int? = null,
    val fraction: Double? = null,
    val page: Int? = null,
    val location: String,
    val createdAt: String = IsoDate.now(),
    val updatedAt: String? = null,
    /** Sohbet yanıtından kaydedilen not (yer imi düğmesiyle açılıp kapanan klasik yer imi değildir). */
    val isNote: Boolean? = null
) {
    val stamp: Instant get() = IsoDate.parseOrNull(updatedAt) ?: IsoDate.parse(createdAt)

    val sortKey: Pair<Int, Double>
        get() = if (chapter != null) chapter to (fraction ?: 0.0) else (page ?: 0) to 0.0
}

class BookmarkStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _items = MutableStateFlow<List<Bookmark>>(emptyList())
    val items: StateFlow<List<Bookmark>> = _items.asStateFlow()

    var deleted: MutableMap<String, String> = mutableMapOf()
        private set

    private var saveJob: Job? = null
    private val file: File get() = File(Paths.base, "bookmarks.json")
    private val deletedFile: File get() = File(Paths.base, "bookmarks-deleted.json")

    init {
        load()
    }

    fun forBook(bookID: String): List<Bookmark> =
        _items.value.filter { it.bookID.equals(bookID, ignoreCase = true) }
            .sortedWith(compareBy({ it.sortKey.first }, { it.sortKey.second }))

    fun add(b: Bookmark) {
        val norm = b.copy(id = b.id.uppercase(), bookID = b.bookID.uppercase(), updatedAt = IsoDate.now())
        _items.value = _items.value + norm
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
        delete(_items.value.filter { it.bookID.equals(bookID, ignoreCase = true) }.map { it.id })
    }

    fun replaceFromSync(items: List<Bookmark>, deletedMap: Map<String, String>) {
        _items.value = items
        deleted = deletedMap.toMutableMap()
        save()
    }

    private fun load() {
        try {
            if (file.exists()) {
                _items.value = json.decodeFromString<List<Bookmark>>(file.readText(Charsets.UTF_8))
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

