import SwiftUI

/// Kitap karnesi: okuma süresi, gün/oturum sayısı, vurgu-not-kelime sayıları ve vurgulardan yapay zekâ kapanış özeti.
struct BookWrapUpView: View {
    let book: Book
    @EnvironmentObject var tracker: ReadingTracker
    @EnvironmentObject var highlights: HighlightStore
    @EnvironmentObject var vocab: VocabStore
    @EnvironmentObject var settings: ReaderSettings
    @StateObject private var ai = AIAssistant()
    @Environment(\.dismiss) private var dismiss

    private var entries: [ReadingEntry] { tracker.entries(for: book.id) }
    private var seconds: Double { entries.reduce(0) { $0 + $1.seconds } }
    private var sessions: Int { entries.reduce(0) { $0 + $1.sessions } }
    private var days: Int { Set(entries.map(\.day)).count }
    private var span: Int {
        let dates = entries.compactMap { ReadingTracker.date(from: $0.day) }
        guard let lo = dates.min(), let hi = dates.max() else { return 0 }
        return (Calendar.current.dateComponents([.day], from: lo, to: hi).day ?? 0) + 1
    }
    private var marks: [Highlight] { highlights.forBook(book.id).filter { !$0.isInsight } }
    private var notes: Int { marks.filter { !$0.note.isEmpty }.count }
    private var words: Int { vocab.entries.filter { $0.bookID == book.id && !$0.isCard }.count }
    private var cards: Int { vocab.entries.filter { $0.bookID == book.id && $0.isCard }.count }

    private func tile(_ value: String, _ label: String, _ icon: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: icon).font(.title3).foregroundStyle(Color.accentColor)
            Text(value).font(.title2.weight(.semibold).monospacedDigit())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
    }

    private func summarize() {
        var text = ""
        for h in marks {
            text += "- \"\(h.text.replacingOccurrences(of: "\n", with: " "))\""
            if !h.note.isEmpty { text += " — not: \(h.note)" }
            text += "\n"
        }
        let config = settings.config(.smart)
        let body = String(text.prefix(60_000))
        ai.start(AIRequest(
            title: "Kapanış özeti: \(book.title)",
            system: Prompts.summarySystem,
            user: Prompts.wrapUp(title: book.title, author: book.author, highlights: body),
            config: config,
            maxTokens: 2048,
            cacheKey: ResponseCache.key("wrapup", config.cacheID, book.id.uuidString, body)))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    VStack(spacing: 6) {
                        Text("🎉").font(.system(size: 44))
                        Text("Kitabı tamamladın!").font(.title2.weight(.bold))
                        Text(book.title).font(.headline).multilineTextAlignment(.center)
                        if !book.author.isEmpty { Text(book.author).font(.subheadline).foregroundStyle(.secondary) }
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 12)], spacing: 12) {
                        tile(Duration.text(seconds), "okuma süresi", "clock")
                        tile("\(span > 0 ? span : days)", span > 1 ? "günde (\(days) gün okudun)" : "gün", "calendar")
                        tile("\(sessions)", "oturum", "book")
                        tile("\(marks.count)", "vurgu (\(notes) not)", "highlighter")
                        tile("\(words)", "öğrenilen kelime", "text.book.closed")
                        tile("\(cards)", "tekrar kartı", "rectangle.on.rectangle.angled")
                    }
                    Button { summarize() } label: {
                        Label("Vurgularımdan kapanış özeti oluştur", systemImage: "sparkles").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(marks.isEmpty)
                    if marks.isEmpty {
                        Text("Bu kitaptan hiç vurgu yapmadığın için özet oluşturulamıyor.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .padding(20)
            }
            .navigationTitle("Kitap karnesi")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Kapat") { dismiss() } } }
            .aiSheet(ai)
        }
    }
}
