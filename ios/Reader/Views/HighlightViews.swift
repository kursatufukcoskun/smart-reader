import SwiftUI

// MARK: - Düzenleme sayfası (renk + not)

struct HighlightEditor: View {
    @State var draft: Highlight
    var focusNote: Bool = false
    var onSave: (Highlight) -> Void
    var onDelete: (Highlight) -> Void

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var vocab: VocabStore
    @EnvironmentObject private var settings: ReaderSettings
    @FocusState private var noteFocused: Bool
    @State private var deleted = false
    @State private var makingCard = false
    @State private var cardInfo: String?

    /// Vurgudan (ve notundan) yapay zekâ ile tek soru/cevap kartı üretip kelime defterine ekler.
    private func makeCard() {
        makingCard = true
        cardInfo = nil
        let h = draft
        let config = settings.config(.fast)
        Task {
            defer { makingCard = false }
            do {
                let out = try await LLMClient.complete(config, system: Prompts.assistantSystem,
                                                       user: Prompts.flashcard(quote: h.text, note: h.note, bookTitle: h.bookTitle),
                                                       maxTokens: 512)
                guard let card = Prompts.parseFlashcard(out) else { cardInfo = "Kart üretilemedi, tekrar dene."; return }
                vocab.addCard(question: card.question, answer: card.answer, quote: h.text, bookID: h.bookID, bookTitle: h.bookTitle)
                cardInfo = "Karta eklendi: \(card.question)"
            } catch {
                cardInfo = error.localizedDescription
            }
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(draft.text)
                        .font(.system(size: 16, design: .serif))
                        .italic()
                        .padding(.vertical, 2)
                    if !draft.location.isEmpty {
                        Text(draft.location).font(.caption).foregroundStyle(.secondary)
                    }
                }
                if !draft.isInsight { Section("Renk") {
                    HStack(spacing: 16) {
                        ForEach(HighlightColor.allCases) { c in
                            Button { draft.color = c } label: {
                                Circle().fill(c.color)
                                    .frame(width: 36, height: 36)
                                    .overlay(Circle().stroke(Color.primary.opacity(draft.color == c ? 0.9 : 0), lineWidth: 3))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                } }
                Section("Not") {
                    TextEditor(text: $draft.note)
                        .frame(minHeight: 140)
                        .focused($noteFocused)
                }
                Section("Paylaş") {
                    QuoteShareButton(highlight: draft)
                }
                Section("Tekrar kartı") {
                    Button { makeCard() } label: {
                        if makingCard { HStack { ProgressView(); Text("Kart hazırlanıyor…") } }
                        else { Label("Bu vurgudan kart yap", systemImage: "rectangle.on.rectangle.angled") }
                    }
                    .disabled(makingCard)
                    if let cardInfo { Text(cardInfo).font(.footnote).foregroundStyle(.secondary) }
                }
                Section {
                    Button(role: .destructive) {
                        deleted = true
                        onDelete(draft)
                        dismiss()
                    } label: { Label("Vurguyu sil", systemImage: "trash") }
                }
            }
            .navigationTitle("Vurgu")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Bitti") { dismiss() } } }
            .onAppear { if focusNote { noteFocused = true } }
            .onDisappear { if !deleted { onSave(draft) } }
        }
    }
}

// MARK: - Satır

struct HighlightRow: View {
    let h: Highlight
    var showBook = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if h.isInsight {
                Image(systemName: "sparkles").foregroundStyle(.purple).frame(width: 4).padding(.top, 2).padding(.trailing, 10)
            } else {
                RoundedRectangle(cornerRadius: 2).fill(h.color.color).frame(width: 4)
            }
            VStack(alignment: .leading, spacing: 5) {
                if h.isInsight {
                    Text("Yapay zekâ içgörüsü").font(.caption2.weight(.semibold)).foregroundStyle(.purple)
                }
                Text(h.text).font(.system(size: 16, design: h.isInsight ? .default : .serif)).lineLimit(h.isInsight ? 8 : 4).multilineTextAlignment(.leading)
                if !h.note.isEmpty {
                    Label(h.note, systemImage: "note.text")
                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
                        .multilineTextAlignment(.leading)
                }
                Text(showBook ? "\(h.bookTitle) · \(h.location)" : h.location)
                    .font(.caption2).foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
    }
}

// MARK: - Bir kitabın vurguları (okuyucu içinde)

struct BookHighlightsList: View {
    let bookID: UUID
    var onSelect: (Highlight) -> Void
    var onDelete: (Highlight) -> Void
    /// Verilirse listenin başında "Bu bölümün içgörülerini çıkar" düğmesi görünür.
    var onInsights: (() -> Void)?
    var isGeneratingInsights = false
    @EnvironmentObject var store: HighlightStore

    var body: some View {
        let items = store.forBook(bookID)
        if items.isEmpty && onInsights == nil {
            ContentUnavailableView("Henüz vurgu yok", systemImage: "highlighter",
                                   description: Text("Bir metni seçip fosforlu kalem düğmesine dokunun."))
        } else {
            List {
                if let onInsights {
                    Section {
                        Button(action: onInsights) {
                            if isGeneratingInsights {
                                HStack { ProgressView(); Text("İçgörüler hazırlanıyor…") }
                            } else {
                                Label("Bu bölümün içgörülerini çıkar", systemImage: "sparkles")
                            }
                        }
                        .disabled(isGeneratingInsights)
                    } footer: {
                        Text("Yapay zekâ bulunduğun bölümden 3-5 içgörü çıkarır; vurgularından ayrı, ✨ ile işaretli görünür.")
                    }
                }
                if items.isEmpty {
                    Text("Henüz vurgu yok. Bir metni seçip fosforlu kalem düğmesine dokun.").font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(items) { h in
                    Button { onSelect(h) } label: { HighlightRow(h: h) }.buttonStyle(.plain)
                }
                .onDelete { idx in idx.map { items[$0] }.forEach(onDelete) }
            }
        }
    }
}

// MARK: - Tüm kitapların vurguları

struct HighlightsView: View {
    @EnvironmentObject var store: HighlightStore
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: ReaderSettings
    @StateObject private var ai = AIAssistant()
    @State private var tag: String?
    @State private var search = ""
    @State private var editing: Highlight?
    @State private var openBook: UUID?

    private var filtered: [Highlight] {
        let tagged: Set<UUID>? = tag.map { t in Set(library.books.filter { ($0.tags ?? []).contains(t) }.map(\.id)) }
        return store.items.filter {
            (tagged == nil || tagged!.contains($0.bookID)) &&
            (search.isEmpty || $0.text.localizedCaseInsensitiveContains(search)
             || $0.note.localizedCaseInsensitiveContains(search)
             || $0.bookTitle.localizedCaseInsensitiveContains(search))
        }
    }

    /// Süzülen vurgu ve notlardan ortak temalar, bağlantılar ve bir sentez çıkarır.
    private func synthesize() {
        var text = ""
        for g in groups {
            text += "\n## \(g.title)\n"
            for h in g.items where !h.isInsight {
                text += "- \"\(h.text.replacingOccurrences(of: "\n", with: " "))\""
                if !h.note.isEmpty { text += " — not: \(h.note)" }
                text += "\n"
            }
        }
        text = String(text.prefix(60_000))
        let scope = tag.map { "#\($0)" } ?? (search.isEmpty ? "tüm kitaplar" : "“\(search)”")
        let config = settings.config(.smart)
        ai.start(AIRequest(
            title: "Vurgu sentezi: \(scope)",
            system: Prompts.summarySystem,
            user: Prompts.highlightSynthesis(text: text, scope: scope),
            config: config,
            maxTokens: 3072,
            cacheKey: ResponseCache.key("hlsyn", config.cacheID, text)))
    }

    private var groups: [(title: String, items: [Highlight])] {
        Dictionary(grouping: filtered, by: \.bookID)
            .map { (title: $0.value.first?.bookTitle ?? "", items: $0.value.sorted { $0.sortKey < $1.sortKey }) }
            .sorted { $0.title < $1.title }
    }

    var body: some View {
        List {
            ForEach(groups, id: \.title) { g in
                Section(g.title) {
                    ForEach(g.items) { h in
                        Button {
                            store.pendingJump = h
                            openBook = h.bookID
                        } label: { HighlightRow(h: h) }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .leading) {
                                Button { editing = h } label: { Label("Düzenle", systemImage: "pencil") }.tint(.orange)
                            }
                            .contextMenu {
                                Button { editing = h } label: { Label("Renk ve notu düzenle", systemImage: "pencil") }
                                Button(role: .destructive) { store.delete([h.id]) } label: { Label("Sil", systemImage: "trash") }
                            }
                    }
                    .onDelete { idx in store.delete(idx.map { g.items[$0].id }) }
                }
            }
        }
        .searchable(text: $search, prompt: "Vurgu, not veya kitap ara")
        .navigationTitle("Vurgular ve notlar")
        .overlay {
            if store.items.isEmpty {
                ContentUnavailableView("Henüz vurgu yok", systemImage: "highlighter",
                                       description: Text("Okurken bir metni seçip fosforlu kalem düğmesine dokunun."))
            }
        }
        .toolbar {
            if !library.allTags.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { tag = nil } label: { Label("Tüm kitaplar", systemImage: tag == nil ? "checkmark" : "books.vertical") }
                        ForEach(library.allTags, id: \.self) { t in
                            Button { tag = t } label: { Label("#\(t)", systemImage: tag == t ? "checkmark" : "tag") }
                        }
                    } label: { Image(systemName: tag == nil ? "tag" : "tag.fill") }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { synthesize() } label: { Image(systemName: "sparkles") }
                    .disabled(filtered.isEmpty)
                    .accessibilityLabel("Yapay zekâ ile sentezle")
            }
            ToolbarItem(placement: .topBarTrailing) {
                ShareLink(item: store.exportMarkdown(), subject: Text("Vurgular ve notlar")) {
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(store.items.isEmpty)
            }
        }
        .aiSheet(ai)
        .navigationDestination(item: $openBook) { id in ReaderContainer(bookID: id) }
        .sheet(item: $editing) { h in
            HighlightEditor(draft: h, focusNote: false,
                            onSave: { store.update($0) },
                            onDelete: { store.delete([$0.id]) })
                .presentationDetents([.large])
        }
    }
}
