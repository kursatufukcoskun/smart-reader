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

enum class SelectionAction { explain, translateParagraph, highlight, note, grammar }

fun jsString(s: String): String = JSONObject.quote(s)

@SuppressLint("SetJavaScriptEnabled")
class ReaderWebView(context: Context) : WebView(context) {
    var onMenuAction: ((SelectionAction) -> Unit)? = null
    var isPagedMode: Boolean = false

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
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val act = when (item.itemId) {
                    1001 -> SelectionAction.explain
                    1002 -> SelectionAction.translateParagraph
                    1003 -> SelectionAction.highlight
                    1004 -> SelectionAction.note
                    1005 -> SelectionAction.grammar
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

    var onCenterTap: (() -> Unit)? = null

    private var restoreFraction: Double = initialBook.scrollFraction
    private var pendingFragment: String? = null
    private var pendingHighlightID: String? = null
    private var restored: Boolean = false
    private var scrollFraction: Double = initialBook.scrollFraction
    private var lastAppliedCss: String? = null
    private var lastAppliedModeJS: String? = null

    val webView: ReaderWebView = ReaderWebView(context)

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
        loadChapter(entry.spineIndex, fragment = entry.fragment)
    }

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
    }

    private val modeJS: String
        get() {
            val s = settings.current
            val paged = s.readingMode == ReadingMode.paged
            val smooth = !s.theme.isEink
            val cols = if (s.columns == ColumnMode.double) 2 else 1
            val side = s.translationLayout == TranslationLayout.side
            return "window.__llmSetMode($paged, ${s.edgeTap}, $smooth, ${s.margin}, $cols); window.__llmSetLayout($side);"
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
                SelectionAction.highlight, SelectionAction.note -> {}
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
            )
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

    fun toggleBookmark() {
        scope.launch {
            val curChap = _chapterIndex.value
            val existing = bookmarks.forBook(_book.value.id).firstOrNull {
                it.chapter == curChap && abs((it.fraction ?: 0.0) - scrollFraction) < 0.012
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
                "window.__llmApplyHighlight(${jsString(h.id)}, $start, $end, ${jsString(h.color.name)}, false); window.getSelection().removeAllRanges();",
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
            "window.__llmStyleHighlight(${jsString(h.id)}, ${jsString(h.color.name)}, ${h.note.isNotEmpty()})",
            null
        )
    }

    fun deleteHighlight(h: Highlight) {
        highlights.delete(listOf(h.id))
        webView.evaluateJavascript("window.__llmRemoveHighlight(${jsString(h.id)})", null)
    }

    fun jumpAfterOpen(h: Highlight) {
        val c = h.chapter ?: return
        pendingHighlightID = h.id
        loadChapter(c)
    }

    fun goTo(h: Highlight) {
        val c = h.chapter ?: return
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
                var text = sel.toString().replace(/\s+/g, ' ').trim();
                text = text.replace(/^[\s"'“”‘’(\[]+|[\s.,;:!?"'“”‘’)\]]+$/g, '');
                if (!text) return null;
                var node = sel.anchorNode;
                var el = node && (node.nodeType === 1 ? node : node.parentElement);
                var block = el ? (el.closest(BLOCKS + ',td') || el.closest('div')) : null;
                var para = block ? (block.innerText || block.textContent || '').replace(/\s+/g, ' ').trim() : text;
                if (para.length > 3500) {
                  var i = para.indexOf(text);
                  var from = Math.max(0, (i < 0 ? 0 : i) - 1500);
                  para = para.substring(from, from + 3500);
                }
                return JSON.stringify({ text: text, paragraph: para });
              };

              window.__llmVisibleParagraphs = function () {
                var vh = window.innerHeight, out = [];
                document.querySelectorAll(BLOCKS).forEach(function (el) {
                  if (el.closest('.llm-tr')) return;
                  if (el.querySelector(BLOCKS)) return;
                  if (el.nextElementSibling && el.nextElementSibling.classList.contains('llm-tr')) return;
                  var r = el.getBoundingClientRect();
                  if (r.bottom < 0 || r.top > vh || r.height === 0) return;
                  var t = (el.innerText || '').replace(/\s+/g, ' ').trim();
                  if (t.length < 2 || !/\p{L}/u.test(t)) return;
                  var id = el.getAttribute('data-llm-id');
                  if (!id) { id = String(++counter); el.setAttribute('data-llm-id', id); }
                  out.push({ id: parseInt(id, 10), text: t });
                });
                return JSON.stringify(out);
              };

              var sideLayout = false;
              function canSide(el) {
                if (!sideLayout) return false;
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
              window.__llmSetLayout = function (side) {
                var changed = (!!side !== sideLayout);
                sideLayout = !!side;
                if (!changed) return;
                var items = [];
                document.querySelectorAll('.llm-tr').forEach(function (t) { items.push([t.getAttribute('data-for'), t.textContent]); });
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
                    var t = (el.innerText || '').replace(/\s+/g, ' ').trim();
                    if (t.length > 3) return t;
                  }
                }
                return '';
              };

              window.__llmClear = function () {
                document.querySelectorAll('.llm-tr').forEach(function (e) { e.remove(); });
                document.querySelectorAll('.llm-row').forEach(unwrapRow);
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
                  var t = (el.innerText || '').replace(/\s+/g, ' ').trim();
                  if (t) parts.push(t);
                });
                return parts.join('\n\n');
              };

              function cleanNodes() {
                var out = [], n;
                var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
                while ((n = w.nextNode())) {
                  var p = n.parentElement;
                  if (!p || p.closest('.llm-tr, script, style, #llm-end')) continue;
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
                if (!sel || sel.rangeCount === 0 || !sel.toString().trim()) return null;
                var r = sel.getRangeAt(0);
                var s = cleanOffset(r.startContainer, r.startOffset), e = cleanOffset(r.endContainer, r.endOffset);
                if (e <= s) return null;
                return JSON.stringify({ start: s, end: e, text: sel.toString().replace(/\s+/g, ' ').trim() });
              };
              window.__llmApplyHighlight = function (id, start, end, color, hasNote) {
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
                  try { r.surroundContents(span); } catch (e) {}
                });
              };
              window.__llmApplyAll = function (list) {
                list.forEach(function (h) { window.__llmApplyHighlight(h.id, h.start, h.end, h.color, h.note); });
              };
              window.__llmStyleHighlight = function (id, color, hasNote) {
                document.querySelectorAll('[data-hl="' + id + '"]').forEach(function (s) {
                  s.setAttribute('data-color', color);
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

              window.__llmChapterText = function () {
                var parts = [];
                document.querySelectorAll(BLOCKS).forEach(function (el) {
                  if (el.closest('.llm-tr') || el.querySelector(BLOCKS)) return;
                  var t = (el.innerText || '').replace(/\s+/g, ' ').trim();
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

              // ---- Seslendirme: bölümün paragrafları ve okunan paragrafın işaretlenmesi ----
              window.__llmSpeechParagraphs = function () {
                var vh = window.innerHeight, vw = window.innerWidth, out = [], start = -1;
                var paged = (mode === 'paged');
                document.querySelectorAll(BLOCKS).forEach(function (el) {
                  if (el.closest('.llm-tr') || el.querySelector(BLOCKS)) return;
                  var t = (el.innerText || '').replace(/\s+/g, ' ').trim();
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

