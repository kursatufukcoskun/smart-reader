import Foundation
import SwiftUI
import UniformTypeIdentifiers
import CoreTransferable

/// "Açıkla" sonucunu deftere eklemek için gereken bilgi.
struct VocabDraft: Equatable {
    var word: String
    var paragraph: String
    var bookID: UUID
    var bookTitle: String

    /// Yalnızca kısa seçimler (kelime/ifade) deftere eklenir.
    static func make(word: String, paragraph: String, book: Book) -> VocabDraft? {
        let w = word.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !w.isEmpty, w.split(whereSeparator: \.isWhitespace).count <= 6 else { return nil }
        return VocabDraft(word: w, paragraph: paragraph, bookID: book.id, bookTitle: book.title)
    }
}

struct VocabEntry: Identifiable, Codable, Hashable {
    var id = UUID()
    var word: String
    /// Kısa Türkçe karşılık (açıklamanın ilk satırı)
    var gloss: String
    /// Modelin tam açıklaması
    var explanation: String
    var paragraph: String
    var bookID: UUID?
    var bookTitle: String
    var addedAt = Date()
    /// Leitner kutusu: 0...5
    var box = 0
    var dueAt = Date()
    var reviews = 0
    var updatedAt: Date?
    var stamp: Date { updatedAt ?? addedAt }

    var isDue: Bool { dueAt <= Date() }

    /// Kelimenin geçtiği cümle civarı (kart ön yüzü için).
    var snippet: String {
        let text = paragraph.replacingOccurrences(of: "\n", with: " ")
        guard let r = text.range(of: word, options: [.caseInsensitive, .diacriticInsensitive]) else {
            return String(text.prefix(220))
        }
        let lo = text.index(r.lowerBound, offsetBy: -140, limitedBy: text.startIndex) ?? text.startIndex
        let hi = text.index(r.upperBound, offsetBy: 140, limitedBy: text.endIndex) ?? text.endIndex
        var s = String(text[lo..<hi])
        if lo > text.startIndex { s = "…" + s }
        if hi < text.endIndex { s += "…" }
        return s
    }
}

enum ReviewGrade { case again, hard, good, easy }

@MainActor
final class VocabStore: ObservableObject {
    @Published private(set) var entries: [VocabEntry] = []
    private(set) var deleted: [UUID: Date] = [:]
    private var saveTask: Task<Void, Never>?
    private var url: URL { Paths.base.appendingPathComponent("vocab.json") }
    private var deletedURL: URL { Paths.base.appendingPathComponent("vocab-deleted.json") }

    /// Kutuya göre bir sonraki tekrar aralığı (gün). 0 = 10 dakika.
    private static let intervals: [Double] = [0, 1, 3, 7, 16, 35]

    init() { load() }

    var dueCount: Int { entries.filter(\.isDue).count }

    func contains(_ d: VocabDraft) -> Bool {
        entries.contains { $0.bookID == d.bookID && $0.word.caseInsensitiveCompare(d.word) == .orderedSame }
    }

    /// Açıklamanın ilk satırından kısa karşılığı çıkarır.
    static func gloss(from explanation: String) -> String {
        let first = explanation.split(separator: "\n").first.map(String.init) ?? explanation
        let cleaned = first.replacingOccurrences(of: "*", with: "").trimmingCharacters(in: .whitespaces)
        return String(cleaned.prefix(160))
    }

    func add(_ d: VocabDraft, explanation: String) {
        let gloss = Self.gloss(from: explanation)
        if let i = entries.firstIndex(where: { $0.bookID == d.bookID && $0.word.caseInsensitiveCompare(d.word) == .orderedSame }) {
            entries[i].explanation = explanation
            entries[i].gloss = gloss
            entries[i].paragraph = d.paragraph
            entries[i].updatedAt = Date()
        } else {
            var e = VocabEntry(word: d.word, gloss: gloss, explanation: explanation,
                               paragraph: d.paragraph, bookID: d.bookID, bookTitle: d.bookTitle)
            e.updatedAt = Date()
            entries.insert(e, at: 0)
        }
        scheduleSave()
    }

    func delete(_ ids: [UUID]) {
        let now = Date()
        for id in ids { deleted[id] = now }
        entries.removeAll { ids.contains($0.id) }
        scheduleSave()
    }

    func deleteBook(_ bookID: UUID) {
        delete(entries.filter { $0.bookID == bookID }.map(\.id))
    }

    func replaceFromSync(entries: [VocabEntry], deleted: [UUID: Date]) {
        self.entries = entries
        self.deleted = deleted
        save()
    }

    func review(_ id: UUID, grade: ReviewGrade) {
        guard let i = entries.firstIndex(where: { $0.id == id }) else { return }
        var e = entries[i]
        switch grade {
        case .again: e.box = 0
        case .hard: break
        case .good: e.box = min(e.box + 1, Self.intervals.count - 1)
        case .easy: e.box = min(e.box + 2, Self.intervals.count - 1)
        }
        var days = Self.intervals[e.box]
        if grade == .hard { days = max(days * 0.5, 0) }
        e.dueAt = days == 0 ? Date().addingTimeInterval(10 * 60) : Date().addingTimeInterval(days * 86_400)
        e.reviews += 1
        e.updatedAt = Date()
        entries[i] = e
        scheduleSave()
    }

    /// Anki'ye "Notları İçe Aktar" ile alınabilen, sekmeyle ayrılmış metin (HTML açık).
    func exportText() -> String {
        func clean(_ s: String) -> String {
            s.replacingOccurrences(of: "\t", with: " ")
             .replacingOccurrences(of: "\r", with: "")
             .replacingOccurrences(of: "\n", with: "<br>")
        }
        return entries.map { e in
            let front = "<b>\(clean(e.word))</b><br><i>\(clean(e.snippet))</i>"
            let back = clean(e.explanation.replacingOccurrences(of: "**", with: ""))
            return "\(front)\t\(back)\t\(clean(e.bookTitle))"
        }.joined(separator: "\n")
    }

    private func load() {
        guard let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([VocabEntry].self, from: data) else { return }
        entries = decoded
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
        if let data = try? JSONEncoder().encode(entries) { try? data.write(to: url, options: .atomic) }
        let m = Dictionary(uniqueKeysWithValues: deleted.map { ($0.key.uuidString, $0.value) })
        if let data = try? JSONEncoder().encode(m) { try? data.write(to: deletedURL, options: .atomic) }
    }
}

/// Paylaşım menüsünden .txt dosyası olarak verilen defter dışa aktarımı.
struct VocabExport: Transferable {
    let text: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .plainText) { export in
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("kelime-defteri.txt")
            try export.text.write(to: url, atomically: true, encoding: .utf8)
            return SentTransferredFile(url)
        }
    }
}
