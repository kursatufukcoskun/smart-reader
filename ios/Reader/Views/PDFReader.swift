import SwiftUI
import PDFKit
import Combine

// MARK: - Metin çıkarma yardımcıları

enum PDFText {
    private static func endsSentence(_ s: String) -> Bool {
        guard let c = s.last else { return false }
        return ".?!:;\"”’)".contains(c)
    }

    /// Sayfa satırlarını paragraf gruplarına böler (satır indeksleri).
    static func groups(lines: [String]) -> [[Int]] {
        let maxLen = lines.map(\.count).max() ?? 0
        func isBreak(_ a: String, _ b: String) -> Bool {
            if a.isEmpty || b.isEmpty { return true }
            return endsSentence(a) && Double(a.count) < Double(maxLen) * 0.8
        }
        var groups: [[Int]] = []
        var cur: [Int] = []
        for i in lines.indices {
            if lines[i].isEmpty {
                if !cur.isEmpty { groups.append(cur); cur = [] }
                continue
            }
            if let last = cur.last, isBreak(lines[last], lines[i]) {
                groups.append(cur); cur = []
            }
            cur.append(i)
        }
        if !cur.isEmpty { groups.append(cur) }
        return groups
    }

    static func join(_ lines: [String]) -> String {
        var out = ""
        for l in lines {
            if out.isEmpty { out = l; continue }
            if out.hasSuffix("-"), let f = l.first, f.isLowercase {
                out.removeLast(); out += l
            } else {
                out += " " + l
            }
        }
        return out
    }

    static func pageLines(_ text: String) -> (lines: [String], ranges: [NSRange]) {
        let ns = text as NSString
        var ranges: [NSRange] = []
        ns.enumerateSubstrings(in: NSRange(location: 0, length: ns.length), options: [.byLines, .substringNotRequired]) { _, r, _, _ in
            ranges.append(r)
        }
        let lines = ranges.map { ns.substring(with: $0).trimmingCharacters(in: .whitespaces) }
        return (lines, ranges)
    }

    static func paragraphs(in text: String) -> [String] {
        let (lines, _) = pageLines(text)
        return groups(lines: lines).map { join($0.map { lines[$0] }) }
    }

    static func paragraph(in text: String, around index: Int) -> String? {
        let (lines, ranges) = pageLines(text)
        guard !lines.isEmpty else { return nil }
        var li = ranges.lastIndex(where: { $0.location <= index }) ?? 0
        if li >= lines.count { li = lines.count - 1 }
        for g in groups(lines: lines) where g.contains(li) {
            return join(g.map { lines[$0] })
        }
        return nil
    }
}

// MARK: - PDFView alt sınıfı

final class ReaderPDFView: PDFView {
    var onMenuAction: ((SelectionAction) -> Void)?

    override var canBecomeFirstResponder: Bool { true }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if let key = presses.first?.key,
           key.modifierFlags.intersection([.command, .control, .alternate]).isEmpty {
            switch key.keyCode {
            case .keyboardRightArrow, .keyboardPageDown: goToNextPage(nil); return
            case .keyboardLeftArrow, .keyboardPageUp: goToPreviousPage(nil); return
            case .keyboardSpacebar:
                if key.modifierFlags.contains(.shift) { goToPreviousPage(nil) } else { goToNextPage(nil) }
                return
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
        builder.insertChild(UIMenu(title: "", options: .displayInline, children: [dict, xray, explain, translate, hl, note, grammar, critique]), atStartOfMenu: .standardEdit)
    }
}

struct PDFViewHost: UIViewRepresentable {
    let pdfView: PDFView
    func makeUIView(context: Context) -> PDFView { pdfView }
    func updateUIView(_ uiView: PDFView, context: Context) {}
}

// MARK: - Model

@MainActor
final class PDFReaderModel: NSObject, ObservableObject, UIGestureRecognizerDelegate {
    @Published private(set) var book: Book
    let tracker: ReadingTracker
    /// Sayfa başına ortalama kelime (örneklemeyle) - süre tahmini için
    private(set) var wordsPerPageAvg: Double = 250
    let document: PDFDocument
    let settings: ReaderSettings
    let library: LibraryStore
    let ai = AIAssistant()
    let chat: ChatModel
    let highlights: HighlightStore
    let bookmarks: BookmarkStore
    @Published var toast: String?
    @Published var hardWords: HardWordsModel?
    @Published var dictionaryTerm: DictionaryTerm?
    let search = BookSearchModel()
    private lazy var finder = PDFFinder(doc: document)
    @Published var study: ChapterStudyModel?
    @Published var editing: EditingHighlight?
    var onCenterTap: (() -> Void)?
    let pdfView = ReaderPDFView()
    let speech = SpeechController()

    @Published var pageIndex: Int
    @Published var hasSelection = false

    /// Başka cihazda daha yeni bir konum varsa (senkron sessizce üzerine yazmaz, sorar)
    @Published var remoteOffer: LibraryStore.RemoteProgressOffer?

    /// Atlamadan önceki sayfa: doluyken "kalıcı olsun mu?" çubuğu görünür.
    /// Kitap başına cihazda saklanır (senkronlanmaz); kullanıcı cevaplayana kadar kalır.
    @Published var returnPage: Int? {
        didSet {
            let key = "returnPage-\(book.id.uuidString)"
            if let p = returnPage { UserDefaults.standard.set(p, forKey: key) }
            else { UserDefaults.standard.removeObject(forKey: key) }
        }
    }

    // Apple Pencil
    @Published var penMode = false
    @Published var penResult: PenResult?
    private let penRecognizer = PenStrokeRecognizer(target: nil, action: nil)
    private let pencilTap = PencilTapHandler()
    private var penDismiss: Task<Void, Never>?
    private var bag = Set<AnyCancellable>()

    var pageCount: Int { document.pageCount }

    init(book: Book, document: PDFDocument, settings: ReaderSettings, library: LibraryStore, highlights: HighlightStore, bookmarks: BookmarkStore, tracker: ReadingTracker) {
        self.bookmarks = bookmarks
        self.tracker = tracker
        self.book = book
        self.document = document
        self.settings = settings
        self.library = library
        self.highlights = highlights
        self.pageIndex = min(max(book.position, 0), max(document.pageCount - 1, 0))
        self.chat = ChatModel(settings: settings, bookTitle: book.title)
        super.init()
        if let p = UserDefaults.standard.object(forKey: "returnPage-\(book.id.uuidString)") as? Int, p < document.pageCount {
            returnPage = p
        }
        chat.contextProvider = { [weak self] budget in await self?.buildChatContext(budget: budget) ?? .empty }
        chat.saveNote = { [weak self] t in self?.saveChatNote(t) }
        speech.settings = settings
        speech.source = SpeechSource(
            paragraphs: { [weak self] in
                guard let self else { return ([], 0) }
                let t = self.document.page(at: self.pageIndex)?.string ?? ""
                let paras = PDFText.paragraphs(in: t).enumerated().map { SpeechUnit(id: $0.offset, text: $0.element) }
                return (paras, 0)
            },
            highlight: { _ in },
            advance: { [weak self] in
                guard let self, self.pageIndex < self.pageCount - 1 else { return false }
                self.go(to: self.pageIndex + 1)
                return true
            },
            title: { [weak self] in self?.book.title ?? "" },
            subtitle: { [weak self] in self.map { "Sayfa \($0.pageIndex + 1) / \($0.pageCount)" } ?? "" })
        speech.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.objectWillChange.send() }
            .store(in: &bag)
        remoteOffer = library.remoteOffers[book.id]
        library.$remoteOffers
            .map { [id = book.id] in $0[id] }
            .removeDuplicates()
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.remoteOffer = $0 }
            .store(in: &bag)
        sampleWordsPerPage()
        defer { computeResume() }
        var nb = self.book
        nb.pagesTotal = document.pageCount
        nb.recomputeProgress()
        self.book = nb

        pdfView.document = document
        applyAllAnnotations()
        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.pageBreakMargins = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        pdfView.backgroundColor = .systemGray5
        pdfView.onMenuAction = { [weak self] a in self?.perform(a) }
        penRecognizer.isEnabled = false
        penRecognizer.appearance = { [weak self] in
            guard let s = self?.settings else { return (.highlight, .systemYellow) }
            return (s.penStyle, s.highlightColor.uiColor)
        }
        penRecognizer.onStroke = { [weak self] pts in self?.penStroke(pts) }
        pdfView.addGestureRecognizer(penRecognizer)
        pencilTap.onTap = { [weak self] in self?.cyclePenStyle() }
        pencilTap.attach(to: pdfView)
        search.searcher = { [weak self] q, limit in await self?.searchBook(q, limit: limit) ?? [] }

        if let page = document.page(at: pageIndex) {
            DispatchQueue.main.async { [weak self] in self?.pdfView.go(to: page) }
        }

        // E-ink: tek sayfa görünümü ve kenara dokunarak sayfa çevirme
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        tap.delegate = self
        tap.cancelsTouchesInView = false
        pdfView.addGestureRecognizer(tap)
        settings.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.applyMode(); self?.applyCrop() }
            .store(in: &bag)
        applyMode()
        applyCrop()

        NotificationCenter.default.publisher(for: .PDFViewPageChanged, object: pdfView)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.pageChanged() }
            .store(in: &bag)
        NotificationCenter.default.publisher(for: .PDFViewSelectionChanged, object: pdfView)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self else { return }
                self.hasSelection = !(self.pdfView.currentSelection?.string?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
            }
            .store(in: &bag)
    }

    // MARK: Kenar boşluğu kırpma

    /// Belgeden örneklenen sayfalara göre ortak kırpma payları (sayfa boyutuna oranla): sol, alt, sağ, üst.
    private var cropFractions: (l: CGFloat, b: CGFloat, r: CGFloat, t: CGFloat)?
    private var cropComputed = false
    private var appliedCrop: Bool?

    /// Bir sayfanın küçük resminde "boş olmayan" alanın sınırlarını bulur (sayfa boyutuna oranla kenar payları).
    private func contentMargins(of page: PDFPage) -> (l: CGFloat, b: CGFloat, r: CGFloat, t: CGFloat)? {
        let media = page.bounds(for: .mediaBox)
        guard media.width > 0, media.height > 0, page.rotation == 0 else { return nil }
        let w = 220, h = max(1, Int((220 * media.height / media.width).rounded()))
        let img = page.thumbnail(of: CGSize(width: w, height: h), for: .mediaBox)
        guard let cg = img.cgImage else { return nil }
        var px = [UInt8](repeating: 255, count: w * h)
        guard let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                  space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
        ctx.setFillColor(gray: 1, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var minX = w, maxX = -1, minY = h, maxY = -1
        for y in 0..<h {
            let row = y * w
            for x in 0..<w where px[row + x] < 235 {
                if x < minX { minX = x }
                if x > maxX { maxX = x }
                if y < minY { minY = y }
                if y > maxY { maxY = y }
            }
        }
        guard maxX >= minX, maxY >= minY else { return nil }
        // Neredeyse boş sayfa (kapak/ayraç) örnek sayılmaz
        let area = CGFloat((maxX - minX + 1) * (maxY - minY + 1)) / CGFloat(w * h)
        guard area > 0.05 else { return nil }
        // Bitmap satırları yukarıdan aşağı: minY üst kenara, maxY alt kenara yakındır.
        return (CGFloat(minX) / CGFloat(w), CGFloat(h - 1 - maxY) / CGFloat(h),
                CGFloat(w - 1 - maxX) / CGFloat(w), CGFloat(minY) / CGFloat(h))
    }

    private func computeCrop() {
        cropComputed = true
        let n = document.pageCount
        guard n > 0 else { return }
        let picks = Set((0..<min(16, n)).map { $0 * max(n / 16, 1) }).filter { $0 < n }
        var l: CGFloat = 1, b: CGFloat = 1, r: CGFloat = 1, t: CGFloat = 1
        var samples = 0
        for i in picks {
            guard let page = document.page(at: i), let m = contentMargins(of: page) else { continue }
            l = min(l, m.l); b = min(b, m.b); r = min(r, m.r); t = min(t, m.t)
            samples += 1
        }
        guard samples >= 2 else { return }
        // Biraz nefes payı bırak ve en fazla %30 kırp
        let pad: CGFloat = 0.012
        func f(_ v: CGFloat) -> CGFloat { min(0.30, max(0, v - pad)) }
        let res = (l: f(l), b: f(b), r: f(r), t: f(t))
        if res.l + res.r > 0.01 || res.t + res.b > 0.01 { cropFractions = res }
    }

    private func applyCrop() {
        let on = settings.cropMargins
        guard appliedCrop != on else { return }
        appliedCrop = on
        if on && !cropComputed { computeCrop() }
        let current = pdfView.currentPage
        for i in 0..<document.pageCount {
            guard let page = document.page(at: i), page.rotation == 0 else { continue }
            let media = page.bounds(for: .mediaBox)
            if on, let f = cropFractions {
                page.setBounds(CGRect(x: media.minX + media.width * f.l, y: media.minY + media.height * f.b,
                                      width: media.width * (1 - f.l - f.r), height: media.height * (1 - f.t - f.b)), for: .cropBox)
            } else {
                page.setBounds(media, for: .cropBox)
            }
        }
        pdfView.displayBox = .cropBox
        pdfView.layoutDocumentView()
        if let current { DispatchQueue.main.async { [weak self] in self?.pdfView.go(to: current) } }
    }

    private var appliedKey: String?

    private func applyMode() {
        let paged = settings.readingMode == .paged
        let double = settings.columns == .double
        let key = "\(paged)-\(double)"
        guard appliedKey != key else { return }
        appliedKey = key
        let current = pdfView.currentPage
        pdfView.usePageViewController(false, withViewOptions: nil)
        if double {
            // Çift kolon: yan yana iki sayfa (kitap gibi)
            pdfView.displayMode = paged ? .twoUp : .twoUpContinuous
            pdfView.displayDirection = paged ? .horizontal : .vertical
            pdfView.displaysAsBook = true
        } else if paged {
            pdfView.displaysAsBook = false
            pdfView.displayMode = .singlePage
            pdfView.displayDirection = .horizontal
            pdfView.usePageViewController(true, withViewOptions: [UIPageViewController.OptionsKey.interPageSpacing: 10])
        } else {
            pdfView.displaysAsBook = false
            pdfView.displayMode = .singlePageContinuous
            pdfView.displayDirection = .vertical
        }
        pdfView.autoScales = true
        if let current { DispatchQueue.main.async { [weak self] in self?.pdfView.go(to: current) } }
    }

    @objc private func handleTap(_ g: UITapGestureRecognizer) {
        let loc = g.location(in: pdfView)
        if !hasSelection, let page = pdfView.page(for: loc, nearest: false) {
            let pt = pdfView.convert(loc, to: page)
            if let h = highlights.hit(bookID: book.id, page: document.index(for: page), point: pt) {
                editing = EditingHighlight(highlight: h, focusNote: false)
                return
            }
        }
        guard !hasSelection, pdfView.bounds.width > 0 else { return }
        let x = g.location(in: pdfView).x / pdfView.bounds.width
        let turns = settings.readingMode == .paged || settings.edgeTap
        if turns && x > 0.7 { pdfView.goToNextPage(nil) }
        else if turns && x < 0.3 { pdfView.goToPreviousPage(nil) }
        else { onCenterTap?() }
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    private func pageChanged() {
        guard let page = pdfView.currentPage else { return }
        let i = document.index(for: page)
        guard i != NSNotFound else { return }
        pageIndex = i
        var b = book
        b.position = i
        b.pagesTotal = pageCount
        b.recomputeProgress()
        book = b
        library.update(b)
        tracker.moved(to: Double(i) * wordsPerPageAvg)
    }

    private func sampleWordsPerPage() {
        let n = document.pageCount
        guard n > 0 else { return }
        let picks = Set((0..<min(12, n)).map { $0 * max(n / 12, 1) }).filter { $0 < n }
        let counts = picks.compactMap { document.page(at: $0)?.string?.split(whereSeparator: \.isWhitespace).count }.filter { $0 > 20 }
        if !counts.isEmpty { wordsPerPageAvg = max(80, Double(counts.reduce(0, +)) / Double(counts.count)) }
    }

    func start() { tracker.begin(bookID: book.id, position: Double(pageIndex) * wordsPerPageAvg) }
    func stop() { tracker.end() }

    /// Kitapta kalan kelime (sayfa x ortalama).
    var bookWordsLeft: Double { Double(max(0, pageCount - pageIndex - 1)) * wordsPerPageAvg }

    func go(to index: Int) {
        guard let page = document.page(at: max(0, min(index, pageCount - 1))) else { return }
        pdfView.go(to: page)
    }

    // MARK: LLM

    private func selectionContext() -> (text: String, paragraph: String)? {
        guard let sel = pdfView.currentSelection,
              let raw = sel.string?.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else { return nil }
        var paragraph = raw
        if let page = sel.pages.first, let pageText = page.string {
            let b = sel.bounds(for: page)
            var idx = page.characterIndex(at: CGPoint(x: b.minX + 1, y: b.midY))
            if idx == NSNotFound || idx < 0 {
                idx = (pageText as NSString).range(of: raw.prefix(20).description).location
            }
            if idx != NSNotFound, idx >= 0, let p = PDFText.paragraph(in: pageText, around: idx) {
                paragraph = p
            }
        }
        return (raw, String(paragraph.prefix(3500)))
    }

    func perform(_ action: SelectionAction, context: (text: String, paragraph: String)? = nil) {
        if context == nil, action == .highlight || action == .note {
            createHighlight(openNote: action == .note)
            return
        }
        guard let sel = context ?? selectionContext() else { return }
        switch action {
        case .xray:
            askBook(Prompts.xray(sel.text))
        case .critique:
            ai.start(AIRequest(
                title: "Eleştirel okuma",
                system: Prompts.assistantSystem,
                user: Prompts.critique(passage: sel.text, paragraph: sel.paragraph, bookTitle: book.title),
                config: settings.config(.smart),
                cacheKey: ResponseCache.key("critique", settings.config(.smart).cacheID, sel.text, sel.paragraph)))
        case .dictionary:
            if let term = QuickDictionary.term(from: sel.text), QuickDictionary.hasDefinition(term) {
                dictionaryTerm = DictionaryTerm(term: term)
            } else {
                if QuickDictionary.term(from: sel.text) != nil { toast = "Sözlükte yok, yapay zekâya soruluyor" }
                perform(.explain, context: sel)
            }
        case .explain:
            ai.start(AIRequest(
                title: sel.text.count > 40 ? String(sel.text.prefix(40)) + "…" : sel.text,
                system: Prompts.assistantSystem,
                user: Prompts.explain(selection: sel.text, paragraph: sel.paragraph, bookTitle: book.title),
                config: settings.config(.fast),
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
                cacheKey: ResponseCache.key("grammar", settings.config(.fast).cacheID, target)))
        case .translateParagraph:
            ai.start(AIRequest(
                title: "Paragraf çevirisi",
                system: Prompts.translatorSystem,
                user: Prompts.translateParagraph(paragraph: sel.paragraph),
                config: settings.config(.fast),
                cacheKey: ResponseCache.key("para", settings.config(.fast).cacheID, sel.paragraph)))
        }
    }

    /// Sohbet yanıtını bulunduğun sayfaya not olarak yer imlerine kaydeder.
    func saveChatNote(_ text: String) {
        let clean = text.replacingOccurrences(of: "**", with: "")
        bookmarks.add(Bookmark(bookID: book.id, bookTitle: book.title, snippet: String(clean.prefix(1500)),
                               page: pageIndex, location: "📌 Sayfa \(pageIndex + 1) / \(pageCount)", isNote: true))
        toast = "Not kaydedildi"
    }

    /// Bu sayfada klasik bir yer imi var mı?
    var isBookmarkedHere: Bool {
        bookmarks.forBook(book.id).contains { $0.isNote != true && $0.page == pageIndex }
    }

    func toggleBookmark() {
        if let existing = bookmarks.forBook(book.id).first(where: { $0.isNote != true && $0.page == pageIndex }) {
            bookmarks.delete([existing.id])
            toast = "Yer imi kaldırıldı"
            return
        }
        let snippet = String((document.page(at: pageIndex)?.string ?? "")
            .split(whereSeparator: \.isWhitespace).joined(separator: " ").prefix(110))
        bookmarks.add(Bookmark(bookID: book.id, bookTitle: book.title, snippet: snippet,
                               page: pageIndex, location: "Sayfa \(pageIndex + 1) / \(pageCount)"))
        toast = "Yer imi eklendi"
    }

    func goTo(_ b: Bookmark) { if let p = b.page { jump(to: p) } }

    /// Son `count` sayfayı (bulunduğunuz sayfa dahil) çalışma setine çevirir.
    func prepareStudy(lastPages count: Int) {
        let start = max(0, pageIndex - count + 1)
        let t = text(ofPages: start..<(pageIndex + 1))
        guard t.trimmingCharacters(in: .whitespacesAndNewlines).count > 300 else {
            toast = "Bu sayfalarda yeterli metin yok"
            return
        }
        let m = ChapterStudyModel(title: "Sayfa \(start + 1)–\(pageIndex + 1)", text: t, book: book,
                                  config: settings.config(.smart), scopeKey: "p\(start)-\(pageIndex)")
        m.insightTarget = InsightTarget(bookID: book.id, bookTitle: book.title, chapter: nil, page: pageIndex,
                                        location: "Sayfa \(start + 1)–\(pageIndex + 1)")
        study = m
    }

    @Published var isGeneratingInsights = false

    /// Son 5 sayfadan yapay zekâ ile 3-5 içgörü çıkarıp vurgular listesine (✨ ile ayrı) ekler.
    func generateInsights() {
        guard !isGeneratingInsights else { return }
        let start = max(0, pageIndex - 4)
        let t = text(ofPages: start..<(pageIndex + 1))
        guard t.trimmingCharacters(in: .whitespacesAndNewlines).count > 300 else {
            toast = "Bu sayfalarda içgörü için yeterli metin yok"
            return
        }
        isGeneratingInsights = true
        let config = settings.config(.smart)
        let page = pageIndex
        let scope = "Sayfa \(start + 1)–\(page + 1)"
        Task {
            defer { isGeneratingInsights = false }
            do {
                let out = try await LLMClient.complete(config, system: Prompts.assistantSystem,
                                                       user: Prompts.insights(text: String(t.prefix(80_000)), bookTitle: book.title, scope: scope),
                                                       maxTokens: 1200)
                let n = highlights.addInsights(Prompts.parseInsights(out), bookID: book.id, bookTitle: book.title,
                                               chapter: nil, page: page, location: scope)
                toast = n > 0 ? "\(n) içgörü eklendi" : "Yeni içgörü çıkmadı"
            } catch {
                ai.error = error.localizedDescription
                ai.title = "İçgörü"; ai.text = ""; ai.isPresented = true
            }
        }
    }

    func prepareHardWords(pages count: Int) {
        let end = min(pageIndex + count, pageCount)
        let t = text(ofPages: pageIndex..<end)
        guard !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            toast = "Bu sayfalarda seçilebilir metin yok"
            return
        }
        hardWords = HardWordsModel(title: "Zor kelimeler: sayfa \(pageIndex + 1)–\(end)", text: t, book: book,
                                   config: settings.config(.smart), scopeKey: "p\(pageIndex)-\(count)")
    }

    private func text(ofPages range: Range<Int>) -> String {
        range.compactMap { document.page(at: $0)?.string }
            .map { PDFText.paragraphs(in: $0).joined(separator: "\n\n") }
            .joined(separator: "\n\n")
    }

    // MARK: Vurgular (PDF vurgu notları)

    private func annotation(for h: Highlight) -> (PDFPage, PDFAnnotation)? {
        guard let pi = h.page, let page = document.page(at: pi), let rects = h.rects, !rects.isEmpty else { return nil }
        let cg = rects.filter { $0.count == 4 }.map { CGRect(x: $0[0], y: $0[1], width: $0[2], height: $0[3]) }
        guard let first = cg.first else { return nil }
        let union = cg.reduce(first) { $0.union($1) }
        let subtype: PDFAnnotationSubtype
        switch h.effectiveStyle {
        case .highlight: subtype = .highlight
        case .underline, .wavy: subtype = .underline  // PDFKit'te dalgalı çizgi yok
        }
        let ann = PDFAnnotation(bounds: union, forType: subtype, withProperties: nil)
        ann.color = h.effectiveStyle == .highlight ? h.color.uiColor.withAlphaComponent(0.42) : h.color.uiColor
        ann.quadrilateralPoints = cg.flatMap { r -> [NSValue] in
            [CGPoint(x: r.minX - union.minX, y: r.maxY - union.minY),
             CGPoint(x: r.maxX - union.minX, y: r.maxY - union.minY),
             CGPoint(x: r.minX - union.minX, y: r.minY - union.minY),
             CGPoint(x: r.maxX - union.minX, y: r.minY - union.minY)].map { NSValue(cgPoint: $0) }
        }
        ann.userName = h.id.uuidString
        return (page, ann)
    }

    private func applyAllAnnotations() {
        for h in highlights.forBook(book.id) {
            if let (page, ann) = annotation(for: h) { page.addAnnotation(ann) }
        }
    }

    private func removeAnnotation(_ h: Highlight) {
        guard let pi = h.page, let page = document.page(at: pi) else { return }
        for a in page.annotations where a.userName == h.id.uuidString { page.removeAnnotation(a) }
    }

    @discardableResult
    private func createHighlight(from selection: PDFSelection? = nil, style: HighlightStyle = .highlight, openNote: Bool) -> Highlight? {
        guard let sel = selection ?? pdfView.currentSelection else { return nil }
        let lines = sel.selectionsByLine()
        var first: Highlight?
        for page in sel.pages {
            let mine = lines.filter { $0.pages.first === page }
            let rects = mine.map { $0.bounds(for: page) }.filter { !$0.isEmpty }
            guard !rects.isEmpty else { continue }
            let text = mine.compactMap { $0.string }.joined(separator: " ")
                .split(whereSeparator: \.isWhitespace).joined(separator: " ")
            guard !text.isEmpty else { continue }
            let idx = document.index(for: page)
            var h = Highlight(bookID: book.id, bookTitle: book.title, text: text, color: settings.highlightColor)
            h.style = style == .highlight ? nil : style
            h.page = idx
            h.rects = rects.map { [Double($0.origin.x), Double($0.origin.y), Double($0.size.width), Double($0.size.height)] }
            h.location = "Sayfa \(idx + 1)"
            highlights.add(h)
            if let (p, ann) = annotation(for: h) { p.addAnnotation(ann) }
            if first == nil { first = h }
        }
        if selection == nil {
            pdfView.clearSelection()
            hasSelection = false
        }
        if openNote, let h = first { editing = EditingHighlight(highlight: h, focusNote: true) }
        return first
    }

    // MARK: Kitapta arama

    private func searchBook(_ q: String, limit: Int) async -> [SearchHit] {
        let sels = await finder.find(q, limit: limit)
        return sels.compactMap { sel -> SearchHit? in
            guard let page = sel.pages.first else { return nil }
            let idx = document.index(for: page)
            let matchText = sel.string ?? q
            var before = "", after = ""
            if let line = sel.copy() as? PDFSelection {
                line.extendForLineBoundaries()
                let full = SearchText.collapse(line.string ?? "")
                if let r = full.range(of: SearchText.collapse(matchText), options: [.caseInsensitive, .diacriticInsensitive]) {
                    before = String(full[..<r.lowerBound].suffix(50))
                    after = String(full[r.upperBound...].prefix(70))
                }
            }
            var h = SearchHit(location: "Sayfa \(idx + 1)", before: before, match: SearchText.collapse(matchText), after: after)
            h.page = idx
            h.selection = sel
            return h
        }
    }

    func openSearchHit(_ hit: SearchHit) {
        guard let page = hit.page, let sel = hit.selection else { return }
        jump(to: page)
        sel.color = UIColor.systemOrange.withAlphaComponent(0.55)
        pdfView.highlightedSelections = [sel]
        if let p = document.page(at: page) {
            let b = sel.bounds(for: p)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in self?.pdfView.go(to: b.insetBy(dx: -40, dy: -120), on: p) }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            if self?.pdfView.highlightedSelections?.first === sel { self?.pdfView.highlightedSelections = nil }
        }
    }

    // MARK: Kitaba hazır soru

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

    func askBook(_ prompt: String) {
        chat.pendingPrompt = prompt
        openChat = true
    }

    // MARK: Apple Pencil

    func togglePenMode() { setPenMode(!penMode) }

    func setPenMode(_ on: Bool) {
        penMode = on
        penRecognizer.isEnabled = on
        PenTouch.restrictScroll(in: pdfView, penOnly: on)
        if !on { dismissPenResult() }
    }

    func cyclePenStyle() {
        guard penMode else { return }
        let all = HighlightStyle.allCases
        let i = all.firstIndex(of: settings.penStyle) ?? 0
        settings.penStyle = all[(i + 1) % all.count]
        toast = settings.penStyle.title
    }

    private static func isWordChar(_ c: unichar) -> Bool {
        guard let u = Unicode.Scalar(c) else { return true }
        return CharacterSet.alphanumerics.contains(u) || c == 0x27 || c == 0x2019
    }

    private func penStroke(_ pts: [CGPoint]) {
        let style = settings.penStyle
        guard let start = pts.first, let page = pdfView.page(for: start, nearest: true),
              let pageText = page.string, !pageText.isEmpty else { return }
        let ns = pageText as NSString
        // PDF sayfa koordinatında y yukarı artar; altı çizgi metnin altında olduğundan biraz yukarı bakılır.
        let offsets: [CGFloat] = style == .highlight ? [0, -3, 3] : [6, 12, 3, -3]
        let step = max(1, pts.count / 80)
        var lo = Int.max, hi = -1
        for p in stride(from: 0, to: pts.count, by: step).map({ pts[$0] }) + [pts[pts.count - 1]] {
            let pp = pdfView.convert(p, to: page)
            for off in offsets {
                let i = page.characterIndex(at: CGPoint(x: pp.x, y: pp.y + off))
                if i != NSNotFound, i >= 0, i < ns.length {
                    lo = min(lo, i); hi = max(hi, i)
                    break
                }
            }
        }
        guard hi >= 0 else { return }
        while lo > 0, Self.isWordChar(ns.character(at: lo - 1)) { lo -= 1 }
        while hi < ns.length - 1, Self.isWordChar(ns.character(at: hi + 1)) { hi += 1 }
        guard let sel = page.selection(for: NSRange(location: lo, length: hi - lo + 1)),
              let h = createHighlight(from: sel, style: style, openNote: false) else { return }
        let paragraph = PDFText.paragraph(in: pageText, around: lo) ?? h.text
        tracker.touch()
        showPenResult(PenResult(highlight: h, paragraph: String(paragraph.prefix(3500))))
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
        if action == .note {
            editing = EditingHighlight(highlight: r.highlight, focusNote: true)
        } else {
            perform(action, context: (r.highlight.text, r.paragraph))
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
        removeAnnotation(h)
        if let (page, ann) = annotation(for: h) { page.addAnnotation(ann) }
    }

    func deleteHighlight(_ h: Highlight) {
        highlights.delete([h.id])
        removeAnnotation(h)
    }

    func goTo(_ h: Highlight) {
        if let p = h.page { jump(to: p) }
    }

    /// Yer imi / vurgu gibi atlamalar: önceki sayfa dönüş noktası olarak saklanır.
    func jump(to page: Int) {
        if returnPage == nil, page != pageIndex { returnPage = pageIndex }
        go(to: page)
    }

    func keepJump() { returnPage = nil }

    // MARK: Başka cihazdaki konum

    var remoteLabel: String { remoteOffer.map { "Sayfa \($0.position + 1)" } ?? "" }

    func acceptRemote() {
        guard let o = remoteOffer else { return }
        library.clearRemoteOffer(book.id)
        jump(to: o.position)   // önceki sayfa dönüş noktası olarak saklanır
    }

    func keepLocalPosition() { library.keepLocalProgress(book.id) }

    func returnFromJump() {
        guard let p = returnPage else { return }
        returnPage = nil
        go(to: p)
    }

    /// Görünüm yerleştikten sonra sayfaya gider (kitap yeni açıldığında).
    func openAfterLayout(page: Int) {
        // Kaldığın sayfa dönüş noktası olarak saklanır ("Geri dön" ile dönebilirsin)
        if page != pageIndex, !book.isUnread, returnPage == nil { returnPage = pageIndex }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.go(to: page) }
    }

    /// Baştan şu anki sayfaya kadar (dahil) olan metin.
    func buildChatContext(budget: Int) async -> BookContext {
        var sections: [(title: String, body: String)] = []
        for i in 0...max(pageIndex, 0) {
            if let t = document.page(at: i)?.string, !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                sections.append(("Sayfa \(i + 1)", PDFText.paragraphs(in: t).joined(separator: "\n\n")))
            }
        }
        return BookContext.assemble(sections: sections, budget: budget, label: "Sayfa \(pageIndex + 1)/\(pageCount)")
    }

    func translateCurrentPage() {
        let t = text(ofPages: pageIndex..<(pageIndex + 1))
        guard !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            ai.error = "Bu sayfada seçilebilir metin yok (taranmış PDF olabilir)."
            ai.title = "Sayfa çevirisi"; ai.text = ""; ai.isPresented = true
            return
        }
        ai.start(AIRequest(
            title: "Sayfa \(pageIndex + 1) çevirisi",
            system: Prompts.translatorSystem,
            user: Prompts.translatePDFPage(t),
            config: settings.config(.fast),
            maxTokens: 4096,
            cacheKey: ResponseCache.key("pdfpage", settings.config(.fast).cacheID, t)))
    }

    func summarize(pages count: Int) {
        let end = min(pageIndex + count, pageCount)
        let t = String(text(ofPages: pageIndex..<end).prefix(90_000))
        guard !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            ai.error = "Bu sayfalarda seçilebilir metin yok (taranmış PDF olabilir)."
            ai.title = "Özet"; ai.text = ""; ai.isPresented = true
            return
        }
        let scope = count == 1 ? "Sayfa \(pageIndex + 1)" : "Sayfa \(pageIndex + 1)–\(end)"
        ai.start(AIRequest(
            title: "Özet: \(scope)",
            system: Prompts.summarySystem,
            user: Prompts.summarize(text: t, scope: scope, bookTitle: book.title),
            config: settings.config(.smart),
            cacheKey: ResponseCache.key("pdfsum", settings.config(.smart).cacheID, book.id.uuidString, String(pageIndex), String(count))))
    }
}

// MARK: - Görünümler

struct PDFLoader: View {
    let book: Book
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: ReaderSettings
    @EnvironmentObject var highlights: HighlightStore
    @EnvironmentObject var tracker: ReadingTracker
    @EnvironmentObject var bookmarks: BookmarkStore
    @State private var model: PDFReaderModel?

    var body: some View {
        Group {
            if let model {
                PDFReaderView(model: model)
            } else if PDFDocument(url: Paths.file(for: book)) == nil {
                ContentUnavailableView("PDF açılamadı", systemImage: "exclamationmark.triangle")
            } else {
                ProgressView()
            }
        }
        .task {
            guard model == nil, let doc = PDFDocument(url: Paths.file(for: book)) else { return }
            let m = PDFReaderModel(book: book, document: doc, settings: settings, library: library, highlights: highlights, bookmarks: bookmarks, tracker: tracker)
            if let h = highlights.pendingJump, h.bookID == book.id {
                highlights.pendingJump = nil
                if let p = h.page { m.openAfterLayout(page: p) }
            }
            model = m
        }
    }
}

struct PDFReaderView: View {
    @ObservedObject var model: PDFReaderModel
    @EnvironmentObject var settings: ReaderSettings
    @State private var showTheme = false
    @State private var showSearch = false
    @State private var showWrapUp = false
    @State private var showChat = false
    @State private var showHighlights = false
    @State private var notesTab = 0
    @EnvironmentObject var bookmarkStore: BookmarkStore
    @State private var showETA = false
    @State private var chrome = false
    @EnvironmentObject var tracker: ReadingTracker

    var body: some View {
        ZStack(alignment: .bottom) {
            settings.theme.color.ignoresSafeArea()
            PDFViewHost(pdfView: model.pdfView)
                .modifier(PDFThemeModifier(theme: settings.theme))
                .ignoresSafeArea(edges: readerContentIgnoredEdges)
            if chrome {
                bottomBar
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .toolbar(chrome ? .visible : .hidden, for: .navigationBar)
        .statusBarHidden(!chrome)
        .persistentSystemOverlays(chrome ? .automatic : .hidden)
        .animation(.easeInOut(duration: 0.2), value: chrome)
        .readerChrome()
        .keepScreenAwake(speech: model.speech)
        .background(SwipeBackGuard(disabled: settings.readingMode == .paged))
        .overlay(alignment: .bottom) {
            if model.hasSelection {
                SelectionBar(
                    xray: { model.perform(.xray) },
                    dictionary: { model.perform(.dictionary) },
                    explain: { model.perform(.explain) },
                    translate: { model.perform(.translateParagraph) },
                    highlight: { model.perform(.highlight) },
                    note: { model.perform(.note) },
                    grammar: { model.perform(.grammar) })
                .padding(.bottom, 70)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.hasSelection)
        .overlay(alignment: .top) {
            if model.penMode {
                PenPalette(close: { model.setPenMode(false) })
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .overlay(alignment: .bottom) {
            if model.penResult != nil, !model.hasSelection {
                PenResultBar(
                    dictionary: model.penResult.flatMap { QuickDictionary.term(from: $0.highlight.text) } != nil
                        ? { model.penAction(.dictionary) } : nil,
                    translate: { model.penAction(.translateParagraph) },
                    explain: { model.penAction(.explain) },
                    note: { model.penAction(.note) },
                    undo: { model.undoPen() })
                .padding(.bottom, chrome ? 70 : 28)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .overlay(alignment: .bottom) {
            if let rp = model.returnPage {
                DraggableJumpBar(from: "Sayfa \(rp + 1)", keep: { model.keepJump() }, back: { model.returnFromJump() },
                                 bottomPadding: chrome ? 124 : 82)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.returnPage)
        .overlay(alignment: .top) {
            if model.remoteOffer != nil {
                RemoteProgressBar(label: model.remoteLabel,
                                  accept: { model.acceptRemote() },
                                  keep: { model.keepLocalPosition() })
                    .padding(.top, (chrome ? 56 : 8) + (model.penMode ? 54 : 0))
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .overlay(alignment: .top) {
            if let d = model.resumeDays {
                ResumeBar(days: d, ask: { model.askResume() }, dismiss: { model.dismissResume() })
                    .padding(.top, (chrome ? 56 : 8) + (model.penMode ? 54 : 0) + (model.remoteOffer != nil ? 62 : 0))
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.resumeDays)
        .animation(.easeOut(duration: 0.2), value: model.remoteOffer)
        .animation(.easeOut(duration: 0.2), value: model.penMode)
        .animation(.easeOut(duration: 0.2), value: model.penResult)
        .overlay(alignment: .bottom) {
            if model.speech.isActive {
                SpeechBar(speech: model.speech)
                    .padding(.bottom, chrome ? 62 : 16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.speech.isActive)
        .onDisappear { model.speech.stop() }
        .onAppear {
            model.start()
            model.onCenterTap = { chrome.toggle() }
        }
        .onDisappear { model.stop() }
        .navigationTitle(model.book.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            #if !targetEnvironment(macCatalyst)
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.togglePenMode() } label: {
                    Image(systemName: model.penMode ? "pencil.tip.crop.circle.fill" : "pencil.tip.crop.circle")
                }
                .accessibilityLabel("Kalem modu")
            }
            #endif
            ToolbarItem(placement: .topBarTrailing) {
                Button { showSearch = true } label: { Image(systemName: "magnifyingglass") }
                    .keyboardShortcut("f", modifiers: .command)
                    .accessibilityLabel("Kitapta ara")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.toggleBookmark() } label: { Image(systemName: model.isBookmarkedHere ? "bookmark.fill" : "bookmark") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showHighlights = true } label: { Image(systemName: "highlighter") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { if model.speech.isActive { model.speech.stop() } else { model.speech.start() } } label: {
                    Image(systemName: model.speech.isActive ? "speaker.wave.2.fill" : "speaker.wave.2")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showChat = true } label: { Image(systemName: "bubble.left.and.text.bubble.right") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.translateCurrentPage() } label: { Image(systemName: "character.bubble") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Şimdiye kadar ne oldu?") { model.askBook(Prompts.recap) }
                    Button("Karakter ve kavram rehberi") { model.askBook(Prompts.cast) }
                    Button("Karakter ve ilişki haritası") { model.showRelationMap() }
                    Divider()
                    Button("Bu sayfayı özetle") { model.summarize(pages: 1) }
                    Button("Bu sayfadan itibaren 5 sayfa") { model.summarize(pages: 5) }
                    Button("Bu sayfadan itibaren 15 sayfa") { model.summarize(pages: 15) }
                    Divider()
                    Button("Pekiştir: son 10 sayfa (özet + quiz)") { model.prepareStudy(lastPages: 10) }
                    Button("Pekiştir: son 25 sayfa") { model.prepareStudy(lastPages: 25) }
                    Button("Zor kelimeler (5 sayfa)") { model.prepareHardWords(pages: 5) }
                } label: { Image(systemName: "sparkles") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showTheme = true } label: { Image(systemName: "circle.lefthalf.filled") }
                    .popover(isPresented: $showTheme) {
                        ThemePanel(showsTextControls: false).presentationCompactAdaptation(.popover)
                    }
            }
        }
        .toolbarBackground(settings.theme.color, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(settings.theme.isDark ? .dark : .light, for: .navigationBar)
        .preferredColorScheme(settings.theme.isDark ? .dark : .light)
        .aiSheet(model.ai)
        .sheet(isPresented: $showWrapUp) { BookWrapUpView(book: model.book).presentationDetents([.large]) }
        .overlay(alignment: .bottom) {
            if model.pageIndex >= model.pageCount - 1 && model.pageCount > 1 && !model.hasSelection {
                Button { showWrapUp = true } label: {
                    Label("Kitabı bitirdin: karneni gör", systemImage: "party.popper.fill")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 18).padding(.vertical, 11)
                        .background(.regularMaterial, in: Capsule())
                        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
                }
                .buttonStyle(.plain)
                .padding(.bottom, chrome ? 124 : 82)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .toast($model.toast)
        .sheet(item: $model.hardWords) { HardWordsView(model: $0).presentationDetents([.large]) }
        .relationMapSheet(model.relationMap) { model.showRelationMap() }
        .sheet(item: $model.dictionaryTerm) { DictionarySheet(term: $0.term).ignoresSafeArea() }
        .sheet(isPresented: $showSearch) {
            BookSearchView(model: model.search, close: { showSearch = false })
                .presentationDetents([.large])
        }
        .onChange(of: model.openChat) { _, v in if v { showChat = true; model.openChat = false } }
        .onAppear { model.search.open = { [weak model] hit in showSearch = false; model?.openSearchHit(hit) } }
        .sheet(item: $model.study) { StudyView(model: $0).presentationDetents([.large]) }
        .sheet(isPresented: $showChat) { ChatView(chat: model.chat).presentationDetents([.large]) }
        .sheet(isPresented: $showHighlights) {
            NavigationStack {
                VStack(spacing: 0) {
                    Picker("", selection: $notesTab) {
                        Text("Vurgular").tag(0)
                        Text("Yer imleri").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .padding(12)
                    if notesTab == 0 {
                        BookHighlightsList(
                            bookID: model.book.id,
                            onSelect: { h in model.goTo(h); showHighlights = false },
                            onDelete: { model.deleteHighlight($0) },
                            onInsights: { model.generateInsights() },
                            isGeneratingInsights: model.isGeneratingInsights)
                    } else {
                        BookmarkList(
                            bookID: model.book.id,
                            onSelect: { b in model.goTo(b); showHighlights = false },
                            onDelete: { bookmarkStore.delete([$0.id]) })
                    }
                }
                .navigationTitle(notesTab == 0 ? "Vurgular ve notlar" : "Yer imleri")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Kapat") { showHighlights = false } } }
            }
        }
        .sheet(item: $model.editing) { e in
            HighlightEditor(draft: e.highlight, focusNote: e.focusNote,
                            onSave: { model.saveHighlight($0) },
                            onDelete: { model.deleteHighlight($0) })
                .presentationDetents([.large])
        }
    }

    private var bottomBar: some View {
        VStack(spacing: 0) {
            Button { showETA.toggle(); tracker.touch() } label: {
                Text(showETA
                     ? "Kitap bitimine ~\(Duration.text(tracker.secondsToRead(words: model.bookWordsLeft)))\(tracker.hasSpeedData ? "" : " (tahmini)")"
                     : "Sayfa \(model.pageIndex + 1) / \(model.pageCount) · %\(Int((model.book.progress * 100).rounded()))")
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(settings.theme.textColor.opacity(0.6))
                    .frame(maxWidth: .infinity, minHeight: 24)
            }
            .buttonStyle(.plain)
            sliderRow
        }
        .padding(.top, 4)
        .background(settings.theme.color)
    }

    private var sliderRow: some View {
        HStack(spacing: 12) {
            Text("\(model.pageIndex + 1)")
                .font(.footnote.monospacedDigit())
                .frame(minWidth: 36)
            Slider(
                value: Binding(
                    get: { Double(model.pageIndex) },
                    set: { model.go(to: Int($0.rounded())) }),
                in: 0...Double(max(model.pageCount - 1, 1)),
                step: 1)
            Text("\(model.pageCount)")
                .font(.footnote.monospacedDigit())
                .frame(minWidth: 36)
        }
        .foregroundStyle(settings.theme.textColor.opacity(0.7))
        .padding(.horizontal, 16)
        .frame(height: 44)
    }
}

/// PDF'te "yeniden akış" olmadığı için koyu/sepya temayı renk filtresiyle taklit eder.
struct PDFThemeModifier: ViewModifier {
    let theme: ReaderTheme

    func body(content: Content) -> some View {
        switch theme {
        case .light:
            content
        case .sepia:
            content.overlay(Color(hex: "#F4ECD8").blendMode(.multiply).allowsHitTesting(false))
        case .dark:
            content
                .colorInvert()
                .hueRotation(.degrees(180))
                .overlay(Color(white: 0.11).blendMode(.lighten).allowsHitTesting(false))
        case .black:
            content.colorInvert().hueRotation(.degrees(180))
        case .eink:
            content
                .grayscale(1)
                .contrast(1.15)
                .overlay(Color(hex: "#E8E7E2").blendMode(.multiply).allowsHitTesting(false))
        }
    }
}
