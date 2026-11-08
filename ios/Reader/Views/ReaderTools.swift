import SwiftUI
import UIKit
import PDFKit

// MARK: - Hızlı sözlük (sistem sözlüğü)

struct DictionaryTerm: Identifiable {
    let term: String
    var id: String { term }
}

enum QuickDictionary {
    /// Seçimden sözlükte aranabilecek kelime/ifade (en çok 3 kelime); değilse nil.
    static func term(from text: String) -> String? {
        let edge = CharacterSet.whitespacesAndNewlines.union(.punctuationCharacters).union(.symbols)
        let t = text.trimmingCharacters(in: edge)
        let words = t.split(whereSeparator: \.isWhitespace)
        guard !t.isEmpty, words.count <= 3 else { return nil }
        return t
    }

    static func hasDefinition(_ term: String) -> Bool {
        UIReferenceLibraryViewController.dictionaryHasDefinition(forTerm: term)
    }
}

/// iOS/Mac'in yerleşik sözlük penceresi (Ayarlar > Sözlük'ten indirilen sözlükleri kullanır).
struct DictionarySheet: UIViewControllerRepresentable {
    let term: String
    func makeUIViewController(context: Context) -> UIReferenceLibraryViewController {
        UIReferenceLibraryViewController(term: term)
    }
    func updateUIViewController(_ vc: UIReferenceLibraryViewController, context: Context) {}
}

// MARK: - Dipnot penceresi

struct FootnoteInfo: Identifiable {
    let id = UUID()
    var text: String
    /// "Şuraya git" için hedef
    var chapter: Int
    var fragment: String
}

struct FootnoteSheet: View {
    let info: FootnoteInfo
    var translate: () -> Void
    var goTo: () -> Void
    var close: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(info.text)
                    .font(.body)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
            }
            .navigationTitle("Dipnot")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Kapat", action: close) }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button(action: translate) { Label("Çevir", systemImage: "character.bubble") }
                        Button(action: goTo) { Label("Metinde göster", systemImage: "arrow.turn.down.right") }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .safeAreaInset(edge: .bottom) {
                HStack(spacing: 12) {
                    Button(action: translate) { Label("Çevir", systemImage: "character.bubble").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered)
                    Button(action: goTo) { Label("Metinde göster", systemImage: "arrow.turn.down.right").frame(maxWidth: .infinity) }
                        .buttonStyle(.bordered)
                }
                .padding(.horizontal, 20).padding(.vertical, 10)
                .background(.bar)
            }
        }
    }
}

// MARK: - Kitap içi arama

struct SearchHit: Identifiable {
    let id = UUID()
    var location: String
    var before: String
    var match: String
    var after: String
    // EPUB
    var chapter: Int?
    var occurrence: Int?
    // PDF
    var page: Int?
    var selection: PDFSelection?
}

@MainActor
final class BookSearchModel: ObservableObject {
    @Published var query = ""
    @Published var hits: [SearchHit] = []
    @Published var searching = false
    @Published var message: String?

    /// Sorguyu arar (kitaba özgü); en çok `limit` sonuç döner.
    var searcher: (String, Int) async -> [SearchHit] = { _, _ in [] }
    var open: (SearchHit) -> Void = { _ in }
    static let limit = 200
    private var task: Task<Void, Never>?

    func queryChanged() {
        task?.cancel()
        let q = query.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        guard q.count >= 2 else {
            hits = []; message = nil; searching = false
            return
        }
        searching = true
        message = nil
        task = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled, let self else { return }
            let r = await self.searcher(q, Self.limit)
            guard !Task.isCancelled else { return }
            self.hits = r
            self.searching = false
            if r.isEmpty { self.message = "Sonuç yok" }
            else if r.count >= Self.limit { self.message = "İlk \(Self.limit) sonuç gösteriliyor" }
            else { self.message = "\(r.count) sonuç" }
        }
    }
}

struct BookSearchView: View {
    @ObservedObject var model: BookSearchModel
    var close: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            List {
                if let m = model.message {
                    Text(m).font(.footnote).foregroundStyle(.secondary).listRowSeparator(.hidden)
                }
                ForEach(model.hits) { hit in
                    Button { model.open(hit) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(hit.location).font(.caption).foregroundStyle(.secondary)
                            (Text(hit.before) + Text(hit.match).bold().foregroundColor(.accentColor) + Text(hit.after))
                                .font(.callout)
                                .foregroundStyle(.primary)
                                .multilineTextAlignment(.leading)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            .listStyle(.plain)
            .overlay { if model.searching && model.hits.isEmpty { ProgressView() } }
            .navigationTitle("Kitapta ara")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $model.query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Kelime veya ifade")
            .onChange(of: model.query) { _, _ in model.queryChanged() }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Kapat", action: close) } }
            .onAppear { if !model.query.isEmpty && model.hits.isEmpty { model.queryChanged() } }
        }
    }
}

/// PDF içinde arama: PDFKit'in asenkron aramasını bir `await`e çevirir.
@MainActor
final class PDFFinder: NSObject, PDFDocumentDelegate {
    private let doc: PDFDocument
    private var results: [PDFSelection] = []
    private var limit = 200
    private var cont: CheckedContinuation<[PDFSelection], Never>?

    init(doc: PDFDocument) { self.doc = doc }

    func find(_ q: String, limit: Int) async -> [PDFSelection] {
        cancel()
        results = []
        self.limit = limit
        return await withCheckedContinuation { c in
            cont = c
            doc.delegate = self
            doc.beginFindString(q, withOptions: [.caseInsensitive, .diacriticInsensitive])
        }
    }

    func cancel() {
        if cont != nil { doc.cancelFindString() }
        finish()
    }

    private func finish() {
        let c = cont
        cont = nil
        c?.resume(returning: results)
    }

    nonisolated func didMatchString(_ instance: PDFSelection) {
        MainActor.assumeIsolated {
            guard cont != nil else { return }
            results.append(instance)
            if results.count >= limit { doc.cancelFindString() }
        }
    }

    nonisolated func documentDidEndDocumentFind(_ notification: Notification) {
        MainActor.assumeIsolated { finish() }
    }
}

enum SearchText {
    /// Arama ve bağlam için boşlukları teke indirir.
    static func collapse(_ s: String) -> String {
        s.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// Düz metinde sorguyu (büyük/küçük harf ve aksan duyarsız) bulur; sonuç parçalarını verir.
    static func matches(in text: String, query: String, limit: Int)
        -> [(occurrence: Int, before: String, match: String, after: String)] {
        var out: [(Int, String, String, String)] = []
        let opts: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        var from = text.startIndex
        var n = 0
        while out.count < limit, from < text.endIndex,
              let r = text.range(of: query, options: opts, range: from..<text.endIndex) {
            let bs = text.index(r.lowerBound, offsetBy: -50, limitedBy: text.startIndex) ?? text.startIndex
            let ae = text.index(r.upperBound, offsetBy: 70, limitedBy: text.endIndex) ?? text.endIndex
            out.append((n, String(text[bs..<r.lowerBound]), String(text[r]), String(text[r.upperBound..<ae])))
            n += 1
            from = r.upperBound
        }
        return out
    }
}
