import Foundation

struct Book: Identifiable, Codable, Hashable {
    enum Kind: String, Codable { case epub, pdf }

    var id: UUID = UUID()
    var title: String
    var author: String = ""
    var kind: Kind
    var coverFile: String?
    var addedAt: Date = Date()
    var lastOpened: Date?
    /// EPUB: bölüm indeksi, PDF: sayfa indeksi
    var position: Int = 0
    /// EPUB bölümü içindeki kaydırma oranı (0...1)
    var scrollFraction: Double = 0
    /// Toplam ilerleme (0...1)
    var progress: Double = 0
    /// EPUB: her bölümdeki kelime sayısı (ilerleme ve süre tahmini için)
    var chapterWords: [Int]?
    /// Sayfa bazlı ilerleme: PDF'te gerçek sayfa, EPUB'da ~250 kelimelik sanal sayfa
    var pagesTotal: Int?
    var pageNow: Int?
    /// Konum en son ne zaman değişti (cihazlar arası senkron için)
    var progressUpdatedAt: Date?
    /// Kimlik içerik özetine çevrildi mi (tek seferlik geçiş)
    var idChecked: Bool?
    /// Kullanıcının verdiği etiketler (raflar)
    var tags: [String]?
    /// Etiketler en son ne zaman değişti (senkron için)
    var tagsUpdatedAt: Date?
    /// Kitap bulutta var ama bu cihaza henüz indirilmedi
    var remoteOnly: Bool?
}

extension Book {
    static let wordsPerPage = 250.0

    var totalWords: Int { chapterWords?.reduce(0, +) ?? 0 }

    func wordsBefore(chapter: Int) -> Int {
        guard let w = chapterWords else { return 0 }
        return w.prefix(max(0, min(chapter, w.count))).reduce(0, +)
    }

    /// EPUB'da okunan kelime konumu.
    var wordPosition: Double {
        guard let w = chapterWords, !w.isEmpty else { return 0 }
        let c = max(0, min(position, w.count - 1))
        return Double(wordsBefore(chapter: c)) + scrollFraction * Double(w[c])
    }

    /// Konumdan ilerleme ve sayfa bilgisini yeniden hesaplar.
    mutating func recomputeProgress() {
        switch kind {
        case .epub:
            let total = totalWords
            guard total > 0 else { return }
            let pos = wordPosition
            progress = min(1, max(0, pos / Double(total)))
            let pages = max(1, Int((Double(total) / Self.wordsPerPage).rounded()))
            pagesTotal = pages
            pageNow = min(pages, max(1, Int(pos / Self.wordsPerPage) + 1))
        case .pdf:
            guard let pages = pagesTotal, pages > 0 else { return }
            let now = min(pages, max(1, position + 1))
            pageNow = now
            progress = Double(now) / Double(pages)
        }
    }

    /// En son ne zaman okundu/açıldı (cihazlar arası konum damgası dahil).
    var lastReadAt: Date? { [lastOpened, progressUpdatedAt].compactMap { $0 }.max() }

    /// Hiç ilerletilmemiş kitap (yalnızca açılmış olabilir).
    var isUnread: Bool { progressUpdatedAt == nil && position == 0 && scrollFraction < 0.001 }

    var isRemoteOnly: Bool { remoteOnly == true }

    /// Senkron için kitaplık kaydı.
    var syncEntry: LibraryEntry {
        LibraryEntry(id: id, title: title, author: author, kind: kind.rawValue, addedAt: addedAt,
                     updatedAt: tagsUpdatedAt ?? addedAt, tags: tags, chapterWords: chapterWords, pagesTotal: pagesTotal)
    }

    var isFinished: Bool { progress >= 0.995 }

    /// Kitaplık kartı için durum metni: "Okunmadı", "Bitti" veya "Sayfa 12 / 340 · %4".
    var statusText: String? {
        if isUnread { return "Okunmadı" }
        if isFinished { return "Bitti" }
        guard let p = pageText else { return nil }
        return "\(p) · %\(Int((progress * 100).rounded()))"
    }

    /// "Sayfa 12 / 340"
    var pageText: String? {
        guard let n = pageNow, let t = pagesTotal else { return nil }
        return "Sayfa \(n) / \(t)"
    }
}

enum Paths {
    static let base: URL = {
        let u = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Reader", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()

    static func file(for book: Book) -> URL { base.appendingPathComponent("\(book.id).\(book.kind.rawValue)") }
    static func epubRoot(for book: Book) -> URL { base.appendingPathComponent("\(book.id)-x", isDirectory: true) }
    static func driveBookName(_ id: UUID, _ kind: Book.Kind) -> String { "book-\(id.uuidString).\(kind.rawValue)" }
    static func driveCoverName(_ id: UUID) -> String { "cover-\(id.uuidString).jpg" }
    static func cover(for book: Book) -> URL? { book.coverFile.map { base.appendingPathComponent($0) } }
}
