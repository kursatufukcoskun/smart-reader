package com.kursat.reader.model

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
enum class BookKind {
    @SerialName("epub") epub,
    @SerialName("pdf") pdf;

    val ext: String get() = name
}

@Serializable
data class Book(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val title: String,
    val author: String = "",
    val kind: BookKind,
    val coverFile: String? = null,
    val addedAt: String = IsoDate.now(),
    val lastOpened: String? = null,
    /** EPUB: bölüm indeksi, PDF: sayfa indeksi */
    val position: Int = 0,
    /** EPUB bölümü içindeki kaydırma oranı (0...1) */
    val scrollFraction: Double = 0.0,
    /** Toplam ilerleme (0...1) */
    val progress: Double = 0.0,
    /** EPUB: her bölümdeki kelime sayısı (ilerleme ve süre tahmini için) */
    val chapterWords: List<Int>? = null,
    /** Sayfa bazlı ilerleme: PDF'te gerçek sayfa, EPUB'da ~250 kelimelik sanal sayfa */
    val pagesTotal: Int? = null,
    val pageNow: Int? = null,
    /** Konum en son ne zaman değişti (cihazlar arası senkron için) */
    val progressUpdatedAt: String? = null,
    /** Kimlik içerik özetine çevrildi mi */
    val idChecked: Boolean? = true,
    /** Kullanıcının verdiği etiketler (raflar) */
    val tags: List<String>? = null,
    /** Etiketler en son ne zaman değişti (senkron için) */
    val tagsUpdatedAt: String? = null,
    /** Kitap bulutta var ama bu cihaza henüz indirilmedi */
    val remoteOnly: Boolean? = null
) {
    companion object {
        const val WORDS_PER_PAGE = 250.0
    }

    val isRemoteOnly: Boolean get() = remoteOnly == true

    /** Senkron için kitaplık kaydı. */
    fun syncEntry(): LibraryEntry = LibraryEntry(
        id = id.uppercase(), title = title, author = author, kind = kind.name, addedAt = addedAt,
        updatedAt = tagsUpdatedAt ?: addedAt, tags = tags, chapterWords = chapterWords, pagesTotal = pagesTotal
    )

    val totalWords: Int
        get() = chapterWords?.sum() ?: 0

    fun wordsBefore(chapter: Int): Int {
        val w = chapterWords ?: return 0
        val limit = max(0, min(chapter, w.size))
        return w.subList(0, limit).sum()
    }

    /** EPUB'da okunan kelime konumu. */
    val wordPosition: Double
        get() {
            val w = chapterWords
            if (w.isNullOrEmpty()) return 0.0
            val c = max(0, min(position, w.size - 1))
            return wordsBefore(c).toDouble() + scrollFraction * w[c].toDouble()
        }

    /** Konumdan ilerleme ve sayfa bilgisini yeniden hesaplar. */
    fun recomputedProgress(): Book {
        return when (kind) {
            BookKind.epub -> {
                val total = totalWords
                if (total <= 0) return this
                val pos = wordPosition
                val prog = min(1.0, max(0.0, pos / total.toDouble()))
                val pages = max(1, (total.toDouble() / WORDS_PER_PAGE).roundToInt())
                val now = min(pages, max(1, (pos / WORDS_PER_PAGE).toInt() + 1))
                copy(progress = prog, pagesTotal = pages, pageNow = now)
            }
            BookKind.pdf -> {
                val pages = pagesTotal ?: return this
                if (pages <= 0) return this
                val now = min(pages, max(1, position + 1))
                copy(pageNow = now, progress = now.toDouble() / pages.toDouble())
            }
        }
    }

    /** En son ne zaman okundu/açıldı (cihazlar arası konum damgası dahil). */
    val lastReadAt: Instant?
        get() = listOfNotNull(
            lastOpened?.let { IsoDate.parseOrNull(it) },
            progressUpdatedAt?.let { IsoDate.parseOrNull(it) }
        ).maxOrNull()

    /** Hiç ilerletilmemiş kitap (yalnızca açılmış olabilir). */
    val isUnread: Boolean
        get() = progressUpdatedAt == null && position == 0 && scrollFraction < 0.001

    val isFinished: Boolean
        get() = progress >= 0.995

    /** "Sayfa 12 / 340" */
    val pageText: String?
        get() {
            val n = pageNow ?: return null
            val t = pagesTotal ?: return null
            return "Sayfa $n / $t"
        }

    /** Kitaplık kartı için durum metni: "Okunmadı", "Bitti" veya "Sayfa 12 / 340 · %4". */
    val statusText: String?
        get() {
            if (isUnread) return "Okunmadı"
            if (isFinished) return "Bitti"
            val p = pageText ?: return null
            return "$p · %${(progress * 100.0).roundToInt()}"
        }
}

/** library.json kaydı (SYNC.md). Alanlar iOS ile aynı adlarla. */
@Serializable
data class LibraryEntry(
    val id: String,
    val title: String,
    val author: String = "",
    val kind: String,
    val addedAt: String,
    val updatedAt: String,
    val tags: List<String>? = null,
    val chapterWords: List<Int>? = null,
    val pagesTotal: Int? = null
)

object IsoDate {
    fun now(): String = Instant.now().toEpochMilli().let {
        // ISO-8601 UTC saniye hassasiyetinde (ör. 2026-11-06T01:30:00Z, iOS ISO8601DateFormatter ile birebir uyumlu)
        Instant.ofEpochSecond(it / 1000).toString()
    }

    fun parseOrNull(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return try {
            Instant.parse(s)
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(s).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun parse(s: String?): Instant = parseOrNull(s) ?: Instant.EPOCH
}

object Paths {
    private lateinit var baseDir: File

    fun init(context: Context) {
        baseDir = File(context.filesDir, "Reader").apply { mkdirs() }
    }

    val base: File
        get() = baseDir

    fun file(book: Book): File = File(baseDir, "${book.id}.${book.kind.ext}")
    fun epubRoot(book: Book): File = File(baseDir, "${book.id}-x")
    fun driveBookName(id: String, kind: BookKind): String = "book-${id.uppercase()}.${kind.ext}"
    fun driveCoverName(id: String): String = "cover-${id.uppercase()}.jpg"
    fun cover(book: Book): File? = book.coverFile?.let { File(baseDir, it) }
}

