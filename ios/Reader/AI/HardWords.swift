import Foundation
import SwiftUI

struct HardWord: Identifiable, Hashable {
    let id = UUID()
    var word: String
    var pos: String
    var meaning: String
    var context: String
}

/// Bir bölüm/sayfa aralığındaki zor kelimeleri çıkarır (okumadan önce göz atmak için).
@MainActor
final class HardWordsModel: ObservableObject, Identifiable {
    let id = UUID()
    let title: String
    let bookID: UUID
    let bookTitle: String
    private let text: String
    private let config: LLMConfig
    private let cacheKey: String

    @Published var items: [HardWord] = []
    @Published var isLoading = true
    @Published var error: String?

    init(title: String, text: String, book: Book, config: LLMConfig, scopeKey: String) {
        self.title = title
        self.text = String(text.prefix(60_000))
        self.bookID = book.id
        self.bookTitle = book.title
        self.config = config
        self.cacheKey = ResponseCache.key("hardwords", config.cacheID, book.id.uuidString, scopeKey)
    }

    func load() async {
        guard items.isEmpty else { return }
        isLoading = true
        error = nil
        do {
            let raw: String
            if let cached = ResponseCache.shared.get(cacheKey) {
                raw = cached
            } else {
                raw = try await LLMClient.complete(config, system: Prompts.assistantSystem,
                                                   user: Prompts.hardWords(text: text, bookTitle: bookTitle), maxTokens: 3000)
                ResponseCache.shared.set(cacheKey, raw)
            }
            items = Self.parse(raw)
            if items.isEmpty { error = "Kelime listesi çıkarılamadı, tekrar deneyin." }
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }

    static func parse(_ raw: String) -> [HardWord] {
        raw.split(separator: "\n").compactMap { line in
            let parts = line.split(separator: "|", omittingEmptySubsequences: false)
                .map { $0.trimmingCharacters(in: .whitespaces.union(CharacterSet(charactersIn: "•-*"))) }
            guard parts.count >= 3, !parts[0].isEmpty, !parts[2].isEmpty else { return nil }
            return HardWord(word: parts[0], pos: parts[1], meaning: parts[2], context: parts.count > 3 ? parts[3] : "")
        }
    }

    func draft(_ w: HardWord) -> VocabDraft {
        VocabDraft(word: w.word, paragraph: w.context, bookID: bookID, bookTitle: bookTitle)
    }

    func explanation(_ w: HardWord) -> String {
        var s = "**\(w.word)** — \(w.pos); \(w.meaning)"
        if !w.context.isEmpty { s += "\n**Bağlam:** \(w.context)" }
        return s
    }
}

struct HardWordsView: View {
    @ObservedObject var model: HardWordsModel
    @EnvironmentObject var vocab: VocabStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if model.isLoading {
                    HStack { ProgressView(); Text("Yapay zekâ metni tarıyor…").foregroundStyle(.secondary) }
                }
                if let e = model.error {
                    Label(e, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red)
                    Button("Tekrar dene") { Task { await model.load() } }
                }
                ForEach(model.items) { w in
                    let added = vocab.contains(model.draft(w))
                    HStack(alignment: .top, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 8) {
                                Text(w.word).font(.headline)
                                if !w.pos.isEmpty { Text(w.pos).font(.caption).foregroundStyle(.secondary) }
                            }
                            Text(w.meaning).font(.subheadline)
                            if !w.context.isEmpty {
                                Text(w.context).font(.footnote).italic().foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Button {
                            vocab.add(model.draft(w), explanation: model.explanation(w))
                        } label: {
                            Image(systemName: added ? "checkmark.circle.fill" : "plus.circle")
                                .font(.title2).foregroundStyle(added ? .green : Color.accentColor)
                        }
                        .buttonStyle(.plain)
                        .disabled(added)
                    }
                    .padding(.vertical, 2)
                }
            }
            .navigationTitle(model.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Kapat") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Hepsini ekle") {
                        for w in model.items where !vocab.contains(model.draft(w)) {
                            vocab.add(model.draft(w), explanation: model.explanation(w))
                        }
                    }
                    .disabled(model.items.isEmpty)
                }
            }
            .task { await model.load() }
        }
    }
}
