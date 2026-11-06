import Foundation
import SwiftUI

struct StudyConcept: Identifiable, Hashable {
    let id = UUID()
    var term: String
    var definition: String
}

struct QuizQuestion: Identifiable, Hashable {
    let id = UUID()
    var question: String
    var options: [String]
    var correct: Int
    var explanation: String
}

struct QAItem: Identifiable, Hashable {
    let id = UUID()
    var question: String
    var answer: String
}

struct StudyData {
    var summary: [String] = []
    var concepts: [StudyConcept] = []
    var quiz: [QuizQuestion] = []
    var qa: [QAItem] = []
}

private struct StudyPayload: Decodable {
    struct C: Decodable { var term: String?; var definition: String? }
    struct Q: Decodable { var question: String?; var options: [String]?; var answer: Int?; var explanation: String? }
    struct A: Decodable { var question: String?; var answer: String? }
    var summary: [String]?
    var concepts: [C]?
    var quiz: [Q]?
    var qa: [A]?
}

/// Bir bölüm için özet, kavramlar, quiz ve soru-cevap üretir (isteğe bağlı, butonla).
@MainActor
final class ChapterStudyModel: ObservableObject, Identifiable {
    let id = UUID()
    let title: String
    private let bookTitle: String
    private let bookID: UUID
    private let text: String
    private let config: LLMConfig
    private let scopeKey: String

    @Published var data: StudyData?
    @Published var isLoading = false
    @Published var error: String?
    /// Soruların dili: "Türkçe" veya "English"
    @Published var language = "Türkçe"

    init(title: String, text: String, book: Book, config: LLMConfig, scopeKey: String) {
        self.title = title
        self.text = String(text.prefix(80_000))
        self.bookTitle = book.title
        self.bookID = book.id
        self.config = config
        self.scopeKey = scopeKey
    }

    private var cacheKey: String {
        ResponseCache.key("study", config.cacheID, bookID.uuidString, scopeKey, language)
    }

    func load(force: Bool = false) async {
        if isLoading { return }
        if !force, data != nil { return }
        isLoading = true
        error = nil
        defer { isLoading = false }
        do {
            let raw: String
            if !force, let cached = ResponseCache.shared.get(cacheKey) {
                raw = cached
            } else {
                raw = try await LLMClient.complete(
                    config, system: Prompts.assistantSystem,
                    user: Prompts.study(text: text, bookTitle: bookTitle, chapter: title, language: language),
                    maxTokens: 7000)
            }
            guard let parsed = Self.parse(raw) else {
                error = "Yanıt okunamadı (biçim bozuk). Tekrar deneyin."
                return
            }
            ResponseCache.shared.set(cacheKey, raw)
            data = parsed
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Yanıttaki JSON nesnesini ayıklayıp modele çevirir.
    static func parse(_ raw: String) -> StudyData? {
        guard let a = raw.firstIndex(of: "{"), let b = raw.lastIndex(of: "}"), a < b,
              let data = String(raw[a...b]).data(using: .utf8),
              let p = try? JSONDecoder().decode(StudyPayload.self, from: data) else { return nil }
        var out = StudyData()
        out.summary = (p.summary ?? []).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        out.concepts = (p.concepts ?? []).compactMap {
            guard let t = $0.term, let d = $0.definition, !t.isEmpty else { return nil }
            return StudyConcept(term: t, definition: d)
        }
        out.quiz = (p.quiz ?? []).compactMap { q in
            guard let question = q.question, let opts = q.options, opts.count >= 2, let ans = q.answer, ans >= 0, ans < opts.count else { return nil }
            // Model doğru şıkkı hep aynı yere koymasın diye şıkları karıştır.
            let order = Array(opts.indices).shuffled()
            return QuizQuestion(question: question, options: order.map { opts[$0] },
                                correct: order.firstIndex(of: ans) ?? 0, explanation: q.explanation ?? "")
        }
        out.qa = (p.qa ?? []).compactMap {
            guard let q = $0.question, let a = $0.answer else { return nil }
            return QAItem(question: q, answer: a)
        }
        let empty = out.summary.isEmpty && out.quiz.isEmpty && out.qa.isEmpty
        return empty ? nil : out
    }
}
