import SwiftUI

/// Vurguyu paylaşmak için estetik alıntı kartı: seçili okuma temasının renkleri, kitap adı ve yazar.
struct QuoteCardView: View {
    let text: String
    let title: String
    let author: String
    let theme: ReaderTheme

    private var fontSize: CGFloat { text.count < 120 ? 54 : (text.count < 260 ? 46 : 38) }

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("“")
                .font(.system(size: 150, weight: .bold, design: .serif))
                .foregroundStyle(Color(hex: theme.translationHex))
                .frame(height: 90, alignment: .top)
            Text(text)
                .font(.system(size: fontSize, design: .serif))
                .lineSpacing(10)
                .foregroundStyle(theme.textColor)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 24)
            VStack(alignment: .leading, spacing: 6) {
                Rectangle().fill(Color(hex: theme.translationHex)).frame(width: 70, height: 4)
                Text(title).font(.system(size: 30, weight: .semibold, design: .serif)).foregroundStyle(theme.textColor)
                if !author.isEmpty {
                    Text(author).font(.system(size: 26, design: .serif)).foregroundStyle(theme.textColor.opacity(0.7))
                }
            }
        }
        .padding(70)
        .frame(width: 900, alignment: .leading)
        .frame(minHeight: 900, alignment: .topLeading)
        .background(theme.color)
    }
}

@MainActor
enum QuoteShare {
    static func image(text: String, title: String, author: String, theme: ReaderTheme) -> UIImage? {
        let r = ImageRenderer(content: QuoteCardView(text: text, title: title, author: author, theme: theme))
        r.scale = 2
        return r.uiImage
    }
}

/// Bir vurguyu görsel alıntı kartı olarak paylaşır.
struct QuoteShareButton: View {
    let highlight: Highlight
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: ReaderSettings
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                ShareLink(item: Image(uiImage: image), preview: SharePreview("Alıntı kartı", image: Image(uiImage: image))) {
                    Label("Alıntı kartı olarak paylaş", systemImage: "square.and.arrow.up")
                }
            } else {
                HStack { ProgressView(); Text("Kart hazırlanıyor…") }
            }
        }
        .task(id: highlight.text) {
            image = QuoteShare.image(text: highlight.text, title: highlight.bookTitle,
                                     author: library.book(highlight.bookID)?.author ?? "", theme: settings.theme)
        }
    }
}

/// Kitaplık ekranında her gün eski bir vurguyla yeniden karşılaşma kartı.
struct DailyHighlightCard: View {
    @EnvironmentObject var store: HighlightStore
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: ReaderSettings
    var onOpen: (Highlight) -> Void
    @State private var shift = 0
    @State private var image: UIImage?

    private var pool: [Highlight] {
        store.items.filter { !$0.isInsight && (30...400).contains($0.text.count) }.sorted { $0.id.uuidString < $1.id.uuidString }
    }

    private var pick: Highlight? {
        let p = pool
        guard !p.isEmpty else { return nil }
        let day = Calendar.current.ordinality(of: .day, in: .era, for: Date()) ?? 0
        return p[((day * 7 + shift) % p.count + p.count) % p.count]
    }

    var body: some View {
        if let h = pick {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label("Günün vurgusu", systemImage: "quote.opening").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                    Spacer()
                    Button { withAnimation { shift += 1 } } label: { Image(systemName: "shuffle") }
                        .accessibilityLabel("Başka bir vurgu")
                }
                Text(h.text.replacingOccurrences(of: "\n", with: " "))
                    .font(.system(size: 18, design: .serif))
                    .lineLimit(6)
                if !h.note.isEmpty {
                    Text("📝 \(h.note)").font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                }
                Text(h.bookTitle).font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 18) {
                    Button { onOpen(h) } label: { Label("Kitapta aç", systemImage: "book") }
                    if let image {
                        ShareLink(item: Image(uiImage: image), preview: SharePreview("Alıntı kartı", image: Image(uiImage: image))) {
                            Label("Paylaş", systemImage: "square.and.arrow.up")
                        }
                    }
                }
                .font(.subheadline)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 16))
            .task(id: h.id) {
                image = QuoteShare.image(text: h.text, title: h.bookTitle,
                                         author: library.book(h.bookID)?.author ?? "", theme: settings.theme)
            }
        }
    }
}
