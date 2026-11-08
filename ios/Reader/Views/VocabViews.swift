import SwiftUI

enum LibraryRoute: Hashable { case vocab, highlights, stats }

private func markdown(_ s: String) -> AttributedString {
    let opts = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
    return (try? AttributedString(markdown: s, options: opts)) ?? AttributedString(s)
}

/// Bağlam cümlesinde kelimeyi kalın ve renkli gösterir.
private func highlighted(_ text: String, word: String) -> AttributedString {
    var a = AttributedString(text)
    if let r = a.range(of: word, options: [.caseInsensitive, .diacriticInsensitive]) {
        a[r].font = .body.bold()
        a[r].foregroundColor = .orange
    }
    return a
}

// MARK: - Liste

struct VocabView: View {
    @EnvironmentObject var vocab: VocabStore
    @EnvironmentObject var settings: ReaderSettings
    @StateObject private var ai = AIAssistant()

    /// Bugün tekrar edilecek (vadesi gelmiş) kelimeler; en gecikenler önde, en çok 15.
    private var storyWords: [VocabEntry] {
        Array(vocab.entries.filter { $0.isDue && !$0.isCard }.sorted { $0.dueAt < $1.dueAt }.prefix(15))
    }

    private func makeStory() {
        let words = storyWords
        guard words.count >= 3 else { return }
        ai.start(AIRequest(
            title: "Bugünkü kelimelerle hikâye",
            system: Prompts.assistantSystem,
            user: Prompts.vocabStory(words.map { ($0.word, $0.gloss) }),
            config: settings.config(.smart),
            maxTokens: 2048,
            cacheKey: nil))
    }
    @State private var search = ""
    @State private var onlyDue = false
    @State private var selected: VocabEntry?
    @State private var studying = false

    private var filtered: [VocabEntry] {
        vocab.entries.filter { e in
            (!onlyDue || e.isDue) &&
            (search.isEmpty
             || e.word.localizedCaseInsensitiveContains(search)
             || e.gloss.localizedCaseInsensitiveContains(search)
             || e.bookTitle.localizedCaseInsensitiveContains(search))
        }
    }

    var body: some View {
        List {
            ForEach(filtered) { e in
                Button { selected = e } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(e.word).font(.headline)
                            if e.isDue { Circle().fill(.orange).frame(width: 8, height: 8) }
                            Spacer()
                            Text("Kutu \(e.box)").font(.caption2).foregroundStyle(.tertiary)
                        }
                        Text(e.gloss).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                        Text(e.bookTitle).font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
                    }
                    .padding(.vertical, 2)
                }
                .buttonStyle(.plain)
            }
            .onDelete { idx in vocab.delete(idx.map { filtered[$0].id }) }
        }
        .searchable(text: $search, prompt: "Kelime, anlam veya kitap ara")
        .navigationTitle("Kelime defteri")
        .overlay {
            if vocab.entries.isEmpty {
                ContentUnavailableView("Defter boş", systemImage: "text.book.closed",
                                       description: Text("Okurken bir kelimeyi seçip “Açıkla”ya basın, sonra “Deftere ekle”ye dokunun."))
            } else if filtered.isEmpty {
                ContentUnavailableView.search
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ShareLink(item: VocabExport(text: vocab.exportText()), preview: SharePreview("Kelime defteri (Anki)")) {
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(vocab.entries.isEmpty)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { makeStory() } label: { Image(systemName: "text.book.closed.fill") }
                    .disabled(storyWords.count < 3)
                    .accessibilityLabel("Bugünkü kelimelerle hikâye")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { onlyDue.toggle() } label: {
                    Image(systemName: onlyDue ? "clock.badge.checkmark.fill" : "clock")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { studying = true } label: {
                    Label(vocab.dueCount > 0 ? "Çalış (\(vocab.dueCount))" : "Çalış", systemImage: "rectangle.on.rectangle.angled")
                }
                .disabled(vocab.dueCount == 0)
            }
        }
        .sheet(item: $selected) { VocabDetail(entry: $0) }
        .fullScreenCover(isPresented: $studying) { ReviewView() }
        .aiSheet(ai)
    }
}

// MARK: - Detay

struct VocabDetail: View {
    let entry: VocabEntry
    @EnvironmentObject var vocab: VocabStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 12) {
                        Text(entry.word).font(.largeTitle.bold())
                        PronounceButton(text: entry.word, context: entry.paragraph).font(.title2)
                    }
                    Text(highlighted(entry.snippet, word: entry.word))
                        .font(.system(size: 17, design: .serif))
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                    Text(markdown(entry.explanation)).font(.system(size: 18)).lineSpacing(5).textSelection(.enabled)
                    Text("\(entry.bookTitle) · \(entry.addedAt.formatted(date: .abbreviated, time: .omitted))")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(entry.word)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Kapat") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive) {
                        vocab.delete([entry.id]); dismiss()
                    } label: { Image(systemName: "trash") }
                }
            }
        }
    }
}

// MARK: - Tekrar kartları

struct ReviewView: View {
    @EnvironmentObject var vocab: VocabStore
    @Environment(\.dismiss) private var dismiss
    @State private var queue: [UUID] = []
    @State private var started = false
    @State private var showBack = false
    @State private var total = 0
    @State private var done = 0

    private var current: VocabEntry? {
        guard let id = queue.first else { return nil }
        return vocab.entries.first { $0.id == id }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if let e = current {
                    ProgressView(value: Double(done), total: Double(max(total, 1)))
                    Spacer(minLength: 0)
                    card(e)
                    Spacer(minLength: 0)
                    controls
                } else if started {
                    ContentUnavailableView("Tamamlandı", systemImage: "checkmark.seal.fill",
                                           description: Text("\(done) kart çalışıldı. Gerisi zamanı gelince tekrar çıkar."))
                    Button("Bitir") { dismiss() }.buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
            .navigationTitle("Tekrar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Kapat") { dismiss() } } }
            .onAppear {
                guard !started else { return }
                queue = vocab.entries.filter(\.isDue).shuffled().map(\.id)
                total = queue.count
                started = true
            }
        }
    }

    private func card(_ e: VocabEntry) -> some View {
        VStack(spacing: 18) {
            if e.isCard {
                Text(e.word).font(.system(size: 28, weight: .semibold, design: .serif)).multilineTextAlignment(.center)
            } else {
                HStack(spacing: 12) {
                    Text(e.word).font(.system(size: 44, weight: .bold, design: .serif)).multilineTextAlignment(.center)
                    PronounceButton(text: e.word, context: e.paragraph).font(.title2)
                }
                Text(highlighted(e.snippet, word: e.word))
                    .font(.system(size: 18, design: .serif))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            if showBack {
                Divider()
                ScrollView {
                    Text(markdown(e.explanation)).font(.system(size: 18)).lineSpacing(5)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 360)
            }
        }
        .padding(28)
        .frame(maxWidth: 640)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))
        .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
        .onTapGesture { withAnimation { showBack = true } }
    }

    @ViewBuilder private var controls: some View {
        if showBack {
            HStack(spacing: 12) {
                gradeButton("Bilmiyorum", .red, .again)
                gradeButton("Zor", .orange, .hard)
                gradeButton("Biliyorum", .green, .good)
                gradeButton("Kolay", .blue, .easy)
            }
        } else {
            Button { withAnimation { showBack = true } } label: {
                Text("Cevabı göster").frame(maxWidth: 320).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private func gradeButton(_ title: String, _ color: Color, _ grade: ReviewGrade) -> some View {
        Button {
            guard let id = queue.first else { return }
            vocab.review(id, grade: grade)
            if grade == .again {
                queue.append(queue.removeFirst())   // oturum sonunda tekrar sor
            } else {
                queue.removeFirst()
                done += 1
            }
            showBack = false
        } label: {
            Text(title).frame(maxWidth: .infinity).padding(.vertical, 6)
        }
        .buttonStyle(.borderedProminent)
        .tint(color)
    }
}
