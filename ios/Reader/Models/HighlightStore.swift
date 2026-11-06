import Foundation
import SwiftUI
import UIKit

enum HighlightColor: String, Codable, CaseIterable, Identifiable {
    case yellow, green, blue, pink, orange
    var id: String { rawValue }

    var title: String {
        switch self {
        case .yellow: return "Sarı"
        case .green: return "Yeşil"
        case .blue: return "Mavi"
        case .pink: return "Pembe"
        case .orange: return "Turuncu"
        }
    }
    var uiColor: UIColor {
        switch self {
        case .yellow: return UIColor(red: 1.0, green: 0.84, blue: 0.04, alpha: 1)
        case .green: return UIColor(red: 0.2, green: 0.78, blue: 0.35, alpha: 1)
        case .blue: return UIColor(red: 0.04, green: 0.52, blue: 1.0, alpha: 1)
        case .pink: return UIColor(red: 1.0, green: 0.22, blue: 0.37, alpha: 1)
        case .orange: return UIColor(red: 1.0, green: 0.58, blue: 0.0, alpha: 1)
        }
    }
    var color: Color { Color(uiColor) }
}

struct Highlight: Identifiable, Codable, Hashable {
    var id = UUID()
    var bookID: UUID
    var bookTitle: String
    var text: String
    var note: String = ""
    var color: HighlightColor = .yellow
    var createdAt = Date()

    // EPUB konumu: bölüm + temiz metin içindeki karakter aralığı
    var chapter: Int?
    var start: Int?
    var end: Int?
    // PDF konumu: sayfa + sayfa koordinatlarındaki satır dikdörtgenleri [x, y, w, h]
    var page: Int?
    var rects: [[Double]]?

    /// Gösterim için konum etiketi ("Bölüm adı" veya "Sayfa 12").
    var location = ""
    /// Son değişiklik zamanı (senkron çakışma çözümü için)
    var updatedAt: Date?
    var stamp: Date { updatedAt ?? createdAt }

    /// Okuma sırasına göre sıralama anahtarı.
    var sortKey: (Int, Int) {
        if let c = chapter { return (c, start ?? 0) }
        if let p = page { return (p, Int(-(rects?.first?[1] ?? 0))) }
        return (0, 0)
    }
}

@MainActor
final class HighlightStore: ObservableObject {
    @Published private(set) var items: [Highlight] = []
    /// Silinen vurguların kayıtları (senkronda silmenin yayılması için)
    private(set) var deleted: [UUID: Date] = [:]
    /// Kitaplık listesinden dokunulan vurgu: kitap açılınca oraya gidilir.
    var pendingJump: Highlight?
    private var saveTask: Task<Void, Never>?
    private var url: URL { Paths.base.appendingPathComponent("highlights.json") }
    private var deletedURL: URL { Paths.base.appendingPathComponent("highlights-deleted.json") }

    init() { load() }

    func forBook(_ id: UUID) -> [Highlight] {
        items.filter { $0.bookID == id }.sorted { $0.sortKey < $1.sortKey }
    }

    func highlight(_ id: UUID) -> Highlight? { items.first { $0.id == id } }

    func add(_ h: Highlight) {
        var h = h
        h.updatedAt = Date()
        items.append(h)
        scheduleSave()
    }

    func update(_ h: Highlight) {
        guard let i = items.firstIndex(where: { $0.id == h.id }) else { return }
        var h = h
        h.updatedAt = Date()
        items[i] = h
        scheduleSave()
    }

    func delete(_ ids: [UUID]) {
        let now = Date()
        for id in ids { deleted[id] = now }
        items.removeAll { ids.contains($0.id) }
        scheduleSave()
    }

    func deleteBook(_ bookID: UUID) {
        delete(items.filter { $0.bookID == bookID }.map(\.id))
    }

    func replaceFromSync(items: [Highlight], deleted: [UUID: Date]) {
        self.items = items
        self.deleted = deleted
        save()
    }

    /// PDF'te dokunulan noktadaki vurgu.
    func hit(bookID: UUID, page: Int, point: CGPoint) -> Highlight? {
        items.first { h in
            h.bookID == bookID && h.page == page &&
            (h.rects ?? []).contains { r in
                r.count == 4 && CGRect(x: r[0], y: r[1], width: r[2], height: r[3]).insetBy(dx: -2, dy: -2).contains(point)
            }
        }
    }

    /// Obsidian/Notion gibi araçlara aktarılabilen Markdown.
    func exportMarkdown() -> String {
        var out = "# Vurgular ve notlar\n"
        let books = Dictionary(grouping: items, by: \.bookID)
        for (_, list) in books.sorted(by: { ($0.value.first?.bookTitle ?? "") < ($1.value.first?.bookTitle ?? "") }) {
            out += "\n## \(list.first?.bookTitle ?? "")\n"
            for h in list.sorted(by: { $0.sortKey < $1.sortKey }) {
                out += "\n> \(h.text.replacingOccurrences(of: "\n", with: " "))\n"
                if !h.location.isEmpty { out += "\n*\(h.location)*\n" }
                if !h.note.isEmpty { out += "\n**Not:** \(h.note)\n" }
            }
        }
        return out
    }

    private func load() {
        guard let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([Highlight].self, from: data) else { return }
        items = decoded
        if let d = try? Data(contentsOf: deletedURL),
           let m = try? JSONDecoder().decode([String: Date].self, from: d) {
            deleted = Dictionary(uniqueKeysWithValues: m.compactMap { k, v in UUID(uuidString: k).map { ($0, v) } })
        }
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            if Task.isCancelled { return }
            self?.save()
        }
    }

    func save() {
        if let data = try? JSONEncoder().encode(items) { try? data.write(to: url, options: .atomic) }
        let m = Dictionary(uniqueKeysWithValues: deleted.map { ($0.key.uuidString, $0.value) })
        if let data = try? JSONEncoder().encode(m) { try? data.write(to: deletedURL, options: .atomic) }
    }
}

/// Düzenleme sayfasını açan kayıt.
struct EditingHighlight: Identifiable {
    var id: UUID { highlight.id }
    var highlight: Highlight
    var focusNote: Bool
}
