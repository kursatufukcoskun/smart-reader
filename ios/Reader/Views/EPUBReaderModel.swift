import SwiftUI
import WebKit
import Combine

enum SelectionAction { case explain, translateParagraph, highlight, note, grammar }

/// Seçim menüsüne "Açıkla / Paragrafı Çevir" ekleyen WKWebView.
final class ReaderWebView: WKWebView {
    var onMenuAction: ((SelectionAction) -> Void)?

    override func buildMenu(with builder: UIMenuBuilder) {
        super.buildMenu(with: builder)
        let explain = UIAction(title: "Açıkla", image: UIImage(systemName: "text.magnifyingglass")) { [weak self] _ in
            self?.onMenuAction?(.explain)
        }
        let translate = UIAction(title: "Paragrafı Çevir", image: UIImage(systemName: "character.bubble")) { [weak self] _ in
            self?.onMenuAction?(.translateParagraph)
        }
        let hl = UIAction(title: "Vurgula", image: UIImage(systemName: "highlighter")) { [weak self] _ in
            self?.onMenuAction?(.highlight)
        }
        let note = UIAction(title: "Not al", image: UIImage(systemName: "note.text.badge.plus")) { [weak self] _ in
            self?.onMenuAction?(.note)
        }
        let grammar = UIAction(title: "Cümleyi çöz", image: UIImage(systemName: "text.badge.checkmark")) { [weak self] _ in
            self?.onMenuAction?(.grammar)
        }
        let menu = UIMenu(title: "", options: .displayInline, children: [explain, translate, hl, note, grammar])
        builder.insertChild(menu, atStartOfMenu: .standardEdit)
    }
}

@MainActor
final class EPUBReaderModel: NSObject, ObservableObject, WKNavigationDelegate, WKScriptMessageHandler {
    @Published private(set) var book: Book
    let tracker: ReadingTracker
    let package: EPUBPackage
    let settings: ReaderSettings
    let library: LibraryStore
    let ai = AIAssistant()
    let chat: ChatModel
    let highlights: HighlightStore
    let bookmarks: BookmarkStore
    let speech = SpeechController()
    private var loadWaiters: [CheckedContinuation<Void, Never>] = []
    private var speechLoading = false
    @Published var toast: String?
    @Published var hardWords: HardWordsModel?
    @Published var study: ChapterStudyModel?

    /// Bölümün sonuna gelindi mi (sonda "pekiştir" önerisi için)
    var atChapterEnd: Bool {
        if settings.readingMode == .paged { return pageTotal > 1 && pageCur >= pageTotal }
        return book.scrollFraction >= 0.96 && !(book.isUnread)
    }
    let webView: ReaderWebView
    @Published var editing: EditingHighlight?
    /// Ekranın ortasına dokununca araç çubuğunu aç/kapat
    var onCenterTap: (() -> Void)?
    private var pendingHighlightID: UUID?

    @Published var chapterIndex: Int
    @Published var hasSelection = false
    @Published var isTranslatingPage = false
    @Published var translationsHidden = false
    @Published var alertMessage: String?
    @Published var pageCur = 0
    @Published var pageTotal = 0

    private var restoreFraction: Double
    private var pendingFragment: String?
    private var restored = false
    private var scrollFraction: Double
    private var bag = Set<AnyCancellable>()

    init(book: Book, package: EPUBPackage, settings: ReaderSettings, library: LibraryStore, highlights: HighlightStore, bookmarks: BookmarkStore, tracker: ReadingTracker) {
        self.bookmarks = bookmarks
        self.tracker = tracker
        self.book = book
        self.package = package
        self.settings = settings
        self.library = library
        self.highlights = highlights
        let start = max(0, min(book.position, package.spine.count - 1))
        self.chapterIndex = start
        self.restoreFraction = book.scrollFraction
        self.scrollFraction = book.scrollFraction

        let cfg = WKWebViewConfiguration()
        self.webView = ReaderWebView(frame: .zero, configuration: cfg)
        self.chat = ChatModel(settings: settings, bookTitle: book.title)
        super.init()
        chat.contextProvider = { [weak self] budget in await self?.buildChatContext(budget: budget) ?? .empty }

        cfg.userContentController.add(WeakScriptHandler(self), name: "llm")
        webView.navigationDelegate = self
        webView.isOpaque = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsLinkPreview = false
        webView.onMenuAction = { [weak self] action in self?.perform(action) }
        applyBackground()

        settings.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.applyStyle() }
            .store(in: &bag)

        speech.settings = settings
        speech.source = SpeechSource(
            paragraphs: { [weak self] in await self?.speechParagraphs() ?? ([], 0) },
            highlight: { [weak self] id in self?.speechMark(id) },
            advance: { [weak self] in await self?.speechAdvance() ?? false },
            title: { [weak self] in self?.book.title ?? "" },
            subtitle: { [weak self] in self?.currentChapterTitle ?? "" },
            showTranslation: { [weak self] id, text in
                self?.insert(id: id, text: text)
                self?.webView.evaluateJavaScript("window.__llmShow()", completionHandler: nil)
            })
        speech.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &bag)

        loadChapter(start, fraction: book.scrollFraction)
        ensureMetrics()
    }

    // MARK: - Seslendirme

    var currentChapterTitle: String { chapterTitle(chapterIndex) }

    func toggleSpeech() {
        if speech.isActive { speech.stop() } else { speech.start() }
    }

    private struct SpeechPayload: Decodable { let paras: [Para]; let start: Int }

    private func speechParagraphs() async -> ([SpeechUnit], Int) {
        guard let json = try? await webView.evaluateJavaScript("window.__llmSpeechParagraphs()") as? String,
              let data = json.data(using: .utf8),
              let p = try? JSONDecoder().decode(SpeechPayload.self, from: data) else { return ([], 0) }
        return (p.paras.map { SpeechUnit(id: $0.id, text: $0.text) }, p.start)
    }

    private func speechMark(_ id: Int?) {
        let arg = id.map(String.init) ?? "null"
        webView.evaluateJavaScript("window.__llmSpeakMark(\(arg))", completionHandler: nil)
    }

    /// Sonraki bölümü yükler ve yüklenmesini bekler.
    private func speechAdvance() async -> Bool {
        guard chapterIndex < package.spine.count - 1 else { return false }
        speechLoading = true
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            loadWaiters.append(c)
            loadChapter(chapterIndex + 1)
        }
        speechLoading = false
        try? await Task.sleep(nanoseconds: 450_000_000)
        return true
    }

    private func releaseLoadWaiters() {
        let w = loadWaiters
        loadWaiters = []
        w.forEach { $0.resume() }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { releaseLoadWaiters() }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { releaseLoadWaiters() }

    // MARK: - İlerleme, sayfa ve süre tahmini

    /// Kelime sayıları yoksa (eski kitap) arka planda hesaplar.
    private func ensureMetrics() {
        guard book.chapterWords == nil else { return }
        let urls = package.spine.map(\.url)
        Task {
            let counts = await Task.detached(priority: .utility) {
                urls.map { HTMLText.extract(from: $0).split(whereSeparator: \.isWhitespace).count }
            }.value
            book.chapterWords = counts
            saveProgress()
        }
    }

    /// Bölümde kalan kelime.
    var chapterWordsLeft: Double {
        guard let w = book.chapterWords, chapterIndex < w.count else { return 0 }
        return Double(w[chapterIndex]) * (1 - book.scrollFraction)
    }
    /// Kitapta kalan kelime.
    var bookWordsLeft: Double { max(0, Double(book.totalWords) - book.wordPosition) }

    func start() { tracker.begin(bookID: book.id, position: book.wordPosition) }
    func stop() { tracker.end() }

    // MARK: - Gezinme

    func loadChapter(_ index: Int, fraction: Double = 0, fragment: String? = nil) {
        let idx = max(0, min(index, package.spine.count - 1))
        chapterIndex = idx
        restoreFraction = fraction
        scrollFraction = fraction
        pendingFragment = fragment
        restored = false
        hasSelection = false
        translationsHidden = false
        if !speechLoading { speech.stopIfActive() }

        let ucc = webView.configuration.userContentController
        ucc.removeAllUserScripts()
        let source = Self.baseScript + "\nwindow.__llmSetStyle(\(jsString(settings.css))); \(modeJS)"
        ucc.addUserScript(WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        webView.loadFileURL(package.spine[idx].url, allowingReadAccessTo: Paths.base)
        saveProgress()
    }

    func openTOC(_ entry: TOCEntry) {
        loadChapter(entry.spineIndex, fragment: entry.fragment)
    }

    func nextChapter() { if chapterIndex < package.spine.count - 1 { loadChapter(chapterIndex + 1) } }
    func previousChapter() { if chapterIndex > 0 { loadChapter(chapterIndex - 1) } }

    private func saveProgress() {
        var b = book
        b.position = chapterIndex
        b.scrollFraction = scrollFraction
        b.chapterWords = book.chapterWords
        b.recomputeProgress()
        book = b
        library.update(b)
        if b.chapterWords != nil {
            if restored { tracker.moved(to: b.wordPosition) } else { tracker.jumped(to: b.wordPosition) }
        }
    }

    // MARK: - Stil

    private func applyBackground() {
        let ui = UIColor(settings.theme.color)
        webView.backgroundColor = ui
        webView.scrollView.backgroundColor = ui
        let eink = settings.theme.isEink
        webView.scrollView.decelerationRate = eink ? .fast : .normal
        webView.scrollView.bounces = !eink
        webView.scrollView.showsVerticalScrollIndicator = !eink
        webView.scrollView.isScrollEnabled = settings.readingMode != .paged
    }

    func applyStyle() {
        applyBackground()
        webView.evaluateJavaScript("window.__llmSetStyle(\(jsString(settings.css))); \(modeJS)", completionHandler: nil)
    }

    private var modeJS: String {
        let paged = settings.readingMode == .paged
        let smooth = !settings.theme.isEink
        return "window.__llmSetMode(\(paged), \(settings.edgeTap), \(smooth), \(settings.margin), \(settings.columns == .double ? 2 : 1)); window.__llmSetLayout(\(settings.translationLayout == .side));"
    }

    // MARK: - WKNavigationDelegate / mesajlar

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        applyHighlightsToPage()
        if let frag = pendingFragment, !frag.isEmpty {
            webView.evaluateJavaScript("(function(){var e=document.getElementById(\(jsString(frag)))||document.getElementsByName(\(jsString(frag)))[0]; if(e) e.scrollIntoView(); if(window.__llmSync) window.__llmSync();})()", completionHandler: nil)
        } else if restoreFraction > 0 || settings.readingMode == .paged {
            webView.evaluateJavaScript("window.__llmGoFraction(\(restoreFraction));", completionHandler: nil)
        }
        if let hid = pendingHighlightID {
            pendingHighlightID = nil
            webView.evaluateJavaScript("window.__llmScrollToHighlight(\(jsString(hid.uuidString)))", completionHandler: nil)
        }
        restored = true
        releaseLoadWaiters()
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard navigationAction.navigationType == .linkActivated, let url = navigationAction.request.url else { return .allow }
        if url.isFileURL {
            let path = normalizedPath(url)
            if let i = package.spine.firstIndex(where: { normalizedPath($0.url) == path }), i != chapterIndex {
                loadChapter(i, fragment: url.fragment)
                return .cancel
            }
            return .allow
        }
        _ = await UIApplication.shared.open(url)
        return .cancel
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
        switch type {
        case "scroll":
            guard restored, let f = body["f"] as? Double else { return }
            scrollFraction = f
            saveProgress()
        case "selection":
            hasSelection = body["has"] as? Bool ?? false
            tracker.touch()
        case "page":
            if let cur = body["cur"] as? Int, let total = body["total"] as? Int {
                pageCur = cur
                pageTotal = total
            }
            guard restored, let f = body["f"] as? Double else { return }
            scrollFraction = f
            saveProgress()
        case "center":
            onCenterTap?()
        case "hlTap":
            if let s = body["id"] as? String, let id = UUID(uuidString: s), let h = highlights.highlight(id) {
                editing = EditingHighlight(highlight: h, focusNote: false)
            }
        case "edge":
            if body["dir"] as? String == "next" { nextChapter() }
            else if chapterIndex > 0 { loadChapter(chapterIndex - 1, fraction: 1) }
        default: break
        }
    }

    // MARK: - LLM işlemleri

    private struct SelectionInfo: Decodable { let text: String; let paragraph: String }

    private func currentSelection() async -> SelectionInfo? {
        guard let json = try? await webView.evaluateJavaScript("window.__llmSelection()") as? String,
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(SelectionInfo.self, from: data)
    }

    func perform(_ action: SelectionAction) {
        Task {
            if action == .highlight || action == .note {
                await createHighlight(openNote: action == .note)
                return
            }
            guard let sel = await currentSelection() else { return }
            switch action {
            case .explain:
                ai.start(AIRequest(
                    title: sel.text.count > 40 ? String(sel.text.prefix(40)) + "…" : sel.text,
                    system: Prompts.assistantSystem,
                    user: Prompts.explain(selection: sel.text, paragraph: sel.paragraph, bookTitle: book.title),
                    config: settings.config(.fast),
                    maxTokens: 2048,
                    cacheKey: ResponseCache.key("explain", settings.config(.fast).cacheID, sel.text, sel.paragraph),
                    vocab: VocabDraft.make(word: sel.text, paragraph: sel.paragraph, book: book)))
            case .highlight, .note:
                break
            case .grammar:
                let words = sel.text.split(whereSeparator: \.isWhitespace).count
                let target = words >= 4 ? sel.text : sel.paragraph
                ai.start(AIRequest(
                    title: "Cümle çözümü",
                    system: Prompts.assistantSystem,
                    user: Prompts.grammar(text: target, bookTitle: book.title),
                    config: settings.config(.fast),
                    maxTokens: 2048,
                    cacheKey: ResponseCache.key("grammar", settings.config(.fast).cacheID, target)))
            case .translateParagraph:
                ai.start(AIRequest(
                    title: "Paragraf çevirisi",
                    system: Prompts.translatorSystem,
                    user: Prompts.translateParagraph(paragraph: sel.paragraph),
                    config: settings.config(.fast),
                    maxTokens: 2048,
                    cacheKey: ResponseCache.key("para", settings.config(.fast).cacheID, sel.paragraph)))
            }
        }
    }

    // MARK: - Yer imleri

    func toggleBookmark() {
        Task {
            if let existing = bookmarks.forBook(book.id).first(where: { $0.chapter == chapterIndex && abs(($0.fraction ?? 0) - scrollFraction) < 0.012 }) {
                bookmarks.delete([existing.id])
                toast = "Yer imi kaldırıldı"
                return
            }
            let snippet = (try? await webView.evaluateJavaScript("window.__llmSnippet()")) as? String ?? ""
            let name = package.toc.first(where: { $0.spineIndex == chapterIndex })?.title ?? "Bölüm \(chapterIndex + 1)"
            let loc = [name, book.pageText].compactMap { $0 }.joined(separator: " · ")
            bookmarks.add(Bookmark(bookID: book.id, bookTitle: book.title, snippet: String(snippet.prefix(110)),
                                   chapter: chapterIndex, fraction: scrollFraction, location: loc))
            toast = "Yer imi eklendi"
        }
    }

    func goTo(_ b: Bookmark) {
        guard let c = b.chapter else { return }
        loadChapter(c, fraction: b.fraction ?? 0)
    }

    // MARK: - Bölüm pekiştirme (özet + quiz + soru-cevap)

    func prepareStudy() {
        Task {
            guard let text = try? await webView.evaluateJavaScript("window.__llmChapterText()") as? String,
                  text.trimmingCharacters(in: .whitespacesAndNewlines).count > 300 else {
                alertMessage = "Bu bölümde çalışma seti için yeterli metin yok."
                return
            }
            let name = package.toc.first(where: { $0.spineIndex == chapterIndex })?.title ?? "Bölüm \(chapterIndex + 1)"
            study = ChapterStudyModel(title: name, text: text, book: book,
                                      config: settings.config(.smart), scopeKey: "ch\(chapterIndex)")
        }
    }

    // MARK: - Zor kelimeler

    func prepareHardWords() {
        Task {
            guard let text = try? await webView.evaluateJavaScript("window.__llmChapterText()") as? String,
                  !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                alertMessage = "Bu bölümde metin bulunamadı."
                return
            }
            let name = package.toc.first(where: { $0.spineIndex == chapterIndex })?.title ?? "Bölüm \(chapterIndex + 1)"
            hardWords = HardWordsModel(title: "Zor kelimeler: \(name)", text: text, book: book,
                                       config: settings.config(.smart), scopeKey: "ch\(chapterIndex)")
        }
    }

    func summarizeChapter() {
        Task {
            guard let text = try? await webView.evaluateJavaScript("window.__llmChapterText()") as? String,
                  !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                alertMessage = "Bu bölümde özetlenecek metin bulunamadı."
                return
            }
            let clipped = String(text.prefix(90_000))
            let name = package.toc.first(where: { $0.spineIndex == chapterIndex })?.title ?? "Bölüm \(chapterIndex + 1)"
            ai.start(AIRequest(
                title: "Özet: \(name)",
                system: Prompts.summarySystem,
                user: Prompts.summarize(text: clipped, scope: name, bookTitle: book.title),
                config: settings.config(.smart),
                maxTokens: 2048,
                cacheKey: ResponseCache.key("sum", settings.config(.smart).cacheID, book.id.uuidString, String(chapterIndex))))
        }
    }

    // MARK: - Vurgular

    private func chapterTitle(_ i: Int) -> String {
        package.toc.first(where: { $0.spineIndex == i })?.title ?? "Bölüm \(i + 1)"
    }

    private func applyHighlightsToPage() {
        let list: [[String: Any]] = highlights.forBook(book.id)
            .filter { $0.chapter == chapterIndex && $0.start != nil && $0.end != nil }
            .map { ["id": $0.id.uuidString, "start": $0.start!, "end": $0.end!, "color": $0.color.rawValue, "note": !$0.note.isEmpty] }
        guard !list.isEmpty, let data = try? JSONSerialization.data(withJSONObject: list),
              let json = String(data: data, encoding: .utf8) else { return }
        webView.evaluateJavaScript("window.__llmApplyAll(\(json))", completionHandler: nil)
    }

    private struct SelRange: Decodable { let start: Int; let end: Int; let text: String }

    private func createHighlight(openNote: Bool) async {
        guard let json = try? await webView.evaluateJavaScript("window.__llmSelectionRange()") as? String,
              let data = json.data(using: .utf8),
              let r = try? JSONDecoder().decode(SelRange.self, from: data) else { return }
        var h = Highlight(bookID: book.id, bookTitle: book.title, text: r.text, color: settings.highlightColor)
        h.chapter = chapterIndex
        h.start = r.start
        h.end = r.end
        h.location = chapterTitle(chapterIndex)
        highlights.add(h)
        webView.evaluateJavaScript("window.__llmApplyHighlight(\(jsString(h.id.uuidString)), \(r.start), \(r.end), \(jsString(h.color.rawValue)), false); window.getSelection().removeAllRanges();", completionHandler: nil)
        hasSelection = false
        if openNote { editing = EditingHighlight(highlight: h, focusNote: true) }
    }

    func saveHighlight(_ h: Highlight) {
        highlights.update(h)
        settings.highlightColor = h.color
        webView.evaluateJavaScript("window.__llmStyleHighlight(\(jsString(h.id.uuidString)), \(jsString(h.color.rawValue)), \(!h.note.isEmpty))", completionHandler: nil)
    }

    func deleteHighlight(_ h: Highlight) {
        highlights.delete([h.id])
        webView.evaluateJavaScript("window.__llmRemoveHighlight(\(jsString(h.id.uuidString)))", completionHandler: nil)
    }

    /// Kitap yeni açıldığında vurguya gider (bölümü yükleyip kaydırır).
    func jumpAfterOpen(_ h: Highlight) {
        guard let c = h.chapter else { return }
        pendingHighlightID = h.id
        loadChapter(c)
    }

    func goTo(_ h: Highlight) {
        guard let c = h.chapter else { return }
        if c == chapterIndex {
            webView.evaluateJavaScript("window.__llmScrollToHighlight(\(jsString(h.id.uuidString)))", completionHandler: nil)
        } else {
            pendingHighlightID = h.id
            loadChapter(c)
        }
    }

    // MARK: - Kitapla sohbet için bağlam

    private var chapterTexts: [Int: String] = [:]

    /// Önceki bölümlerin tamamı + şu anki bölümün ekrana kadar olan kısmı.
    func buildChatContext(budget: Int) async -> BookContext {
        let upto = chapterIndex
        let missing = (0..<upto).filter { chapterTexts[$0] == nil }
        if !missing.isEmpty {
            let jobs = missing.map { ($0, package.spine[$0].url) }
            let parsed = await Task.detached(priority: .userInitiated) {
                jobs.map { ($0.0, HTMLText.extract(from: $0.1)) }
            }.value
            for (i, t) in parsed { chapterTexts[i] = t }
        }
        func title(_ i: Int) -> String {
            package.toc.first(where: { $0.spineIndex == i })?.title ?? "Bölüm \(i + 1)"
        }
        var sections: [(title: String, body: String)] = (0..<upto).map { (title($0), chapterTexts[$0] ?? "") }
        let current = (try? await webView.evaluateJavaScript("window.__llmTextUpToViewport()")) as? String ?? ""
        sections.append((title(upto), current))
        var label = "Bölüm \(upto + 1)/\(package.spine.count)"
        if settings.readingMode == .paged, pageTotal > 0 { label += ", sayfa \(pageCur)" }
        return BookContext.assemble(sections: sections, budget: budget, label: label)
    }

    private struct Para: Decodable { let id: Int; let text: String }

    /// Ekrandaki paragrafları çevirip her birinin altına ekler.
    func translateVisible() {
        guard !isTranslatingPage else { return }
        isTranslatingPage = true
        let config = settings.config(.fast)

        Task {
            defer { isTranslatingPage = false }
            guard let json = try? await webView.evaluateJavaScript("window.__llmVisibleParagraphs()") as? String,
                  let data = json.data(using: .utf8),
                  let paras = try? JSONDecoder().decode([Para].self, from: data), !paras.isEmpty else {
                alertMessage = "Ekranda çevrilecek yeni paragraf yok."
                return
            }

            var todo: [Para] = []
            for p in paras {
                if let cached = ResponseCache.shared.get(ResponseCache.key("pg", config.cacheID, p.text)) {
                    insert(id: p.id, text: cached)
                } else {
                    todo.append(p)
                }
            }
            translationsHidden = false
            webView.evaluateJavaScript("window.__llmShow()", completionHandler: nil)

            // Paragrafları ~3000 karakterlik gruplara böl
            var batches: [[Para]] = []
            var cur: [Para] = []
            var size = 0
            for p in todo {
                if !cur.isEmpty && (size + p.text.count > 3000 || cur.count >= 10) {
                    batches.append(cur); cur = []; size = 0
                }
                cur.append(p); size += p.text.count
            }
            if !cur.isEmpty { batches.append(cur) }

            var firstError: String?
            await withTaskGroup(of: ([(id: Int, text: String)], String?).self) { group in
                for batch in batches {
                    let items = batch.map { (id: $0.id, text: $0.text) }
                    group.addTask {
                        do {
                            let out = try await LLMClient.complete(
                                config,
                                system: Prompts.translatorSystem,
                                user: Prompts.translateBatch(items),
                                maxTokens: 4096)
                            return (Prompts.parseBatch(out), nil)
                        } catch {
                            return ([], error.localizedDescription)
                        }
                    }
                }
                for await (results, err) in group {
                    if let err, firstError == nil { firstError = err }
                    for r in results {
                        if let src = todo.first(where: { $0.id == r.id }) {
                            ResponseCache.shared.set(ResponseCache.key("pg", config.cacheID, src.text), r.text)
                        }
                        insert(id: r.id, text: r.text)
                    }
                }
            }
            if let firstError { alertMessage = firstError }
        }
    }

    private func insert(id: Int, text: String) {
        webView.evaluateJavaScript("window.__llmInsert(\(id), \(jsString(text)))", completionHandler: nil)
    }

    func toggleTranslations() {
        translationsHidden.toggle()
        webView.evaluateJavaScript("window.__llmSetHidden(\(translationsHidden))", completionHandler: nil)
    }

    func clearTranslations() {
        webView.evaluateJavaScript("window.__llmClear()", completionHandler: nil)
    }

    // MARK: - Enjekte edilen JS

    static let baseScript = #"""
    (function () {
      if (window.__llmInstalled) return;
      window.__llmInstalled = true;
      var counter = 0;
      var BLOCKS = 'p,li,blockquote,h1,h2,h3,h4,h5,h6,dd,dt,figcaption';

      window.__llmSetStyle = function (css) {
        var s = document.getElementById('llm-style');
        if (!s) {
          s = document.createElement('style');
          s.id = 'llm-style';
          (document.head || document.documentElement).appendChild(s);
        }
        s.textContent = css;
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

      // Sohbet için: ekranın (veya sayfanın) altına/sağına kadar olan, yani okunmuş sayılan metin.
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

      // ---- Vurgular: temiz metin (çeviri/ayraç hariç) içindeki karakter aralığıyla saklanır ----
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

      // Okuma modu: sonsuz kaydırma veya sayfa sayfa (CSS sütunları ile)
      var mode = 'scroll', edgeTap = false, smooth = false, margin = 8, columnCount = 1;
      var curPage = 0, total = 1, t0 = null;
      function post(m) { window.webkit.messageHandlers.llm.postMessage(m); }
      function pageW() { return window.innerWidth; }
      function padPx() { return Math.round(pageW() * margin / 100); }
      function spacer() {
        var sp = document.getElementById('llm-end');
        if (mode !== 'paged') { if (sp) sp.remove(); return null; }
        if (!sp && document.body) {
          sp = document.createElement('div');
          sp.id = 'llm-end';
          sp.style.cssText = 'position:absolute;top:0;width:1px;height:1px;visibility:hidden;pointer-events:none;';
          document.body.appendChild(sp);
        }
        return sp;
      }
      function measure() {
        // Tarayıcı son sütunun sağ boşluğunu kaydırma alanına saymıyor; görünmez bir
        // ayırıcı ile son sayfanın tam hizalanmasını sağlıyoruz.
        var sp = spacer();
        if (sp) sp.style.left = '0px';
        total = Math.max(1, Math.ceil((document.documentElement.scrollWidth + padPx()) / pageW() - 0.02));
        if (sp) sp.style.left = (total * pageW()) + 'px';
      }
      function fraction() { return total > 1 ? curPage / (total - 1) : 0; }
      function scrollFraction() {
        var h = document.documentElement.scrollHeight - window.innerHeight;
        return h > 0 ? Math.min(1, Math.max(0, window.scrollY / h)) : 0;
      }
      function report() { post({ type: 'page', cur: curPage + 1, total: total, f: fraction() }); }
      function goPage(p, animate) {
        measure();
        p = Math.max(0, Math.min(total - 1, p));
        curPage = p;
        window.scrollTo({ left: p * pageW(), top: 0, behavior: (animate && smooth) ? 'smooth' : 'auto' });
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
        var w = pageW(), h = window.innerHeight, pad = padPx(), colW = w - 2 * pad, gap = 2 * pad;
        // Çift kolon: bir sayfa (ekran) iki sütun içerir; sütun+boşluk = yarım ekran.
        if (columnCount === 2 && w >= 600) { gap = Math.max(2 * pad, 40); colW = w / 2 - gap; if (colW < 220) { colW = w - 2 * pad; gap = 2 * pad; } }
        s.textContent =
          'html{overflow:hidden!important;height:100%!important;}' +
          'body{position:relative!important;box-sizing:border-box!important;height:' + h + 'px!important;margin:0!important;' +
          'padding:28px ' + pad + 'px 28px ' + pad + 'px!important;column-width:' + colW + 'px!important;' +
          'column-gap:' + gap + 'px!important;column-fill:auto!important;overflow:visible!important;' +
          'width:auto!important;max-width:none!important;}' +
          'img,svg{max-height:' + (h - 70) + 'px!important;object-fit:contain!important;}' +
          'h1,h2,h3{break-after:avoid!important;}';
      }
      function restoreFraction(f) {
        if (mode === 'paged') { measure(); goPage(Math.round(f * (total - 1)), false); }
        else { var h = document.documentElement.scrollHeight - window.innerHeight; window.scrollTo(0, Math.max(0, h) * f); }
      }
      window.__llmSetMode = function (paged, edge, sm, marginPct, cols) {
        var frac = null;
        if (document.body) frac = (mode === 'paged') ? fraction() : scrollFraction();
        mode = paged ? 'paged' : 'scroll';
        edgeTap = !!edge; smooth = !!sm; margin = marginPct || 8; columnCount = cols || 1;
        applyPagedStyle();
        if (frac !== null) requestAnimationFrame(function () { restoreFraction(frac); });
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
        t0 = e.touches.length === 1 ? { x: t.clientX, y: t.clientY, time: Date.now() } : null;
      }, { passive: true });
      document.addEventListener('touchend', function (e) {
        if (!t0) return;
        var t = e.changedTouches[0], s = t0; t0 = null;
        var dx = t.clientX - s.x, dy = t.clientY - s.y;
        var sel = window.getSelection();
        if (sel && sel.toString().trim().length > 0) return;
        if (mode === 'paged' && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5) { turn(dx < 0 ? 1 : -1); return; }
        if (Math.abs(dx) > 10 || Math.abs(dy) > 10 || Date.now() - s.time > 350) return;
        if (e.target && e.target.closest && e.target.closest('a')) return;
        var hl = e.target && e.target.closest && e.target.closest('.llm-hl');
        if (hl) { post({ type: 'hlTap', id: hl.getAttribute('data-hl') }); return; }
        var w = window.innerWidth;
        var side = t.clientX > w * 0.7 || t.clientX < w * 0.3;
        if (mode === 'paged') {
          if (t.clientX > w * 0.7) turn(1); else if (t.clientX < w * 0.3) turn(-1);
          else post({ type: 'center' });
        } else if (!edgeTap || !side) {
          post({ type: 'center' });
        } else if (edgeTap) {
          var step = window.innerHeight - 48;
          var max = document.documentElement.scrollHeight - window.innerHeight;
          if (t.clientX > w * 0.7) {
            if (window.scrollY >= max - 2) post({ type: 'edge', dir: 'next' });
            else window.scrollTo(0, Math.min(max, window.scrollY + step));
          } else if (t.clientX < w * 0.3) {
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
          window.webkit.messageHandlers.llm.postMessage({ type: 'scroll', f: h > 0 ? Math.min(1, Math.max(0, window.scrollY / h)) : 0 });
        }, 400);
      }, { passive: true });

      document.addEventListener('selectionchange', function () {
        var s = window.getSelection();
        window.webkit.messageHandlers.llm.postMessage({ type: 'selection', has: !!s && s.toString().trim().length > 0 });
      });
    })();
    """#
}
