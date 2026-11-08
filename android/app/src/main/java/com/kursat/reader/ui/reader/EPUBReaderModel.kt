package com.kursat.reader.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kursat.reader.ai.AIAssistant
import com.kursat.reader.ai.AIRequest
import com.kursat.reader.ai.BookContext
import com.kursat.reader.ai.ChapterStudyModel
import com.kursat.reader.ai.ChatModel
import com.kursat.reader.ai.HardWordsModel
import com.kursat.reader.ai.LLMClient
import com.kursat.reader.ai.Prompts
import com.kursat.reader.ai.ResponseCache
import com.kursat.reader.epub.EPUBPackage
import com.kursat.reader.epub.TOCEntry
import com.kursat.reader.model.Book
import com.kursat.reader.model.Bookmark
import com.kursat.reader.model.BookmarkStore
import com.kursat.reader.model.ColumnMode
import com.kursat.reader.model.EditingHighlight
import com.kursat.reader.model.HTMLText
import com.kursat.reader.model.Highlight
import com.kursat.reader.model.HighlightStore
import com.kursat.reader.model.HighlightStyle
import com.kursat.reader.ui.shared.BookSearchModel
import com.kursat.reader.ui.shared.FoldedText
import com.kursat.reader.ui.shared.FootnoteInfo
import com.kursat.reader.ui.shared.QuickDictionary
import com.kursat.reader.ui.shared.SearchHit
import com.kursat.reader.ui.shared.SearchText
import com.kursat.reader.model.LLMTier
import com.kursat.reader.model.LibraryStore
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.ReadingMode
import com.kursat.reader.model.ReadingTracker
import com.kursat.reader.model.TranslationLayout
import com.kursat.reader.model.VocabDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class SelectionAction { explain, translateParagraph, highlight, note, grammar, dictionary, xray, critique }

fun jsString(s: String): String = JSONObject.quote(s)

@SuppressLint("SetJavaScriptEnabled")
class ReaderWebView(context: Context) : WebView(context) {
    var onMenuAction: ((SelectionAction) -> Unit)? = null
    var isPagedMode: Boolean = false

    // Kalem (stylus): kalem modundayken yalnızca stylus dokunuşları çizgi olur; parmak kaydırmaya devam eder.
    var penEnabled: Boolean = false
    var onPenStroke: ((List<Pair<Float, Float>>) -> Unit)? = null
    var inkAppearance: () -> Pair<com.kursat.reader.model.HighlightStyle, com.kursat.reader.model.HighlightColor> =
        { com.kursat.reader.model.HighlightStyle.highlight to com.kursat.reader.model.HighlightColor.yellow }
    private val inkPoints = ArrayList<Pair<Float, Float>>()
    private val inkPath = android.graphics.Path()
    private val inkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    private fun inkColorInt(c: com.kursat.reader.model.HighlightColor, alpha: Int): Int {
        val rgb = when (c) {
            com.kursat.reader.model.HighlightColor.yellow -> 0xFFD60A
            com.kursat.reader.model.HighlightColor.green -> 0x34C759
            com.kursat.reader.model.HighlightColor.blue -> 0x0A84FF
            com.kursat.reader.model.HighlightColor.pink -> 0xFF375F
            com.kursat.reader.model.HighlightColor.orange -> 0xFF9500
        }
        return (alpha shl 24) or rgb
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (penEnabled && ev.pointerCount >= 1 && ev.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_STYLUS) {
            val density = resources.displayMetrics.density
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    inkPoints.clear()
                    inkPath.reset()
                    val (style, color) = inkAppearance()
                    if (style == com.kursat.reader.model.HighlightStyle.highlight) {
                        inkPaint.color = inkColorInt(color, 90)
                        inkPaint.strokeWidth = 16f * density
                    } else {
                        inkPaint.color = inkColorInt(color, 255)
                        inkPaint.strokeWidth = 2.5f * density
                    }
                    inkPath.moveTo(ev.x, ev.y)
                    inkPoints.add(ev.x / density to ev.y / density)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    for (h in 0 until ev.historySize) {
                        val hx = ev.getHistoricalX(0, h); val hy = ev.getHistoricalY(0, h)
                        inkPath.lineTo(hx, hy)
                        inkPoints.add(hx / density to hy / density)
                    }
                    inkPath.lineTo(ev.x, ev.y)
                    inkPoints.add(ev.x / density to ev.y / density)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    inkPoints.add(ev.x / density to ev.y / density)
                    var len = 0f
                    for (i in 1 until inkPoints.size) {
                        len += kotlin.math.hypot(inkPoints[i].first - inkPoints[i - 1].first, inkPoints[i].second - inkPoints[i - 1].second)
                    }
                    val pts = ArrayList(inkPoints)
                    inkPoints.clear()
                    inkPath.reset()
                    if (len > 24f) onPenStroke?.invoke(pts)
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    inkPoints.clear()
                    inkPath.reset()
                }
            }
            invalidate()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun draw(canvas: android.graphics.Canvas) {
        super.draw(canvas)
        if (!inkPath.isEmpty) canvas.drawPath(inkPath, inkPaint)
    }

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean
    ): Boolean {
        if (isPagedMode) return false
        return super.overScrollBy(
            deltaX,
            deltaY,
            scrollX,
            scrollY,
            scrollRangeX,
            scrollRangeY,
            maxOverScrollX,
            maxOverScrollY,
            isTouchEvent
        )
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? {
        return super.startActionMode(wrapCallback(callback))
    }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        return super.startActionMode(wrapCallback(callback), type)
    }

    private fun wrapCallback(orig: ActionMode.Callback?): ActionMode.Callback2 {
        return object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                orig?.onCreateActionMode(mode, menu)
                populateCustomMenu(menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                orig?.onPrepareActionMode(mode, menu)
                populateCustomMenu(menu)
                return true
            }

            private fun populateCustomMenu(menu: Menu) {
                menu.removeGroup(999)
                menu.add(999, 1001, 1, "Açıkla")
                menu.add(999, 1002, 2, "Paragrafı Çevir")
                menu.add(999, 1003, 3, "Vurgula")
                menu.add(999, 1004, 4, "Not al")
                menu.add(999, 1005, 5, "Cümleyi çöz")
                menu.add(999, 1006, 0, "Sözlük")
                menu.add(999, 1007, 0, "Kim/Ne bu?")
                menu.add(999, 1008, 6, "Eleştirel oku")
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val act = when (item.itemId) {
                    1001 -> SelectionAction.explain
                    1002 -> SelectionAction.translateParagraph
                    1003 -> SelectionAction.highlight
                    1004 -> SelectionAction.note
                    1005 -> SelectionAction.grammar
                    1006 -> SelectionAction.dictionary
                    1007 -> SelectionAction.xray
                    1008 -> SelectionAction.critique
                    else -> null
                }
                if (act != null) {
                    onMenuAction?.invoke(act)
                    mode.finish()
                    return true
                }
                return orig?.onActionItemClicked(mode, item) ?: false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                orig?.onDestroyActionMode(mode)
            }

            override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: android.graphics.Rect?) {
                if (orig is ActionMode.Callback2) {
                    orig.onGetContentRect(mode, view, outRect)
                } else {
                    super.onGetContentRect(mode, view, outRect)
                }
            }
        }
    }
}

class EPUBReaderModel(
    val context: Context,
    initialBook: Book,
    val pkg: EPUBPackage,
    val settings: ReaderSettings,
    val library: LibraryStore,
    val highlights: HighlightStore,
    val bookmarks: BookmarkStore,
    val tracker: ReadingTracker
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _book = MutableStateFlow(initialBook)
    val book: StateFlow<Book> = _book.asStateFlow()

    private val _chapterIndex = MutableStateFlow(max(0, min(initialBook.position, pkg.spine.size - 1)))
    val chapterIndex: StateFlow<Int> = _chapterIndex.asStateFlow()

    private val _hasSelection = MutableStateFlow(false)
    val hasSelection: StateFlow<Boolean> = _hasSelection.asStateFlow()

    private val _isTranslatingPage = MutableStateFlow(false)
    val isTranslatingPage: StateFlow<Boolean> = _isTranslatingPage.asStateFlow()

    private val _translationsHidden = MutableStateFlow(false)
    val translationsHidden: StateFlow<Boolean> = _translationsHidden.asStateFlow()

    private val _alertMessage = MutableStateFlow<String?>(null)
    val alertMessage: StateFlow<String?> = _alertMessage.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _pageCur = MutableStateFlow(0)
    val pageCur: StateFlow<Int> = _pageCur.asStateFlow()

    private val _pageTotal = MutableStateFlow(0)
    val pageTotal: StateFlow<Int> = _pageTotal.asStateFlow()

    private val _editing = MutableStateFlow<EditingHighlight?>(null)
    val editing: StateFlow<EditingHighlight?> = _editing.asStateFlow()

    val ai = AIAssistant()
    val chat = ChatModel(settings = settings, bookTitle = initialBook.title)
    val speech = com.kursat.reader.speech.SpeechController(context, settings)
    private var loadWaiter: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var speechLoading = false

    private val _study = MutableStateFlow<ChapterStudyModel?>(null)
    val study: StateFlow<ChapterStudyModel?> = _study.asStateFlow()

    private val _hardWords = MutableStateFlow<HardWordsModel?>(null)
    val hardWords: StateFlow<HardWordsModel?> = _hardWords.asStateFlow()

    private val chapterTexts = mutableMapOf<Int, String>()

    // Kalem (stylus), dipnot, arama, dönüş noktası
    private val _penMode = MutableStateFlow(false)
    val penMode: StateFlow<Boolean> = _penMode.asStateFlow()
    data class PenResult(val highlight: Highlight, val paragraph: String)
    private val _penResult = MutableStateFlow<PenResult?>(null)
    val penResult: StateFlow<PenResult?> = _penResult.asStateFlow()
    private var penDismissJob: kotlinx.coroutines.Job? = null

    private val _footnote = MutableStateFlow<FootnoteInfo?>(null)
    val footnote: StateFlow<FootnoteInfo?> = _footnote.asStateFlow()

    val search = BookSearchModel(scope)
    private var searchTexts: List<FoldedText>? = null
    private var pendingFind: Triple<String, Int, String>? = null

    private val _remoteOffer = MutableStateFlow<com.kursat.reader.model.LibraryStore.RemoteProgressOffer?>(null)
    /** Başka cihazda daha yeni bir konum varsa (senkron sessizce üzerine yazmaz, sorar) */
    val remoteOffer: StateFlow<com.kursat.reader.model.LibraryStore.RemoteProgressOffer?> = _remoteOffer.asStateFlow()

    private val returnPrefs = context.getSharedPreferences("reader_return", Context.MODE_PRIVATE)
    private val returnKey = "epub-" + initialBook.id
    /** Atlamadan önceki yer (bölüm, kaydırma oranı): doluyken "kalıcı olsun mu?" çubuğu görünür. */
    data class ReturnPoint(val chapter: Int, val fraction: Double)
    private val _returnPoint = MutableStateFlow(loadReturn())
    val returnPoint: StateFlow<ReturnPoint?> = _returnPoint.asStateFlow()

    var onCenterTap: (() -> Unit)? = null

    private var restoreFraction: Double = initialBook.scrollFraction
    private var pendingFragment: String? = null
    private var pendingHighlightID: String? = null
    private var restored: Boolean = false
    private var scrollFraction: Double = initialBook.scrollFraction
    private var lastAppliedCss: String? = null
    private var lastAppliedModeJS: String? = null

    val webView: ReaderWebView = ReaderWebView(context)

    /** Kitabın son bölümünün sonuna gelindi mi (kitap karnesi önerilir) */
    val isBookEnd: Boolean
        get() = _chapterIndex.value >= pkg.spine.size - 1 && atChapterEnd

    val atChapterEnd: Boolean
        get() {
            val s = settings.current
            if (s.readingMode == ReadingMode.paged) {
                return _pageTotal.value > 1 && _pageCur.value >= _pageTotal.value
            }
            return _book.value.scrollFraction >= 0.96 && !_book.value.isUnread
        }

    val chapterWordsLeft: Double
        get() {
            val w = _book.value.chapterWords ?: return 0.0
            val idx = _chapterIndex.value
            if (idx !in w.indices) return 0.0
            return w[idx].toDouble() * (1.0 - _book.value.scrollFraction)
        }

    val bookWordsLeft: Double
        get() = max(0.0, _book.value.totalWords.toDouble() - _book.value.wordPosition)

    init {
        chat.contextProvider = { budget -> buildChatContext(budget) }
        chat.saveNote = { saveChatNote(it) }
        speech.source = com.kursat.reader.speech.SpeechSource(
            paragraphs = { speechParagraphs() },
            highlight = { id -> webView.evaluateJavascript("window.__llmSpeakMark(${id ?: "null"})", null) },
            advance = { speechAdvance() },
            title = { _book.value.title },
            subtitle = { chapterTitle(_chapterIndex.value) },
            showTranslation = { id, text ->
                webView.evaluateJavascript("window.__llmInsert($id, ${jsString(text)}); window.__llmShow();", null)
            }
        )
        setupWebView()
        computeResume()
        search.searcher = { q, limit -> searchBook(q, limit) }
        search.open = { hit -> openSearchHit(hit) }
        scope.launch {
            library.remoteOffers.collect { _remoteOffer.value = it[initialBook.id.uppercase()] }
        }
        webView.inkAppearance = {
            val s = settings.current
            s.penStyle to s.highlightColor
        }
        webView.onPenStroke = { pts -> penStroke(pts) }
        scope.launch {
            settings.state.collect {
                applyStyle()
            }
        }
        loadChapter(_chapterIndex.value, fraction = initialBook.scrollFraction)
        ensureMetrics()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
        webView.onMenuAction = { action -> perform(action) }
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun postMessage(rawJson: String) {
                mainHandler.post { handleJsMessage(rawJson) }
            }
        }, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val css = settings.current.css
                val mjs = modeJS
                lastAppliedCss = css
                lastAppliedModeJS = mjs
                val source = "$BASE_SCRIPT\nwindow.__llmSetStyle(${jsString(css)}); $mjs"
                webView.evaluateJavascript(source) {
                    applyHighlightsToPage()
                    val frag = pendingFragment
                    if (!frag.isNullOrEmpty()) {
                        webView.evaluateJavascript(
                            "(function(){var e=document.getElementById(${jsString(frag)})||document.getElementsByName(${jsString(frag)})[0]; if(e) e.scrollIntoView(); if(window.__llmSync) window.__llmSync();})()",
                            null
                        )
                    } else if (restoreFraction > 0.0 || settings.current.readingMode == ReadingMode.paged) {
                        webView.evaluateJavascript("window.__llmGoFraction($restoreFraction);", null)
                    }
                    val hid = pendingHighlightID
                    if (hid != null) {
                        pendingHighlightID = null
                        webView.evaluateJavascript("window.__llmScrollToHighlight(${jsString(hid)})", null)
                    }
                    applyReadingAids(force = true)
                    pendingFind?.let { (q, n, before) ->
                        pendingFind = null
                        webView.evaluateJavascript("window.__llmFindShow(${jsString(q)}, $n, ${jsString(before)})", null)
                    }
                    restored = true
                    loadWaiter?.complete(Unit)
                    loadWaiter = null
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "file") {
                    val path = uri.path ?: return false
                    val targetFile = try {
                        java.io.File(path).canonicalPath
                    } catch (_: Exception) {
                        path
                    }
                    val idx = pkg.spine.indexOfFirst {
                        val sp = try {
                            it.file.canonicalPath
                        } catch (_: Exception) {
                            it.file.absolutePath
                        }
                        sp == targetFile
                    }
                    if (idx >= 0 && idx != _chapterIndex.value) {
                        markReturnPoint()
                        loadChapter(idx, fragment = uri.fragment)
                        return true
                    }
                    return false
                }
                return try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }
        applyBackground()
    }

    private fun ensureMetrics() {
        if (_book.value.chapterWords != null) return
        val files = pkg.spine.map { it.file }
        scope.launch(Dispatchers.IO) {
            val counts = files.map { f ->
                HTMLText.extract(f).split(Regex("\\s+")).count { it.isNotEmpty() }
            }
            withContext(Dispatchers.Main) {
                _book.value = _book.value.copy(chapterWords = counts)
                saveProgress()
            }
        }
    }

    fun start() {
        tracker.begin(bookID = _book.value.id, position = _book.value.wordPosition)
    }

    fun stop() {
        tracker.end()
        speech.release()
    }

    // MARK: - Seslendirme

    fun toggleSpeech() {
        if (speech.isActive) speech.stop() else speech.start()
    }

    private suspend fun speechParagraphs(): Pair<List<com.kursat.reader.speech.SpeechUnit>, Int> {
        val raw = evaluateJsString("window.__llmSpeechParagraphs()") ?: return emptyList<com.kursat.reader.speech.SpeechUnit>() to 0
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("paras")
            val list = (0 until arr.length()).map {
                val p = arr.getJSONObject(it)
                com.kursat.reader.speech.SpeechUnit(p.getInt("id"), p.getString("text"))
            }
            list to o.optInt("start", 0)
        } catch (_: Exception) {
            emptyList<com.kursat.reader.speech.SpeechUnit>() to 0
        }
    }

    /** Sonraki bölümü yükler ve yüklenmesini bekler. */
    private suspend fun speechAdvance(): Boolean {
        if (_chapterIndex.value >= pkg.spine.size - 1) return false
        speechLoading = true
        val waiter = kotlinx.coroutines.CompletableDeferred<Unit>()
        loadWaiter = waiter
        loadChapter(_chapterIndex.value + 1)
        kotlinx.coroutines.withTimeoutOrNull(15_000) { waiter.await() }
        speechLoading = false
        kotlinx.coroutines.delay(450)
        return true
    }

    fun loadChapter(index: Int, fraction: Double = 0.0, fragment: String? = null) {
        val idx = max(0, min(index, pkg.spine.size - 1))
        _chapterIndex.value = idx
        restoreFraction = fraction
        scrollFraction = fraction
        pendingFragment = fragment
        restored = false
        lastAppliedCss = null
        lastAppliedModeJS = null
        _hasSelection.value = false
        _translationsHidden.value = false
        if (!speechLoading) speech.stopIfActive()

        val fileUrl = Uri.fromFile(pkg.spine[idx].file).toString()
        webView.loadUrl(fileUrl)
        saveProgress()
    }

    fun openTOC(entry: TOCEntry) {
        markReturnPoint()
        loadChapter(entry.spineIndex, fragment = entry.fragment)
    }

    // MARK: - Dönüş noktası (kalıcı: kitap kapansa da kullanıcı cevaplayana kadar kalır)

    private fun loadReturn(): ReturnPoint? {
        val raw = returnPrefs.getString(returnKey, null) ?: return null
        val parts = raw.split(":")
        val c = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val f = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return if (c in pkg.spine.indices) ReturnPoint(c, f) else null
    }

    private fun setReturn(r: ReturnPoint?) {
        _returnPoint.value = r
        returnPrefs.edit().apply {
            if (r == null) remove(returnKey) else putString(returnKey, "${r.chapter}:${r.fraction}")
        }.apply()
    }

    /** İlk atlamada mevcut yeri saklar; arka arkaya atlamalarda en baştaki yer korunur. */
    private fun markReturnPoint() {
        if (_returnPoint.value == null) setReturn(ReturnPoint(_chapterIndex.value, scrollFraction))
    }

    fun keepJump() = setReturn(null)

    // MARK: - Başka cihazdaki konum

    val remoteLabel: String get() = _remoteOffer.value?.let { chapterTitle(it.position) } ?: ""

    fun acceptRemote() {
        val o = _remoteOffer.value ?: return
        library.clearRemoteOffer(_book.value.id)
        markReturnPoint()   // istersen "Geri dön" ile bu cihazdaki yere dönebilirsin
        loadChapter(o.position, fraction = o.scrollFraction)
    }

    fun keepLocalPosition() = library.keepLocalProgress(_book.value.id)

    fun returnFromJump() {
        val r = _returnPoint.value ?: return
        setReturn(null)
        loadChapter(r.chapter, fraction = r.fraction)
    }

    val returnLabel: String get() = _returnPoint.value?.let { chapterTitle(it.chapter) } ?: ""

    fun nextChapter() {
        if (_chapterIndex.value < pkg.spine.size - 1) {
            loadChapter(_chapterIndex.value + 1)
        }
    }

    fun previousChapter() {
        if (_chapterIndex.value > 0) {
            loadChapter(_chapterIndex.value - 1)
        }
    }

    private fun saveProgress() {
        val b = _book.value.copy(
            position = _chapterIndex.value,
            scrollFraction = scrollFraction
        ).recomputedProgress()
        _book.value = b
        library.update(b)
        if (b.chapterWords != null) {
            if (restored) tracker.moved(b.wordPosition) else tracker.jumped(b.wordPosition)
        }
    }

    private fun applyBackground() {
        val s = settings.current
        val bgColor = android.graphics.Color.parseColor(s.theme.bgHex)
        webView.setBackgroundColor(bgColor)
        val eink = s.theme.isEink
        val paged = s.readingMode == ReadingMode.paged
        webView.isPagedMode = paged
        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = !eink && !paged
        webView.overScrollMode = if (eink || paged) View.OVER_SCROLL_NEVER else View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    fun applyStyle() {
        applyBackground()
        val css = settings.current.css
        val mjs = modeJS
        if (css == lastAppliedCss && mjs == lastAppliedModeJS) return
        lastAppliedCss = css
        lastAppliedModeJS = mjs
        val script = "if (window.__llmSetStyle) { window.__llmSetStyle(${jsString(css)}); $mjs }"
        webView.evaluateJavascript(script, null)
        applyReadingAids()
    }

    private val modeJS: String
        get() {
            val s = settings.current
            val paged = s.readingMode == ReadingMode.paged
            val smooth = !s.theme.isEink
            val cols = if (s.columns == ColumnMode.double) 2 else 1
            return "window.__llmSetMode($paged, ${s.edgeTap}, $smooth, ${s.margin}, $cols); window.__llmSetLayout(${jsString(s.translationLayout.name)});"
        }

    private fun handleJsMessage(rawJson: String) {
        try {
            val body = JSONObject(rawJson)
            when (body.optString("type")) {
                "scroll" -> {
                    if (!restored || !body.has("f")) return
                    scrollFraction = body.optDouble("f", 0.0)
                    saveProgress()
                }
                "selection" -> {
                    _hasSelection.value = body.optBoolean("has", false)
                    tracker.touch()
                }
                "page" -> {
                    if (body.has("cur") && body.has("total")) {
                        _pageCur.value = body.optInt("cur", 1)
                        _pageTotal.value = body.optInt("total", 1)
                    }
                    if (!restored || !body.has("f")) return
                    scrollFraction = body.optDouble("f", 0.0)
                    saveProgress()
                }
                "ruler" -> {
                    val f = body.optDouble("f", -1.0)
                    if (f > 0.05 && f < 0.95) settings.update { it.copy(rulerFraction = f) }
                }
                "note" -> {
                    val href = body.optString("href")
                    if (href.isNotEmpty()) openNote(href)
                }
                "center" -> {
                    onCenterTap?.invoke()
                }
                "hlTap" -> {
                    val id = body.optString("id")
                    val h = highlights.highlight(id)
                    if (h != null) {
                        _editing.value = EditingHighlight(highlight = h, focusNote = false)
                    }
                }
                "edge" -> {
                    if (body.optString("dir") == "next") {
                        nextChapter()
                    } else if (_chapterIndex.value > 0) {
                        loadChapter(_chapterIndex.value - 1, fraction = 1.0)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun evaluateJsString(script: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { raw ->
            if (!cont.isActive) return@evaluateJavascript
            if (raw == null || raw == "null") {
                cont.resume(null)
            } else {
                // WebView evaluateJavascript JSON-encoded string döndürür
                val unquoted = try {
                    JSONArray("[$raw]").getString(0)
                } catch (_: Exception) {
                    raw.removeSurrounding("\"")
                }
                cont.resume(unquoted)
            }
        }
    }

    data class SelectionInfo(val text: String, val paragraph: String)

    suspend fun currentSelection(): SelectionInfo? {
        val jsonStr = evaluateJsString("window.__llmSelection()") ?: return null
        return try {
            val obj = JSONObject(jsonStr)
            val text = obj.optString("text")
            val para = obj.optString("paragraph")
            if (text.isBlank()) null else SelectionInfo(text, para)
        } catch (_: Exception) {
            null
        }
    }

    fun perform(action: SelectionAction) {
        scope.launch {
            if (action == SelectionAction.highlight || action == SelectionAction.note) {
                createHighlight(openNote = action == SelectionAction.note)
                return@launch
            }
            val sel = currentSelection() ?: return@launch
            val fastCfg = settings.current.config(LLMTier.fast)
            val b = _book.value
            when (action) {
                SelectionAction.explain -> {
                    ai.start(
                        AIRequest(
                            title = if (sel.text.length > 40) sel.text.take(40) + "…" else sel.text,
                            system = Prompts.assistantSystem,
                            user = Prompts.explain(selection = sel.text, paragraph = sel.paragraph, bookTitle = b.title),
                            config = fastCfg,
                            maxTokens = 2048,
                            cacheKey = ResponseCache.key("explain", fastCfg.cacheID, sel.text, sel.paragraph),
                            vocab = VocabDraft.make(word = sel.text, paragraph = sel.paragraph, book = b)
                        )
                    )
                }
                SelectionAction.grammar -> {
                    val words = sel.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
                    val target = if (words >= 4) sel.text else sel.paragraph
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
                            user = Prompts.translateParagraph(paragraph = sel.paragraph),
                            config = fastCfg,
                            maxTokens = 2048,
                            cacheKey = ResponseCache.key("para", fastCfg.cacheID, sel.paragraph)
                        )
                    )
                }
                SelectionAction.dictionary -> lookUp(sel)
                SelectionAction.xray -> askBook(Prompts.xray(sel.text))
                SelectionAction.critique -> {
                    val smart = settings.current.config(LLMTier.smart)
                    ai.start(
                        AIRequest(
                            title = "Eleştirel okuma",
                            system = Prompts.assistantSystem,
                            user = Prompts.critique(sel.text, sel.paragraph, b.title),
                            config = smart,
                            maxTokens = 2048,
                            cacheKey = ResponseCache.key("critique", smart.cacheID, sel.text, sel.paragraph)
                        )
                    )
                }
                SelectionAction.highlight, SelectionAction.note -> {}
            }
        }
    }

    // MARK: - Kitaba hazır soru (şimdiye kadar ne oldu, karakter rehberi, kim/ne bu)

    // MARK: - Kelime ipuçları, biyonik okuma, satır odaklayıcı

    private val _isGlossing = MutableStateFlow(false)
    val isGlossing: StateFlow<Boolean> = _isGlossing.asStateFlow()
    private val _glossesHidden = MutableStateFlow(false)
    val glossesHidden: StateFlow<Boolean> = _glossesHidden.asStateFlow()
    private var appliedBionic: Boolean? = null
    private var appliedRuler: Boolean? = null

    /** Okuma yardımcılarını (biyonik, odaklayıcı) sayfaya uygular; `force` ile yeni bölüm yüklenince yeniden. */
    private fun applyReadingAids(force: Boolean = false) {
        val s = settings.current
        if (force) { appliedBionic = null; appliedRuler = null }
        if (appliedBionic != s.bionic) {
            appliedBionic = s.bionic
            webView.evaluateJavascript("window.__llmSetBionic(${s.bionic})", null)
        }
        if (appliedRuler != s.readingRuler) {
            appliedRuler = s.readingRuler
            webView.evaluateJavascript("window.__llmSetRuler(${s.readingRuler}, ${s.rulerFraction})", null)
        }
    }

    fun glossVisible() {
        if (_isGlossing.value) return
        _isGlossing.value = true
        val config = settings.current.config(LLMTier.fast)
        val level = settings.current.glossLevel
        scope.launch {
            try {
                val jsonStr = evaluateJsString("window.__llmVisibleParagraphs(true)")
                val paras = mutableListOf<VisiblePara>()
                if (!jsonStr.isNullOrBlank()) {
                    try {
                        val arr = JSONArray(jsonStr)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            paras.add(VisiblePara(id = o.optInt("id"), text = o.optString("text")))
                        }
                    } catch (_: Exception) {}
                }
                if (paras.isEmpty()) {
                    _alertMessage.value = "Ekranda ipucu eklenecek yeni paragraf yok."
                    return@launch
                }
                val found = mutableListOf<Pair<Int, List<List<String>>>>()
                val todo = mutableListOf<VisiblePara>()
                for (p in paras) {
                    val cached = ResponseCache.get(ResponseCache.key("gloss", config.cacheID, level.name, p.text))
                    val pairs = cached?.let { c ->
                        try {
                            val a = JSONArray(c)
                            (0 until a.length()).map { i -> val w = a.getJSONArray(i); listOf(w.getString(0), w.getString(1)) }
                        } catch (_: Exception) { null }
                    }
                    if (pairs != null) found.add(p.id to pairs) else todo.add(p)
                }
                val batches = mutableListOf<List<VisiblePara>>()
                var cur = mutableListOf<VisiblePara>()
                var size = 0
                for (p in todo) {
                    if (cur.isNotEmpty() && (size + p.text.length > 3000 || cur.size >= 8)) { batches.add(cur); cur = mutableListOf(); size = 0 }
                    cur.add(p); size += p.text.length
                }
                if (cur.isNotEmpty()) batches.add(cur)
                val textByID = todo.associate { it.id to it.text }
                var firstError: String? = null
                val results = withContext(Dispatchers.IO) {
                    batches.map { batch ->
                        async {
                            try {
                                val out = LLMClient.complete(
                                    config,
                                    system = Prompts.assistantSystem,
                                    user = Prompts.glossBatch(batch.map { it.id to it.text }, level.perParagraph),
                                    maxTokens = 2048
                                )
                                Prompts.parseGloss(out) to null
                            } catch (e: Exception) {
                                emptyList<Pair<Int, List<List<String>>>>() to e.message
                            }
                        }
                    }.awaitAll()
                }
                for ((list, err) in results) {
                    if (err != null && firstError == null) firstError = err
                    for ((id, pairs) in list) {
                        found.add(id to pairs)
                        textByID[id]?.let { src ->
                            val arr = JSONArray()
                            pairs.forEach { arr.put(JSONArray().put(it[0]).put(it[1])) }
                            ResponseCache.set(ResponseCache.key("gloss", config.cacheID, level.name, src), arr.toString())
                        }
                    }
                }
                if (found.isNotEmpty()) {
                    val payload = JSONArray()
                    found.forEach { (id, pairs) ->
                        val ps = JSONArray()
                        pairs.forEach { ps.put(JSONArray().put(it[0]).put(it[1])) }
                        payload.put(JSONArray().put(id).put(ps))
                    }
                    _glossesHidden.value = false
                    webView.evaluateJavascript("window.__llmSetGlossHidden(false); window.__llmApplyGlossBatch($payload)", null)
                }
                if (firstError != null) _alertMessage.value = firstError
            } finally {
                _isGlossing.value = false
            }
        }
    }

    fun toggleGlosses() {
        val next = !_glossesHidden.value
        _glossesHidden.value = next
        webView.evaluateJavascript("window.__llmSetGlossHidden($next)", null)
    }

    fun clearGlosses() {
        _glossesHidden.value = false
        webView.evaluateJavascript("window.__llmClearGlosses()", null)
    }

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

    // MARK: - Sözlük

    /** Seçili kelimeyi cihazdaki sözlük uygulamasında açar; yoksa (veya ifade uzunsa) yapay zekâ açıklamasına düşer. */
    private fun lookUp(sel: SelectionInfo) {
        val term = QuickDictionary.term(sel.text)
        if (term != null && QuickDictionary.open(context, term)) return
        if (term != null) showToast("Sözlük uygulaması yok, yapay zekâya soruluyor")
        explain(sel)
    }

    private fun explain(sel: SelectionInfo) {
        val fastCfg = settings.current.config(LLMTier.fast)
        val b = _book.value
        ai.start(
            AIRequest(
                title = if (sel.text.length > 40) sel.text.take(40) + "…" else sel.text,
                system = Prompts.assistantSystem,
                user = Prompts.explain(selection = sel.text, paragraph = sel.paragraph, bookTitle = b.title),
                config = fastCfg,
                maxTokens = 2048,
                cacheKey = ResponseCache.key("explain", fastCfg.cacheID, sel.text, sel.paragraph),
                vocab = VocabDraft.make(word = sel.text, paragraph = sel.paragraph, book = b)
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

    // MARK: - Dipnotlar

    private fun openNote(href: String) {
        val hashAt = href.indexOf('#')
        if (hashAt < 0) return
        val path = href.substring(0, hashAt)
        val frag = try { java.net.URLDecoder.decode(href.substring(hashAt + 1), "UTF-8") } catch (_: Exception) { href.substring(hashAt + 1) }
        if (frag.isEmpty()) return
        var chapter = _chapterIndex.value
        if (path.isNotEmpty()) {
            val base = pkg.spine[_chapterIndex.value].file.parentFile
            val target = try { java.io.File(base, java.net.URLDecoder.decode(path, "UTF-8")).canonicalPath } catch (_: Exception) { return }
            val i = pkg.spine.indexOfFirst {
                try { it.file.canonicalPath == target } catch (_: Exception) { false }
            }
            if (i < 0) return
            chapter = i
        }
        scope.launch {
            val text: String? = if (chapter == _chapterIndex.value) {
                evaluateJsString("window.__llmNoteText(${jsString(frag)})")?.let {
                    try { JSONObject(it).optString("text") } catch (_: Exception) { null }
                }
            } else {
                val file = pkg.spine[chapter].file
                withContext(Dispatchers.IO) {
                    try { HTMLText.elementText(frag, file.readText(Charsets.UTF_8)) } catch (_: Exception) { null }
                }
            }
            val t = SearchText.collapse(text.orEmpty())
            if (t.isEmpty() || t.length > 1500) {
                // Dipnota benzemiyor (başlık, uzun bölüm…): normal bağlantı gibi git
                goToFragment(chapter, frag)
            } else {
                _footnote.value = FootnoteInfo(t, chapter, frag)
            }
        }
    }

    fun goToFragment(chapter: Int, fragment: String) {
        markReturnPoint()
        if (chapter == _chapterIndex.value) {
            webView.evaluateJavascript("window.__llmScrollToId(${jsString(fragment)})", null)
        } else {
            loadChapter(chapter, fragment = fragment)
        }
    }

    fun dismissFootnote() { _footnote.value = null }

    fun showFootnoteInText(info: FootnoteInfo) {
        _footnote.value = null
        goToFragment(info.chapter, info.fragment)
    }

    fun translateFootnote(info: FootnoteInfo) {
        _footnote.value = null
        scope.launch {
            kotlinx.coroutines.delay(300)
            translateParagraphAI(info.text)
        }
    }

    // MARK: - Kitapta arama

    private suspend fun ensureSearchTexts(): List<FoldedText> {
        searchTexts?.let { return it }
        val files = pkg.spine.map { it.file }
        val texts = withContext(Dispatchers.Default) {
            files.map { FoldedText(SearchText.collapse(HTMLText.extract(it))) }
        }
        searchTexts = texts
        return texts
    }

    private suspend fun searchBook(q: String, limit: Int): List<SearchHit> {
        val texts = ensureSearchTexts()
        return withContext(Dispatchers.Default) {
            val out = ArrayList<SearchHit>()
            for ((i, t) in texts.withIndex()) {
                if (out.size >= limit) break
                for (m in SearchText.matches(t, q, limit - out.size)) {
                    out.add(SearchHit(chapterTitle(i), m.before, m.match, m.after, chapter = i, occurrence = m.occurrence))
                }
            }
            out
        }
    }

    fun openSearchHit(hit: SearchHit) {
        val c = hit.chapter ?: return
        markReturnPoint()
        val q = SearchText.collapse(search.query.value)
        val before = hit.before.takeLast(25)
        if (c == _chapterIndex.value) {
            webView.evaluateJavascript("window.__llmFindShow(${jsString(q)}, ${hit.occurrence ?: 0}, ${jsString(before)})", null)
        } else {
            pendingFind = Triple(q, hit.occurrence ?: 0, before)
            loadChapter(c)
        }
    }

    // MARK: - Kalem (stylus)

    fun setPenMode(on: Boolean) {
        _penMode.value = on
        webView.penEnabled = on
        if (!on) dismissPenResult()
    }

    fun togglePenMode() = setPenMode(!_penMode.value)

    private fun penStroke(pts: List<Pair<Float, Float>>) {
        val style = settings.current.penStyle
        val color = settings.current.highlightColor
        val arr = JSONArray()
        val step = max(1, pts.size / 60)
        pts.forEachIndexed { i, p -> if (i % step == 0 || i == pts.lastIndex) arr.put(JSONArray().put(p.first.toDouble()).put(p.second.toDouble())) }
        scope.launch {
            val json = evaluateJsString("window.__llmPenRange($arr, ${style != HighlightStyle.highlight})") ?: return@launch
            try {
                val o = JSONObject(json)
                val start = o.getInt("start")
                val end = o.getInt("end")
                val h = Highlight(
                    bookID = _book.value.id,
                    bookTitle = _book.value.title,
                    text = o.getString("text"),
                    color = color,
                    style = if (style == HighlightStyle.highlight) null else style,
                    chapter = _chapterIndex.value,
                    start = start,
                    end = end,
                    location = chapterTitle(_chapterIndex.value)
                )
                highlights.add(h)
                webView.evaluateJavascript(
                    "window.__llmApplyHighlight(${jsString(h.id)}, $start, $end, ${jsString(color.name)}, false, ${jsString(style.name)})",
                    null
                )
                tracker.touch()
                showPenResult(PenResult(h, o.optString("paragraph")))
            } catch (_: Exception) {
            }
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
            SelectionAction.dictionary -> lookUp(SelectionInfo(r.highlight.text, r.paragraph))
            else -> explain(SelectionInfo(r.highlight.text, r.paragraph))
        }
    }

    fun undoPen() {
        val r = _penResult.value ?: return
        dismissPenResult()
        deleteHighlight(r.highlight)
    }

    private val _isGeneratingInsights = MutableStateFlow(false)
    val isGeneratingInsights: StateFlow<Boolean> = _isGeneratingInsights.asStateFlow()

    /** Bulunduğun bölümden yapay zekâ ile 3-5 içgörü çıkarıp vurgular listesine (✨ ile ayrı) ekler. */
    fun generateInsights() {
        if (_isGeneratingInsights.value) return
        _isGeneratingInsights.value = true
        val config = settings.current.config(LLMTier.smart)
        val chapter = _chapterIndex.value
        val name = chapterTitle(chapter)
        scope.launch {
            try {
                val text = evaluateJsString("window.__llmChapterText()").orEmpty()
                if (text.trim().length <= 300) {
                    _alertMessage.value = "Bu bölümde içgörü için yeterli metin yok."
                    return@launch
                }
                val out = LLMClient.complete(
                    config,
                    system = Prompts.assistantSystem,
                    user = Prompts.insights(text.take(80_000), _book.value.title, name),
                    maxTokens = 1200
                )
                val n = highlights.addInsights(Prompts.parseInsights(out), _book.value.id, _book.value.title, chapter, null, name)
                showToast(if (n > 0) "$n içgörü eklendi" else "Yeni içgörü çıkmadı")
            } catch (e: Exception) {
                _alertMessage.value = e.message ?: "İçgörü üretilemedi."
            } finally {
                _isGeneratingInsights.value = false
            }
        }
    }

    fun prepareStudy() {
        scope.launch {
            val text = evaluateJsString("window.__llmChapterText()").orEmpty()
            if (text.trim().length <= 300) {
                _alertMessage.value = "Bu bölümde çalışma seti için yeterli metin yok."
                return@launch
            }
            val idx = _chapterIndex.value
            val name = chapterTitle(idx)
            _study.value = ChapterStudyModel(
                title = name,
                text = text,
                book = _book.value,
                config = settings.current.config(LLMTier.smart),
                scopeKey = "ch$idx"
            ).also { it.insightTarget = com.kursat.reader.ai.InsightTarget(chapter = idx, page = null, location = name) }
        }
    }

    fun dismissStudy() {
        _study.value = null
    }

    fun prepareHardWords() {
        scope.launch {
            val text = evaluateJsString("window.__llmChapterText()").orEmpty()
            if (text.isBlank()) {
                _alertMessage.value = "Bu bölümde metin bulunamadı."
                return@launch
            }
            val idx = _chapterIndex.value
            val name = chapterTitle(idx)
            _hardWords.value = HardWordsModel(
                title = "Zor kelimeler: $name",
                text = text,
                book = _book.value,
                config = settings.current.config(LLMTier.smart),
                scopeKey = "ch$idx"
            )
        }
    }

    fun dismissHardWords() {
        _hardWords.value = null
    }

    fun summarizeChapter() {
        scope.launch {
            val text = evaluateJsString("window.__llmChapterText()").orEmpty()
            if (text.isBlank()) {
                _alertMessage.value = "Bu bölümde özetlenecek metin bulunamadı."
                return@launch
            }
            val clipped = text.take(90_000)
            val idx = _chapterIndex.value
            val name = chapterTitle(idx)
            val smartCfg = settings.current.config(LLMTier.smart)
            ai.start(
                AIRequest(
                    title = "Özet: $name",
                    system = Prompts.summarySystem,
                    user = Prompts.summarize(text = clipped, scope = name, bookTitle = _book.value.title),
                    config = smartCfg,
                    maxTokens = 2048,
                    cacheKey = ResponseCache.key("sum", smartCfg.cacheID, _book.value.id, idx.toString())
                )
            )
        }
    }

    suspend fun buildChatContext(budget: Int): BookContext {
        val upto = _chapterIndex.value
        val missing = (0 until upto).filter { !chapterTexts.containsKey(it) }
        if (missing.isNotEmpty()) {
            val parsed = withContext(Dispatchers.IO) {
                missing.map { i -> i to HTMLText.extract(pkg.spine[i].file) }
            }
            for ((i, t) in parsed) {
                chapterTexts[i] = t
            }
        }
        val sections = mutableListOf<Pair<String, String>>()
        for (i in 0 until upto) {
            sections.add(chapterTitle(i) to (chapterTexts[i] ?: ""))
        }
        val current = evaluateJsString("window.__llmTextUpToViewport()").orEmpty()
        sections.add(chapterTitle(upto) to current)
        var label = "Bölüm ${upto + 1}/${pkg.spine.size}"
        if (settings.current.readingMode == ReadingMode.paged && _pageTotal.value > 0) {
            label += ", sayfa ${_pageCur.value}"
        }
        return BookContext.assemble(sections = sections, budget = budget, label = label)
    }

    private data class VisiblePara(val id: Int, val text: String)

    fun translateVisible() {
        if (_isTranslatingPage.value) return
        _isTranslatingPage.value = true
        val config = settings.current.config(LLMTier.fast)

        scope.launch {
            try {
                val jsonStr = evaluateJsString("window.__llmVisibleParagraphs()")
                val paras = mutableListOf<VisiblePara>()
                if (!jsonStr.isNullOrBlank()) {
                    try {
                        val arr = JSONArray(jsonStr)
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            paras.add(VisiblePara(id = o.optInt("id"), text = o.optString("text")))
                        }
                    } catch (_: Exception) {}
                }
                if (paras.isEmpty()) {
                    _alertMessage.value = "Ekranda çevrilecek yeni paragraf yok."
                    return@launch
                }

                val todo = mutableListOf<VisiblePara>()
                for (p in paras) {
                    val cached = ResponseCache.get(ResponseCache.key("pg", config.cacheID, p.text))
                    if (cached != null) {
                        insertTranslation(p.id, cached)
                    } else {
                        todo.add(p)
                    }
                }
                _translationsHidden.value = false
                webView.evaluateJavascript("window.__llmShow()", null)

                val batches = mutableListOf<List<VisiblePara>>()
                var cur = mutableListOf<VisiblePara>()
                var size = 0
                for (p in todo) {
                    if (cur.isNotEmpty() && (size + p.text.length > 3000 || cur.size >= 10)) {
                        batches.add(cur)
                        cur = mutableListOf()
                        size = 0
                    }
                    cur.add(p)
                    size += p.text.length
                }
                if (cur.isNotEmpty()) batches.add(cur)

                val textByID = todo.associate { it.id to it.text }
                var firstError: String? = null
                val results = batches.map { batch ->
                    async {
                        try {
                            val out = LLMClient.complete(
                                config,
                                system = Prompts.translatorSystem,
                                user = Prompts.translateBatch(batch.map { it.id to it.text }),
                                maxTokens = 4096
                            )
                            Prompts.parseBatch(out) to null
                        } catch (e: Exception) {
                            emptyList<Pair<Int, String>>() to (e.message ?: "Çeviri hatası")
                        }
                    }
                }.awaitAll()

                for ((pairs, err) in results) {
                    if (err != null && firstError == null) firstError = err
                    for ((id, tr) in pairs) {
                        val orig = textByID[id]
                        if (orig != null) {
                            ResponseCache.set(ResponseCache.key("pg", config.cacheID, orig), tr)
                        }
                        insertTranslation(id, tr)
                    }
                }
                if (firstError != null) {
                    _alertMessage.value = firstError
                }
            } finally {
                _isTranslatingPage.value = false
            }
        }
    }

    private fun insertTranslation(id: Int, text: String) {
        webView.evaluateJavascript("window.__llmInsert($id, ${jsString(text)})", null)
    }

    // MARK: - Yer imleri

    /** Sohbet yanıtını bulunduğun konuma (bölüm + oran) not olarak yer imlerine kaydeder. */
    fun saveChatNote(text: String) {
        val chap = _chapterIndex.value
        bookmarks.add(
            Bookmark(
                bookID = _book.value.id,
                bookTitle = _book.value.title,
                snippet = text.replace("**", "").take(1500),
                chapter = chap,
                fraction = scrollFraction,
                location = "📌 " + chapterTitle(chap),
                isNote = true
            )
        )
        showToast("Not kaydedildi")
    }

    fun toggleBookmark() {
        scope.launch {
            val curChap = _chapterIndex.value
            val existing = bookmarks.forBook(_book.value.id).firstOrNull {
                it.isNote != true && it.chapter == curChap && abs((it.fraction ?: 0.0) - scrollFraction) < 0.012
            }
            if (existing != null) {
                bookmarks.delete(listOf(existing.id))
                showToast("Yer imi kaldırıldı")
                return@launch
            }
            val snippet = evaluateJsString("window.__llmSnippet()").orEmpty()
            val name = chapterTitle(curChap)
            val loc = listOfNotNull(name, _book.value.pageText).joinToString(" · ")
            bookmarks.add(
                Bookmark(
                    bookID = _book.value.id,
                    bookTitle = _book.value.title,
                    snippet = snippet.take(110),
                    chapter = curChap,
                    fraction = scrollFraction,
                    location = loc
                )
            )
            showToast("Yer imi eklendi")
        }
    }

    fun goTo(b: Bookmark) {
        val c = b.chapter ?: return
        markReturnPoint()
        loadChapter(c, fraction = b.fraction ?: 0.0)
    }

    fun showToast(msg: String?) {
        _toast.value = msg
    }

    fun dismissAlert() {
        _alertMessage.value = null
    }

    fun setEditing(edit: EditingHighlight?) {
        _editing.value = edit
    }

    // MARK: - Vurgular

    fun chapterTitle(i: Int): String =
        pkg.toc.firstOrNull { it.spineIndex == i }?.title ?: "Bölüm ${i + 1}"

    private fun applyHighlightsToPage() {
        val list = highlights.forBook(_book.value.id)
            .filter { it.chapter == _chapterIndex.value && it.start != null && it.end != null }
        if (list.isEmpty()) return
        val arr = JSONArray()
        for (h in list) {
            arr.put(
                JSONObject().apply {
                    put("id", h.id)
                    put("start", h.start)
                    put("end", h.end)
                    put("color", h.color.name)
                    put("note", h.note.isNotEmpty())
                    put("style", h.effectiveStyle.name)
                }
            )
        }
        webView.evaluateJavascript("window.__llmApplyAll($arr)", null)
    }

    private suspend fun createHighlight(openNote: Boolean) {
        val jsonStr = evaluateJsString("window.__llmSelectionRange()") ?: return
        try {
            val obj = JSONObject(jsonStr)
            val start = obj.getInt("start")
            val end = obj.getInt("end")
            val text = obj.getString("text")
            val h = Highlight(
                bookID = _book.value.id,
                bookTitle = _book.value.title,
                text = text,
                color = settings.current.highlightColor,
                chapter = _chapterIndex.value,
                start = start,
                end = end,
                location = chapterTitle(_chapterIndex.value)
            )
            highlights.add(h)
            webView.evaluateJavascript(
                "window.__llmApplyHighlight(${jsString(h.id)}, $start, $end, ${jsString(h.color.name)}, false, 'highlight'); window.getSelection().removeAllRanges();",
                null
            )
            _hasSelection.value = false
            if (openNote) {
                _editing.value = EditingHighlight(highlight = h, focusNote = true)
            }
        } catch (_: Exception) {
        }
    }

    fun saveHighlight(h: Highlight) {
        highlights.update(h)
        settings.update { it.copy(highlightColor = h.color) }
        webView.evaluateJavascript(
            "window.__llmStyleHighlight(${jsString(h.id)}, ${jsString(h.color.name)}, ${h.note.isNotEmpty()}, ${jsString(h.effectiveStyle.name)})",
            null
        )
    }

    fun deleteHighlight(h: Highlight) {
        highlights.delete(listOf(h.id))
        webView.evaluateJavascript("window.__llmRemoveHighlight(${jsString(h.id)})", null)
    }

    /** Kitap bir vurguya atlayarak açılırken (günün vurgusu, vurgular listesi) okuma yeri dönüş noktası olarak saklanır. */
    fun jumpAfterOpen(h: Highlight) {
        val c = h.chapter ?: return
        if (!_book.value.isUnread) markReturnPoint()
        pendingHighlightID = h.id
        loadChapter(c)
    }

    fun goTo(h: Highlight) {
        val c = h.chapter ?: return
        if (h.isInsight && c == _chapterIndex.value) return   // içgörü metinde işaretli değil; zaten bu bölümdesin
        markReturnPoint()
        if (c == _chapterIndex.value) {
            webView.evaluateJavascript("window.__llmScrollToHighlight(${jsString(h.id)})", null)
        } else {
            pendingHighlightID = h.id
            loadChapter(c)
        }
    }

    fun toggleTranslations() {
        val next = !_translationsHidden.value
        _translationsHidden.value = next
        webView.evaluateJavascript("window.__llmSetHidden($next)", null)
    }

    fun clearTranslations() {
        webView.evaluateJavascript("window.__llmClear()", null)
    }

    companion object {
        /**
         * iOS EPUBReaderModel.baseScript'in birebir karşılığı.
         * Yalnızca post(m) fonksiyonu AndroidBridge.postMessage(JSON.stringify(m)) çağıracak şekilde uyarlanmıştır.
         */
        val BASE_SCRIPT = """
            (function () {
              if (window.__llmInstalled) return;
              window.__llmInstalled = true;
              var counter = 0;
              var BLOCKS = 'p,li,blockquote,h1,h2,h3,h4,h5,h6,dd,dt,figcaption';

              (function ensureViewport() {
                var head = document.head || document.documentElement;
                if (!head) return;
                var vp = head.querySelector('meta[name="viewport"]');
                if (!vp) {
                  vp = document.createElement('meta');
                  vp.name = 'viewport';
                  head.appendChild(vp);
                }
                vp.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
              })();

      // Metin okuma yardımcıları: kelime ipuçları (<rt>) okunan/çevrilen metne karışmasın
      function plain(el) {
        if (!el.querySelector || !el.querySelector('rt')) return el.innerText || el.textContent || '';
        var c = el.cloneNode(true);
        c.querySelectorAll('rt').forEach(function (r) { r.remove(); });
        c.querySelectorAll('br').forEach(function (b) { b.replaceWith('\n'); });
        return c.textContent || '';
      }
      function selText(sel) {
        var s = sel.toString();
        if (!document.querySelector('rt.llm-rt') || sel.rangeCount === 0) return s;
        var d = document.createElement('div');
        d.appendChild(sel.getRangeAt(0).cloneContents());
        d.querySelectorAll('rt').forEach(function (r) { r.remove(); });
        return d.textContent || '';
      }

              window.__llmSetStyle = function (css) {
                var s = document.getElementById('llm-style');
                if (!s) {
                  s = document.createElement('style');
                  s.id = 'llm-style';
                  (document.head || document.documentElement).appendChild(s);
                }
                var changed = s.textContent !== css;
                s.textContent = css;
                if (changed && typeof modeInitialized !== 'undefined' && modeInitialized && mode === 'paged') {
                  var f = fraction();
                  applyPagedStyle();
                  requestAnimationFrame(function () { restoreFraction(f); });
                }
              };

              window.__llmSelection = function () {
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0) return null;
                var text = selText(sel).replace(/\s+/g, ' ').trim();
                text = text.replace(/^[\s"'“”‘’(\[]+|[\s.,;:!?"'“”‘’)\]]+$/g, '');
                if (!text) return null;
                var node = sel.anchorNode;
                var el = node && (node.nodeType === 1 ? node : node.parentElement);
                var block = el ? (el.closest(BLOCKS + ',td') || el.closest('div')) : null;
                var para = block ? plain(block).replace(/\s+/g, ' ').trim() : text;
                if (para.length > 3500) {
                  var i = para.indexOf(text);
                  var from = Math.max(0, (i < 0 ? 0 : i) - 1500);
                  para = para.substring(from, from + 3500);
                }
                return JSON.stringify({ text: text, paragraph: para });
              };

              window.__llmVisibleParagraphs = function (forGloss) {
                var vh = window.innerHeight, out = [];
                document.querySelectorAll(BLOCKS).forEach(function (el) {
                  if (el.closest('.llm-tr')) return;
                  if (el.querySelector(BLOCKS)) return;
                  if (forGloss && el.hasAttribute('data-glossed')) return;
                  if (el.nextElementSibling && el.nextElementSibling.classList.contains('llm-tr')) return;
                  var r = el.getBoundingClientRect();
                  if (r.bottom < 0 || r.top > vh || r.height === 0) return;
                  var t = plain(el).replace(/\s+/g, ' ').trim();
                  if (t.length < 2 || !/\p{L}/u.test(t)) return;
                  var id = el.getAttribute('data-llm-id');
                  if (!id) { id = String(++counter); el.setAttribute('data-llm-id', id); }
                  out.push({ id: parseInt(id, 10), text: t });
                });
                return JSON.stringify(out);
              };

              var layoutMode = 'below';
              var TR_ICON = '<svg viewBox="0 0 24 24"><path d="M12.87 15.07l-2.54-2.51.03-.03A17.52 17.52 0 0014.07 6H17V4h-7V2H8v2H1v1.99h11.17C11.5 7.92 10.44 9.75 9 11.35 8.07 10.32 7.3 9.19 6.69 8h-2c.73 1.63 1.73 3.17 2.98 4.56l-5.09 5.02L4 19l5-5 3.11 3.11.76-2.04zM18.5 10h-2L12 22h2l1.12-3h4.75L21 22h2l-4.5-12zm-2.62 7l1.62-4.33L19.12 17h-3.24z"/></svg>';
              function canSide(el) {
                if (layoutMode !== 'side') return false;
                if (!/^(P|BLOCKQUOTE|H1|H2|H3|H4|H5|H6)$/.test(el.tagName)) return false;
                if (window.innerWidth < 700) return false;
                if (typeof mode !== 'undefined' && mode === 'paged' && columnCount === 2) return false;
                return true;
              }
              function unwrapRow(row) {
                var p = row.parentNode;
                Array.prototype.slice.call(row.children).forEach(function (c) { if (!c.classList.contains('llm-tr')) p.insertBefore(c, row); });
                row.remove();
              }
              function removeTr(el) {
                el.classList.remove('llm-rep', 'llm-showorig');
                var row = el.parentNode;
                if (row && row.classList && row.classList.contains('llm-row')) {
                  var tr = row.querySelector('.llm-tr'); if (tr) tr.remove();
                  unwrapRow(row);
                  return;
                }
                var next = el.nextElementSibling;
                if (next && next.classList.contains('llm-tr')) next.remove();
              }
              window.__llmInsert = function (id, text) {
                var el = document.querySelector('[data-llm-id="' + id + '"]');
                if (!el) return;
                removeTr(el);
                var d = document.createElement('div');
                d.className = 'llm-tr';
                d.setAttribute('data-for', id);
                d.setAttribute('data-text', text);
                if (layoutMode === 'replace') {
                  d.className = 'llm-tr llm-r';
                  var ic = document.createElement('span');
                  ic.className = 'llm-ic';
                  ic.innerHTML = TR_ICON;
                  var tx = document.createElement('span');
                  tx.className = 'llm-tx';
                  tx.textContent = text;
                  d.appendChild(ic);
                  d.appendChild(tx);
                  el.classList.add('llm-rep');
                  el.parentNode.insertBefore(d, el.nextSibling);
                  return;
                }
                d.textContent = text;
                if (canSide(el)) {
                  var row = document.createElement('div');
                  row.className = 'llm-row';
                  el.parentNode.insertBefore(row, el);
                  row.appendChild(el);
                  row.appendChild(d);
                } else {
                  el.parentNode.insertBefore(d, el.nextSibling);
                }
              };
              // Çevirinin ikonuna dokununca orijinal ile çeviri arasında geçiş
              function toggleOrig(tr) {
                var el = tr.previousElementSibling;
                if (!el || !el.classList.contains('llm-rep')) return;
                var showOrig = !el.classList.contains('llm-showorig');
                el.classList.toggle('llm-showorig', showOrig);
                tr.classList.toggle('llm-collapsed', showOrig);
              }
              window.__llmSetLayout = function (layout) {
                layout = (layout === true) ? 'side' : (layout || 'below');
                var changed = (layout !== layoutMode);
                layoutMode = layout;
                if (!changed) return;
                var items = [];
                document.querySelectorAll('.llm-tr').forEach(function (t) { items.push([t.getAttribute('data-for'), t.getAttribute('data-text') || t.textContent]); });
                items.forEach(function (it) { window.__llmInsert(it[0], it[1]); });
              };
              window.__llmSnippet = function () {
                var vw = window.innerWidth, vh = window.innerHeight;
                var els = document.querySelectorAll(BLOCKS);
                for (var i = 0; i < els.length; i++) {
                  var el = els[i];
                  if (el.closest('.llm-tr') || el.querySelector(BLOCKS)) continue;
                  var r = el.getBoundingClientRect();
                  if (r.height === 0) continue;
                  if (r.bottom > 0 && r.top < vh && r.right > 0 && r.left < vw) {
                    var t = plain(el).replace(/\s+/g, ' ').trim();
                    if (t.length > 3) return t;
                  }
                }
                return '';
              };

              window.__llmClear = function () {
                document.querySelectorAll('.llm-tr').forEach(function (e) { e.remove(); });
                document.querySelectorAll('.llm-row').forEach(unwrapRow);
                document.querySelectorAll('.llm-rep').forEach(function (e) { e.classList.remove('llm-rep', 'llm-showorig'); });
                document.querySelectorAll('[data-llm-id]').forEach(function (e) { e.removeAttribute('data-llm-id'); });
              };
              window.__llmSetHidden = function (h) { document.documentElement.classList.toggle('llm-hide', !!h); };
              window.__llmShow = function () { document.documentElement.classList.remove('llm-hide'); };

              window.__llmTextUpToViewport = function () {
                var parts = [];
                var paged = (typeof mode !== 'undefined' && mode === 'paged');
                var lim = paged ? window.innerWidth : window.innerHeight;
                document.querySelectorAll(BLOCKS).forEach(function (el) {
                  if (el.closest('.llm-tr') || el.querySelector(BLOCKS)) return;
                  var r = el.getBoundingClientRect();
                  if ((paged ? r.left : r.top) >= lim) return;
                  var t = plain(el).replace(/\s+/g, ' ').trim();
                  if (t) parts.push(t);
                });
                return parts.join('\n\n');
              };

              function cleanNodes() {
                var out = [], n;
                var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
                while ((n = w.nextNode())) {
                  var p = n.parentElement;
                  if (!p || p.closest('.llm-tr, script, style, #llm-end, rt')) continue;
                  out.push(n);
                }
                return out;
              }
              function cleanOffset(container, offset) {
                var r = document.createRange();
                r.setStart(container, offset);
                r.collapse(true);
                var total = 0, nodes = cleanNodes();
                for (var i = 0; i < nodes.length; i++) {
                  var n = nodes[i];
                  if (n === container) return total + offset;
                  if (r.comparePoint(n, n.nodeValue.length) <= 0) total += n.nodeValue.length; else break;
                }
                return total;
              }
              window.__llmSelectionRange = function () {
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0 || !selText(sel).trim()) return null;
                var r = sel.getRangeAt(0);
                var s = cleanOffset(r.startContainer, r.startOffset), e = cleanOffset(r.endContainer, r.endOffset);
                if (e <= s) return null;
                return JSON.stringify({ start: s, end: e, text: selText(sel).replace(/\s+/g, ' ').trim() });
              };

              // ---- Apple Pencil: çizilen çizgiyi metin aralığına çevirir ----
              function penWordChar(ch) { return /[\p{L}\p{N}'’]/u.test(ch); }
              function penCaret(x, y, offs) {
                for (var i = 0; i < offs.length; i++) {
                  var r = document.caretRangeFromPoint(x, y + offs[i]);
                  if (!r || r.startContainer.nodeType !== 3) continue;
                  var p = r.startContainer.parentElement;
                  if (!p || p.closest('.llm-tr, script, style, #llm-end, rt')) continue;
                  return { n: r.startContainer, o: r.startOffset };
                }
                return null;
              }
              window.__llmPenRange = function (pts, underline) {
                // Altı çizgi metnin biraz altına çizilir: noktayı yukarı kaydırarak satırı bul
                var offs = underline ? [-4, -9, -15, 3] : [0, 4, -4];
                var lo = null, hi = null;
                pts.forEach(function (p) {
                  var c = penCaret(p[0], p[1], offs);
                  if (!c) return;
                  if (!lo) { lo = c; hi = c; return; }
                  var t = document.createRange();
                  t.setStart(lo.n, lo.o);
                  if (t.comparePoint(c.n, c.o) < 0) lo = c;
                  t.setStart(hi.n, hi.o);
                  if (t.comparePoint(c.n, c.o) > 0) hi = c;
                });
                if (!lo) return null;
                var lv = lo.n.nodeValue, ls = lo.o;
                while (ls > 0 && penWordChar(lv[ls - 1])) ls--;
                var hv = hi.n.nodeValue, he = hi.o;
                while (he < hv.length && penWordChar(hv[he])) he++;
                var r = document.createRange();
                r.setStart(lo.n, ls);
                r.setEnd(hi.n, he);
                var s = cleanOffset(lo.n, ls), e = cleanOffset(hi.n, he);
                var text = r.toString().replace(/\s+/g, ' ').trim();
                if (e <= s || !text) return null;
                var el = lo.n.parentElement;
                var block = el ? (el.closest(BLOCKS + ',td') || el.closest('div')) : null;
                var para = block ? plain(block).replace(/\s+/g, ' ').trim() : text;
                if (para.length > 3500) {
                  var i = para.indexOf(text);
                  var from = Math.max(0, (i < 0 ? 0 : i) - 1500);
                  para = para.substring(from, from + 3500);
                }
                return JSON.stringify({ start: s, end: e, text: text, paragraph: para });
              };
              window.__llmApplyHighlight = function (id, start, end, color, hasNote, style) {
                var off = 0;
                cleanNodes().forEach(function (n) {
                  var len = n.nodeValue.length, ns = off, ne = off + len;
                  off = ne;
                  var lo = Math.max(start, ns), hi = Math.min(end, ne);
                  if (hi <= lo) return;
                  var r = document.createRange();
                  r.setStart(n, lo - ns);
                  r.setEnd(n, hi - ns);
                  var span = document.createElement('span');
                  span.className = 'llm-hl';
                  span.setAttribute('data-hl', id);
                  span.setAttribute('data-color', color);
                  if (hasNote) span.setAttribute('data-note', '1');
                  if (style && style !== 'highlight') span.setAttribute('data-style', style);
                  try { r.surroundContents(span); } catch (e) {}
                });
              };
              window.__llmApplyAll = function (list) {
                list.forEach(function (h) { window.__llmApplyHighlight(h.id, h.start, h.end, h.color, h.note, h.style); });
              };
              window.__llmStyleHighlight = function (id, color, hasNote, style) {
                document.querySelectorAll('[data-hl="' + id + '"]').forEach(function (s) {
                  s.setAttribute('data-color', color);
                  if (style && style !== 'highlight') s.setAttribute('data-style', style); else s.removeAttribute('data-style');
                  if (hasNote) s.setAttribute('data-note', '1'); else s.removeAttribute('data-note');
                });
              };
              window.__llmRemoveHighlight = function (id) {
                document.querySelectorAll('[data-hl="' + id + '"]').forEach(function (s) {
                  var p = s.parentNode;
                  while (s.firstChild) p.insertBefore(s.firstChild, s);
                  p.removeChild(s);
                  p.normalize();
                });
              };
              window.__llmScrollToHighlight = function (id) {
                var el = document.querySelector('[data-hl="' + id + '"]');
                if (!el) return;
                el.scrollIntoView({ block: 'center', inline: 'nearest' });
                if (window.__llmSync) window.__llmSync();
              };


              // ---- Dipnotlar: dipnot bağlantısına dokununca sayfa değiştirmek yerine metni Swift'e gönder ----
              window.__llmNoteText = function (id) {
                var el = document.getElementById(id) || document.getElementsByName(id)[0];
                if (!el) return null;
                var blk = el;
                if (!/^(P|LI|DIV|ASIDE|DD|BLOCKQUOTE|SECTION|FIGCAPTION|TD)$/.test(blk.tagName) || !plain(blk).trim()) {
                  blk = el.closest('p,li,aside,dd,div,blockquote') || el;
                }
                if (/^H[1-6]$/.test(blk.tagName)) return null;
                var t = plain(blk).replace(/\s+/g, ' ').trim();
                return JSON.stringify({ text: t });
              };
              window.__llmScrollToId = function (id) {
                var e = document.getElementById(id) || document.getElementsByName(id)[0];
                if (e) e.scrollIntoView({ block: 'center', inline: 'nearest' });
                if (window.__llmSync) window.__llmSync();
              };
              function isNoteLink(a) {
                var href = a.getAttribute('href') || '';
                if (href.indexOf('#') < 0 || /^[a-z][a-z0-9+.-]*:/i.test(href)) return false;
                if (a.closest('.llm-tr')) return false;
                var type = (a.getAttribute('epub:type') || '') + ' ' + (a.getAttribute('role') || '');
                if (/noteref|doc-noteref/.test(type)) return true;
                var t = (a.textContent || '').trim();
                if (/^[\[\(]?\s*[\d*†‡§#]{1,4}\s*[\]\)]?$/.test(t)) return true;
                return !!a.closest('sup') && t.length <= 6;
              }
              document.addEventListener('click', function (e) {
                var a = e.target && e.target.closest && e.target.closest('a[href]');
                if (!a || !isNoteLink(a)) return;
                e.preventDefault();
                e.stopPropagation();
                post({ type: 'note', href: a.getAttribute('href') });
              }, true);

              // ---- Kitapta arama: bölümde eşleşmeyi bulup geçici olarak işaretler ----
              function foldChar(ch) { return ch.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(); }
              window.__llmFindClear = function () {
                document.querySelectorAll('.llm-find').forEach(function (s) {
                  var p = s.parentNode;
                  while (s.firstChild) p.insertBefore(s.firstChild, s);
                  p.removeChild(s);
                  p.normalize();
                });
              };
              var findTimer = null;
              window.__llmFindShow = function (q, n, before) {
                window.__llmFindClear();
                if (findTimer) { clearTimeout(findTimer); findTimer = null; }
                var s = '', map = [], lastSpace = true;
                cleanNodes().forEach(function (nd) {
                  var v = nd.nodeValue;
                  for (var i = 0; i < v.length; i++) {
                    var ch = v[i];
                    if (/\s/.test(ch)) {
                      if (lastSpace) continue;
                      s += ' '; map.push([nd, i]); lastSpace = true;
                    } else {
                      var f = foldChar(ch);
                      for (var k = 0; k < f.length; k++) { s += f[k]; map.push([nd, i]); }
                      lastSpace = false;
                    }
                  }
                });
                var qf = '';
                q.split('').forEach(function (c) { qf += (/\s/.test(c) ? ' ' : foldChar(c)); });
                qf = qf.replace(/\s+/g, ' ').trim();
                if (!qf) return;
                var bf = '';
                (before || '').split('').forEach(function (c) { bf += (/\s/.test(c) ? ' ' : foldChar(c)); });
                var starts = [], from = 0, idx;
                while ((idx = s.indexOf(qf, from)) >= 0) { starts.push(idx); from = idx + qf.length; }
                if (!starts.length) return;
                var pick = -1;
                if (bf) {
                  for (var j = 0; j < starts.length; j++) {
                    if (s.slice(Math.max(0, starts[j] - bf.length), starts[j]) === bf) { pick = j; break; }
                  }
                }
                if (pick < 0) pick = (n >= 0 && n < starts.length) ? n : 0;
                var st = starts[pick], en = st + qf.length;
                var per = [];
                for (var m = st; m < en; m++) {
                  var e2 = map[m]; if (!e2) continue;
                  var last = per[per.length - 1];
                  if (last && last.node === e2[0]) { last.end = e2[1] + 1; }
                  else per.push({ node: e2[0], start: e2[1], end: e2[1] + 1 });
                }
                var first = null;
                per.forEach(function (p) {
                  var r = document.createRange();
                  r.setStart(p.node, p.start); r.setEnd(p.node, p.end);
                  var span = document.createElement('span');
                  span.className = 'llm-find';
                  try { r.surroundContents(span); if (!first) first = span; } catch (e) {}
                });
                if (first) {
                  first.scrollIntoView({ block: 'center', inline: 'nearest' });
                  if (window.__llmSync) window.__llmSync();
                }
                findTimer = setTimeout(window.__llmFindClear, 8000);
              };


              // ---- Görsel büyütme: görsele dokununca tam ekran, iki parmakla yakınlaştır, çift dokunmayla yakınlaş ----
              var lb = null;
              function closeLightbox() { if (lb) { lb.remove(); lb = null; } }
              function openLightbox(img) {
                var src = img.currentSrc || img.src;
                if (!src) return;
                closeLightbox();
                var vw = window.innerWidth, vh = window.innerHeight;
                lb = document.createElement('div');
                lb.id = 'llm-lb';
                lb.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;background:rgba(0,0,0,0.94);z-index:2147483647;overflow:hidden;touch-action:none;';
                var im = document.createElement('img');
                im.style.cssText = 'position:absolute;left:0;top:0;transform-origin:0 0;max-width:none;max-height:none;width:auto;height:auto;user-select:none;-webkit-user-select:none;-webkit-user-drag:none;';
                var x = 0, y = 0, s = 1, fit = 1, nw = 1, nh = 1;
                function apply() { im.style.transform = 'translate(' + x + 'px,' + y + 'px) scale(' + s + ')'; }
                function clampPos() {
                  var w = nw * s, h = nh * s;
                  x = w <= vw ? (vw - w) / 2 : Math.min(0, Math.max(vw - w, x));
                  y = h <= vh ? (vh - h) / 2 : Math.min(0, Math.max(vh - h, y));
                }
                function zoomAt(px, py, ns) {
                  ns = Math.max(fit, Math.min(fit * 8, ns));
                  x = px - (px - x) * (ns / s);
                  y = py - (py - y) * (ns / s);
                  s = ns; clampPos(); apply();
                }
                function layout() {
                  nw = im.naturalWidth || 1; nh = im.naturalHeight || 1;
                  fit = Math.min(vw / nw, vh / nh);
                  s = fit; x = (vw - nw * s) / 2; y = (vh - nh * s) / 2; apply();
                }
                im.onload = layout;
                im.src = src;
                var close = document.createElement('div');
                close.textContent = '✕';
                close.style.cssText = 'position:absolute;top:14px;right:14px;width:40px;height:40px;line-height:40px;text-align:center;font:22px sans-serif;color:#fff;background:rgba(255,255,255,0.18);border-radius:20px;';
                lb.appendChild(im); lb.appendChild(close);
                var mode = 0, sx = 0, sy = 0, ox = 0, oy = 0, moved = false, d0 = 0, s0 = 1, cx0 = 0, cy0 = 0, lastTap = 0, tapX = 0, tapY = 0;
                function dist(t) { var a = t[0], b = t[1]; return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY); }
                lb.addEventListener('touchstart', function (e) {
                  e.stopPropagation(); e.preventDefault();
                  if (e.touches.length === 2) {
                    mode = 2; d0 = dist(e.touches); s0 = s; moved = true;
                    cx0 = (e.touches[0].clientX + e.touches[1].clientX) / 2; cy0 = (e.touches[0].clientY + e.touches[1].clientY) / 2;
                  } else if (e.touches.length === 1) {
                    mode = 1; sx = e.touches[0].clientX; sy = e.touches[0].clientY; ox = x; oy = y; moved = false;
                  }
                }, { passive: false });
                lb.addEventListener('touchmove', function (e) {
                  e.stopPropagation(); e.preventDefault();
                  if (mode === 2 && e.touches.length === 2) {
                    var cx = (e.touches[0].clientX + e.touches[1].clientX) / 2, cy = (e.touches[0].clientY + e.touches[1].clientY) / 2;
                    var ns = Math.max(fit, Math.min(fit * 8, s0 * dist(e.touches) / (d0 || 1)));
                    x = cx - (cx0 - ox) * (ns / s0) ; y = cy - (cy0 - oy) * (ns / s0);
                    s = ns; clampPos(); apply();
                  } else if (mode === 1 && e.touches.length === 1) {
                    var dx = e.touches[0].clientX - sx, dy = e.touches[0].clientY - sy;
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true;
                    if (s > fit * 1.01) { x = ox + dx; y = oy + dy; clampPos(); apply(); }
                  }
                }, { passive: false });
                lb.addEventListener('touchend', function (e) {
                  e.stopPropagation(); e.preventDefault();
                  if (e.touches.length === 1 && mode === 2) { mode = 1; sx = e.touches[0].clientX; sy = e.touches[0].clientY; ox = x; oy = y; moved = true; return; }
                  if (e.touches.length > 0) return;
                  if (mode === 2) { mode = 0; ox = x; oy = y; return; }
                  mode = 0;
                  if (moved) return;
                  var t = e.changedTouches[0], now = Date.now();
                  if (now - lastTap < 320 && Math.abs(t.clientX - tapX) < 30 && Math.abs(t.clientY - tapY) < 30) {
                    lastTap = 0;
                    zoomAt(t.clientX, t.clientY, s > fit * 1.05 ? fit : fit * 2.5);
                  } else {
                    lastTap = now; tapX = t.clientX; tapY = t.clientY;
                    var tp = lastTap;
                    setTimeout(function () { if (lastTap === tp && lb) closeLightbox(); }, 330);
                  }
                }, { passive: false });
                (document.body || document.documentElement).appendChild(lb);
              }
              window.__llmCloseLightbox = closeLightbox;


              // ---- Biyonik okuma: kelimelerin ilk harflerini kalın yapar (metin düğümlerinin birleşik metni değişmez) ----
              var bionicOn = false;
              window.__llmSetBionic = function (on) {
                on = !!on;
                if (!on) {
                  document.querySelectorAll('b.llm-bio').forEach(function (b) {
                    var p = b.parentNode;
                    while (b.firstChild) p.insertBefore(b.firstChild, b);
                    p.removeChild(b);
                  });
                  if (document.body) document.body.normalize();
                  bionicOn = false;
                  if (window.__llmSync) window.__llmSync();
                  return;
                }
                if (bionicOn || !document.body) return;
                bionicOn = true;
                var nodes = [], n;
                var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
                while ((n = w.nextNode())) {
                  var p = n.parentElement;
                  if (!p || p.closest('.llm-tr, script, style, #llm-end, rt, code, pre, #llm-lb, #llm-ruler')) continue;
                  if (!/[\p{L}\p{N}]/u.test(n.nodeValue)) continue;
                  nodes.push(n);
                }
                nodes.forEach(function (node) {
                  var text = node.nodeValue, re = /[\p{L}\p{N}]+/gu, m, last = 0, any = false;
                  var frag = document.createDocumentFragment();
                  while ((m = re.exec(text))) {
                    var word = m[0], len = word.length;
                    var k = len <= 3 ? 1 : (len === 4 ? 2 : Math.ceil(len * 0.45));
                    if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
                    var b = document.createElement('b');
                    b.className = 'llm-bio';
                    b.textContent = word.slice(0, k);
                    frag.appendChild(b);
                    frag.appendChild(document.createTextNode(word.slice(k)));
                    last = m.index + len;
                    any = true;
                  }
                  if (!any) return;
                  if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
                  node.parentNode.replaceChild(frag, node);
                });
                if (window.__llmSync) window.__llmSync();
              };

              // ---- Satır odaklayıcı: ekranda yalnızca okunan şerit parlak kalır; sağdaki tutamaçla yukarı/aşağı sürüklenir ----
              var rulerF = 0.42, rulerPlace = null;
              window.__llmSetRuler = function (on, f) {
                var old = document.getElementById('llm-ruler');
                if (old) old.remove();
                if (rulerPlace) { window.removeEventListener('resize', rulerPlace); rulerPlace = null; }
                if (!on || !document.documentElement) return;
                if (typeof f === 'number' && f > 0.05 && f < 0.95) rulerF = f;
                var cs = getComputedStyle(document.body);
                var lh = parseFloat(cs.lineHeight) || (parseFloat(cs.fontSize) * 1.6);
                var bandH = Math.round(lh * 3);
                var root = document.createElement('div');
                root.id = 'llm-ruler';
                root.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;pointer-events:none;z-index:2147483000;';
                var top = document.createElement('div'), bot = document.createElement('div'), grip = document.createElement('div');
                var dim = 'position:absolute;left:0;right:0;background:rgba(0,0,0,0.55);';
                top.style.cssText = dim + 'top:0;';
                bot.style.cssText = dim + 'bottom:0;';
                grip.style.cssText = 'position:absolute;right:0;width:34px;height:' + bandH + 'px;pointer-events:auto;touch-action:none;background:rgba(128,128,128,0.35);border-radius:10px 0 0 10px;';
                var bars = document.createElement('div');
                bars.style.cssText = 'position:absolute;left:12px;top:50%;margin-top:-14px;width:8px;height:28px;border-left:2px solid rgba(255,255,255,0.85);border-right:2px solid rgba(255,255,255,0.85);';
                grip.appendChild(bars);
                function place() {
                  var vh = window.innerHeight;
                  var y = Math.round(Math.min(vh - bandH, Math.max(0, rulerF * vh - bandH / 2)));
                  top.style.height = y + 'px';
                  bot.style.height = Math.max(0, vh - y - bandH) + 'px';
                  grip.style.top = y + 'px';
                }
                grip.addEventListener('touchstart', function (e) { e.stopPropagation(); e.preventDefault(); }, { passive: false });
                grip.addEventListener('touchmove', function (e) {
                  e.stopPropagation(); e.preventDefault();
                  rulerF = Math.min(0.95, Math.max(0.05, e.touches[0].clientY / window.innerHeight));
                  place();
                }, { passive: false });
                grip.addEventListener('touchend', function (e) {
                  e.stopPropagation(); e.preventDefault();
                  post({ type: 'ruler', f: rulerF });
                }, { passive: false });
                root.appendChild(top); root.appendChild(bot); root.appendChild(grip);
                document.documentElement.appendChild(root);
                rulerPlace = place;
                window.addEventListener('resize', place);
                place();
              };

              // ---- Kelime ipuçları: zor kelimenin üstüne <ruby> ile kısa karşılık ----
              function glossFind(text, word) {
                var lt = text.toLowerCase(), lw = word.toLowerCase(), from = 0, i;
                while ((i = lt.indexOf(lw, from)) >= 0) {
                  var b = i > 0 ? text[i - 1] : '', a = i + lw.length < text.length ? text[i + lw.length] : '';
                  if (!/[\p{L}\p{N}]/u.test(b) && !/[\p{L}\p{N}]/u.test(a)) return i;
                  from = i + 1;
                }
                return -1;
              }
              function glossOne(el, word, gloss) {
                var w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null), n;
                while ((n = w.nextNode())) {
                  var p = n.parentElement;
                  if (!p || p.closest('rt, ruby, .llm-tr, script, style')) continue;
                  var i = glossFind(n.nodeValue, word);
                  if (i < 0) continue;
                  var text = n.nodeValue;
                  var ruby = document.createElement('ruby');
                  ruby.className = 'llm-ruby';
                  ruby.appendChild(document.createTextNode(text.slice(i, i + word.length)));
                  var rt = document.createElement('rt');
                  rt.className = 'llm-rt';
                  rt.textContent = gloss;
                  ruby.appendChild(rt);
                  var frag = document.createDocumentFragment();
                  if (i > 0) frag.appendChild(document.createTextNode(text.slice(0, i)));
                  frag.appendChild(ruby);
                  if (i + word.length < text.length) frag.appendChild(document.createTextNode(text.slice(i + word.length)));
                  n.parentNode.replaceChild(frag, n);
                  return true;
                }
                return false;
              }
              // list: [[paragrafId, [[kelime, karşılık], …]], …]
              window.__llmApplyGlossBatch = function (list) {
                var wasBionic = bionicOn;
                if (wasBionic) window.__llmSetBionic(false);
                var count = 0;
                list.forEach(function (it) {
                  var el = document.querySelector('[data-llm-id="' + it[0] + '"]');
                  if (!el) return;
                  (it[1] || []).forEach(function (pr) {
                    if (pr && pr[0] && pr[1] && glossOne(el, String(pr[0]), String(pr[1]))) count++;
                  });
                  el.setAttribute('data-glossed', '1');
                });
                if (wasBionic) window.__llmSetBionic(true);
                if (window.__llmSync) window.__llmSync();
                return count;
              };
              window.__llmClearGlosses = function () {
                var wasBionic = bionicOn;
                if (wasBionic) window.__llmSetBionic(false);
                document.querySelectorAll('ruby.llm-ruby').forEach(function (r) {
                  var t = document.createTextNode(r.firstChild ? r.firstChild.textContent : '');
                  r.parentNode.replaceChild(t, r);
                });
                document.querySelectorAll('[data-glossed]').forEach(function (e) { e.removeAttribute('data-glossed'); });
                if (document.body) document.body.normalize();
                if (wasBionic) window.__llmSetBionic(true);
              };
              window.__llmSetGlossHidden = function (h) { document.documentElement.classList.toggle('llm-gl-hide', !!h); };

              window.__llmChapterText = function () {
                var parts = [];
                document.querySelectorAll(BLOCKS).forEach(function (el) {
                  if (el.closest('.llm-tr') || el.querySelector(BLOCKS)) return;
                  var t = plain(el).replace(/\s+/g, ' ').trim();
                  if (t) parts.push(t);
                });
                if (parts.length === 0) return (document.body && document.body.innerText) || '';
                return parts.join('\n\n');
              };

              var mode = 'scroll', edgeTap = false, smooth = false, margin = 8, columnCount = 1;
              var modeInitialized = false;
              var curPage = 0, total = 1, t0 = null, animId = null;
              function post(m) {
                if (window.AndroidBridge && window.AndroidBridge.postMessage) {
                  window.AndroidBridge.postMessage(JSON.stringify(m));
                }
              }
              function pageW() { return window.innerWidth; }
              function padPx() { return Math.round(pageW() * margin / 100); }
              function spacer() {
                var sp = document.getElementById('llm-end');
                if (mode !== 'paged') { if (sp) sp.remove(); return null; }
                if (!sp && document.documentElement) {
                  sp = document.createElement('div');
                  sp.id = 'llm-end';
                  sp.style.cssText = 'position:absolute;top:0;width:1px;height:1px;visibility:hidden;pointer-events:none;';
                  document.documentElement.appendChild(sp);
                }
                return sp;
              }
              function measure() {
                if (mode !== 'paged' || !document.body) return;
                var sp = spacer();
                var w = pageW();
                if (w <= 0) return;
                var r = document.createRange();
                r.selectNodeContents(document.body);
                var rect = r.getBoundingClientRect();
                var sx = window.scrollX || window.pageXOffset || 0;
                var contentWidth = Math.max(0, rect.right + sx);
                total = Math.max(1, Math.ceil((contentWidth + padPx()) / w - 0.02));
                if (sp) sp.style.left = Math.max(0, total * w - 1) + 'px';
              }
              function fraction() { return total > 1 ? curPage / (total - 1) : 0; }
              function scrollFraction() {
                var h = document.documentElement.scrollHeight - window.innerHeight;
                return h > 0 ? Math.min(1, Math.max(0, window.scrollY / h)) : 0;
              }
              function report() { post({ type: 'page', cur: curPage + 1, total: total, f: fraction() }); }
              function stopAnim() {
                if (animId !== null) {
                  cancelAnimationFrame(animId);
                  animId = null;
                }
              }
              function goPage(p, animate) {
                measure();
                p = Math.max(0, Math.min(total - 1, p));
                curPage = p;
                var targetX = p * pageW();
                stopAnim();
                if (animate && smooth) {
                  var startX = window.scrollX || window.pageXOffset || 0;
                  var dist = targetX - startX;
                  if (Math.abs(dist) < 2) {
                    window.scrollTo(targetX, 0);
                  } else {
                    var startTime = performance.now();
                    var dur = 150;
                    function step(now) {
                      var t = Math.min(1, (now - startTime) / dur);
                      var ease = 1 - Math.pow(1 - t, 3);
                      window.scrollTo(Math.round(startX + dist * ease), 0);
                      if (t < 1) {
                        animId = requestAnimationFrame(step);
                      } else {
                        window.scrollTo(targetX, 0);
                        animId = null;
                      }
                    }
                    animId = requestAnimationFrame(step);
                  }
                } else {
                  window.scrollTo(targetX, 0);
                }
                report();
              }
              function turn(dir) {
                measure();
                if (dir > 0) {
                  if (curPage >= total - 1) post({ type: 'edge', dir: 'next' }); else goPage(curPage + 1, true);
                } else {
                  if (curPage <= 0) post({ type: 'edge', dir: 'prev' }); else goPage(curPage - 1, true);
                }
              }
              function applyPagedStyle() {
                var s = document.getElementById('llm-paged');
                if (mode !== 'paged') { if (s) s.remove(); spacer(); return; }
                if (!s) {
                  s = document.createElement('style');
                  s.id = 'llm-paged';
                  (document.head || document.documentElement).appendChild(s);
                }
                var w = pageW(), h = window.innerHeight, pad = padPx(), colW = w - 2 * pad, gap = 2 * pad, cols = 1;
                if (columnCount === 2 && w >= 600) {
                  cols = 2;
                  pad = Math.max(pad, 20);
                  gap = 2 * pad;
                  colW = (w - 4 * pad) / 2;
                  if (colW < 220) { cols = 1; pad = padPx(); colW = w - 2 * pad; gap = 2 * pad; }
                }
                s.textContent =
                  'html{position:relative!important;overflow:hidden!important;height:100%!important;width:100%!important;}' +
                  'body{position:relative!important;box-sizing:border-box!important;width:' + w + 'px!important;height:' + h + 'px!important;margin:0!important;' +
                  'padding:28px ' + pad + 'px 28px ' + pad + 'px!important;column-count:' + cols + '!important;column-width:' + colW + 'px!important;' +
                  'column-gap:' + gap + 'px!important;column-fill:auto!important;overflow:visible!important;' +
                  'max-width:none!important;}' +
                  'img,svg{max-height:' + (h - 70) + 'px!important;object-fit:contain!important;}' +
                  'h1,h2,h3{break-after:avoid!important;}';
              }
              function restoreFraction(f) {
                if (mode === 'paged') { measure(); goPage(Math.round(f * (total - 1)), false); }
                else { var h = document.documentElement.scrollHeight - window.innerHeight; window.scrollTo(0, Math.max(0, h) * f); }
              }
              window.__llmSetMode = function (paged, edge, sm, marginPct, cols) {
                var nextMode = paged ? 'paged' : 'scroll';
                var nextMargin = marginPct || 8;
                var nextCols = cols || 1;
                var layoutChanged = !modeInitialized || mode !== nextMode || margin !== nextMargin || columnCount !== nextCols;
                var frac = null;
                if (modeInitialized && layoutChanged && document.body) {
                  frac = (mode === 'paged') ? fraction() : scrollFraction();
                }
                mode = nextMode;
                edgeTap = !!edge; smooth = !!sm; margin = nextMargin; columnCount = nextCols;
                modeInitialized = true;
                if (layoutChanged) {
                  applyPagedStyle();
                  if (frac !== null) requestAnimationFrame(function () { restoreFraction(frac); });
                }
              };
              window.__llmGoFraction = function (f) { restoreFraction(f); };
      // Klavye: sayfa modunda sayfa çevir, kaydırma modunda bir ekran kaydır
      window.__llmTurn = function (dir) {
        if (mode === 'paged') { turn(dir); return; }
        var step = window.innerHeight - 48;
        var max = document.documentElement.scrollHeight - window.innerHeight;
        if (dir > 0) {
          if (window.scrollY >= max - 2) post({ type: 'edge', dir: 'next' });
          else window.scrollTo({ top: Math.min(max, window.scrollY + step), behavior: smooth ? 'smooth' : 'auto' });
        } else {
          if (window.scrollY <= 2) post({ type: 'edge', dir: 'prev' });
          else window.scrollTo({ top: Math.max(0, window.scrollY - step), behavior: smooth ? 'smooth' : 'auto' });
        }
      };

              // ---- Seslendirme: bölümün paragrafları ve okunan paragrafın işaretlenmesi ----
              window.__llmSpeechParagraphs = function () {
                var vh = window.innerHeight, vw = window.innerWidth, out = [], start = -1;
                var paged = (mode === 'paged');
                document.querySelectorAll(BLOCKS).forEach(function (el) {
                  if (el.closest('.llm-tr') || el.querySelector(BLOCKS)) return;
                  var t = plain(el).replace(/\s+/g, ' ').trim();
                  if (t.length < 2 || !/\p{L}/u.test(t)) return;
                  var id = el.getAttribute('data-llm-id');
                  if (!id) { id = String(++counter); el.setAttribute('data-llm-id', id); }
                  if (start < 0) {
                    var r = el.getBoundingClientRect();
                    if (paged ? (r.right > 24 && r.left < vw) : (r.bottom > 24 && r.top < vh)) start = out.length;
                  }
                  out.push({ id: parseInt(id, 10), text: t });
                });
                return JSON.stringify({ paras: out, start: Math.max(0, start) });
              };
              window.__llmSpeakMark = function (id) {
                document.querySelectorAll('.llm-speak').forEach(function (e) { e.classList.remove('llm-speak'); });
                if (id === null || id === undefined) return;
                var el = document.querySelector('[data-llm-id="' + id + '"]');
                if (!el) return;
                if (el.classList.contains('llm-rep') && !el.classList.contains('llm-showorig') && el.nextElementSibling) el = el.nextElementSibling;
                el.classList.add('llm-speak');
                var rects = el.getClientRects();
                var r = rects.length ? rects[0] : el.getBoundingClientRect();
                if (mode === 'paged') {
                  measure();
                  var p = Math.floor((r.left + window.scrollX + 2) / pageW());
                  if (p !== curPage) goPage(p, true);
                } else if (r.top < 40 || r.bottom > window.innerHeight - 40) {
                  window.scrollTo({ top: Math.max(0, window.scrollY + r.top - window.innerHeight * 0.25), behavior: smooth ? 'smooth' : 'auto' });
                }
              };
              window.__llmSync = function () {
                if (mode !== 'paged') return;
                measure();
                curPage = Math.max(0, Math.min(total - 1, Math.round(window.scrollX / pageW())));
                report();
              };
              window.addEventListener('resize', function () {
                if (mode !== 'paged') return;
                var f = fraction();
                applyPagedStyle();
                requestAnimationFrame(function () { restoreFraction(f); });
              });
              window.addEventListener('load', function () { if (mode === 'paged') { measure(); report(); } });

              document.addEventListener('touchstart', function (e) {
                var t = e.touches[0];
                t0 = e.touches.length === 1 ? { x: t.clientX, y: t.clientY, lastX: t.clientX, lastY: t.clientY, time: Date.now() } : null;
              }, { passive: true });
              document.addEventListener('touchmove', function (e) {
                if (t0 && e.touches.length === 1) {
                  t0.lastX = e.touches[0].clientX;
                  t0.lastY = e.touches[0].clientY;
                }
                if (mode !== 'paged') return;
                var sel = window.getSelection();
                if (sel && sel.toString().trim().length > 0) return;
                if (e.cancelable) e.preventDefault();
              }, { passive: false });
              document.addEventListener('touchend', function (e) {
                if (!t0) return;
                var t = e.changedTouches[0], s = t0; t0 = null;
                var endX = t ? t.clientX : s.lastX;
                var endY = t ? t.clientY : s.lastY;
                var dx = endX - s.x, dy = endY - s.y;
                var sel = window.getSelection();
                if (sel && sel.toString().trim().length > 0) return;
                if (mode === 'paged' && Math.abs(dx) > 45 && Math.abs(dx) > Math.abs(dy) * 1.3) { turn(dx < 0 ? 1 : -1); return; }
                if (Math.abs(dx) > 12 || Math.abs(dy) > 12 || Date.now() - s.time > 350) return;
                if (e.target && e.target.closest && e.target.closest('a')) return;
                var icn = e.target && e.target.closest && e.target.closest('.llm-ic');
                if (icn) { toggleOrig(icn.closest('.llm-tr')); return; }
                var pic = e.target && e.target.tagName === 'IMG' ? e.target : null;
                if (pic && !pic.closest('.llm-tr') && (pic.naturalWidth >= 200 || pic.getBoundingClientRect().width >= 80)) { openLightbox(pic); return; }
                var hl = e.target && e.target.closest && e.target.closest('.llm-hl');
                if (hl) { post({ type: 'hlTap', id: hl.getAttribute('data-hl') }); return; }
                var w = window.innerWidth;
                var side = endX > w * 0.7 || endX < w * 0.3;
                if (mode === 'paged') {
                  if (endX > w * 0.7) turn(1); else if (endX < w * 0.3) turn(-1);
                  else post({ type: 'center' });
                } else if (!edgeTap || !side) {
                  post({ type: 'center' });
                } else if (edgeTap) {
                  var step = window.innerHeight - 48;
                  var max = document.documentElement.scrollHeight - window.innerHeight;
                  if (endX > w * 0.7) {
                    if (window.scrollY >= max - 2) post({ type: 'edge', dir: 'next' });
                    else window.scrollTo(0, Math.min(max, window.scrollY + step));
                  } else if (endX < w * 0.3) {
                    if (window.scrollY <= 2) post({ type: 'edge', dir: 'prev' });
                    else window.scrollTo(0, Math.max(0, window.scrollY - step));
                  }
                }
              }, { passive: true });

              var timer = null;
              window.addEventListener('scroll', function () {
                if (mode === 'paged' || timer) return;
                timer = setTimeout(function () {
                  timer = null;
                  var h = document.documentElement.scrollHeight - window.innerHeight;
                  post({ type: 'scroll', f: h > 0 ? Math.min(1, Math.max(0, window.scrollY / h)) : 0 });
                }, 400);
              }, { passive: true });

              document.addEventListener('selectionchange', function () {
                var s = window.getSelection();
                post({ type: 'selection', has: !!s && s.toString().trim().length > 0 });
              });
            })();
        """.trimIndent()
    }
}

