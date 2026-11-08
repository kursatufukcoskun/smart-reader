package com.kursat.reader.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.kursat.reader.epub.EPUBPackage
import com.kursat.reader.epub.ZipArchive
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object HTMLText {
    private val scriptStyleRegex = Regex("<(script|style|head)\\b[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val blockBreakRegex = Regex("</(p|div|h[1-6]|li|blockquote|tr|section|article|figcaption|dd|dt)\\s*>|<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val tagRegex = Regex("<[^>]+>")
    private val entityRegex = Regex("&#(x?)([0-9a-fA-F]+);")

    private val namedEntities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&apos;" to "'", "&mdash;" to "—", "&ndash;" to "–",
        "&hellip;" to "…", "&rsquo;" to "’", "&lsquo;" to "‘",
        "&rdquo;" to "”", "&ldquo;" to "“"
    )

    fun extract(file: File): String {
        if (!file.exists()) return ""
        return try {
            plain(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            ""
        }
    }

    fun plain(html: String): String {
        var s = scriptStyleRegex.replace(html, " ")
        s = blockBreakRegex.replace(s, "\n")
        s = tagRegex.replace(s, "")
        s = decodeEntities(s)
        val lines = mutableListOf<String>()
        for (l in s.lines()) {
            val t = l.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            if (t.isEmpty()) {
                if (lines.lastOrNull() != "") lines.add("")
            } else {
                lines.add(t)
            }
        }
        return lines.joinToString("\n").trim()
    }

    /** HTML içinde `id`'si verilen öğenin (boş bir <a>/<span> ise onu içeren paragrafın) düz metni. */
    fun elementText(id: String, html: String): String? {
        val esc = Regex.escape(id)
        val re = Regex("<([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*\\b(?:id|name)\\s*=\\s*[\"']" + esc + "[\"'][^>]*>")
        val m = re.find(html) ?: return null
        val tag = m.groupValues[1].lowercase()
        var text = ""
        if (tag != "a" && tag != "span") balanced(tag, m.range.last + 1, html)?.let { text = plain(it) }
        if (text.isEmpty()) {
            val before = html.substring(0, m.range.first)
            val last = Regex("<(p|li|div|aside|dd|blockquote)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(before).lastOrNull()
            if (last != null) {
                balanced(last.groupValues[1].lowercase(), last.range.last + 1, html)?.let { text = plain(it) }
            }
        }
        text = text.trim { it.isWhitespace() || it in "↩︎↩⤴↑⏎" }
        text = text.lines().filter { it.isNotBlank() }.joinToString("\n")
        return if (text.isEmpty()) null else text.take(6000)
    }

    private fun balanced(tag: String, start: Int, html: String): String? {
        val re = Regex("<(/?)" + tag + "\\b[^>]*>", RegexOption.IGNORE_CASE)
        var depth = 1
        for (m in re.findAll(html, start)) {
            val closing = m.groupValues[1].isNotEmpty()
            if (!closing && m.value.endsWith("/>")) continue
            depth += if (closing) -1 else 1
            if (depth == 0) return html.substring(start, m.range.first)
        }
        return null
    }

    private fun decodeEntities(s: String): String {
        var out = s
        for ((k, v) in namedEntities) {
            out = out.replace(k, v)
        }
        if (!out.contains("&#")) return out
        return entityRegex.replace(out) { m ->
            val isHex = m.groupValues[1].equals("x", ignoreCase = true)
            val code = m.groupValues[2].toIntOrNull(if (isHex) 16 else 10)
            if (code != null && Character.isValidCodePoint(code)) {
                String(Character.toChars(code))
            } else m.value
        }
    }
}

class LibraryStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val httpClient = OkHttpClient()

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private var saveJob: Job? = null
    private val indexFile: File get() = File(Paths.base, "books.json")
    private val deletedFile: File get() = File(Paths.base, "library-deleted.json")

    /** Her yerden silinen kitaplar (senkronda silmenin yayılması için): kimlik -> ISO tarih */
    var deleted: Map<String, String> = emptyMap()
        private set

    init {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
        load()
        backfillMetrics()
    }

    private fun backfillMetrics() {
        val current = _books.value
        for (b in current.filterNot { it.isRemoteOnly }) {
            if ((b.kind == BookKind.epub && b.chapterWords == null) ||
                (b.kind == BookKind.pdf && b.pagesTotal == null)
            ) {
                scope.launch(Dispatchers.IO) {
                    var nb = b
                    when (b.kind) {
                        BookKind.epub -> {
                            val pkg = try {
                                EPUBPackage.load(Paths.epubRoot(b))
                            } catch (_: Exception) {
                                return@launch
                            }
                            val counts = pkg.spine.map { item ->
                                HTMLText.extract(item.file).split(Regex("\\s+")).count { it.isNotEmpty() }
                            }
                            nb = nb.copy(chapterWords = counts)
                        }
                        BookKind.pdf -> {
                            val pdfFile = Paths.file(b)
                            if (pdfFile.exists()) {
                                try {
                                    ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                                        PdfRenderer(pfd).use { renderer ->
                                            nb = nb.copy(pagesTotal = renderer.pageCount)
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        mergeMetrics(nb)
                    }
                }
            }
        }
    }

    fun mergeMetrics(m: Book) {
        val cur = book(m.id) ?: return
        val updated = cur.copy(
            chapterWords = m.chapterWords ?: cur.chapterWords,
            pagesTotal = m.pagesTotal ?: cur.pagesTotal
        ).recomputedProgress()
        update(updated)
    }

    enum class Sort(val title: String) {
        lastRead("Son okunan"), added("Eklenme tarihi"), byTitle("Başlık"), byAuthor("Yazar"), byProgress("İlerleme")
    }

    private val sortPrefs = context.getSharedPreferences("library_sort", Context.MODE_PRIVATE)
    private val _sort = MutableStateFlow(
        Sort.entries.firstOrNull { it.name == sortPrefs.getString("sort", null) } ?: Sort.lastRead
    )
    /** Kitaplık sıralaması (cihaza özel). */
    val sort: StateFlow<Sort> = _sort.asStateFlow()

    fun setSort(s: Sort) {
        _sort.value = s
        sortPrefs.edit().putString("sort", s.name).apply()
    }

    /** Başlık/yazar düzenlemesi: etiketlerle aynı damgayı kullanır, böylece diğer cihazlara senkronlanır. */
    fun updateInfo(id: String, title: String, author: String) {
        val b = book(id) ?: return
        val t = title.trim()
        if (t.isEmpty()) return
        update(b.copy(title = t, author = author.trim(), tagsUpdatedAt = IsoDate.now()))
    }

    /** Seçili sıralamaya göre kitaplar (varsayılan: en son okunan en üstte; hiç açılmamışlar sonda). */
    val sortedBooks: List<Book>
        get() = when (_sort.value) {
            Sort.added -> _books.value.sortedByDescending { IsoDate.parse(it.addedAt) }
            Sort.byTitle -> _books.value.sortedWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
            Sort.byAuthor -> _books.value.sortedWith { a, b ->
                when {
                    a.author.isEmpty() != b.author.isEmpty() -> if (a.author.isNotEmpty()) -1 else 1
                    else -> a.author.compareTo(b.author, ignoreCase = true)
                }
            }
            Sort.byProgress -> _books.value.sortedByDescending { it.progress }
            Sort.lastRead -> lastReadSorted()
        }

    private fun lastReadSorted(): List<Book> =
        _books.value.sortedWith { a, b ->
            val la = a.lastReadAt
            val lb = b.lastReadAt
            when {
                la != null && lb != null -> lb.compareTo(la)
                la != null -> -1
                lb != null -> 1
                else -> IsoDate.parse(b.addedAt).compareTo(IsoDate.parse(a.addedAt))
            }
        }

    fun book(id: String): Book? = _books.value.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun update(book: Book) {
        val list = _books.value.toMutableList()
        val i = list.indexOfFirst { it.id.equals(book.id, ignoreCase = true) }
        if (i < 0) return
        val old = list[i]
        val changedPos = book.position != old.position || book.scrollFraction != old.scrollFraction
        val updated = book.copy(
            progressUpdatedAt = if (changedPos) IsoDate.now() else old.progressUpdatedAt
        )
        list[i] = updated
        _books.value = list
        scheduleSave()
    }

    // MARK: - Başka cihazdaki konum teklifi

    data class RemoteProgressOffer(
        val position: Int,
        val scrollFraction: Double,
        val progress: Double,
        val updatedAt: String
    )

    /** Başka cihazda daha yeni bir konum var ve yerelden anlamlı farklı: kullanıcıya sorulur (kitap kimliği büyük harf). */
    private val _remoteOffers = MutableStateFlow<Map<String, RemoteProgressOffer>>(emptyMap())
    val remoteOffers: StateFlow<Map<String, RemoteProgressOffer>> = _remoteOffers.asStateFlow()

    /** Uzak konum yerelden anlamlı farklıysa (bölüm/sayfa değişmiş ya da %5'ten fazla kaymış) true. */
    fun isSignificant(b: Book, position: Int, scrollFraction: Double): Boolean = when (b.kind) {
        BookKind.pdf -> position != b.position
        BookKind.epub -> position != b.position || kotlin.math.abs(scrollFraction - b.scrollFraction) > 0.05
    }

    fun offerRemoteProgress(id: String, offer: RemoteProgressOffer) {
        val key = id.uppercase()
        if (_remoteOffers.value[key] != offer) _remoteOffers.value = _remoteOffers.value + (key to offer)
    }

    fun clearRemoteOffer(id: String) {
        val key = id.uppercase()
        if (_remoteOffers.value.containsKey(key)) _remoteOffers.value = _remoteOffers.value - key
    }

    /** "Burada kal": yerel konum yeni damga alır, bir sonraki senkronda uzaktakinin yerine geçer. */
    fun keepLocalProgress(id: String) {
        clearRemoteOffer(id)
        val list = _books.value.toMutableList()
        val i = list.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (i < 0) return
        list[i] = list[i].copy(progressUpdatedAt = IsoDate.now())
        _books.value = list
        scheduleSave()
    }

    /** Başka cihazdan gelen konumu uygular (damgayı uzaktakinden alır). */
    fun applyRemoteProgress(id: String, position: Int, scrollFraction: Double, updatedAt: String) {
        val list = _books.value.toMutableList()
        val i = list.indexOfFirst { it.id.equals(id, ignoreCase = true) }
        if (i < 0) return
        val b = list[i]
        val clampedPos = when (b.kind) {
            BookKind.epub -> {
                val n = b.chapterWords?.size ?: 0
                if (n > 0) min(max(position, 0), n - 1) else max(position, 0)
            }
            BookKind.pdf -> {
                val n = b.pagesTotal ?: 0
                if (n > 0) min(max(position, 0), n - 1) else max(position, 0)
            }
        }
        val clampedFrac = min(max(scrollFraction, 0.0), 1.0)
        list[i] = b.copy(
            position = clampedPos,
            scrollFraction = clampedFrac,
            progressUpdatedAt = updatedAt
        ).recomputedProgress()
        _books.value = list
        scheduleSave()
    }

    val allTags: List<String>
        get() = _books.value
            .flatMap { it.tags.orEmpty() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun setTags(id: String, tags: List<String>) {
        val b = book(id) ?: return
        update(b.copy(tags = tags.ifEmpty { null }, tagsUpdatedAt = IsoDate.now()))
    }

    /** Open Library'den başlık/yazara göre kapak bulur ve kaydeder. */
    suspend fun fetchCover(id: String) = withContext(Dispatchers.IO) {
        val b = book(id) ?: return@withContext
        val urlBuilder = "https://openlibrary.org/search.json".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("title", b.title)
            .addQueryParameter("limit", "5")
            .addQueryParameter("fields", "cover_i,title")
        if (b.author.isNotBlank()) {
            urlBuilder.addQueryParameter("author", b.author)
        }
        val req = Request.Builder().url(urlBuilder.build()).build()
        val bodyStr = httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Open Library'de bu kitap için kapak bulunamadı.")
            resp.body?.string().orEmpty()
        }
        val obj = JSONObject(bodyStr)
        val docs = obj.optJSONArray("docs") ?: throw Exception("Open Library'de bu kitap için kapak bulunamadı.")
        var coverId: Int? = null
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            if (d.has("cover_i")) {
                val ci = d.optInt("cover_i", 0)
                if (ci > 0) {
                    coverId = ci
                    break
                }
            }
        }
        val ci = coverId ?: throw Exception("Open Library'de bu kitap için kapak bulunamadı.")
        val imgReq = Request.Builder()
            .url("https://covers.openlibrary.org/b/id/$ci-L.jpg?default=false")
            .build()
        val imgBytes = httpClient.newCall(imgReq).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Open Library'de bu kitap için kapak bulunamadı.")
            resp.body?.bytes() ?: throw Exception("Open Library'de bu kitap için kapak bulunamadı.")
        }
        val bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
            ?: throw Exception("Open Library'de bu kitap için kapak bulunamadı.")
        val scaled = scaleBitmap(bmp, 700)
        val name = "${b.id}-cover.jpg"
        FileOutputStream(File(Paths.base, name)).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        withContext(Dispatchers.Main) {
            val nb = book(id) ?: return@withContext
            update(nb.copy(coverFile = name))
        }
    }

    fun touch(id: String) {
        val b = book(id) ?: return
        update(b.copy(lastOpened = IsoDate.now()))
    }

    fun delete(book: Book) {
        removeFiles(book)
        _books.value = _books.value.filterNot { it.id.equals(book.id, ignoreCase = true) }
        save()
    }

    /** Kitabı bulutla birlikte, tüm cihazlardan siler (silme kaydı senkronla yayılır). */
    fun deleteEverywhere(book: Book) {
        deleted = deleted + (book.id.uppercase() to IsoDate.now())
        delete(book)
    }

    private fun removeFiles(book: Book) {
        try {
            Paths.file(book).delete()
            Paths.epubRoot(book).deleteRecursively()
            Paths.cover(book)?.delete()
        } catch (_: Exception) {
        }
    }

    /**
     * Başka cihazlardaki kitaplık kayıtlarını uygular: yeni kitaplar "bulutta" olarak eklenir,
     * her yerden silinenler kaldırılır, etiketler birleşir.
     */
    fun applyRemoteLibrary(entries: List<LibraryEntry>, deleted: Map<String, String>) {
        this.deleted = deleted
        val keep = entries.map { it.id.uppercase() }.toSet()
        var list = _books.value
        for (b in list) if (b.id.uppercase() !in keep) removeFiles(b)
        list = list.filter { it.id.uppercase() in keep }.toMutableList()
        for (e in entries) {
            val i = list.indexOfFirst { it.id.equals(e.id, ignoreCase = true) }
            if (i >= 0) {
                val cur = list[i]
                val curStamp = IsoDate.parse(cur.tagsUpdatedAt ?: cur.addedAt)
                if (IsoDate.parse(e.updatedAt).isAfter(curStamp)) {
                    (list as MutableList)[i] = cur.copy(
                        title = e.title.ifEmpty { cur.title },
                        author = e.author,
                        tags = e.tags?.ifEmpty { null },
                        tagsUpdatedAt = e.updatedAt
                    )
                }
            } else {
                val kind = if (e.kind == "pdf") BookKind.pdf else BookKind.epub
                val nb = Book(
                    id = e.id.uppercase(), title = e.title, author = e.author, kind = kind, addedAt = e.addedAt,
                    chapterWords = e.chapterWords, pagesTotal = e.pagesTotal, tags = e.tags?.ifEmpty { null },
                    tagsUpdatedAt = if (IsoDate.parse(e.updatedAt).isAfter(IsoDate.parse(e.addedAt))) e.updatedAt else null,
                    remoteOnly = true, idChecked = true
                ).recomputedProgress()
                (list as MutableList).add(nb)
            }
        }
        _books.value = list
        save()
    }

    fun setCover(id: String, file: String) {
        val b = book(id) ?: return
        update(b.copy(coverFile = file))
    }

    /** Buluttan inen dosyayı kuran ve kitabı kullanıma açan adım. */
    suspend fun installDownloaded(id: String, tmp: File) {
        val b = book(id) ?: return
        var words: List<Int>? = null
        withContext(Dispatchers.IO) {
            when (b.kind) {
                BookKind.epub -> {
                    val root = Paths.epubRoot(b)
                    root.deleteRecursively()
                    try {
                        ZipArchive.extractAll(tmp, root)
                        val pkg = EPUBPackage.load(root)
                        if (b.chapterWords == null) {
                            words = pkg.spine.map { item ->
                                HTMLText.extract(item.file).split(Regex("\\s+")).count { it.isNotEmpty() }
                            }
                        }
                        tmp.delete()
                    } catch (e: Exception) {
                        root.deleteRecursively()
                        throw e
                    }
                }
                BookKind.pdf -> {
                    val dest = Paths.file(b)
                    dest.delete()
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                }
            }
        }
        withContext(Dispatchers.Main) {
            val cur = book(id) ?: return@withContext
            update(cur.copy(chapterWords = words ?: cur.chapterWords, remoteOnly = null).recomputedProgress())
        }
    }

    /** İçe aktarılan kitabı ekler; aynı kitap bulutta "indirilmemiş" duruyorsa konum ve etiketlerini korur. */
    private fun addImported(book: Book) {
        var b = book
        val old = _books.value.firstOrNull { it.id.equals(b.id, ignoreCase = true) && it.isRemoteOnly }
        if (old != null) {
            b = b.copy(
                addedAt = old.addedAt, tags = old.tags, tagsUpdatedAt = old.tagsUpdatedAt, position = old.position,
                scrollFraction = old.scrollFraction, progressUpdatedAt = old.progressUpdatedAt, lastOpened = old.lastOpened
            ).recomputedProgress()
        }
        deleted = deleted - b.id.uppercase()
        _books.value = _books.value.filterNot { it.id.equals(b.id, ignoreCase = true) } + b
        save()
    }

    suspend fun importBook(uri: Uri): Book = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri) ?: "Kitap.epub"
        val tempFile = File(context.cacheDir, "import_${UUID.randomUUID()}_$displayName")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Dosya okunamadı.")
            val book = importBookFromFile(tempFile, fallbackTitle = displayName.substringBeforeLast('.'))
            withContext(Dispatchers.Main) { addImported(book) }
            book
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Web adresinden kitap ekler: PDF/EPUB bağlantısıysa dosya olduğu gibi, web sayfasıysa makale metni
     * ayıklanıp tek bölümlük EPUB olarak.
     */
    suspend fun importArticle(address: String): Book {
        val dir = File(context.cacheDir, "articles").apply { mkdirs() }
        val file = ArticleImporter.fetch(address, dir)
        try {
            return importBookFromPath(file)
        } finally {
            file.delete()
        }
    }

    suspend fun importBookFromPath(file: File): Book = withContext(Dispatchers.IO) {
        val book = importBookFromFile(file, fallbackTitle = file.nameWithoutExtension)
        withContext(Dispatchers.Main) { addImported(book) }
        book
    }

    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun importBookFromFile(sourceFile: File, fallbackTitle: String): Book {
        val ext = sourceFile.extension.lowercase()
        val kind = when {
            ext == "epub" -> BookKind.epub
            ext == "pdf" -> BookKind.pdf
            isZipWithContainer(sourceFile) -> BookKind.epub
            isPdfHeader(sourceFile) -> BookKind.pdf
            else -> throw Exception("Yalnızca EPUB ve PDF desteklenir.")
        }

        var book = Book(title = fallbackTitle.ifBlank { "Kitap" }, kind = kind)
        val dest = Paths.file(book)
        sourceFile.copyTo(dest, overwrite = true)
        var contentID: String? = null

        when (kind) {
            BookKind.epub -> {
                val root = Paths.epubRoot(book)
                try {
                    ZipArchive.extractAll(dest, root)
                    val pkg = EPUBPackage.load(root)
                    if (pkg.title.isNotBlank()) book = book.copy(title = pkg.title)
                    book = book.copy(author = pkg.author)
                    contentID = BookIdentity.epubID(pkg.opfFile)
                    val counts = pkg.spine.map { item ->
                        HTMLText.extract(item.file).split(Regex("\\s+")).count { it.isNotEmpty() }
                    }
                    book = book.copy(chapterWords = counts).recomputedProgress()
                    pkg.coverFile?.let { coverFile ->
                        val bmp = BitmapFactory.decodeFile(coverFile.absolutePath)
                        if (bmp != null) {
                            val scaled = scaleBitmap(bmp, 700)
                            val name = "${book.id}-cover.jpg"
                            FileOutputStream(File(Paths.base, name)).use { out ->
                                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            }
                            book = book.copy(coverFile = name)
                        }
                    }
                    dest.delete() // açılmış hali yeterli
                } catch (e: Exception) {
                    dest.delete()
                    root.deleteRecursively()
                    throw e
                }
            }
            BookKind.pdf -> {
                try {
                    runCatching {
                        PDDocument.load(dest).use { doc ->
                            val info = doc.documentInformation
                            val t = info?.title?.trim().orEmpty()
                            val a = info?.author?.trim().orEmpty()
                            if (t.isNotBlank()) book = book.copy(title = t)
                            if (a.isNotBlank()) book = book.copy(author = a)
                        }
                    }
                    ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            val count = renderer.pageCount
                            if (count <= 0) throw Exception("PDF açılamadı (şifreli olabilir).")
                            book = book.copy(pagesTotal = count).recomputedProgress()
                            renderer.openPage(0).use { page ->
                                val bmp = Bitmap.createBitmap(480, 680, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val name = "${book.id}-cover.jpg"
                                FileOutputStream(File(Paths.base, name)).use { out ->
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                }
                                book = book.copy(coverFile = name)
                            }
                        }
                    }
                    contentID = BookIdentity.pdfID(dest)
                } catch (e: Exception) {
                    dest.delete()
                    throw Exception("PDF açılamadı (şifreli olabilir).")
                }
            }
        }

        if (contentID != null) {
            val probe = book.copy(id = contentID)
            if (Paths.file(probe).exists() || Paths.epubRoot(probe).exists() ||
                _books.value.any { it.id.equals(contentID, ignoreCase = true) && !it.isRemoteOnly }
            ) {
                Paths.file(book).delete()
                Paths.epubRoot(book).deleteRecursively()
                Paths.cover(book)?.delete()
                throw Exception("Bu kitap zaten kitaplıkta.")
            }
            book = relocate(book, contentID)
        }
        return book.copy(idChecked = true)
    }

    private fun relocate(book: Book, newID: String): Book {
        val old = book
        var nb = book.copy(id = newID)
        val oldFile = Paths.file(old)
        if (oldFile.exists()) oldFile.renameTo(Paths.file(nb))
        val oldRoot = Paths.epubRoot(old)
        if (oldRoot.exists()) oldRoot.renameTo(Paths.epubRoot(nb))
        old.coverFile?.let { c ->
            val newName = "$newID-cover.jpg"
            File(Paths.base, c).renameTo(File(Paths.base, newName))
            nb = nb.copy(coverFile = newName)
        }
        return nb
    }

    private fun isZipWithContainer(file: File): Boolean = try {
        java.util.zip.ZipFile(file).use { zip ->
            zip.getEntry("META-INF/container.xml") != null
        }
    } catch (_: Exception) {
        false
    }

    private fun isPdfHeader(file: File): Boolean = try {
        val b = ByteArray(5)
        file.inputStream().use { it.read(b) }
        String(b, Charsets.US_ASCII).startsWith("%PDF-")
    } catch (_: Exception) {
        false
    }

    private fun load() {
        if (!indexFile.exists()) return
        try {
            val raw = indexFile.readText(Charsets.UTF_8)
            _books.value = json.decodeFromString<List<Book>>(raw)
        } catch (_: Exception) {
        }
        try {
            if (deletedFile.exists()) {
                deleted = json.decodeFromString<Map<String, String>>(deletedFile.readText(Charsets.UTF_8))
            }
        } catch (_: Exception) {
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            save()
        }
    }

    fun save() {
        try {
            val data = json.encodeToString(_books.value)
            val tmp = File(Paths.base, "books.json.tmp")
            tmp.writeText(data, Charsets.UTF_8)
            tmp.renameTo(indexFile)
            deletedFile.writeText(json.encodeToString(deleted), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}

fun scaleBitmap(bmp: Bitmap, maxDimension: Int): Bitmap {
    val longest = max(bmp.width, bmp.height)
    if (longest <= maxDimension) return bmp
    val f = maxDimension.toFloat() / longest.toFloat()
    val w = max(1, (bmp.width * f).roundToInt())
    val h = max(1, (bmp.height * f).roundToInt())
    return Bitmap.createScaledBitmap(bmp, w, h, true)
}

