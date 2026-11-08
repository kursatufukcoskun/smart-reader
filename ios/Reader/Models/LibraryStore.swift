import Foundation
import PDFKit
import UIKit

@MainActor
final class LibraryStore: ObservableObject {
    @Published private(set) var books: [Book] = []
    /// Her yerden silinen kitaplar (senkronda silmenin yayılması için)
    private(set) var deleted: [UUID: Date] = [:]
    private var saveTask: Task<Void, Never>?

    private var deletedURL: URL { Paths.base.appendingPathComponent("library-deleted.json") }
    private var indexURL: URL { Paths.base.appendingPathComponent("books.json") }

    init() {
        load()
        backfillMetrics()
    }

    /// Eski kitaplarda eksik olan kelime/sayfa bilgisini arka planda tamamlar.
    private func backfillMetrics() {
        for b in books where !b.isRemoteOnly && (b.kind == .epub && b.chapterWords == nil) || (b.kind == .pdf && b.pagesTotal == nil) {
            let book = b
            Task.detached(priority: .utility) { [weak self] in
                var nb = book
                switch book.kind {
                case .epub:
                    guard let pkg = try? EPUBPackage.load(from: Paths.epubRoot(for: book)) else { return }
                    nb.chapterWords = pkg.spine.map { HTMLText.extract(from: $0.url).split(whereSeparator: \.isWhitespace).count }
                case .pdf:
                    guard let doc = PDFDocument(url: Paths.file(for: book)) else { return }
                    nb.pagesTotal = doc.pageCount
                }
                let result = nb
                await self?.mergeMetrics(result)
            }
        }
    }

    func mergeMetrics(_ m: Book) {
        guard var cur = book(m.id) else { return }
        cur.chapterWords = m.chapterWords
        cur.pagesTotal = m.pagesTotal ?? cur.pagesTotal
        cur.recomputeProgress()
        update(cur)
    }

    enum Sort: String, CaseIterable, Identifiable {
        case lastRead, added, title, author, progress
        var id: String { rawValue }
        var title: String {
            switch self {
            case .lastRead: return "Son okunan"
            case .added: return "Eklenme tarihi"
            case .title: return "Başlık"
            case .author: return "Yazar"
            case .progress: return "İlerleme"
            }
        }
    }

    /// Kitaplık sıralaması (cihaza özel).
    @Published var sort: Sort = Sort(rawValue: UserDefaults.standard.string(forKey: "librarySort") ?? "") ?? .lastRead {
        didSet { UserDefaults.standard.set(sort.rawValue, forKey: "librarySort") }
    }

    /// Başlık/yazar düzenlemesi: etiketlerle aynı damgayı kullanır, böylece diğer cihazlara senkronlanır.
    func updateInfo(_ id: UUID, title: String, author: String) {
        guard var b = book(id) else { return }
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        b.title = t
        b.author = author.trimmingCharacters(in: .whitespacesAndNewlines)
        b.tagsUpdatedAt = Date()
        update(b)
    }

    /// Seçili sıralamaya göre kitaplar (varsayılan: en son okunan en üstte; hiç açılmamışlar sonda).
    var sortedBooks: [Book] {
        let cmp: (String, String) -> Bool = { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
        switch sort {
        case .lastRead: break
        case .added: return books.sorted { $0.addedAt > $1.addedAt }
        case .title: return books.sorted { cmp($0.title, $1.title) }
        case .author: return books.sorted { $0.author.isEmpty != $1.author.isEmpty ? !$0.author.isEmpty : cmp($0.author, $1.author) }
        case .progress: return books.sorted { $0.progress > $1.progress }
        }
        return books.sorted { a, b in
            switch (a.lastReadAt, b.lastReadAt) {
            case let (x?, y?): return x > y
            case (.some, nil): return true
            case (nil, .some): return false
            default: return a.addedAt > b.addedAt
            }
        }
    }

    func book(_ id: UUID) -> Book? { books.first { $0.id == id } }

    func update(_ book: Book) {
        guard let i = books.firstIndex(where: { $0.id == book.id }) else { return }
        var b = book
        let old = books[i]
        // Yalnızca konum gerçekten değiştiyse "değişti" damgası vur (senkron bu damgaya bakar).
        b.progressUpdatedAt = (b.position != old.position || b.scrollFraction != old.scrollFraction) ? Date() : old.progressUpdatedAt
        books[i] = b
        scheduleSave()
    }

    // MARK: - Başka cihazdaki konum teklifi

    struct RemoteProgressOffer: Equatable {
        var position: Int
        var scrollFraction: Double
        var progress: Double
        var updatedAt: Date
    }

    /// Başka cihazda daha yeni bir konum var ve yerelden anlamlı biçimde farklı: kullanıcıya sorulur.
    @Published private(set) var remoteOffers: [UUID: RemoteProgressOffer] = [:]

    /// Uzak konum yerelden anlamlı farklıysa (bölüm/sayfa değişmiş ya da %5'ten fazla kaymış) true.
    func isSignificant(_ b: Book, position: Int, scrollFraction: Double) -> Bool {
        switch b.kind {
        case .pdf: return position != b.position
        case .epub: return position != b.position || abs(scrollFraction - b.scrollFraction) > 0.05
        }
    }

    func offerRemoteProgress(_ id: UUID, _ offer: RemoteProgressOffer) {
        if remoteOffers[id] != offer { remoteOffers[id] = offer }
    }

    func clearRemoteOffer(_ id: UUID) {
        if remoteOffers[id] != nil { remoteOffers[id] = nil }
    }

    /// "Burada kal": yerel konum yeni damga alır, bir sonraki senkronda uzaktakinin yerine geçer.
    func keepLocalProgress(_ id: UUID) {
        remoteOffers[id] = nil
        guard let i = books.firstIndex(where: { $0.id == id }) else { return }
        books[i].progressUpdatedAt = Date()
        scheduleSave()
    }

    /// Başka cihazdan gelen konumu uygular (damgayı uzaktakinden alır).
    func applyRemoteProgress(_ id: UUID, position: Int, scrollFraction: Double, updatedAt: Date) {
        guard let i = books.firstIndex(where: { $0.id == id }) else { return }
        var b = books[i]
        switch b.kind {
        case .epub: if let n = b.chapterWords?.count, n > 0 { b.position = min(max(position, 0), n - 1) } else { b.position = max(position, 0) }
        case .pdf: if let n = b.pagesTotal, n > 0 { b.position = min(max(position, 0), n - 1) } else { b.position = max(position, 0) }
        }
        b.scrollFraction = min(max(scrollFraction, 0), 1)
        b.progressUpdatedAt = updatedAt
        b.recomputeProgress()
        books[i] = b
        scheduleSave()
    }

    var allTags: [String] {
        Array(Set(books.flatMap { $0.tags ?? [] })).sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    func setTags(_ id: UUID, _ tags: [String]) {
        guard var b = book(id) else { return }
        b.tags = tags.isEmpty ? nil : tags
        b.tagsUpdatedAt = Date()
        update(b)
    }

    enum CoverError: LocalizedError {
        case notFound
        var errorDescription: String? { "Open Library'de bu kitap için kapak bulunamadı." }
    }

    /// Open Library'den başlık/yazara göre kapak bulur ve kaydeder.
    func fetchCover(_ id: UUID) async throws {
        guard let b = book(id) else { return }
        var c = URLComponents(string: "https://openlibrary.org/search.json")!
        c.queryItems = [.init(name: "title", value: b.title), .init(name: "limit", value: "5"), .init(name: "fields", value: "cover_i,title")]
        if !b.author.isEmpty { c.queryItems?.append(.init(name: "author", value: b.author)) }
        let (data, _) = try await URLSession.shared.data(from: c.url!)
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let docs = obj["docs"] as? [[String: Any]],
              let ci = docs.compactMap({ $0["cover_i"] as? Int }).first else { throw CoverError.notFound }
        let (img, resp) = try await URLSession.shared.data(from: URL(string: "https://covers.openlibrary.org/b/id/\(ci)-L.jpg?default=false")!)
        guard (resp as? HTTPURLResponse)?.statusCode == 200, let ui = UIImage(data: img),
              let jpg = ui.scaled(maxDimension: 700).jpegData(compressionQuality: 0.8) else { throw CoverError.notFound }
        let name = "\(id)-cover.jpg"
        try jpg.write(to: Paths.base.appendingPathComponent(name))
        guard var nb = book(id) else { return }
        nb.coverFile = name
        update(nb)
        objectWillChange.send()
    }

    func touch(_ id: UUID) {
        guard var b = book(id) else { return }
        b.lastOpened = Date()
        update(b)
    }

    func delete(_ book: Book) {
        removeFiles(book)
        books.removeAll { $0.id == book.id }
        save()
    }

    /// Kitabı bulutla birlikte, tüm cihazlardan siler (silme kaydı senkronla yayılır).
    func deleteEverywhere(_ book: Book) {
        deleted[book.id] = Date()
        delete(book)
    }

    private func removeFiles(_ book: Book) {
        let fm = FileManager.default
        try? fm.removeItem(at: Paths.file(for: book))
        try? fm.removeItem(at: Paths.epubRoot(for: book))
        if let c = Paths.cover(for: book) { try? fm.removeItem(at: c) }
    }

    /// Başka cihazlardaki kitaplık kayıtlarını uygular: yeni kitaplar "bulutta" olarak eklenir,
    /// her yerden silinenler kaldırılır, etiketler birleşir.
    func applyRemoteLibrary(_ entries: [LibraryEntry], deleted: [UUID: Date]) {
        self.deleted = deleted
        let keep = Set(entries.map(\.id))
        for b in books where !keep.contains(b.id) { removeFiles(b) }
        books.removeAll { !keep.contains($0.id) }
        for e in entries {
            if let i = books.firstIndex(where: { $0.id == e.id }) {
                if e.updatedAt > (books[i].tagsUpdatedAt ?? books[i].addedAt) {
                    if !e.title.isEmpty { books[i].title = e.title }
                    books[i].author = e.author
                    books[i].tags = (e.tags ?? []).isEmpty ? nil : e.tags
                    books[i].tagsUpdatedAt = e.updatedAt
                }
            } else {
                var b = Book(title: e.title, kind: Book.Kind(rawValue: e.kind) ?? .epub)
                b.id = e.id
                b.author = e.author
                b.addedAt = e.addedAt
                b.chapterWords = e.chapterWords
                b.pagesTotal = e.pagesTotal
                b.tags = (e.tags ?? []).isEmpty ? nil : e.tags
                b.tagsUpdatedAt = e.updatedAt > e.addedAt ? e.updatedAt : nil
                b.remoteOnly = true
                b.idChecked = true
                b.recomputeProgress()
                books.append(b)
            }
        }
        save()
    }

    func setCover(_ id: UUID, file: String) {
        guard var b = book(id) else { return }
        b.coverFile = file
        update(b)
    }

    /// Buluttan inen dosyayı kuran ve kitabı kullanıma açan adım.
    func installDownloaded(_ id: UUID, from tmp: URL) async throws {
        guard let b = book(id) else { return }
        let words: [Int]? = try await Task.detached(priority: .userInitiated) { () -> [Int]? in
            let fm = FileManager.default
            switch b.kind {
            case .epub:
                let root = Paths.epubRoot(for: b)
                try? fm.removeItem(at: root)
                do {
                    try ZipArchive(url: tmp).extractAll(to: root)
                    let pkg = try EPUBPackage.load(from: root)
                    try? fm.removeItem(at: tmp)
                    return b.chapterWords == nil ? pkg.spine.map { HTMLText.extract(from: $0.url).split(whereSeparator: \.isWhitespace).count } : nil
                } catch {
                    try? fm.removeItem(at: root)
                    throw error
                }
            case .pdf:
                let dest = Paths.file(for: b)
                try? fm.removeItem(at: dest)
                try fm.moveItem(at: tmp, to: dest)
                return nil
            }
        }.value
        guard var nb = book(id) else { return }
        if let words { nb.chapterWords = words }
        nb.remoteOnly = nil
        nb.recomputeProgress()
        update(nb)
    }

    func importBook(from url: URL) async throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var book = try await Task.detached(priority: .userInitiated) {
            try BookImporter.importBook(from: url)
        }.value
        deleted[book.id] = nil
        // Aynı kitap bulutta "indirilmemiş" olarak duruyorsa, konum ve etiketlerini koruyarak onun yerine geç.
        if let old = books.first(where: { $0.id == book.id && $0.isRemoteOnly }) {
            book.addedAt = old.addedAt; book.tags = old.tags; book.tagsUpdatedAt = old.tagsUpdatedAt
            book.position = old.position; book.scrollFraction = old.scrollFraction
            book.progressUpdatedAt = old.progressUpdatedAt; book.lastOpened = old.lastOpened
            book.recomputeProgress()
            books.removeAll { $0.id == old.id }
        }
        books.append(book)
        save()
    }

    // MARK: persistence

    private func load() {
        guard let data = try? Data(contentsOf: indexURL),
              let decoded = try? JSONDecoder().decode([Book].self, from: data) else { return }
        books = decoded
        if let d = try? Data(contentsOf: deletedURL),
           let m = try? JSONDecoder().decode([String: Date].self, from: d) {
            deleted = Dictionary(uniqueKeysWithValues: m.compactMap { k, v in UUID(uuidString: k).map { ($0, v) } })
        }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 800_000_000)
            if !Task.isCancelled { self?.save() }
        }
    }

    func save() {
        if let data = try? JSONEncoder().encode(books) {
            try? data.write(to: indexURL, options: .atomic)
        }
        let m = Dictionary(uniqueKeysWithValues: deleted.map { ($0.key.uuidString, $0.value) })
        if let data = try? JSONEncoder().encode(m) { try? data.write(to: deletedURL, options: .atomic) }
    }
}

enum BookImporter {
    enum ImportError: LocalizedError {
        case unsupported, unreadablePDF, duplicate
        var errorDescription: String? {
            switch self {
            case .duplicate: return "Bu kitap zaten kitaplıkta."
            case .unsupported: return "Yalnızca EPUB ve PDF desteklenir."
            case .unreadablePDF: return "PDF açılamadı (şifreli olabilir)."
            }
        }
    }

    static func importBook(from url: URL) throws -> Book {
        let fm = FileManager.default
        let ext = url.pathExtension.lowercased()
        guard let kind = Book.Kind(rawValue: ext) else { throw ImportError.unsupported }

        var book = Book(title: url.deletingPathExtension().lastPathComponent, kind: kind)
        let dest = Paths.file(for: book)
        try fm.copyItem(at: url, to: dest)
        var contentID: UUID?

        switch kind {
        case .epub:
            let root = Paths.epubRoot(for: book)
            do {
                try ZipArchive(url: dest).extractAll(to: root)
                let pkg = try EPUBPackage.load(from: root)
                if !pkg.title.isEmpty { book.title = pkg.title }
                book.author = pkg.author
                contentID = BookIdentity.epubID(opf: pkg.opfURL)
                book.chapterWords = pkg.spine.map { HTMLText.extract(from: $0.url).split(whereSeparator: \.isWhitespace).count }
                book.recomputeProgress()
                if let cover = pkg.coverURL, let data = try? Data(contentsOf: cover),
                   let img = UIImage(data: data) {
                    let name = "\(book.id)-cover.jpg"
                    if let jpg = img.scaled(maxDimension: 700).jpegData(compressionQuality: 0.8) {
                        try? jpg.write(to: Paths.base.appendingPathComponent(name))
                        book.coverFile = name
                    }
                }
                try? fm.removeItem(at: dest)  // açılmış hali yeterli
            } catch {
                try? fm.removeItem(at: dest)
                try? fm.removeItem(at: root)
                throw error
            }
        case .pdf:
            guard let doc = PDFDocument(url: dest), !doc.isLocked else {
                try? fm.removeItem(at: dest)
                throw ImportError.unreadablePDF
            }
            if let t = doc.documentAttributes?[PDFDocumentAttribute.titleAttribute] as? String,
               !t.trimmingCharacters(in: .whitespaces).isEmpty { book.title = t }
            if let a = doc.documentAttributes?[PDFDocumentAttribute.authorAttribute] as? String { book.author = a }
            book.pagesTotal = doc.pageCount
            book.recomputeProgress()
            if let page = doc.page(at: 0) {
                let img = page.thumbnail(of: CGSize(width: 480, height: 680), for: .mediaBox)
                let name = "\(book.id)-cover.jpg"
                if let jpg = img.jpegData(compressionQuality: 0.8) {
                    try? jpg.write(to: Paths.base.appendingPathComponent(name))
                    book.coverFile = name
                }
            }
            contentID = BookIdentity.pdfID(file: dest)
        }
        if let nid = contentID {
            var probe = book
            probe.id = nid
            if fm.fileExists(atPath: Paths.file(for: probe).path) || fm.fileExists(atPath: Paths.epubRoot(for: probe).path) {
                try? fm.removeItem(at: Paths.file(for: book))
                try? fm.removeItem(at: Paths.epubRoot(for: book))
                if let c = Paths.cover(for: book) { try? fm.removeItem(at: c) }
                throw ImportError.duplicate
            }
            IDMigration.relocate(&book, to: nid)
        }
        book.idChecked = true
        return book
    }
}

extension UIImage {
    func scaled(maxDimension: CGFloat) -> UIImage {
        let longest = max(size.width, size.height)
        guard longest > maxDimension else { return self }
        let f = maxDimension / longest
        let newSize = CGSize(width: size.width * f, height: size.height * f)
        return UIGraphicsImageRenderer(size: newSize).image { _ in draw(in: CGRect(origin: .zero, size: newSize)) }
    }
}
