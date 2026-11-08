package com.kursat.reader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kursat.reader.ai.AIAssistant
import com.kursat.reader.ai.AIRequest
import com.kursat.reader.ai.BookContext
import com.kursat.reader.ai.ChapterStudyModel
import com.kursat.reader.ai.ChatModel
import com.kursat.reader.ai.HardWordsModel
import com.kursat.reader.ai.Prompts
import com.kursat.reader.ai.ResponseCache
import com.kursat.reader.model.Book
import com.kursat.reader.model.Bookmark
import com.kursat.reader.model.BookmarkStore
import com.kursat.reader.model.ColumnMode
import com.kursat.reader.model.DurationText
import com.kursat.reader.model.EditingHighlight
import com.kursat.reader.model.Highlight
import com.kursat.reader.model.HighlightStore
import com.kursat.reader.model.HighlightStyle
import com.kursat.reader.ui.shared.BookSearchModel
import com.kursat.reader.ui.shared.FoldedText
import com.kursat.reader.ui.shared.QuickDictionary
import com.kursat.reader.ui.shared.SearchHit
import com.kursat.reader.ui.shared.SearchText
import com.kursat.reader.model.LLMTier
import com.kursat.reader.model.LibraryStore
import com.kursat.reader.model.Paths
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.ReaderTheme
import com.kursat.reader.model.ReadingMode
import com.kursat.reader.model.ReadingTracker
import com.kursat.reader.model.VocabDraft
import com.kursat.reader.model.VocabStore
import com.kursat.reader.ui.ai.BookChatSheet
import com.kursat.reader.ui.ai.ChapterStudySheet
import com.kursat.reader.ui.ai.HardWordsSheet
import com.kursat.reader.ui.shared.AIPanelSheet
import com.kursat.reader.ui.shared.ReaderChromeBox
import com.kursat.reader.ui.shared.SelectionBar
import com.kursat.reader.ui.shared.ThemePanel
import com.kursat.reader.ui.shared.ToastHost
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Metin çıkarma yardımcıları (iOS PDFText birebir karşılığı)

object PDFText {
    private fun endsSentence(s: String): Boolean {
        val c = s.lastOrNull() ?: return false
        return ".?!:;\"”’)".contains(c)
    }

    /** Sayfa satırlarını paragraf gruplarına böler (satır indeksleri). */
    fun groups(lines: List<String>): List<List<Int>> {
        val maxLen = lines.maxOfOrNull { it.length } ?: 0
        fun isBreak(a: String, b: String): Boolean {
            if (a.isEmpty() || b.isEmpty()) return true
            return endsSentence(a) && a.length.toDouble() < maxLen.toDouble() * 0.8
        }
        val groups = mutableListOf<List<Int>>()
        var cur = mutableListOf<Int>()
        for (i in lines.indices) {
            if (lines[i].isEmpty()) {
                if (cur.isNotEmpty()) {
                    groups.add(cur)
                    cur = mutableListOf()
                }
                continue
            }
            val last = cur.lastOrNull()
            if (last != null && isBreak(lines[last], lines[i])) {
                groups.add(cur)
                cur = mutableListOf()
            }
            cur.add(i)
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    fun join(lines: List<String>): String {
        val out = StringBuilder()
        for (l in lines) {
            if (out.isEmpty()) {
                out.append(l)
                continue
            }
            val first = l.firstOrNull()
            if (out.endsWith("-") && first != null && first.isLowerCase()) {
                out.deleteCharAt(out.length - 1)
                out.append(l)
            } else {
                out.append(" ").append(l)
            }
        }
        return out.toString()
    }

    data class PageLinesResult(val lines: List<String>, val ranges: List<IntRange>)

    fun pageLines(text: String): PageLinesResult {
        val lines = mutableListOf<String>()
        val ranges = mutableListOf<IntRange>()
        var idx = 0
        val n = text.length
        while (idx < n) {
            var end = text.indexOf('\n', idx)
            if (end < 0) end = n
            val line = text.substring(idx, end).trim()
            lines.add(line)
            ranges.add(idx until end)
            idx = end + 1
        }
        return PageLinesResult(lines, ranges)
    }

    fun paragraphs(text: String): List<String> {
        val (lines, _) = pageLines(text)
        return groups(lines).map { g -> join(g.map { lines[it] }) }
    }

    fun paragraphAround(text: String, index: Int): String? {
        val (lines, ranges) = pageLines(text)
        if (lines.isEmpty()) return null
        var li = ranges.indexOfLast { it.first <= index }.coerceAtLeast(0)
        if (li >= lines.size) li = lines.size - 1
        for (g in groups(lines)) {
            if (g.contains(li)) {
                return join(g.map { lines[it] })
            }
        }
        return null
    }
}

data class PDFWordBox(
    val text: String,
    val charOffset: Int,
    /** Normalized [0..1] top-left origin rect on the page */
    val normRect: RectF,
    /** PDF point coordinates [x, y, width, height] with bottom-left origin (SYNC.md & iOS PDFKit uyumlu) */
    val pdfRect: List<Double>
)

data class PDFPageLayout(
    val pageIndex: Int,
    val pageWidthPt: Float,
    val pageHeightPt: Float,
    val rawText: String,
    val words: List<PDFWordBox>
)

// MARK: - PDFReaderModel

class PDFReaderModel(
    val context: Context,
    initialBook: Book,
    val pdfFile: File,
    val settings: ReaderSettings,
    val library: LibraryStore,
    val highlights: HighlightStore,
    val bookmarks: BookmarkStore,
    val tracker: ReadingTracker
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val renderMutex = Mutex()
    private val textMutex = Mutex()

    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
    private val pdfRenderer: PdfRenderer = PdfRenderer(pfd)
    private var pdDocument: PDDocument? = null

    val pageCount: Int = pdfRenderer.pageCount.coerceAtLeast(1)

    private val _book = MutableStateFlow(
        initialBook.copy(pagesTotal = pageCount).recomputedProgress()
    )
    val book: StateFlow<Book> = _book.asStateFlow()

    private val _pageIndex = MutableStateFlow(
        initialBook.position.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    )
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()

    private val _hasSelection = MutableStateFlow(false)
    val hasSelection: StateFlow<Boolean> = _hasSelection.asStateFlow()

    private val _selectedPage = MutableStateFlow<Int?>(null)
    val selectedPage: StateFlow<Int?> = _selectedPage.asStateFlow()

    private val _selectedWordRange = MutableStateFlow<IntRange?>(null)
    val selectedWordRange: StateFlow<IntRange?> = _selectedWordRange.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // Kalem (stylus), arama, dönüş noktası
    private val _penMode = MutableStateFlow(false)
    val penMode: StateFlow<Boolean> = _penMode.asStateFlow()
    data class PenResult(val highlight: Highlight, val paragraph: String)
    private val _penResult = MutableStateFlow<PenResult?>(null)
    val penResult: StateFlow<PenResult?> = _penResult.asStateFlow()
    private var penDismissJob: kotlinx.coroutines.Job? = null

    val search = BookSearchModel(scope)
    private val searchCache = mutableMapOf<Int, Pair<FoldedText, IntArray>>()
    data class FindMark(val page: Int, val range: IntRange)
    private val _findMark = MutableStateFlow<FindMark?>(null)
    val findMark: StateFlow<FindMark?> = _findMark.asStateFlow()

    private val _remoteOffer = MutableStateFlow<com.kursat.reader.model.LibraryStore.RemoteProgressOffer?>(null)
    /** Başka cihazda daha yeni bir konum varsa (senkron sessizce üzerine yazmaz, sorar) */
    val remoteOffer: StateFlow<com.kursat.reader.model.LibraryStore.RemoteProgressOffer?> = _remoteOffer.asStateFlow()

    private val returnPrefs = context.getSharedPreferences("reader_return", Context.MODE_PRIVATE)
    private val returnKey = "pdf-" + initialBook.id
    /** Atlamadan önceki sayfa: doluyken "kalıcı olsun mu?" çubuğu görünür (kitap kapansa da kalır). */
    private val _returnPage = MutableStateFlow(
        returnPrefs.getInt(returnKey, -1).takeIf { it in 0 until pdfRenderer.pageCount }
    )
    val returnPage: StateFlow<Int?> = _returnPage.asStateFlow()

    private val _editing = MutableStateFlow<EditingHighlight?>(null)
    val editing: StateFlow<EditingHighlight?> = _editing.asStateFlow()

    private val _study = MutableStateFlow<ChapterStudyModel?>(null)
    val study: StateFlow<ChapterStudyModel?> = _study.asStateFlow()

    private val _hardWords = MutableStateFlow<HardWordsModel?>(null)
    val hardWords: StateFlow<HardWordsModel?> = _hardWords.asStateFlow()

    val ai = AIAssistant()
    val chat = ChatModel(settings = settings, bookTitle = initialBook.title)
    val speech = com.kursat.reader.speech.SpeechController(context, settings).also { sc ->
        sc.source = com.kursat.reader.speech.SpeechSource(
            paragraphs = {
                val raw = getPageLayout(_pageIndex.value).rawText
                val paras = PDFText.paragraphs(raw).mapIndexed { i, t -> com.kursat.reader.speech.SpeechUnit(i, t) }
                paras to 0
            },
            highlight = { },
            advance = {
                if (_pageIndex.value >= pageCount - 1) false
                else { onPageChanged(_pageIndex.value + 1); true }
            },
            title = { _book.value.title },
            subtitle = { "Sayfa ${_pageIndex.value + 1} / $pageCount" }
        )
    }

    var wordsPerPageAvg: Double = 250.0
        private set

    var onCenterTap: (() -> Unit)? = null

    private val bitmapCache = object : LruCache<Int, Bitmap>(12) {}
    private val layoutCache = mutableMapOf<Int, PDFPageLayout>()
    private val pageSizeCache = mutableMapOf<Int, Pair<Float, Float>>()

    val bookWordsLeft: Double
        get() = max(0, pageCount - _pageIndex.value - 1).toDouble() * wordsPerPageAvg

    init {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
        chat.contextProvider = { budget -> buildChatContext(budget) }
        chat.saveNote = { saveChatNote(it) }
        search.searcher = { q, limit -> searchBook(q, limit) }
        search.open = { hit -> openSearchHit(hit) }
        computeResume()
        scope.launch {
            library.remoteOffers.collect { _remoteOffer.value = it[initialBook.id.uppercase()] }
        }
        library.update(_book.value)
        scope.launch(Dispatchers.IO) {
            runCatching {
                pdDocument = PDDocument.load(pdfFile)
                sampleWordsPerPage()
            }
        }
    }

    fun start() {
        tracker.begin(bookID = _book.value.id, position = _pageIndex.value.toDouble() * wordsPerPageAvg)
    }

    fun stop() {
        tracker.end()
    }

    fun close() {
        scope.cancel()
        runCatching { pdDocument?.close() }
        runCatching { pdfRenderer.close() }
        runCatching { pfd.close() }
    }

    fun onPageChanged(index: Int) {
        val clamped = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (clamped == _pageIndex.value && _book.value.position == clamped) return
        _pageIndex.value = clamped
        val updated = _book.value.copy(
            position = clamped,
            pagesTotal = pageCount
        ).recomputedProgress()
        _book.value = updated
        library.update(updated)
        tracker.moved(clamped.toDouble() * wordsPerPageAvg)
    }

    fun clearSelection() {
        _selectedPage.value = null
        _selectedWordRange.value = null
        _hasSelection.value = false
    }

    fun setWordSelection(page: Int, startIdx: Int, endIdx: Int) {
        val lo = min(startIdx, endIdx)
        val hi = max(startIdx, endIdx)
        _selectedPage.value = page
        _selectedWordRange.value = lo..hi
        _hasSelection.value = true
        tracker.touch()
    }

    suspend fun getPageAspectRatio(index: Int): Float = withContext(Dispatchers.IO) {
        pageSizeCache[index]?.let { (w, h) ->
            return@withContext if (h > 0f) w / h else 0.707f
        }
        renderMutex.withLock {
            runCatching {
                pdfRenderer.openPage(index.coerceIn(0, pageCount - 1)).use { page ->
                    val w = page.width.toFloat().coerceAtLeast(1f)
                    val h = page.height.toFloat().coerceAtLeast(1f)
                    pageSizeCache[index] = w to h
                    w / h
                }
            }.getOrDefault(0.707f)
        }
    }

    suspend fun renderPage(index: Int, targetWidthPx: Int = 1440): Bitmap? = withContext(Dispatchers.IO) {
        val clamped = index.coerceIn(0, pageCount - 1)
        bitmapCache.get(clamped)?.let { if (!it.isRecycled) return@withContext it }
        renderMutex.withLock {
            bitmapCache.get(clamped)?.let { if (!it.isRecycled) return@withLock it }
            runCatching {
                pdfRenderer.openPage(clamped).use { page ->
                    val pw = page.width.coerceAtLeast(1)
                    val ph = page.height.coerceAtLeast(1)
                    pageSizeCache[clamped] = pw.toFloat() to ph.toFloat()
                    val bmpW = targetWidthPx.coerceIn(720, 2200)
                    val bmpH = ((bmpW.toFloat() * ph.toFloat()) / pw.toFloat()).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmapCache.put(clamped, bmp)
                    bmp
                }
            }.getOrNull()
        }
    }

    // MARK: - Kenar boşluğu kırpma

    /** Sayfa boyutuna oranla kırpma payları (belgeden örneklenen sayfaların ortak içerik alanı). */
    data class CropInsets(val l: Float, val t: Float, val r: Float, val b: Float)

    private val _crop = MutableStateFlow<CropInsets?>(null)
    val crop: StateFlow<CropInsets?> = _crop.asStateFlow()
    private var cropComputed = false

    suspend fun ensureCrop() {
        if (cropComputed) return
        cropComputed = true
        val result = withContext(Dispatchers.IO) {
            val n = pageCount
            val picks = (0 until min(16, n)).map { it * max(n / 16, 1) }.filter { it < n }.distinct()
            var l = 1f; var t = 1f; var r = 1f; var b = 1f
            var samples = 0
            for (i in picks) {
                val m = renderMutex.withLock {
                    runCatching {
                        pdfRenderer.openPage(i).use { page ->
                            val w = 220
                            val h = ((w * page.height.toFloat()) / page.width.toFloat()).roundToInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val px = IntArray(w * h)
                            bmp.getPixels(px, 0, w, 0, 0, w, h)
                            bmp.recycle()
                            var minX = w; var maxX = -1; var minY = h; var maxY = -1
                            for (y in 0 until h) {
                                val row = y * w
                                for (x in 0 until w) {
                                    val c = px[row + x]
                                    val lum = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
                                    if (lum < 235) {
                                        if (x < minX) minX = x
                                        if (x > maxX) maxX = x
                                        if (y < minY) minY = y
                                        if (y > maxY) maxY = y
                                    }
                                }
                            }
                            if (maxX < minX || maxY < minY) null
                            else {
                                val area = ((maxX - minX + 1).toFloat() * (maxY - minY + 1)) / (w * h)
                                if (area <= 0.05f) null
                                else floatArrayOf(
                                    minX.toFloat() / w, minY.toFloat() / h,
                                    (w - 1 - maxX).toFloat() / w, (h - 1 - maxY).toFloat() / h
                                )
                            }
                        }
                    }.getOrNull()
                } ?: continue
                l = min(l, m[0]); t = min(t, m[1]); r = min(r, m[2]); b = min(b, m[3])
                samples++
            }
            if (samples < 2) null
            else {
                val pad = 0.012f
                fun f(v: Float) = (v - pad).coerceIn(0f, 0.30f)
                val c = CropInsets(f(l), f(t), f(r), f(b))
                if (c.l + c.r > 0.01f || c.t + c.b > 0.01f) c else null
            }
        }
        _crop.value = result
    }

    suspend fun getPageLayout(index: Int): PDFPageLayout = withContext(Dispatchers.IO) {
        val clamped = index.coerceIn(0, pageCount - 1)
        layoutCache[clamped]?.let { return@withContext it }
        textMutex.withLock {
            layoutCache[clamped]?.let { return@withLock it }
            val doc = pdDocument ?: runCatching { PDDocument.load(pdfFile).also { pdDocument = it } }.getOrNull()
            if (doc == null || clamped >= doc.numberOfPages) {
                val (pw, ph) = pageSizeCache[clamped] ?: (612f to 792f)
                return@withLock PDFPageLayout(clamped, pw, ph, "", emptyList())
            }
            runCatching {
                val pdPage = doc.getPage(clamped)
                val box = pdPage.mediaBox
                val pageW = box.width.coerceAtLeast(1f)
                val pageH = box.height.coerceAtLeast(1f)
                pageSizeCache[clamped] = pageW to pageH

                val words = mutableListOf<PDFWordBox>()
                val fullTextBuilder = StringBuilder()

                val stripper = object : PDFTextStripper() {
                    init {
                        sortByPosition = true
                        startPage = clamped + 1
                        endPage = clamped + 1
                    }

                    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                        if (text.isNullOrEmpty() || textPositions.isNullOrEmpty()) {
                            super.writeString(text, textPositions)
                            return
                        }
                        var i = 0
                        val n = textPositions.size
                        while (i < n) {
                            while (i < n && textPositions[i].unicode.isNullOrBlank()) i++
                            if (i >= n) break
                            val start = i
                            val wordChars = StringBuilder()
                            var minX = Float.MAX_VALUE
                            var minYTop = Float.MAX_VALUE
                            var maxX = Float.MIN_VALUE
                            var maxYBottom = Float.MIN_VALUE

                            while (i < n && !textPositions[i].unicode.isNullOrBlank()) {
                                val tp = textPositions[i]
                                wordChars.append(tp.unicode)
                                val x = tp.xDirAdj
                                val w = tp.widthDirAdj.coerceAtLeast(2f)
                                val h = (tp.heightDir * 1.25f).coerceAtLeast(8f)
                                val yTop = (tp.yDirAdj - h * 0.85f).coerceAtLeast(0f)
                                val yBottom = tp.yDirAdj + h * 0.25f
                                minX = min(minX, x)
                                minYTop = min(minYTop, yTop)
                                maxX = max(maxX, x + w)
                                maxYBottom = max(maxYBottom, yBottom)
                                i++
                            }

                            val wStr = wordChars.toString().trim()
                            if (wStr.isNotEmpty() && maxX > minX && maxYBottom > minYTop) {
                                val charOff = fullTextBuilder.length
                                fullTextBuilder.append(wStr).append(' ')
                                val norm = RectF(
                                    (minX / pageW).coerceIn(0f, 1f),
                                    (minYTop / pageH).coerceIn(0f, 1f),
                                    (maxX / pageW).coerceIn(0f, 1f),
                                    (maxYBottom / pageH).coerceIn(0f, 1f)
                                )
                                val pdfW = (maxX - minX).toDouble()
                                val pdfH = (maxYBottom - minYTop).toDouble()
                                val pdfX = minX.toDouble()
                                val pdfY = (pageH - maxYBottom).coerceAtLeast(0f).toDouble()
                                words.add(
                                    PDFWordBox(
                                        text = wStr,
                                        charOffset = charOff,
                                        normRect = norm,
                                        pdfRect = listOf(pdfX, pdfY, pdfW, pdfH)
                                    )
                                )
                            }
                        }
                        fullTextBuilder.append('\n')
                        super.writeString(text, textPositions)
                    }
                }
                val stripped = stripper.getText(doc)
                val finalRaw = if (stripped.isNotBlank()) stripped else fullTextBuilder.toString()
                PDFPageLayout(
                    pageIndex = clamped,
                    pageWidthPt = pageW,
                    pageHeightPt = pageH,
                    rawText = finalRaw,
                    words = words
                ).also { layoutCache[clamped] = it }
            }.getOrElse {
                val (pw, ph) = pageSizeCache[clamped] ?: (612f to 792f)
                PDFPageLayout(clamped, pw, ph, "", emptyList())
            }
        }
    }

    private suspend fun sampleWordsPerPage() {
        val n = pageCount
        if (n <= 0) return
        val step = max(n / 12, 1)
        val picks = (0 until min(12, n)).map { it * step }.filter { it < n }.distinct()
        val counts = mutableListOf<Int>()
        for (p in picks) {
            val layout = getPageLayout(p)
            val wc = layout.rawText.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            if (wc > 20) counts.add(wc)
        }
        if (counts.isNotEmpty()) {
            wordsPerPageAvg = max(80.0, counts.sum().toDouble() / counts.size.toDouble())
        }
    }

    private suspend fun textOfPages(range: IntRange): String {
        val parts = mutableListOf<String>()
        for (p in range) {
            if (p !in 0 until pageCount) continue
            val raw = getPageLayout(p).rawText
            if (raw.isNotBlank()) {
                val joined = PDFText.paragraphs(raw).joinToString("\n\n")
                if (joined.isNotBlank()) parts.add(joined)
            }
        }
        return parts.joinToString("\n\n")
    }

    private suspend fun selectionContext(): Pair<String, String>? {
        val page = _selectedPage.value ?: return null
        val range = _selectedWordRange.value ?: return null
        val layout = getPageLayout(page)
        if (layout.words.isEmpty()) return null
        val lo = range.first.coerceIn(0, layout.words.lastIndex)
        val hi = range.last.coerceIn(lo, layout.words.lastIndex)
        val selectedWords = layout.words.subList(lo, hi + 1)
        val raw = selectedWords.joinToString(" ") { it.text }
            .replace(Regex("^[\\s\"'“”‘’(\\[]+|[\\s.,;:!?\"'“”‘’)\\]]+$"), "")
            .trim()
        if (raw.isEmpty()) return null

        var paragraph = raw
        val rawPageText = layout.rawText
        if (rawPageText.isNotBlank()) {
            val probe = raw.take(20)
            var idx = rawPageText.indexOf(probe, ignoreCase = true)
            if (idx < 0) idx = selectedWords.first().charOffset
            val p = PDFText.paragraphAround(rawPageText, idx.coerceAtLeast(0))
            if (!p.isNullOrBlank()) {
                paragraph = p
            }
        }
        return raw to paragraph.take(3500)
    }

    fun perform(action: SelectionAction) {
        scope.launch {
            if (action == SelectionAction.highlight || action == SelectionAction.note) {
                createHighlight(openNote = action == SelectionAction.note)
                return@launch
            }
            val (text, paragraph) = selectionContext() ?: return@launch
            val fastCfg = settings.current.config(LLMTier.fast)
            val b = _book.value
            when (action) {
                SelectionAction.dictionary -> lookUp(text, paragraph)
                SelectionAction.xray -> askBook(Prompts.xray(text))
                SelectionAction.critique -> {
                    val smart = settings.current.config(LLMTier.smart)
                    ai.start(
                        AIRequest(
                            title = "Eleştirel okuma",
                            system = Prompts.assistantSystem,
                            user = Prompts.critique(text, paragraph, b.title),
                            config = smart,
                            maxTokens = 2048,
                            cacheKey = ResponseCache.key("critique", smart.cacheID, text, paragraph)
                        )
                    )
                }
                SelectionAction.explain -> {
                    ai.start(
                        AIRequest(
                            title = if (text.length > 40) text.take(40) + "…" else text,
                            system = Prompts.assistantSystem,
                            user = Prompts.explain(selection = text, paragraph = paragraph, bookTitle = b.title),
                            config = fastCfg,
                            maxTokens = 2048,
                            cacheKey = ResponseCache.key("explain", fastCfg.cacheID, text, paragraph),
                            vocab = VocabDraft.make(word = text, paragraph = paragraph, book = b)
                        )
                    )
                }
                SelectionAction.grammar -> {
                    val wc = text.split(Regex("\\s+")).count { it.isNotEmpty() }
                    val target = if (wc >= 4) text else paragraph
                    ai.start(
                        AIRequest(
                            title = "Cümle çözümü",
                            system = Prompts.assistantSystem,
                            user = Prompts.grammar(text = target, bookTitle = b.title),
                            config = fastCfg,
                            maxTokens = 2048,
                            cacheKey = ResponseCache.key("grammar", fastCfg.cacheID, target)
                        )
                    )
                }
                SelectionAction.translateParagraph -> {
                    ai.start(
                        AIRequest(
                            title = "Paragraf çevirisi",
                            system = Prompts.translatorSystem,
                            user = Prompts.translateParagraph(paragraph = paragraph),
                            config = fastCfg,
                            maxTokens = 2048,
                            cacheKey = ResponseCache.key("para", fastCfg.cacheID, paragraph)
                        )
                    )
                }
                SelectionAction.highlight, SelectionAction.note -> {}
            }
        }
    }

    private suspend fun createHighlight(openNote: Boolean) {
        val page = _selectedPage.value ?: return
        val range = _selectedWordRange.value ?: return
        val h = buildHighlight(page, range, null) ?: return
        clearSelection()
        if (openNote) {
            _editing.value = EditingHighlight(highlight = h, focusNote = true)
        }
    }

    /** Sayfadaki kelime aralığından vurgu kaydı üretir ve ekler (seçim ve kalem ortak kullanır). */
    private suspend fun buildHighlight(page: Int, range: IntRange, style: HighlightStyle?): Highlight? {
        val layout = getPageLayout(page)
        if (layout.words.isEmpty()) return null
        val lo = range.first.coerceIn(0, layout.words.lastIndex)
        val hi = range.last.coerceIn(lo, layout.words.lastIndex)
        val selectedWords = layout.words.subList(lo, hi + 1)
        val text = selectedWords.joinToString(" ") { it.text }.trim()
        if (text.isEmpty()) return null

        // Aynı satırdaki kelimeleri tek dikdörtgende birleştir (PDFReader.swift selectionsByLine gibi)
        val lineRects = mutableListOf<List<Double>>()
        var curRect: DoubleArray? = null
        for (w in selectedWords) {
            val r = w.pdfRect
            if (r.size != 4) continue
            val rx = r[0]
            val ry = r[1]
            val rw = r[2]
            val rh = r[3]
            val cur = curRect
            if (cur == null) {
                curRect = doubleArrayOf(rx, ry, rw, rh)
            } else {
                val sameLine = abs(ry - cur[1]) < max(rh, cur[3]) * 0.65
                if (sameLine) {
                    val minX = min(cur[0], rx)
                    val minY = min(cur[1], ry)
                    val maxX = max(cur[0] + cur[2], rx + rw)
                    val maxY = max(cur[1] + cur[3], ry + rh)
                    curRect = doubleArrayOf(minX, minY, maxX - minX, maxY - minY)
                } else {
                    lineRects.add(cur.toList())
                    curRect = doubleArrayOf(rx, ry, rw, rh)
                }
            }
        }
        curRect?.let { lineRects.add(it.toList()) }

        val h = Highlight(
            bookID = _book.value.id,
            bookTitle = _book.value.title,
            text = text,
            color = settings.current.highlightColor,
            style = style,
            page = page,
            rects = lineRects,
            location = "Sayfa ${page + 1}"
        )
        highlights.add(h)
        return h
    }

    // MARK: - Kitaba hazır soru

    // Uzun aradan sonra açılınca "son kaldığın yeri hatırlat" önerisi için geçen gün sayısı (null: gösterme)
    private val _resumeDays = MutableStateFlow<Int?>(null)
    val resumeDays: StateFlow<Int?> = _resumeDays.asStateFlow()

    private fun computeResume() {
        val b = _book.value
        if (b.isUnread || b.progress >= 0.98) return
        val t = b.progressUpdatedAt?.let { com.kursat.reader.model.IsoDate.parseOrNull(it) } ?: return
        val d = java.time.temporal.ChronoUnit.DAYS.between(t, java.time.Instant.now()).toInt()
        if (d < 3) return
        _resumeDays.value = d
        scope.launch {
            kotlinx.coroutines.delay(20_000)
            _resumeDays.value = null
        }
    }

    fun askResume() {
        _resumeDays.value = null
        askBook(Prompts.resume)
    }

    fun dismissResume() { _resumeDays.value = null }

    val relationMap = com.kursat.reader.ui.shared.RelationMapModel()

    fun showRelationMap() {
        relationMap.start(_book.value.title, settings) { budget -> buildChatContext(budget) }
    }

    private val _openChat = MutableStateFlow(false)
    val openChat: StateFlow<Boolean> = _openChat.asStateFlow()

    fun askBook(prompt: String) {
        chat.pendingPrompt = prompt
        _openChat.value = true
    }

    fun chatOpened() { _openChat.value = false }

    // MARK: - Sözlük / yapay zekâ yardımcıları

    private fun explainAI(text: String, paragraph: String) {
        val fastCfg = settings.current.config(LLMTier.fast)
        val b = _book.value
        ai.start(
            AIRequest(
                title = if (text.length > 40) text.take(40) + "…" else text,
                system = Prompts.assistantSystem,
                user = Prompts.explain(selection = text, paragraph = paragraph, bookTitle = b.title),
                config = fastCfg,
                maxTokens = 2048,
                cacheKey = ResponseCache.key("explain", fastCfg.cacheID, text, paragraph),
                vocab = VocabDraft.make(word = text, paragraph = paragraph, book = b)
            )
        )
    }

    private fun translateParagraphAI(paragraph: String) {
        val fastCfg = settings.current.config(LLMTier.fast)
        ai.start(
            AIRequest(
                title = "Paragraf çevirisi",
                system = Prompts.translatorSystem,
                user = Prompts.translateParagraph(paragraph = paragraph),
                config = fastCfg,
                maxTokens = 2048,
                cacheKey = ResponseCache.key("para", fastCfg.cacheID, paragraph)
            )
        )
    }

    /** Kelimeyi cihazdaki sözlük uygulamasında açar; yoksa (veya ifade uzunsa) yapay zekâ açıklamasına düşer. */
    private fun lookUp(text: String, paragraph: String) {
        val term = QuickDictionary.term(text)
        if (term != null && QuickDictionary.open(context, term)) return
        if (term != null) showToast("Sözlük uygulaması yok, yapay zekâya soruluyor")
        explainAI(text, paragraph)
    }

    // MARK: - Dönüş noktası

    private fun setReturn(p: Int?) {
        _returnPage.value = p
        returnPrefs.edit().apply { if (p == null) remove(returnKey) else putInt(returnKey, p) }.apply()
    }

    /** Yer imi / vurgu / arama gibi atlamalar: önceki sayfa dönüş noktası olarak saklanır. */
    fun jumpTo(page: Int) {
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (_returnPage.value == null && target != _pageIndex.value) setReturn(_pageIndex.value)
        clearSelection()
        onPageChanged(target)
    }

    fun keepJump() = setReturn(null)

    // MARK: - Başka cihazdaki konum

    val remoteLabel: String get() = _remoteOffer.value?.let { "Sayfa ${it.position + 1}" } ?: ""

    fun acceptRemote() {
        val o = _remoteOffer.value ?: return
        library.clearRemoteOffer(_book.value.id)
        jumpTo(o.position)   // önceki sayfa dönüş noktası olarak saklanır
    }

    fun keepLocalPosition() = library.keepLocalProgress(_book.value.id)

    fun returnFromJump() {
        val p = _returnPage.value ?: return
        setReturn(null)
        clearSelection()
        onPageChanged(p)
    }

    // MARK: - Kitapta arama

    private suspend fun pageSearchIndex(page: Int): Pair<FoldedText, IntArray> {
        searchCache[page]?.let { return it }
        val layout = getPageLayout(page)
        val starts = IntArray(layout.words.size)
        val sb = StringBuilder()
        layout.words.forEachIndexed { i, w ->
            if (sb.isNotEmpty()) sb.append(' ')
            starts[i] = sb.length
            sb.append(w.text)
        }
        val r = FoldedText(sb.toString()) to starts
        searchCache[page] = r
        return r
    }

    private suspend fun searchBook(q: String, limit: Int): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        for (page in 0 until pageCount) {
            if (out.size >= limit) break
            val (text, starts) = pageSearchIndex(page)
            if (starts.isEmpty()) continue
            for (m in SearchText.matches(text, q, limit - out.size)) {
                // eşleşmenin başladığı ve bittiği kelimeyi bul
                val lo = (starts.indexOfLast { it <= m.start }).coerceAtLeast(0)
                val hi = (starts.indexOfLast { it < m.end }).coerceAtLeast(lo)
                out.add(SearchHit("Sayfa ${page + 1}", m.before, m.match, m.after, page = page, wordRange = lo..hi))
            }
        }
        return out
    }

    fun openSearchHit(hit: SearchHit) {
        val page = hit.page ?: return
        jumpTo(page)
        val r = hit.wordRange ?: return
        val mark = FindMark(page, r)
        _findMark.value = mark
        scope.launch {
            kotlinx.coroutines.delay(8000)
            if (_findMark.value == mark) _findMark.value = null
        }
    }

    // MARK: - Kalem (stylus)

    fun setPenMode(on: Boolean) {
        _penMode.value = on
        if (!on) dismissPenResult()
    }

    fun togglePenMode() = setPenMode(!_penMode.value)

    /** Normalleştirilmiş (0..1) çizgi noktalarını kelime aralığına çevirip vurgu oluşturur. */
    fun penStroke(page: Int, pts: List<Pair<Float, Float>>) {
        scope.launch {
            val style = settings.current.penStyle
            val layout = getPageLayout(page)
            if (layout.words.isEmpty()) return@launch
            // Altı çizgi metnin biraz altına çizilir: noktayı yukarı kaydırarak satırı bul
            val dys = if (style == HighlightStyle.highlight) listOf(0f, 0.004f, -0.004f) else listOf(-0.005f, -0.011f, -0.018f, 0.003f)
            var lo = Int.MAX_VALUE
            var hi = -1
            for (p in pts) {
                for (dy in dys) {
                    val i = findWordAt(layout.words, p.first, p.second + dy)
                    if (i != null) { lo = min(lo, i); hi = max(hi, i); break }
                }
            }
            if (hi < 0) return@launch
            val h = buildHighlight(page, lo..hi, if (style == HighlightStyle.highlight) null else style) ?: return@launch
            tracker.touch()
            val raw = layout.rawText
            val para = if (raw.isNotBlank()) {
                val idx = raw.indexOf(h.text.take(20), ignoreCase = true).let { if (it < 0) layout.words[lo].charOffset else it }
                PDFText.paragraphAround(raw, idx.coerceAtLeast(0)) ?: h.text
            } else h.text
            showPenResult(PenResult(h, para.take(3500)))
        }
    }

    private fun showPenResult(r: PenResult) {
        _penResult.value = r
        penDismissJob?.cancel()
        penDismissJob = scope.launch {
            kotlinx.coroutines.delay(7000)
            _penResult.value = null
        }
    }

    fun dismissPenResult() {
        penDismissJob?.cancel()
        _penResult.value = null
    }

    fun penAction(action: SelectionAction) {
        val r = _penResult.value ?: return
        dismissPenResult()
        when (action) {
            SelectionAction.note -> _editing.value = EditingHighlight(highlight = r.highlight, focusNote = true)
            SelectionAction.translateParagraph -> translateParagraphAI(r.paragraph.ifEmpty { r.highlight.text })
            SelectionAction.dictionary -> lookUp(r.highlight.text, r.paragraph)
            else -> explainAI(r.highlight.text, r.paragraph)
        }
    }

    fun undoPen() {
        val r = _penResult.value ?: return
        dismissPenResult()
        deleteHighlight(r.highlight)
    }

    fun saveHighlight(h: Highlight) {
        highlights.update(h)
        settings.update { it.copy(highlightColor = h.color) }
    }

    fun deleteHighlight(h: Highlight) {
        highlights.delete(listOf(h.id))
    }

    fun setEditing(edit: EditingHighlight?) {
        _editing.value = edit
    }

    /** Sohbet yanıtını bulunduğun sayfaya not olarak yer imlerine kaydeder. */
    fun saveChatNote(text: String) {
        val cur = _pageIndex.value
        bookmarks.add(
            Bookmark(
                bookID = _book.value.id,
                bookTitle = _book.value.title,
                snippet = text.replace("**", "").take(1500),
                page = cur,
                location = "📌 Sayfa ${cur + 1} / $pageCount",
                isNote = true
            )
        )
        _toast.value = "Not kaydedildi"
    }

    fun toggleBookmark() {
        scope.launch {
            val cur = _pageIndex.value
            val existing = bookmarks.forBook(_book.value.id).firstOrNull { it.isNote != true && it.page == cur }
            if (existing != null) {
                bookmarks.delete(listOf(existing.id))
                _toast.value = "Yer imi kaldırıldı"
                return@launch
            }
            val raw = getPageLayout(cur).rawText
            val snippet = raw.trim().split(Regex("\\s+")).joinToString(" ").take(110)
            bookmarks.add(
                Bookmark(
                    bookID = _book.value.id,
                    bookTitle = _book.value.title,
                    snippet = snippet,
                    page = cur,
                    location = "Sayfa ${cur + 1} / $pageCount"
                )
            )
            _toast.value = "Yer imi eklendi"
        }
    }

    fun showToast(msg: String?) {
        _toast.value = msg
    }

    fun translateCurrentPage() {
        scope.launch {
            val cur = _pageIndex.value
            val t = textOfPages(cur..cur)
            if (t.isBlank()) {
                ai.showError(
                    title = "Sayfa çevirisi",
                    message = "Bu sayfada seçilebilir metin yok (taranmış PDF olabilir)."
                )
                return@launch
            }
            val fastCfg = settings.current.config(LLMTier.fast)
            ai.start(
                AIRequest(
                    title = "Sayfa ${cur + 1} çevirisi",
                    system = Prompts.translatorSystem,
                    user = Prompts.translatePDFPage(t),
                    config = fastCfg,
                    maxTokens = 4096,
                    cacheKey = ResponseCache.key("pdfpage", fastCfg.cacheID, t)
                )
            )
        }
    }

    fun summarize(pages: Int) {
        scope.launch {
            val cur = _pageIndex.value
            val end = min(cur + pages, pageCount)
            val t = textOfPages(cur until end).take(90_000)
            if (t.isBlank()) {
                ai.showError(
                    title = "Özet",
                    message = "Bu sayfalarda seçilebilir metin yok (taranmış PDF olabilir)."
                )
                return@launch
            }
            val scopeLabel = if (pages == 1) "Sayfa ${cur + 1}" else "Sayfa ${cur + 1}–$end"
            val smartCfg = settings.current.config(LLMTier.smart)
            ai.start(
                AIRequest(
                    title = "Özet: $scopeLabel",
                    system = Prompts.summarySystem,
                    user = Prompts.summarize(text = t, scope = scopeLabel, bookTitle = _book.value.title),
                    config = smartCfg,
                    maxTokens = 2048,
                    cacheKey = ResponseCache.key(
                        "pdfsum",
                        smartCfg.cacheID,
                        _book.value.id,
                        cur.toString(),
                        pages.toString()
                    )
                )
            )
        }
    }

    private val _isGeneratingInsights = MutableStateFlow(false)
    val isGeneratingInsights: StateFlow<Boolean> = _isGeneratingInsights.asStateFlow()

    /** Son 5 sayfadan yapay zekâ ile 3-5 içgörü çıkarıp vurgular listesine (✨ ile ayrı) ekler. */
    fun generateInsights() {
        if (_isGeneratingInsights.value) return
        _isGeneratingInsights.value = true
        val config = settings.current.config(LLMTier.smart)
        scope.launch {
            try {
                val cur = _pageIndex.value
                val start = max(0, cur - 4)
                val t = textOfPages(start..cur)
                if (t.trim().length <= 300) {
                    _toast.value = "Bu sayfalarda içgörü için yeterli metin yok"
                    return@launch
                }
                val scopeLabel = "Sayfa ${start + 1}–${cur + 1}"
                val out = com.kursat.reader.ai.LLMClient.complete(
                    config,
                    system = Prompts.assistantSystem,
                    user = Prompts.insights(t.take(80_000), _book.value.title, scopeLabel),
                    maxTokens = 1200
                )
                val n = highlights.addInsights(Prompts.parseInsights(out), _book.value.id, _book.value.title, null, cur, scopeLabel)
                _toast.value = if (n > 0) "$n içgörü eklendi" else "Yeni içgörü çıkmadı"
            } catch (e: Exception) {
                _toast.value = e.message ?: "İçgörü üretilemedi."
            } finally {
                _isGeneratingInsights.value = false
            }
        }
    }

    fun prepareStudy(lastPages: Int) {
        scope.launch {
            val cur = _pageIndex.value
            val start = max(0, cur - lastPages + 1)
            val t = textOfPages(start..cur)
            if (t.trim().length <= 300) {
                _toast.value = "Bu sayfalarda yeterli metin yok"
                return@launch
            }
            _study.value = ChapterStudyModel(
                title = "Sayfa ${start + 1}–${cur + 1}",
                text = t,
                book = _book.value,
                config = settings.current.config(LLMTier.smart),
                scopeKey = "p$start-$cur"
            ).also { it.insightTarget = com.kursat.reader.ai.InsightTarget(chapter = null, page = cur, location = "Sayfa ${start + 1}–${cur + 1}") }
        }
    }

    fun dismissStudy() {
        _study.value = null
    }

    fun prepareHardWords(pages: Int) {
        scope.launch {
            val cur = _pageIndex.value
            val end = min(cur + pages, pageCount)
            val t = textOfPages(cur until end)
            if (t.isBlank()) {
                _toast.value = "Bu sayfalarda seçilebilir metin yok"
                return@launch
            }
            _hardWords.value = HardWordsModel(
                title = "Zor kelimeler: sayfa ${cur + 1}–$end",
                text = t,
                book = _book.value,
                config = settings.current.config(LLMTier.smart),
                scopeKey = "p$cur-$pages"
            )
        }
    }

    fun dismissHardWords() {
        _hardWords.value = null
    }

    suspend fun buildChatContext(budget: Int): BookContext {
        val upto = _pageIndex.value.coerceAtLeast(0)
        val sections = mutableListOf<Pair<String, String>>()
        for (i in 0..upto) {
            val raw = getPageLayout(i).rawText
            if (raw.isNotBlank()) {
                val body = PDFText.paragraphs(raw).joinToString("\n\n")
                if (body.isNotBlank()) {
                    sections.add("Sayfa ${i + 1}" to body)
                }
            }
        }
        return BookContext.assemble(
            sections = sections,
            budget = budget,
            label = "Sayfa ${upto + 1}/$pageCount"
        )
    }
}

// MARK: - Görünümler (PDFLoader & PDFReaderView)

@Composable
fun PDFLoader(
    book: Book,
    library: LibraryStore,
    settings: ReaderSettings,
    highlights: HighlightStore,
    vocab: VocabStore,
    bookmarks: BookmarkStore,
    tracker: ReadingTracker,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var model by remember(book.id) { mutableStateOf<PDFReaderModel?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(book.id) {
        if (model != null) return@LaunchedEffect
        try {
            val file = Paths.file(book)
            if (!file.exists()) {
                error = "PDF dosyası bulunamadı."
                return@LaunchedEffect
            }
            val m = PDFReaderModel(
                context = context,
                initialBook = book,
                pdfFile = file,
                settings = settings,
                library = library,
                highlights = highlights,
                bookmarks = bookmarks,
                tracker = tracker
            )
            val jump = highlights.pendingJump
            if (jump != null && jump.bookID.equals(book.id, ignoreCase = true)) {
                highlights.pendingJump = null
                jump.page?.let { m.jumpTo(it) }   // kaldığın sayfa dönüş noktası olarak saklanır
            }
            model = m
        } catch (e: Exception) {
            error = e.message ?: "PDF açılamadı"
        }
    }

    DisposableEffect(book.id) {
        onDispose {
            model?.close()
        }
    }

    when {
        model != null -> {
            PDFReaderView(
                model = model!!,
                settings = settings,
                vocab = vocab,
                bookmarks = bookmarks,
                tracker = tracker,
                onBack = onBack
            )
        }
        error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("PDF açılamadı", style = MaterialTheme.typography.titleMedium)
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onBack) { Text("Geri dön") }
                }
            }
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text("PDF açılıyor…")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFReaderView(
    model: PDFReaderModel,
    settings: ReaderSettings,
    vocab: VocabStore,
    bookmarks: BookmarkStore,
    tracker: ReadingTracker,
    onBack: () -> Unit
) {
    val settingsState by settings.state.collectAsState()
    val book by model.book.collectAsState()
    val pageIndex by model.pageIndex.collectAsState()
    val hasSelection by model.hasSelection.collectAsState()
    val toast by model.toast.collectAsState()
    val editing by model.editing.collectAsState()
    val studyModel by model.study.collectAsState()
    val hardWordsModel by model.hardWords.collectAsState()
    val penMode by model.penMode.collectAsState()
    val penResult by model.penResult.collectAsState()
    val returnPage by model.returnPage.collectAsState()
    val remoteOffer by model.remoteOffer.collectAsState()
    val resumeDays by model.resumeDays.collectAsState()
    val openChat by model.openChat.collectAsState()
    val settingsStateForPen by settings.state.collectAsState()

    var chrome by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showWrapUp by remember { mutableStateOf(false) }
    DisposableEffect(model) {
        val inner = model.search.open
        model.search.open = { hit -> showSearch = false; inner(hit) }
        onDispose { model.search.open = inner }
    }
    com.kursat.reader.ui.shared.ImmersiveBars(hidden = !chrome)
    var showTheme by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    LaunchedEffect(openChat) {
        if (openChat) { showChat = true; model.chatOpened() }
    }
    var showHighlights by remember { mutableStateOf(false) }
    var notesTab by remember { mutableIntStateOf(0) }
    var showETA by remember { mutableStateOf(false) }
    var showAiMenu by remember { mutableStateOf(false) }

    val isPaged = settingsState.readingMode == ReadingMode.paged
    val isDouble = settingsState.columns == ColumnMode.double
    val spreadCount = if (isDouble) (model.pageCount + 1) / 2 else model.pageCount
    val currentSpread = if (isDouble) pageIndex / 2 else pageIndex

    val pagerState = rememberPagerState(
        initialPage = currentSpread.coerceIn(0, (spreadCount - 1).coerceAtLeast(0)),
        pageCount = { spreadCount.coerceAtLeast(1) }
    )
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentSpread.coerceIn(0, (spreadCount - 1).coerceAtLeast(0))
    )

    // Pager / LazyList -> model.pageIndex senkronu
    LaunchedEffect(pagerState, isPaged, isDouble) {
        if (!isPaged) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { spread ->
                val targetPage = if (isDouble) spread * 2 else spread
                if (pageIndex / (if (isDouble) 2 else 1) != spread) {
                    model.clearSelection()
                    model.onPageChanged(targetPage)
                }
            }
    }

    LaunchedEffect(listState, isPaged, isDouble) {
        if (isPaged) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { spread ->
                val targetPage = if (isDouble) spread * 2 else spread
                if (pageIndex / (if (isDouble) 2 else 1) != spread) {
                    model.onPageChanged(targetPage)
                }
            }
    }

    // model.pageIndex -> Pager / LazyList senkronu (Slider, yer imi, vurgu atlaması için)
    LaunchedEffect(pageIndex, isPaged, isDouble) {
        val targetSpread = (if (isDouble) pageIndex / 2 else pageIndex)
            .coerceIn(0, (spreadCount - 1).coerceAtLeast(0))
        if (isPaged) {
            if (pagerState.currentPage != targetSpread) {
                pagerState.scrollToPage(targetSpread)
            }
        } else {
            if (listState.firstVisibleItemIndex != targetSpread) {
                listState.scrollToItem(targetSpread)
            }
        }
    }

    BackHandler(enabled = isPaged && !chrome) {
        chrome = true
    }

    DisposableEffect(model) {
        model.start()
        model.onCenterTap = { chrome = !chrome }
        onDispose {
            model.speech.release()
            model.stop()
        }
    }

    fun goToPage(target: Int) {
        val clamped = target.coerceIn(0, (model.pageCount - 1).coerceAtLeast(0))
        model.clearSelection()
        model.onPageChanged(clamped)
    }

    val bookBookmarks by remember(book.id) {
        derivedStateOf { bookmarks }
    }
    val allBookmarks by bookmarks.items.collectAsState()
    val isCurrentPageBookmarked = remember(allBookmarks, book.id, pageIndex) {
        allBookmarks.any { it.bookID.equals(book.id, ignoreCase = true) && it.isNote != true && it.page == pageIndex }
    }

    com.kursat.reader.ui.shared.KeepScreenAwake(settings, model.speech)
    ReaderChromeBox(settings = settings) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(settingsState.theme.color)
        ) {
            if (isPaged) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !hasSelection,
                    modifier = Modifier.fillMaxSize()
                ) { spreadIdx ->
                    if (isDouble) {
                        val leftPage = spreadIdx * 2
                        val rightPage = leftPage + 1
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                PDFSinglePageSurface(
                                    model = model,
                                    pageIndex = leftPage,
                                    theme = settingsState.theme,
                                    edgeTapEnabled = true,
                                    onPrevPage = { goToPage(leftPage - 2) },
                                    onNextPage = { goToPage(leftPage + 2) },
                                    onCenterTap = { chrome = !chrome }
                                )
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                if (rightPage < model.pageCount) {
                                    PDFSinglePageSurface(
                                        model = model,
                                        pageIndex = rightPage,
                                        theme = settingsState.theme,
                                        edgeTapEnabled = true,
                                        onPrevPage = { goToPage(leftPage - 2) },
                                        onNextPage = { goToPage(leftPage + 2) },
                                        onCenterTap = { chrome = !chrome }
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            PDFSinglePageSurface(
                                model = model,
                                pageIndex = spreadIdx,
                                theme = settingsState.theme,
                                edgeTapEnabled = true,
                                onPrevPage = { goToPage(spreadIdx - 1) },
                                onNextPage = { goToPage(spreadIdx + 1) },
                                onCenterTap = { chrome = !chrome }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(count = spreadCount, key = { it }) { spreadIdx ->
                        if (isDouble) {
                            val leftPage = spreadIdx * 2
                            val rightPage = leftPage + 1
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    PDFSinglePageSurface(
                                        model = model,
                                        pageIndex = leftPage,
                                        theme = settingsState.theme,
                                        edgeTapEnabled = settingsState.edgeTap,
                                        onPrevPage = { goToPage(leftPage - 2) },
                                        onNextPage = { goToPage(leftPage + 2) },
                                        onCenterTap = { chrome = !chrome }
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    if (rightPage < model.pageCount) {
                                        PDFSinglePageSurface(
                                            model = model,
                                            pageIndex = rightPage,
                                            theme = settingsState.theme,
                                            edgeTapEnabled = settingsState.edgeTap,
                                            onPrevPage = { goToPage(leftPage - 2) },
                                            onNextPage = { goToPage(leftPage + 2) },
                                            onCenterTap = { chrome = !chrome }
                                        )
                                    }
                                }
                            }
                        } else {
                            PDFSinglePageSurface(
                                model = model,
                                pageIndex = spreadIdx,
                                theme = settingsState.theme,
                                edgeTapEnabled = settingsState.edgeTap,
                                onPrevPage = { goToPage(spreadIdx - 1) },
                                onNextPage = { goToPage(spreadIdx + 1) },
                                onCenterTap = { chrome = !chrome }
                            )
                        }
                    }
                }
            }

            // Üst araç çubuğu (iOS PDFReaderView.toolbar ile birebir aynı aksiyonlar)
            AnimatedVisibility(
                visible = chrome,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                TopAppBar(
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            text = book.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = settingsState.theme.textColor
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Kitaplık",
                                tint = settingsState.theme.textColor
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { model.toggleBookmark() }) {
                            Icon(
                                if (isCurrentPageBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Yer imi",
                                tint = settingsState.theme.textColor
                            )
                        }
                        IconButton(onClick = { showHighlights = true }) {
                            Icon(
                                Icons.Default.EditNote,
                                contentDescription = "Vurgular ve yer imleri",
                                tint = settingsState.theme.textColor
                            )
                        }
                        IconButton(onClick = { showChat = true }) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = "Kitapla sohbet",
                                tint = settingsState.theme.textColor
                            )
                        }
                        val speechOn by model.speech.state.collectAsState()
                        Box {
                            IconButton(onClick = { showAiMenu = true }) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "Yapay Zekâ",
                                    tint = settingsState.theme.textColor
                                )
                            }
                            DropdownMenu(
                                expanded = showAiMenu,
                                onDismissRequest = { showAiMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Şimdiye kadar ne oldu?") },
                                    onClick = { showAiMenu = false; model.askBook(com.kursat.reader.ai.Prompts.recap) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Karakter ve kavram rehberi") },
                                    onClick = { showAiMenu = false; model.askBook(com.kursat.reader.ai.Prompts.cast) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Karakter ve ilişki haritası") },
                                    onClick = { showAiMenu = false; model.showRelationMap() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bu sayfayı özetle") },
                                    onClick = {
                                        showAiMenu = false
                                        model.summarize(pages = 1)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bu sayfadan itibaren 5 sayfa") },
                                    onClick = {
                                        showAiMenu = false
                                        model.summarize(pages = 5)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bu sayfadan itibaren 15 sayfa") },
                                    onClick = {
                                        showAiMenu = false
                                        model.summarize(pages = 15)
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Pekiştir: son 10 sayfa (özet + quiz)") },
                                    onClick = {
                                        showAiMenu = false
                                        model.prepareStudy(lastPages = 10)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Pekiştir: son 25 sayfa") },
                                    onClick = {
                                        showAiMenu = false
                                        model.prepareStudy(lastPages = 25)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Zor kelimeler (5 sayfa)") },
                                    onClick = {
                                        showAiMenu = false
                                        model.prepareHardWords(pages = 5)
                                    }
                                )
                            }
                        }
                        val speechStateTop by model.speech.state.collectAsState()
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Diğer", tint = settingsState.theme.textColor)
                            }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                DropdownMenuItem(text = { Text("Kitapta ara") }, onClick = { showOverflow = false; showSearch = true })
                                DropdownMenuItem(
                                    text = { Text("Kalem modu" + if (penMode) "  ✓" else "") },
                                    onClick = { showOverflow = false; model.togglePenMode() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sesli oku" + if (speechStateTop != com.kursat.reader.speech.SpeechController.State.idle) "  ✓" else "") },
                                    onClick = { showOverflow = false; model.speech.toggle() }
                                )
                                DropdownMenuItem(text = { Text("Sayfayı çevir") }, onClick = { showOverflow = false; model.translateCurrentPage() })
                                DropdownMenuItem(text = { Text("Görünüm") }, onClick = { showOverflow = false; showTheme = true })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = settingsState.theme.color.copy(alpha = 0.96f)
                    )
                )
            }

            // Seslendirme oynatıcısı
            val speechState by model.speech.state.collectAsState()
            AnimatedVisibility(
                visible = speechState != com.kursat.reader.speech.SpeechController.State.idle,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chrome) 84.dp else 20.dp)
            ) {
                com.kursat.reader.ui.shared.SpeechBar(model.speech, settings)
            }

            // Seçim çubuğu
            AnimatedVisibility(
                visible = hasSelection,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chrome) 84.dp else 24.dp)
            ) {
                SelectionBar(
                    onXray = { model.perform(SelectionAction.xray) },
                    onDictionary = { model.perform(SelectionAction.dictionary) },
                    onExplain = { model.perform(SelectionAction.explain) },
                    onTranslate = { model.perform(SelectionAction.translateParagraph) },
                    onHighlight = { model.perform(SelectionAction.highlight) },
                    onNote = { model.perform(SelectionAction.note) },
                    onGrammar = { model.perform(SelectionAction.grammar) }
                )
            }


            // Kalem paleti (kalem modunda üstte)
            AnimatedVisibility(
                visible = penMode,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (chrome) 64.dp else 12.dp)
            ) {
                com.kursat.reader.ui.shared.PenPalette(
                    style = settingsStateForPen.penStyle,
                    color = settingsStateForPen.highlightColor,
                    onStyle = { st -> settings.update { it.copy(penStyle = st) } },
                    onColor = { c -> settings.update { it.copy(highlightColor = c) } },
                    onClose = { model.setPenMode(false) }
                )
            }

            // Kalemle çizince çıkan kısa menü
            AnimatedVisibility(
                visible = penResult != null && !hasSelection,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chrome) 84.dp else 24.dp)
            ) {
                com.kursat.reader.ui.shared.PenResultBar(
                    showDictionary = penResult?.let { com.kursat.reader.ui.shared.QuickDictionary.term(it.highlight.text) } != null,
                    onDictionary = { model.penAction(SelectionAction.dictionary) },
                    onTranslate = { model.penAction(SelectionAction.translateParagraph) },
                    onExplain = { model.penAction(SelectionAction.explain) },
                    onNote = { model.penAction(SelectionAction.note) },
                    onUndo = { model.undoPen() }
                )
            }

            // "Buraya atladın · Kalıcı olsun mu?" çubuğu (sürüklenebilir)
            if (returnPage != null) {
                com.kursat.reader.ui.shared.DraggableJumpBar(
                    from = "Sayfa ${(returnPage ?: 0) + 1}",
                    onKeep = { model.keepJump() },
                    onBack = { model.returnFromJump() },
                    bottomPadding = if (chrome) 130.dp else 84.dp
                )
            }


            // Başka cihazda daha yeni konum varsa sor
            if (remoteOffer != null) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = (if (chrome) 64.dp else 12.dp) + (if (penMode) 56.dp else 0.dp))
                ) {
                    com.kursat.reader.ui.shared.RemoteProgressBar(
                        label = model.remoteLabel,
                        onAccept = { model.acceptRemote() },
                        onKeep = { model.keepLocalPosition() }
                    )
                }
            }

            // Uzun aradan sonra "son kaldığın yeri hatırlat" önerisi
            resumeDays?.let { d ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = (if (chrome) 64.dp else 12.dp) + (if (penMode) 56.dp else 0.dp) + (if (remoteOffer != null) 66.dp else 0.dp))
                ) {
                    com.kursat.reader.ui.shared.ResumeBar(days = d, onAsk = { model.askResume() }, onDismiss = { model.dismissResume() })
                }
            }

            // Son sayfada "kitap karnesi" önerisi
            AnimatedVisibility(
                visible = pageIndex >= model.pageCount - 1 && model.pageCount > 1 && !hasSelection,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chrome) 130.dp else 84.dp)
            ) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.clickable { showWrapUp = true }
                ) {
                    Text(
                        "Kitabı bitirdin: karneni gör",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }

            // Alt çubuk (Durum satırı + Sayfa kaydırıcısı)
            AnimatedVisibility(
                visible = chrome,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(settingsState.theme.color.copy(alpha = 0.96f))
                        .padding(top = 4.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val statusText = if (showETA) {
                        val rem = DurationText.text(tracker.secondsToRead(model.bookWordsLeft))
                        val est = if (tracker.hasSpeedData) "" else " (tahmini)"
                        "Kitap bitimine ~$rem$est"
                    } else {
                        "Sayfa ${pageIndex + 1} / ${model.pageCount} · %${(book.progress * 100.0).roundToInt()}"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = settingsState.theme.textColor.copy(alpha = 0.65f),
                        modifier = Modifier
                            .clickable {
                                showETA = !showETA
                                tracker.touch()
                            }
                            .padding(vertical = 4.dp, horizontal = 16.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "${pageIndex + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = settingsState.theme.textColor.copy(alpha = 0.7f),
                            modifier = Modifier.width(36.dp)
                        )
                        Slider(
                            value = pageIndex.toFloat(),
                            onValueChange = { goToPage(it.roundToInt()) },
                            valueRange = 0f..max(model.pageCount - 1, 1).toFloat(),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${model.pageCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = settingsState.theme.textColor.copy(alpha = 0.7f),
                            modifier = Modifier.width(36.dp)
                        )
                    }
                }
            }

            ToastHost(
                message = toast,
                onDismiss = { model.showToast(null) },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    com.kursat.reader.ui.shared.RelationMapDialog(model.relationMap, onRegenerate = { model.showRelationMap() })
    if (showWrapUp) {
        com.kursat.reader.ui.shared.BookWrapUpSheet(book = book, onDismiss = { showWrapUp = false })
    }
    if (showSearch) {
        com.kursat.reader.ui.shared.BookSearchSheet(
            model = model.search,
            onDismiss = { showSearch = false }
        )
    }

    // AI Panel
    AIPanelSheet(ai = model.ai, vocab = vocab)

    // Görünüm (Tema) Paneli (PDF'te yazı tipi kontrolleri gizlenir: showsTextControls = false)
    if (showTheme) {
        ModalBottomSheet(
            onDismissRequest = { showTheme = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ThemePanel(settings = settings, showsTextControls = false)
        }
    }

    // Kitapla Sohbet
    if (showChat) {
        BookChatSheet(
            model = model.chat,
            onDismiss = { showChat = false }
        )
    }

    // Bölümü / Sayfaları Pekiştir
    studyModel?.let { sm ->
        ChapterStudySheet(
            model = sm,
            onDismiss = { model.dismissStudy() }
        )
    }

    // Zor Kelimeler
    hardWordsModel?.let { hw ->
        HardWordsSheet(
            model = hw,
            vocab = vocab,
            onDismiss = { model.dismissHardWords() }
        )
    }

    // Vurgular ve Yer İmleri
    if (showHighlights) {
        val bookHighlights by model.highlights.items.collectAsState()
        val filteredHighlights = remember(bookHighlights, book.id) {
            model.highlights.forBook(book.id)
        }
        val filteredBookmarks = remember(allBookmarks, book.id) {
            bookBookmarks.forBook(book.id)
        }

        ModalBottomSheet(
            onDismissRequest = { showHighlights = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.fillMaxWidth().height(540.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = notesTab == 0,
                        onClick = { notesTab = 0 },
                        label = { Text("Vurgular") }
                    )
                    FilterChip(
                        selected = notesTab == 1,
                        onClick = { notesTab = 1 },
                        label = { Text("Yer imleri") }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showHighlights = false }) {
                        Text("Kapat")
                    }
                }
                HorizontalDivider()
                if (notesTab == 0) {
                    val generating by model.isGeneratingInsights.collectAsState()
                    Column(modifier = Modifier.fillMaxSize()) {
                        com.kursat.reader.ui.shared.InsightsHeader(generating, "Son sayfaların içgörülerini çıkar") { model.generateInsights() }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (filteredHighlights.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Henüz vurgu yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredHighlights.size) { i ->
                                val h = filteredHighlights[i]
                                ListItem(
                                    headlineContent = { Text(h.text, maxLines = if (h.isInsight) 8 else 3, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Column {
                                            if (h.note.isNotBlank()) {
                                                Text(h.note, color = MaterialTheme.colorScheme.primary)
                                            }
                                            if (h.location.isNotBlank()) {
                                                Text(h.location, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    },
                                    leadingContent = {
                                        if (h.isInsight) {
                                            Text("✨")
                                        } else {
                                            Surface(
                                                modifier = Modifier.size(16.dp),
                                                shape = androidx.compose.foundation.shape.CircleShape,
                                                color = h.color.color
                                            ) {}
                                        }
                                    },
                                    trailingContent = {
                                        IconButton(onClick = { model.deleteHighlight(h) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Sil")
                                        }
                                    },
                                    modifier = Modifier.clickable {
                                        showHighlights = false
                                        h.page?.let { model.jumpTo(it) }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                                        }
                    }
                } else {
                    if (filteredBookmarks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Henüz yer imi yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredBookmarks.size) { i ->
                                val bm = filteredBookmarks[i]
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            bm.location.ifBlank { "Sayfa ${(bm.page ?: 0) + 1}" },
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    },
                                    supportingContent = {
                                        if (bm.snippet.isNotBlank()) {
                                            Text(bm.snippet, maxLines = if (bm.isNote == true) 6 else 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Bookmark,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    trailingContent = {
                                        IconButton(onClick = { bookmarks.delete(listOf(bm.id)) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Sil")
                                        }
                                    },
                                    modifier = Modifier.clickable {
                                        showHighlights = false
                                        bm.page?.let { model.jumpTo(it) }
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    // Vurgu düzenleyici
    editing?.let { ed ->
        HighlightEditorSheet(
            initial = ed.highlight,
            onSave = {
                model.saveHighlight(it)
                model.setEditing(null)
            },
            onDelete = {
                model.deleteHighlight(it)
                model.setEditing(null)
            },
            onDismiss = { model.setEditing(null) }
        )
    }
}

@Composable
private fun PDFSinglePageSurface(
    model: PDFReaderModel,
    pageIndex: Int,
    theme: ReaderTheme,
    edgeTapEnabled: Boolean,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onCenterTap: () -> Unit
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var aspectRatio by remember(pageIndex) { mutableStateOf(0.707f) }
    var pageLayout by remember(pageIndex) { mutableStateOf<PDFPageLayout?>(null) }

    val selectedPage by model.selectedPage.collectAsState()
    val selectedRange by model.selectedWordRange.collectAsState()
    val allHighlights by model.highlights.items.collectAsState()
    val book by model.book.collectAsState()
    val penMode by model.penMode.collectAsState()
    val findMark by model.findMark.collectAsState()
    val cropState by model.crop.collectAsState()
    val cropOn by model.settings.state.collectAsState().let { st -> androidx.compose.runtime.derivedStateOf { st.value.cropMargins } }
    val crop = if (cropOn) cropState else null
    androidx.compose.runtime.LaunchedEffect(cropOn) { if (cropOn) model.ensureCrop() }
    val inkPoints = remember(pageIndex) { androidx.compose.runtime.mutableStateListOf<Offset>() }

    val pageHighlights = remember(allHighlights, book.id, pageIndex) {
        model.highlights.forBook(book.id).filter { it.page == pageIndex }
    }

    LaunchedEffect(pageIndex) {
        aspectRatio = model.getPageAspectRatio(pageIndex)
        bitmap = model.renderPage(pageIndex)
        pageLayout = model.getPageLayout(pageIndex)
    }

    val themeFilter = remember(theme) { pdfColorFilterForTheme(theme) }

    // Kırpma açıkken görünür alan sayfanın içerik bölgesidir; tüm sayfa katmanı büyütülüp kaydırılır, böylece
    // dokunma/seçim/vurgu koordinatları (sayfaya göre 0..1) aynen çalışır.
    val effAspect = crop?.let { aspectRatio * (1f - it.l - it.r) / (1f - it.t - it.b) } ?: aspectRatio
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(effAspect.coerceIn(0.4f, 2.2f))
            .clipToBounds(),
        contentAlignment = Alignment.TopStart
    ) {
        val outerW = constraints.maxWidth.toFloat()
        val outerH = constraints.maxHeight.toFloat()
        val fullW = if (crop != null) outerW / (1f - crop.l - crop.r) else outerW
        val fullH = if (crop != null) outerH / (1f - crop.t - crop.b) else outerH
        val density = androidx.compose.ui.platform.LocalDensity.current
        Box(
            modifier = Modifier
                .requiredSize(with(density) { fullW.toDp() }, with(density) { fullH.toDp() })
                .offset { androidx.compose.ui.unit.IntOffset(((-(crop?.l ?: 0f)) * fullW).roundToInt(), ((-(crop?.t ?: 0f)) * fullH).roundToInt()) }
        ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Sayfa ${pageIndex + 1}",
                contentScale = ContentScale.FillBounds,
                colorFilter = themeFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .pdfThemeOverlay(theme)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(theme.color),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }

        // Vurgular + Metin Seçimi Katmanı + Dokunma / Sürükleme Algılayıcısı
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageIndex, pageLayout, pageHighlights, edgeTapEnabled) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val nx = pos.x / w
                        val ny = pos.y / h
                        val layout = pageLayout

                        // 1. Aktif seçim varsa: bir kelimeye dokunulduysa seçimi genişlet, boşluğa dokunulduysa temizle
                        if (model.hasSelection.value) {
                            if (selectedPage == pageIndex && layout != null) {
                                val hitIdx = findWordAt(layout.words, nx, ny)
                                val curRange = model.selectedWordRange.value
                                if (hitIdx != null && curRange != null) {
                                    model.setWordSelection(
                                        pageIndex,
                                        min(curRange.first, hitIdx),
                                        max(curRange.last, hitIdx)
                                    )
                                    return@detectTapGestures
                                }
                            }
                            model.clearSelection()
                            return@detectTapGestures
                        }

                        // 2. Kayıtlı bir vurguya dokunuldu mu?
                        if (layout != null) {
                            val pdfX = nx * layout.pageWidthPt
                            val pdfY = (1f - ny) * layout.pageHeightPt
                            val hitHl = pageHighlights.firstOrNull { hl ->
                                hl.rects?.any { r ->
                                    r.size == 4 &&
                                        pdfX >= r[0] && pdfX <= r[0] + r[2] &&
                                        pdfY >= r[1] && pdfY <= r[1] + r[3]
                                } == true
                            }
                            if (hitHl != null) {
                                model.setEditing(EditingHighlight(highlight = hitHl, focusNote = false))
                                return@detectTapGestures
                            }
                        }

                        // 3. Kenar dokunuşu veya orta dokunuş
                        if (edgeTapEnabled && nx > 0.7f) {
                            onNextPage()
                        } else if (edgeTapEnabled && nx < 0.3f) {
                            onPrevPage()
                        } else {
                            onCenterTap()
                        }
                    }
                }
                .pointerInput(pageIndex, pageLayout) {
                    var anchorWordIdx: Int? = null
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startOffset ->
                            val layout = pageLayout ?: return@detectDragGesturesAfterLongPress
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val nx = startOffset.x / w
                            val ny = startOffset.y / h
                            val hit = findNearestWordAt(layout.words, nx, ny)
                            if (hit != null) {
                                anchorWordIdx = hit
                                model.setWordSelection(pageIndex, hit, hit)
                            } else if (layout.words.isEmpty()) {
                                model.showToast("Bu sayfada seçilebilir metin yok (taranmış PDF olabilir)")
                            }
                        },
                        onDrag = { change, _ ->
                            val anchor = anchorWordIdx ?: return@detectDragGesturesAfterLongPress
                            val layout = pageLayout ?: return@detectDragGesturesAfterLongPress
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val nx = (change.position.x / w).coerceIn(0f, 1f)
                            val ny = (change.position.y / h).coerceIn(0f, 1f)
                            val cur = findNearestWordAt(layout.words, nx, ny)
                            if (cur != null) {
                                change.consume()
                                model.setWordSelection(pageIndex, anchor, cur)
                            }
                        },
                        onDragEnd = { anchorWordIdx = null },
                        onDragCancel = { anchorWordIdx = null }
                    )
                }
                // Kalem (stylus): kalem modunda yalnızca stylus dokunuşları çizgi olur; parmak kaydırmaya devam eder.
                // En içteki modifier olduğundan stylus olaylarını diğer jestlerden önce görür ve tüketir.
                .pointerInput(pageIndex, penMode) {
                    if (!penMode) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val first = ev.changes.firstOrNull {
                                it.type == androidx.compose.ui.input.pointer.PointerType.Stylus && it.pressed && !it.previousPressed
                            } ?: continue
                            first.consume()
                            inkPoints.clear()
                            inkPoints.add(first.position)
                            while (true) {
                                val e2 = awaitPointerEvent()
                                val ch = e2.changes.firstOrNull { it.id == first.id } ?: break
                                ch.consume()
                                inkPoints.add(ch.position)
                                if (!ch.pressed) break
                            }
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            var len = 0f
                            for (i in 1 until inkPoints.size) len += (inkPoints[i] - inkPoints[i - 1]).getDistance()
                            val minLen = 24.dp.toPx()
                            if (len > minLen) {
                                val step = max(1, inkPoints.size / 80)
                                val norm = inkPoints.filterIndexed { i, _ -> i % step == 0 || i == inkPoints.lastIndex }
                                    .map { (it.x / w).coerceIn(0f, 1f) to (it.y / h).coerceIn(0f, 1f) }
                                model.penStroke(pageIndex, norm)
                            }
                            inkPoints.clear()
                        }
                    }
                }
        ) {
            val canvasW = size.width
            val canvasH = size.height
            val layout = pageLayout
            val pageW = layout?.pageWidthPt ?: 612f
            val pageH = layout?.pageHeightPt ?: 792f

            // Kayıtlı vurguları çiz
            for (hl in pageHighlights) {
                val hlColor = hl.color.color.copy(alpha = 0.38f)
                val hlStyle = hl.effectiveStyle
                for (r in hl.rects.orEmpty()) {
                    if (r.size != 4) continue
                    val rx = (r[0].toFloat() / pageW) * canvasW
                    val rw = (r[2].toFloat() / pageW) * canvasW
                    val rh = (r[3].toFloat() / pageH) * canvasH
                    val ry = ((pageH - r[1].toFloat() - r[3].toFloat()) / pageH) * canvasH
                    if (hlStyle != com.kursat.reader.model.HighlightStyle.highlight) {
                        // Altı çizili / dalgalı çizgi: satırın altına çizilir
                        val lineColor = hl.color.color.copy(alpha = 0.95f)
                        val y = ry + rh
                        if (hlStyle == com.kursat.reader.model.HighlightStyle.underline) {
                            drawLine(color = lineColor, start = Offset(rx, y), end = Offset(rx + rw, y), strokeWidth = 3f)
                        } else {
                            val path = androidx.compose.ui.graphics.Path()
                            val wave = 10f
                            val amp = 3f
                            path.moveTo(rx, y)
                            var x = rx
                            var up = true
                            while (x < rx + rw) {
                                val nx2 = min(x + wave / 2f, rx + rw)
                                path.lineTo(nx2, y + if (up) -amp else amp)
                                x = nx2
                                up = !up
                            }
                            drawPath(path, color = lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
                        }
                        continue
                    }
                    drawRoundRect(
                        color = hlColor,
                        topLeft = Offset(rx, ry),
                        size = Size(rw, rh),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                    if (hl.note.isNotBlank()) {
                        drawLine(
                            color = hl.color.color.copy(alpha = 0.85f),
                            start = Offset(rx, ry + rh),
                            end = Offset(rx + rw, ry + rh),
                            strokeWidth = 3f
                        )
                    }
                }
            }

            // Aktif metin seçimini çiz
            if (selectedPage == pageIndex && selectedRange != null && layout != null) {
                val range = selectedRange!!
                val lo = range.first.coerceIn(0, (layout.words.size - 1).coerceAtLeast(0))
                val hi = range.last.coerceIn(lo, (layout.words.size - 1).coerceAtLeast(0))
                if (layout.words.isNotEmpty() && lo <= hi) {
                    val selColor = Color(0xFFFFBE3C).copy(alpha = 0.45f)
                    for (idx in lo..hi) {
                        val nr = layout.words[idx].normRect
                        drawRoundRect(
                            color = selColor,
                            topLeft = Offset(nr.left * canvasW, nr.top * canvasH),
                            size = Size((nr.right - nr.left) * canvasW, (nr.bottom - nr.top) * canvasH),
                            cornerRadius = CornerRadius(3f, 3f)
                        )
                    }
                }
            }

            // Arama sonucu işareti (birkaç saniye görünür)
            val fm = findMark
            if (fm != null && fm.page == pageIndex && layout != null && layout.words.isNotEmpty()) {
                val lo = fm.range.first.coerceIn(0, layout.words.lastIndex)
                val hi = fm.range.last.coerceIn(lo, layout.words.lastIndex)
                for (idx in lo..hi) {
                    val nr = layout.words[idx].normRect
                    drawRoundRect(
                        color = Color(0xFFFF7800).copy(alpha = 0.55f),
                        topLeft = Offset(nr.left * canvasW, nr.top * canvasH),
                        size = Size((nr.right - nr.left) * canvasW, (nr.bottom - nr.top) * canvasH),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                }
            }

            // Kalemle çizilen çizgi (canlı)
            if (inkPoints.size > 1) {
                val st = model.settings.current
                val inkColor = st.highlightColor.color
                val path = androidx.compose.ui.graphics.Path()
                path.moveTo(inkPoints[0].x, inkPoints[0].y)
                for (i in 1 until inkPoints.size) path.lineTo(inkPoints[i].x, inkPoints[i].y)
                val isHl = st.penStyle == com.kursat.reader.model.HighlightStyle.highlight
                drawPath(
                    path,
                    color = inkColor.copy(alpha = if (isHl) 0.35f else 1f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = if (isHl) 16.dp.toPx() else 2.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
        }
    }
    }
}

private fun findWordAt(words: List<PDFWordBox>, nx: Float, ny: Float): Int? {
    for (i in words.indices) {
        val r = words[i].normRect
        if (nx >= r.left - 0.01f && nx <= r.right + 0.01f &&
            ny >= r.top - 0.008f && ny <= r.bottom + 0.008f
        ) {
            return i
        }
    }
    return null
}

private fun findNearestWordAt(words: List<PDFWordBox>, nx: Float, ny: Float): Int? {
    findWordAt(words, nx, ny)?.let { return it }
    var bestIdx: Int? = null
    var bestDist = Float.MAX_VALUE
    for (i in words.indices) {
        val r = words[i].normRect
        val cx = (r.left + r.right) * 0.5f
        val cy = (r.top + r.bottom) * 0.5f
        val dx = nx - cx
        val dy = (ny - cy) * 2.5f // Aynı satıra öncelik ver
        val d2 = dx * dx + dy * dy
        if (d2 < bestDist && d2 < 0.03f) {
            bestDist = d2
            bestIdx = i
        }
    }
    return bestIdx
}

/** iOS PDFThemeModifier birebir karşılığı (Açık, Sepya, Koyu, Siyah, E-ink). */
private fun pdfColorFilterForTheme(theme: ReaderTheme): ColorFilter? {
    return when (theme) {
        ReaderTheme.light, ReaderTheme.sepia -> null
        ReaderTheme.dark, ReaderTheme.black -> {
            // Renk ters çevirme (Beyaz kağıt -> Siyah/Koyu arka plan, siyah yazı -> açık yazı)
            val invertMatrix = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorFilter.colorMatrix(invertMatrix)
        }
        ReaderTheme.eink -> {
            val gray = ColorMatrix().apply { setToSaturation(0f) }
            ColorFilter.colorMatrix(gray)
        }
    }
}

private fun Modifier.pdfThemeOverlay(theme: ReaderTheme): Modifier = this.drawWithContent {
    drawContent()
    when (theme) {
        ReaderTheme.light -> {}
        ReaderTheme.sepia -> {
            drawRect(
                color = Color(0xFFF4ECD8),
                blendMode = BlendMode.Multiply
            )
        }
        ReaderTheme.dark -> {
            drawRect(
                color = Color(0xFF1C1C1E),
                blendMode = BlendMode.Lighten
            )
        }
        ReaderTheme.black -> {}
        ReaderTheme.eink -> {
            drawRect(
                color = Color(0xFFE8E7E2),
                blendMode = BlendMode.Multiply
            )
        }
    }
}

