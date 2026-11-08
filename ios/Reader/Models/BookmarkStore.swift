import Foundation

struct Bookmark: Identifiable, Codable, Hashable {
    var id = UUID()
    var bookID: UUID
    var bookTitle: String
    var snippet: String
    /// EPUB: bölüm + bölüm içi oran
    var chapter: Int?
    var fraction: Double?
    /// PDF: sayfa indeksi
    var page: Int?
    var location: String
    var createdAt = Date()
    var updatedAt: Date?
    /// Sohbet yanıtından kaydedilen not (yer imi düğmesiyle açılıp kapanan klasik yer imi değildir).
    var isNote: Bool?
    var stamp: Date { updatedAt ?? createdAt }

    var sortKey: (Int, Double) {
        if let c = chapter { return (c, fraction ?? 0) }
        return (page ?? 0, 0)
    }
}

@MainActor
final class BookmarkStore: ObservableObject {
    @Published private(set) var items: [Bookmark] = []
    private(set) var deleted: [UUID: Date] = [:]
    private var saveTask: Task<Void, Never>?
    private var url: URL { Paths.base.appendingPathComponent("bookmarks.json") }
    private var deletedURL: URL { Paths.base.appendingPathComponent("bookmarks-deleted.json") }

    init() { load() }

    func forBook(_ id: UUID) -> [Bookmark] {
        items.filter { $0.bookID == id }.sorted { $0.sortKey < $1.sortKey }
    }

    func add(_ b: Bookmark) {
        var b = b
        b.updatedAt = Date()
        items.append(b)
        scheduleSave()
    }

    func delete(_ ids: [UUID]) {
        let now = Date()
        for id in ids { deleted[id] = now }
        items.removeAll { ids.contains($0.id) }
        scheduleSave()
    }

    func deleteBook(_ bookID: UUID) { delete(items.filter { $0.bookID == bookID }.map(\.id)) }

    func replaceFromSync(items: [Bookmark], deleted: [UUID: Date]) {
        self.items = items
        self.deleted = deleted
        save()
    }

    private func load() {
        if let d = try? Data(contentsOf: url), let v = try? JSONDecoder().decode([Bookmark].self, from: d) { items = v }
        if let d = try? Data(contentsOf: deletedURL), let m = try? JSONDecoder().decode([String: Date].self, from: d) {
            deleted = Dictionary(uniqueKeysWithValues: m.compactMap { k, v in UUID(uuidString: k).map { ($0, v) } })
        }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            if !Task.isCancelled { self?.save() }
        }
    }

    func save() {
        if let d = try? JSONEncoder().encode(items) { try? d.write(to: url, options: .atomic) }
        let m = Dictionary(uniqueKeysWithValues: deleted.map { ($0.key.uuidString, $0.value) })
        if let d = try? JSONEncoder().encode(m) { try? d.write(to: deletedURL, options: .atomic) }
    }
}
