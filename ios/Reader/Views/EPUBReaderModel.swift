import SwiftUI
import WebKit
import Combine

enum SelectionAction { case explain, translateParagraph, highlight, note, grammar, dictionary, xray, critique }

/// Seçim menüsüne "Açıkla / Paragrafı Çevir" ekleyen WKWebView.
final class ReaderWebView: WKWebView {
    var onMenuAction: ((SelectionAction) -> Void)?
    /// Klavye ile sayfa çevirme (Mac / harici klavye): +1 ileri, -1 geri
    var onKey: ((Int) -> Void)?

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if let key = presses.first?.key,
           key.modifierFlags.intersection([.command, .control, .alternate]).isEmpty {
            switch key.keyCode {
            case .keyboardRightArrow, .keyboardPageDown: onKey?(1); return
            case .keyboardLeftArrow, .keyboardPageUp: onKey?(-1); return
            case .keyboardSpacebar: onKey?(key.modifierFlags.contains(.shift) ? -1 : 1); return
            default: break
            }
        }
        super.pressesBegan(presses, with: event)
    }

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
        let dict = UIAction(title: "Sözlük", image: UIImage(systemName: "character.book.closed")) { [weak self] _ in
            self?.onMenuAction?(.dictionary)
        }
        let xray = UIAction(title: "Kim/Ne bu?", image: UIImage(systemName: "person.text.rectangle")) { [weak self] _ in
            self?.onMenuAction?(.xray)
        }
        let critique = UIAction(title: "Eleştirel oku", image: UIImage(systemName: "scale.3d")) { [weak self] _ in
            self?.onMenuAction?(.critique)
        }
        let menu = UIMenu(title: "", options: .displayInline, children: [dict, xray, explain, translate, hl, note, grammar, critique])
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

    /// Kitabın son bölümünün sonuna gelindi mi (kitap karnesi önerilir)
    var isBookEnd: Bool { chapterIndex >= package.spine.count - 1 && atChapterEnd }

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

    /// Başka cihazda daha yeni bir konum varsa (senkron sessizce üzerine yazmaz, sorar)
    @Published var remoteOffer: LibraryStore.RemoteProgressOffer?

    // Sözlük, dipnot, arama
    @Published var dictionaryTerm: DictionaryTerm?
    @Published var footnote: FootnoteInfo?
    let search = BookSearchModel()
    private var searchTexts: [String]?
    private var pendingFind: (query: String, occurrence: Int, before: String)?

    /// Atlamadan önceki yer: doluyken "kalıcı olsun mu?" çubuğu görünür.
    struct ReturnPoint: Codable, Equatable { var chapter: Int; var fraction: Double }
    /// Kitap başına cihazda saklanır (senkronlanmaz); kullanıcı cevaplayana kadar kalır.
    @Published var returnPoint: ReturnPoint? {
        didSet { Self.saveReturn(returnPoint, bookID: book.id) }
    }
    private static func returnKey(_ id: UUID) -> String { "returnPoint-\(id.uuidString)" }
    private static func saveReturn(_ r: ReturnPoint?, bookID: UUID) {
        let d = UserDefaults.standard
        if let r, let data = try? JSONEncoder().encode(r) { d.set(data, forKey: returnKey(bookID)) }
        else { d.removeObject(forKey: returnKey(bookID)) }
    }
    private static func loadReturn(_ bookID: UUID) -> ReturnPoint? {
        guard let data = UserDefaults.standard.data(forKey: returnKey(bookID)) else { return nil }
        return try? JSONDecoder().decode(ReturnPoint.self, from: data)
    }

    // Apple Pencil
    @Published var penMode = false
    @Published var penResult: PenResult?
    private let penRecognizer = PenStrokeRecognizer(target: nil, action: nil)
    private let pencilTap = PencilTapHandler()
    private var penDismiss: Task<Void, Never>?

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
        defer { computeResume() }
        self.scrollFraction = book.scrollFraction

        let cfg = WKWebViewConfiguration()
        self.webView = ReaderWebView(frame: .zero, configuration: cfg)
        self.chat = ChatModel(settings: settings, bookTitle: book.title)
        super.init()
        if let r = Self.loadReturn(book.id), r.chapter < package.spine.count { returnPoint = r }
        chat.contextProvider = { [weak self] budget in await self?.buildChatContext(budget: budget) ?? .empty }

        cfg.userContentController.add(WeakScriptHandler(self), name: "llm")
        webView.navigationDelegate = self
        webView.isOpaque = false
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.allowsLinkPreview = false
        webView.onMenuAction = { [weak self] action in self?.perform(action) }
        webView.onKey = { [weak self] dir in
            self?.tracker.touch()
            self?.webView.evaluateJavaScript("window.__llmTurn(\(dir))", completionHandler: nil)
        }
        chat.saveNote = { [weak self] t in self?.saveChatNote(t) }
        remoteOffer = library.remoteOffers[book.id]
        library.$remoteOffers
            .map { [id = book.id] in $0[id] }
            .removeDuplicates()
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.remoteOffer = $0 }
            .store(in: &bag)
        search.searcher = { [weak self] q, limit in await self?.searchBook(q, limit: limit) ?? [] }
        search.open = { [weak self] hit in self?.openHit(hit) }
        penRecognizer.isEnabled = false
        penRecognizer.appearance = { [weak self] in
            guard let s = self?.settings else { return (.highlight, .systemYellow) }
            return (s.penStyle, s.highlightColor.uiColor)
        }
        penRecognizer.onStroke = { [weak self] pts in self?.penStroke(pts) }
        webView.addGestureRecognizer(penRecognizer)
        pencilTap.onTap = { [weak self] in self?.cyclePenStyle() }
        pencilTap.attach(to: webView)
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

    /// Bir atlamadan önce çağrılır: ilk atlamada mevcut yeri dönüş noktası olarak saklar
    /// (arka arkaya atlamalarda en baştaki yer korunur).
    private func markReturnPoint() {
        if returnPoint == nil { returnPoint = ReturnPoint(chapter: chapterIndex, fraction: scrollFraction) }
    }

    func keepJump() { returnPoint = nil }

    // MARK: - Başka cihazdaki konum

    var remoteLabel: String { remoteOffer.map { chapterTitle($0.position) } ?? "" }

    func acceptRemote() {
        guard let o = remoteOffer else { return }
        library.clearRemoteOffer(book.id)
        markReturnPoint()   // istersen "Geri dön" ile bu cihazdaki yere dönebilirsin
        loadChapter(o.position, fraction: o.scrollFraction)
    }

    func keepLocalPosition() { library.keepLocalProgress(book.id) }

    func returnFromJump() {
        guard let r = returnPoint else { return }
        returnPoint = nil
        loadChapter(r.chapter, fraction: r.fraction)
    }

    var returnLabel: String { returnPoint.map { chapterTitle($0.chapter) } ?? "" }

    func openTOC(_ entry: TOCEntry) {
        markReturnPoint()
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
        applyReadingAids()
    }

    private var modeJS: String {
        let paged = settings.readingMode == .paged
        let smooth = !settings.theme.isEink
        return "window.__llmSetMode(\(paged), \(settings.edgeTap), \(smooth), \(settings.margin), \(settings.columns == .double ? 2 : 1)); window.__llmSetLayout(\(jsString(settings.translationLayout.rawValue)));"
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
        applyReadingAids(force: true)
        if let f = pendingFind {
            pendingFind = nil
            webView.evaluateJavaScript("window.__llmFindShow(\(jsString(f.query)), \(f.occurrence), \(jsString(f.before)))", completionHandler: nil)
        }
        restored = true
        releaseLoadWaiters()
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard navigationAction.navigationType == .linkActivated, let url = navigationAction.request.url else { return .allow }
        if url.isFileURL {
            let path = normalizedPath(url)
            if let i = package.spine.firstIndex(where: { normalizedPath($0.url) == path }), i != chapterIndex {
                markReturnPoint()
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
        case "note":
            if let href = body["href"] as? String { openNote(href: href) }
        case "center":
            onCenterTap?()
        case "ruler":
            if let f = body["f"] as? Double, f > 0.05, f < 0.95 { settings.rulerFraction = f }
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
            runAI(action, sel)
        }
    }

    private func runAI(_ action: SelectionAction, _ sel: SelectionInfo) {
        do {
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
            case .dictionary:
                lookUp(sel)
            case .xray:
                askBook(Prompts.xray(sel.text))
            case .critique:
                ai.start(AIRequest(
                    title: "Eleştirel okuma",
                    system: Prompts.assistantSystem,
                    user: Prompts.critique(passage: sel.text, paragraph: sel.paragraph, bookTitle: book.title),
                    config: settings.config(.smart),
                    maxTokens: 2048,
                    cacheKey: ResponseCache.key("critique", settings.config(.smart).cacheID, sel.text, sel.paragraph)))
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

    // MARK: - Kitaba hazır soru (şimdiye kadar ne oldu, karakter rehberi, kim/ne bu)

    /// Uzun aradan sonra açılınca "son kaldığın yeri hatırlat" önerisi için geçen gün sayısı (nil: gösterme)
    @Published var resumeDays: Int?

    private func computeResume() {
        guard !book.isUnread, book.progress < 0.98, let t = book.progressUpdatedAt else { return }
        let d = Calendar.current.dateComponents([.day], from: t, to: Date()).day ?? 0
        guard d >= 3 else { return }
        resumeDays = d
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            self?.resumeDays = nil
        }
    }

    func askResume() {
        resumeDays = nil
        askBook(Prompts.resume)
    }

    func dismissResume() { resumeDays = nil }

    @Published var openChat = false

    let relationMap = RelationMapModel()

    func showRelationMap() {
        relationMap.start(bookTitle: book.title, settings: settings) { [weak self] budget in
            await self?.buildChatContext(budget: budget) ?? .empty
        }
    }

    // MARK: - Kelime ipuçları, biyonik okuma, satır odaklayıcı

    @Published var isGlossing = false
    @Published var glossesHidden = false
    private var appliedBionic: Bool?
    private var appliedRuler: Bool?

    /// Okuma yardımcılarını (biyonik, odaklayıcı) sayfaya uygular; `force` ile yeni bölüm yüklenince yeniden.
    private func applyReadingAids(force: Bool = false) {
        if force { appliedBionic = nil; appliedRuler = nil }
        if appliedBionic != settings.bionic {
            appliedBionic = settings.bionic
            webView.evaluateJavaScript("window.__llmSetBionic(\(settings.bionic))", completionHandler: nil)
        }
        if appliedRuler != settings.readingRuler {
            appliedRuler = settings.readingRuler
            webView.evaluateJavaScript("window.__llmSetRuler(\(settings.readingRuler), \(settings.rulerFraction))", completionHandler: nil)
        }
    }

    func glossVisible() {
        guard !isGlossing else { return }
        isGlossing = true
        let config = settings.config(.fast)
        let level = settings.glossLevel
        Task {
            defer { isGlossing = false }
            guard let json = try? await webView.evaluateJavaScript("window.__llmVisibleParagraphs(true)") as? String,
                  let data = json.data(using: .utf8),
                  let paras = try? JSONDecoder().decode([Para].self, from: data), !paras.isEmpty else {
                alertMessage = "Ekranda ipucu eklenecek yeni paragraf yok."
                return
            }
            var found: [(id: Int, pairs: [[String]])] = []
            var todo: [Para] = []
            for p in paras {
                if let cached = ResponseCache.shared.get(ResponseCache.key("gloss", config.cacheID, level.rawValue, p.text)),
                   let d = cached.data(using: .utf8), let pairs = try? JSONSerialization.jsonObject(with: d) as? [[String]] {
                    found.append((p.id, pairs))
                } else { todo.append(p) }
            }
            var batches: [[Para]] = []
            var cur: [Para] = []
            var size = 0
            for p in todo {
                if !cur.isEmpty && (size + p.text.count > 3000 || cur.count >= 8) { batches.append(cur); cur = []; size = 0 }
                cur.append(p); size += p.text.count
            }
            if !cur.isEmpty { batches.append(cur) }

            var firstError: String?
            await withTaskGroup(of: ([(id: Int, pairs: [[String]])], String?).self) { group in
                for batch in batches {
                    let items = batch.map { (id: $0.id, text: $0.text) }
                    group.addTask {
                        do {
                            let out = try await LLMClient.complete(config, system: Prompts.assistantSystem,
                                                                   user: Prompts.glossBatch(items, perParagraph: level.perParagraph),
                                                                   maxTokens: 2048)
                            return (Prompts.parseGloss(out), nil)
                        } catch { return ([], error.localizedDescription) }
                    }
                }
                for await (results, err) in group {
                    if let err, firstError == nil { firstError = err }
                    for r in results {
                        found.append(r)
                        if let src = todo.first(where: { $0.id == r.id }),
                           let d = try? JSONSerialization.data(withJSONObject: r.pairs), let s = String(data: d, encoding: .utf8) {
                            ResponseCache.shared.set(ResponseCache.key("gloss", config.cacheID, level.rawValue, src.text), s)
                        }
                    }
                }
            }
            if !found.isEmpty {
                let payload = found.map { [$0.id, $0.pairs] as [Any] }
                if let d = try? JSONSerialization.data(withJSONObject: payload), let js = String(data: d, encoding: .utf8) {
                    glossesHidden = false
                    webView.evaluateJavaScript("window.__llmSetGlossHidden(false); window.__llmApplyGlossBatch(\(js))", completionHandler: nil)
                }
            }
            if let firstError { alertMessage = firstError }
        }
    }

    func toggleGlosses() {
        glossesHidden.toggle()
        webView.evaluateJavaScript("window.__llmSetGlossHidden(\(glossesHidden))", completionHandler: nil)
    }

    func clearGlosses() {
        glossesHidden = false
        webView.evaluateJavaScript("window.__llmClearGlosses()", completionHandler: nil)
    }

    func askBook(_ prompt: String) {
        chat.pendingPrompt = prompt
        openChat = true
    }

    // MARK: - Sözlük

    /// Seçili kelimeyi sistem sözlüğünde açar; sözlükte yoksa (veya ifade uzunsa) yapay zekâ açıklamasına düşer.
    private func lookUp(_ sel: SelectionInfo) {
        if let term = QuickDictionary.term(from: sel.text), QuickDictionary.hasDefinition(term) {
            dictionaryTerm = DictionaryTerm(term: term)
        } else {
            if QuickDictionary.term(from: sel.text) != nil { toast = "Sözlükte yok, yapay zekâya soruluyor" }
            runAI(.explain, sel)
        }
    }

    // MARK: - Dipnotlar

    private func openNote(href: String) {
        let parts = href.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
        let path = parts.first.map(String.init) ?? ""
        let frag = (parts.count > 1 ? String(parts[1]) : "").removingPercentEncoding ?? ""
        guard !frag.isEmpty else { return }

        var chapter = chapterIndex
        if !path.isEmpty {
            let target = URL(string: path.removingPercentEncoding ?? path, relativeTo: package.spine[chapterIndex].url)?.standardizedFileURL
            guard let target, let i = package.spine.firstIndex(where: { normalizedPath($0.url) == normalizedPath(target) }) else {
                return
            }
            chapter = i
        }
        Task {
            var text: String?
            if chapter == chapterIndex {
                if let json = try? await webView.evaluateJavaScript("window.__llmNoteText(\(jsString(frag)))") as? String,
                   let d = json.data(using: .utf8),
                   let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
                    text = o["text"] as? String
                }
            } else {
                let url = package.spine[chapter].url
                text = await Task.detached {
                    guard let data = try? Data(contentsOf: url) else { return nil as String? }
                    let html = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
                    return HTMLText.elementText(id: frag, in: html)
                }.value
            }
            let t = (text ?? "").split(whereSeparator: \.isWhitespace).joined(separator: " ")
            if t.isEmpty || t.count > 1500 {
                // Dipnota benzemiyor (başlık, uzun bölüm…): normal bağlantı gibi git
                goToFragment(chapter: chapter, fragment: frag)
            } else {
                footnote = FootnoteInfo(text: t, chapter: chapter, fragment: frag)
            }
        }
    }

    func goToFragment(chapter: Int, fragment: String) {
        markReturnPoint()
        if chapter == chapterIndex {
            webView.evaluateJavaScript("window.__llmScrollToId(\(jsString(fragment)))", completionHandler: nil)
        } else {
            loadChapter(chapter, fragment: fragment)
        }
    }

    func showFootnoteInText(_ info: FootnoteInfo) {
        footnote = nil
        goToFragment(chapter: info.chapter, fragment: info.fragment)
    }

    func translateFootnote(_ info: FootnoteInfo) {
        footnote = nil
        Task {
            try? await Task.sleep(nanoseconds: 400_000_000)
            runAI(.translateParagraph, SelectionInfo(text: info.text, paragraph: info.text))
        }
    }

    // MARK: - Kitapta arama

    private func ensureChapterTexts() async -> [String] {
        if let t = searchTexts { return t }
        let urls = package.spine.map(\.url)
        let texts = await Task.detached(priority: .userInitiated) {
            urls.map { SearchText.collapse(HTMLText.extract(from: $0)) }
        }.value
        searchTexts = texts
        return texts
    }

    fileprivate func searchBook(_ q: String, limit: Int) async -> [SearchHit] {
        let texts = await ensureChapterTexts()
        let raw = await Task.detached(priority: .userInitiated) { () -> [(Int, Int, String, String, String)] in
            var out: [(Int, Int, String, String, String)] = []
            for (i, t) in texts.enumerated() {
                if out.count >= limit { break }
                for m in SearchText.matches(in: t, query: q, limit: limit - out.count) {
                    out.append((i, m.occurrence, m.before, m.match, m.after))
                }
            }
            return out
        }.value
        return raw.map { c, n, b, m, a in
            var h = SearchHit(location: chapterTitle(c), before: b, match: m, after: a)
            h.chapter = c
            h.occurrence = n
            return h
        }
    }

    func openSearchHit(_ hit: SearchHit) { openHit(hit) }

    fileprivate func openHit(_ hit: SearchHit) {
        guard let c = hit.chapter else { return }
        markReturnPoint()
        let q = SearchText.collapse(search.query)
        let before = String(hit.before.suffix(25))
        if c == chapterIndex {
            webView.evaluateJavaScript("window.__llmFindShow(\(jsString(q)), \(hit.occurrence ?? 0), \(jsString(before)))", completionHandler: nil)
        } else {
            pendingFind = (q, hit.occurrence ?? 0, before)
            loadChapter(c)
        }
    }

    // MARK: - Yer imleri

    /// Sohbet yanıtını bulunduğun konuma (bölüm + oran) not olarak yer imlerine kaydeder.
    func saveChatNote(_ text: String) {
        let name = package.toc.first(where: { $0.spineIndex == chapterIndex })?.title ?? "Bölüm \(chapterIndex + 1)"
        let clean = text.replacingOccurrences(of: "**", with: "")
        bookmarks.add(Bookmark(bookID: book.id, bookTitle: book.title, snippet: String(clean.prefix(1500)),
                               chapter: chapterIndex, fraction: scrollFraction, location: "📌 \(name)", isNote: true))
        toast = "Not kaydedildi"
    }

    /// Bu konumda (aynı bölüm, ~%1 içinde) klasik bir yer imi var mı?
    var isBookmarkedHere: Bool {
        bookmarks.forBook(book.id).contains { $0.isNote != true && $0.chapter == chapterIndex && abs(($0.fraction ?? 0) - book.scrollFraction) < 0.012 }
    }

    func toggleBookmark() {
        Task {
            if let existing = bookmarks.forBook(book.id).first(where: { $0.isNote != true && $0.chapter == chapterIndex && abs(($0.fraction ?? 0) - scrollFraction) < 0.012 }) {
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
        markReturnPoint()
        loadChapter(c, fraction: b.fraction ?? 0)
    }

    // MARK: - Bölüm pekiştirme (özet + quiz + soru-cevap)

    @Published var isGeneratingInsights = false

    /// Bulunduğun bölümden yapay zekâ ile 3-5 içgörü çıkarıp vurgular listesine (✨ ile ayrı) ekler.
    func generateInsights() {
        guard !isGeneratingInsights else { return }
        isGeneratingInsights = true
        let config = settings.config(.smart)
        let chapter = chapterIndex
        let name = chapterTitle(chapter)
        Task {
            defer { isGeneratingInsights = false }
            guard let text = try? await webView.evaluateJavaScript("window.__llmChapterText()") as? String,
                  text.trimmingCharacters(in: .whitespacesAndNewlines).count > 300 else {
                alertMessage = "Bu bölümde içgörü için yeterli metin yok."
                return
            }
            do {
                let out = try await LLMClient.complete(config, system: Prompts.assistantSystem,
                                                       user: Prompts.insights(text: String(text.prefix(80_000)), bookTitle: book.title, scope: name),
                                                       maxTokens: 1200)
                let n = highlights.addInsights(Prompts.parseInsights(out), bookID: book.id, bookTitle: book.title,
                                               chapter: chapter, page: nil, location: name)
                toast = n > 0 ? "\(n) içgörü eklendi" : "Yeni içgörü çıkmadı"
            } catch {
                alertMessage = error.localizedDescription
            }
        }
    }

    func prepareStudy() {
        Task {
            guard let text = try? await webView.evaluateJavaScript("window.__llmChapterText()") as? String,
                  text.trimmingCharacters(in: .whitespacesAndNewlines).count > 300 else {
                alertMessage = "Bu bölümde çalışma seti için yeterli metin yok."
                return
            }
            let name = package.toc.first(where: { $0.spineIndex == chapterIndex })?.title ?? "Bölüm \(chapterIndex + 1)"
            let m = ChapterStudyModel(title: name, text: text, book: book,
                                      config: settings.config(.smart), scopeKey: "ch\(chapterIndex)")
            m.insightTarget = InsightTarget(bookID: book.id, bookTitle: book.title, chapter: chapterIndex, page: nil, location: name)
            study = m
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
            .map { ["id": $0.id.uuidString, "start": $0.start!, "end": $0.end!, "color": $0.color.rawValue, "note": !$0.note.isEmpty, "style": $0.effectiveStyle.rawValue] }
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
        webView.evaluateJavaScript("window.__llmApplyHighlight(\(jsString(h.id.uuidString)), \(r.start), \(r.end), \(jsString(h.color.rawValue)), false, 'highlight'); window.getSelection().removeAllRanges();", completionHandler: nil)
        hasSelection = false
        if openNote { editing = EditingHighlight(highlight: h, focusNote: true) }
    }

    // MARK: - Apple Pencil

    func togglePenMode() { setPenMode(!penMode) }

    func setPenMode(_ on: Bool) {
        penMode = on
        penRecognizer.isEnabled = on
        PenTouch.restrictScroll(in: webView, penOnly: on)
        if !on { dismissPenResult() }
    }

    func cyclePenStyle() {
        guard penMode else { return }
        let all = HighlightStyle.allCases
        let i = all.firstIndex(of: settings.penStyle) ?? 0
        settings.penStyle = all[(i + 1) % all.count]
        toast = settings.penStyle.title
    }

    private struct PenRange: Decodable { let start: Int; let end: Int; let text: String; let paragraph: String }

    private func penStroke(_ pts: [CGPoint]) {
        let style = settings.penStyle
        let color = settings.highlightColor
        guard let arg = try? JSONSerialization.data(withJSONObject: PenTouch.sample(pts)),
              let argJSON = String(data: arg, encoding: .utf8) else { return }
        Task {
            guard let json = try? await webView.evaluateJavaScript("window.__llmPenRange(\(argJSON), \(style != .highlight))") as? String,
                  let data = json.data(using: .utf8),
                  let r = try? JSONDecoder().decode(PenRange.self, from: data) else { return }
            var h = Highlight(bookID: book.id, bookTitle: book.title, text: r.text, color: color)
            h.style = style == .highlight ? nil : style
            h.chapter = chapterIndex
            h.start = r.start
            h.end = r.end
            h.location = chapterTitle(chapterIndex)
            highlights.add(h)
            webView.evaluateJavaScript("window.__llmApplyHighlight(\(jsString(h.id.uuidString)), \(r.start), \(r.end), \(jsString(color.rawValue)), false, \(jsString(style.rawValue)))", completionHandler: nil)
            tracker.touch()
            showPenResult(PenResult(highlight: h, paragraph: r.paragraph))
        }
    }

    private func showPenResult(_ r: PenResult) {
        penResult = r
        penDismiss?.cancel()
        penDismiss = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 7_000_000_000)
            if !Task.isCancelled { self?.penResult = nil }
        }
    }

    func dismissPenResult() {
        penDismiss?.cancel()
        penResult = nil
    }

    func penAction(_ action: SelectionAction) {
        guard let r = penResult else { return }
        dismissPenResult()
        switch action {
        case .note:
            editing = EditingHighlight(highlight: r.highlight, focusNote: true)
        default:
            runAI(action, SelectionInfo(text: r.highlight.text, paragraph: r.paragraph))
        }
    }

    func undoPen() {
        guard let r = penResult else { return }
        dismissPenResult()
        deleteHighlight(r.highlight)
    }

    func saveHighlight(_ h: Highlight) {
        highlights.update(h)
        settings.highlightColor = h.color
        webView.evaluateJavaScript("window.__llmStyleHighlight(\(jsString(h.id.uuidString)), \(jsString(h.color.rawValue)), \(!h.note.isEmpty), \(jsString(h.effectiveStyle.rawValue)))", completionHandler: nil)
    }

    func deleteHighlight(_ h: Highlight) {
        highlights.delete([h.id])
        webView.evaluateJavaScript("window.__llmRemoveHighlight(\(jsString(h.id.uuidString)))", completionHandler: nil)
    }

    /// Kitap yeni açıldığında vurguya gider (bölümü yükleyip kaydırır).
    /// Kitap bir vurguya atlayarak açılırken (günün vurgusu, vurgular listesi) okuma yeri dönüş noktası olarak
    /// saklanır; böylece "Geri dön" ile kaldığın yere dönebilirsin.
    func jumpAfterOpen(_ h: Highlight) {
        guard let c = h.chapter else { return }
        if !book.isUnread { markReturnPoint() }
        pendingHighlightID = h.id
        loadChapter(c)
    }

    func goTo(_ h: Highlight) {
        guard let c = h.chapter else { return }
        if h.isInsight && c == chapterIndex { return }   // içgörü metinde işaretli değil; zaten bu bölümdesin
        markReturnPoint()
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
        s.textContent = css;
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

      // Sohbet için: ekranın (veya sayfanın) altına/sağına kadar olan, yani okunmuş sayılan metin.
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

      // ---- Vurgular: temiz metin (çeviri/ayraç hariç) içindeki karakter aralığıyla saklanır ----
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
        var icn = e.target && e.target.closest && e.target.closest('.llm-ic');
        if (icn) { toggleOrig(icn.closest('.llm-tr')); return; }
        var pic = e.target && e.target.tagName === 'IMG' ? e.target : null;
        if (pic && !pic.closest('.llm-tr') && (pic.naturalWidth >= 200 || pic.getBoundingClientRect().width >= 80)) { openLightbox(pic); return; }
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
