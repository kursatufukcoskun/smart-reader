import SwiftUI
import UniformTypeIdentifiers

enum LibraryFilter: Hashable {
    case all, reading, unread, finished
    case tag(String)

    var title: String {
        switch self {
        case .all: return "Tümü"
        case .reading: return "Okunuyor"
        case .unread: return "Okunmadı"
        case .finished: return "Bitti"
        case .tag(let t): return t
        }
    }
    func matches(_ b: Book) -> Bool {
        switch self {
        case .all: return true
        case .reading: return !b.isUnread && !b.isFinished
        case .unread: return b.isUnread
        case .finished: return b.isFinished
        case .tag(let t): return b.tags?.contains(t) ?? false
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: ReaderSettings
    @EnvironmentObject var highlights: HighlightStore
    @EnvironmentObject var vocab: VocabStore
    @EnvironmentObject var bookmarks: BookmarkStore
    @EnvironmentObject var tracker: ReadingTracker
    @EnvironmentObject var sync: SyncManager
    @State private var path = NavigationPath()
    @State private var showImporter = false
    @State private var showSettings = false
    @State private var isImporting = false
    @State private var importError: String?
    @State private var filter: LibraryFilter = .all
    @State private var search = ""
    @State private var bookToDelete: Book?
    @State private var tagBook: Book?
    @State private var editBook: Book?
    @State private var wrapBook: Book?
    @State private var showArticleAlert = false
    @State private var articleURL = ""
    @State private var info: String?
    @State private var findingCover: UUID?
    #if DEBUG
    @State private var demoStudy: ChapterStudyModel?
    #endif

    private func addArticle() {
        let address = articleURL
        isImporting = true
        Task {
            do {
                let file = try await ArticleImporter.fetch(from: address)
                try await library.importBook(from: file)
                try? FileManager.default.removeItem(at: file)
            } catch {
                importError = error.localizedDescription
            }
            isImporting = false
            sync.syncSoon()
        }
    }

    private var visibleBooks: [Book] {
        library.sortedBooks.filter { b in
            filter.matches(b) && (search.isEmpty
                || b.title.localizedCaseInsensitiveContains(search)
                || b.author.localizedCaseInsensitiveContains(search)
                || (b.tags ?? []).contains { $0.localizedCaseInsensitiveContains(search) })
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    GoalBanner()
                    DailyHighlightCard { h in
                        highlights.pendingJump = h
                        path.append(h.bookID)
                    }
                    if !library.books.isEmpty { filterChips }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 170, maximum: 220), spacing: 28)], spacing: 32) {
                        ForEach(visibleBooks) { book in
                            NavigationLink(value: book.id) {
                                BookCard(book: book).opacity(findingCover == book.id ? 0.5 : 1)
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button { wrapBook = book } label: { Label("Kitap karnesi", systemImage: "chart.bar.doc.horizontal") }
                                Button { editBook = book } label: { Label("Bilgileri düzenle", systemImage: "pencil") }
                                Button { tagBook = book } label: { Label("Etiketler", systemImage: "tag") }
                                Button {
                                    Task {
                                        findingCover = book.id
                                        defer { findingCover = nil }
                                        do { try await library.fetchCover(book.id) }
                                        catch { info = error.localizedDescription }
                                    }
                                } label: { Label("Kapak ara", systemImage: "photo.badge.magnifyingglass") }
                                Button(role: .destructive) { bookToDelete = book } label: {
                                    Label("Sil", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
                .padding(28)
            }
            .searchable(text: $search, prompt: "Kitap, yazar veya etiket ara")
            .overlay {
                if library.books.isEmpty && !isImporting {
                    ContentUnavailableView {
                        Label("Kitaplık boş", systemImage: "books.vertical")
                    } description: {
                        Text("Sağ üstteki + ile EPUB veya PDF ekleyin.")
                    } actions: {
                        Button("Kitap ekle") { showImporter = true }.buttonStyle(.borderedProminent)
                    }
                } else if visibleBooks.isEmpty && !library.books.isEmpty {
                    ContentUnavailableView("Eşleşen kitap yok", systemImage: "magnifyingglass")
                }
                if isImporting { ProgressView("İçe aktarılıyor…").padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) }
            }
            .navigationTitle("Kitaplık")
            .navigationDestination(for: UUID.self) { id in ReaderContainer(bookID: id) }
            .navigationDestination(for: LibraryRoute.self) { route in
                switch route {
                case .vocab: VocabView()
                case .highlights: HighlightsView()
                case .stats: StatsView()
                }
            }
            .onOpenURL { url in
                // reader://book/<uuid> (widget'tan)
                guard url.scheme == "reader", url.host == "book",
                      let id = UUID(uuidString: url.lastPathComponent), library.book(id) != nil else { return }
                path = NavigationPath()
                path.append(id)
            }
            .onAppear {
                sync.attach(library: library, highlights: highlights, vocab: vocab, bookmarks: bookmarks, tracker: tracker, settings: settings)
                sync.syncSoon()
                WidgetBridge.update(library: library, tracker: tracker, settings: settings)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    SyncButton()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Sırala", selection: $library.sort) {
                            ForEach(LibraryStore.Sort.allCases) { Text($0.title).tag($0) }
                        }
                    } label: { Image(systemName: "arrow.up.arrow.down") }
                    .accessibilityLabel("Sırala")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(value: LibraryRoute.stats) { Image(systemName: "chart.bar.xaxis") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(value: LibraryRoute.highlights) { Image(systemName: "highlighter") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink(value: LibraryRoute.vocab) { Image(systemName: "text.book.closed") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { showImporter = true } label: { Label("Dosyadan ekle (EPUB, PDF)", systemImage: "doc.badge.plus") }
                        Button {
                            // Panoda bir web adresi varsa hazır gelsin
                            if let s = UIPasteboard.general.string, s.hasPrefix("http") { articleURL = s } else { articleURL = "" }
                            showArticleAlert = true
                        } label: { Label("Web adresinden ekle…", systemImage: "globe") }
                    } label: { Image(systemName: "plus") }
                }
            }
            .fileImporter(isPresented: $showImporter, allowedContentTypes: [.epub, .pdf], allowsMultipleSelection: true) { result in
                guard case .success(let urls) = result else { return }
                isImporting = true
                Task {
                    for url in urls {
                        do { try await library.importBook(from: url) }
                        catch { importError = "\(url.lastPathComponent): \(error.localizedDescription)" }
                    }
                    isImporting = false
                    sync.syncSoon()
                }
            }
            #if DEBUG
            .task {
                // Test için: -importPath /yol/kitap.epub
                let args = ProcessInfo.processInfo.arguments
                if let i = args.firstIndex(of: "-importPath"), i + 1 < args.count {
                    try? await library.importBook(from: URL(fileURLWithPath: args[i + 1]))
                }
                if args.contains("-studyDemo"), let b = library.books.first { demoStudy = ChapterStudyModel.demo(book: b) }
            }
            #endif
            #if DEBUG
            .sheet(item: $demoStudy) { StudyView(model: $0) }
            #endif
            .sheet(isPresented: $showSettings) { SettingsView() }
            .alert("Web adresinden ekle", isPresented: $showArticleAlert) {
                TextField("https://…", text: $articleURL)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                Button("Ekle") { addArticle() }
                Button("Vazgeç", role: .cancel) {}
            } message: {
                Text("PDF ya da EPUB bağlantısıysa dosya olduğu gibi eklenir; web sayfasıysa makale metni ayıklanıp tek bölümlük bir kitap olarak eklenir.")
            }
            .sheet(item: $tagBook) { b in TagEditor(bookID: b.id).presentationDetents([.medium, .large]) }
            .sheet(item: $editBook) { b in BookInfoEditor(book: b).presentationDetents([.medium]) }
            .sheet(item: $wrapBook) { b in BookWrapUpView(book: b).presentationDetents([.large]) }
            .confirmationDialog(
                bookToDelete.map { "“\($0.title)” silinsin mi?" } ?? "",
                isPresented: Binding(get: { bookToDelete != nil }, set: { if !$0 { bookToDelete = nil } }),
                titleVisibility: .visible,
                presenting: bookToDelete
            ) { b in
                Button("Kitabı sil, vurgu ve kelimeler kalsın") { remove(b, withNotes: false) }
                Button("Kitabı, vurguları ve kelimeleriyle sil", role: .destructive) { remove(b, withNotes: true) }
                if sync.isSignedIn {
                    Button("Her yerden sil (Drive ve diğer cihazlar dahil)", role: .destructive) { removeEverywhere(b) }
                }
                Button("Vazgeç", role: .cancel) {}
            } message: { _ in
                Text(sync.isSignedIn
                     ? "İlk iki seçenek kitabı yalnızca bu cihazdan kaldırır; Drive'da kalır ve “bulutta” olarak görünür. Okuma istatistikleri her durumda korunur."
                     : "Okuma istatistikleri her durumda korunur. Kalan vurgu ve kelimeler, kitabı sonradan tekrar eklerseniz yine görünür.")
            }
            .alert("İçe aktarma hatası", isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
                Button("Tamam", role: .cancel) {}
            } message: { Text(importError ?? "") }
            .alert("Bilgi", isPresented: Binding(get: { info != nil }, set: { if !$0 { info = nil } })) {
                Button("Tamam", role: .cancel) {}
            } message: { Text(info ?? "") }
        }
    }

    private func removeEverywhere(_ b: Book) {
        highlights.deleteBook(b.id)
        vocab.deleteBook(b.id)
        bookmarks.deleteBook(b.id)
        library.deleteEverywhere(b)
        sync.syncSoon()
        if case .tag(let t) = filter, !library.allTags.contains(t) { filter = .all }
    }

    private func remove(_ b: Book, withNotes: Bool) {
        if withNotes {
            highlights.deleteBook(b.id)
            vocab.deleteBook(b.id)
            bookmarks.deleteBook(b.id)
        }
        library.delete(b)
        if case .tag(let t) = filter, !library.allTags.contains(t) { filter = .all }
    }

    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach([LibraryFilter.all, .reading, .unread, .finished] + library.allTags.map { LibraryFilter.tag($0) }, id: \.self) { f in
                    Button { withAnimation { filter = f } } label: {
                        HStack(spacing: 4) {
                            if case .tag = f { Image(systemName: "tag.fill").font(.caption2) }
                            Text(f.title)
                        }
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .background(filter == f ? Color.accentColor : Color(.secondarySystemFill), in: Capsule())
                        .foregroundStyle(filter == f ? Color.white : Color.primary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

/// Bugünkü okuma hedefi ve seri.
struct GoalBanner: View {
    @EnvironmentObject var tracker: ReadingTracker
    @EnvironmentObject var settings: ReaderSettings

    var body: some View {
        let goal = Double(settings.dailyGoalMinutes) * 60
        let today = tracker.todaySeconds
        let streak = tracker.streak()
        if settings.dailyGoalMinutes > 0 || streak > 0 {
            HStack(spacing: 14) {
                if settings.dailyGoalMinutes > 0 {
                    ZStack {
                        Circle().stroke(Color.accentColor.opacity(0.2), lineWidth: 6)
                        Circle().trim(from: 0, to: min(1, today / max(goal, 1)))
                            .stroke(today >= goal ? Color.green : Color.accentColor, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                        Image(systemName: today >= goal ? "checkmark" : "book.fill").font(.caption).foregroundStyle(today >= goal ? .green : .accentColor)
                    }
                    .frame(width: 38, height: 38)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Bugün \(Duration.text(today)) / \(settings.dailyGoalMinutes) dk").font(.subheadline.weight(.semibold))
                        Text(today >= goal ? "Hedefi tamamladın 🎉" : "Hedefe \(Duration.text(goal - today)) kaldı")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if streak > 0 {
                    Label("\(streak) gün", systemImage: "flame.fill").font(.subheadline.weight(.semibold)).foregroundStyle(.orange)
                }
            }
            .padding(14)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        }
    }
}

struct TagEditor: View {
    let bookID: UUID
    @EnvironmentObject var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @State private var newTag = ""

    private var current: [String] { library.book(bookID)?.tags ?? [] }

    var body: some View {
        NavigationStack {
            List {
                Section("Yeni etiket") {
                    HStack {
                        TextField("ör. okunacak, teknik, roman", text: $newTag)
                            .submitLabel(.done)
                            .onSubmit(add)
                        Button("Ekle", action: add).disabled(newTag.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                Section("Etiketler") {
                    ForEach(library.allTags, id: \.self) { t in
                        Button {
                            var tags = current
                            if let i = tags.firstIndex(of: t) { tags.remove(at: i) } else { tags.append(t) }
                            library.setTags(bookID, tags)
                        } label: {
                            HStack {
                                Text(t).foregroundStyle(.primary)
                                Spacer()
                                if current.contains(t) { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                            }
                        }
                    }
                    if library.allTags.isEmpty { Text("Henüz etiket yok").foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("Etiketler")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Bitti") { dismiss() } } }
        }
    }

    private func add() {
        let t = newTag.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        var tags = current
        if !tags.contains(t) { tags.append(t) }
        library.setTags(bookID, tags)
        newTag = ""
    }
}

struct ReaderContainer: View {
    let bookID: UUID
    @EnvironmentObject var library: LibraryStore

    var body: some View {
        if let book = library.book(bookID) {
            Group {
                if book.isRemoteOnly {
                    CloudDownloadView(book: book)
                } else {
                    switch book.kind {
                    case .epub: EPUBLoader(book: book)
                    case .pdf: PDFLoader(book: book)
                    }
                }
            }
            .onAppear { if !book.isRemoteOnly { library.touch(bookID) } }
            .onChange(of: book.isRemoteOnly) { _, remote in if !remote { library.touch(bookID) } }
        } else {
            ContentUnavailableView("Kitap bulunamadı", systemImage: "questionmark.folder")
        }
    }
}

/// Bulutta duran kitap açılınca otomatik indirir.
struct CloudDownloadView: View {
    let book: Book
    @EnvironmentObject var sync: SyncManager
    @State private var error: String?

    var body: some View {
        VStack(spacing: 16) {
            if let error {
                Image(systemName: "exclamationmark.icloud").font(.system(size: 44)).foregroundStyle(.red)
                Text(error).multilineTextAlignment(.center).foregroundStyle(.secondary)
                Button("Tekrar dene") { Task { await load() } }.buttonStyle(.borderedProminent)
            } else {
                ProgressView().controlSize(.large)
                Text("“\(book.title)” Drive'dan indiriliyor…").foregroundStyle(.secondary)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { await load() }
    }

    private func load() async {
        error = nil
        do { try await sync.downloadBook(book.id) }
        catch { self.error = error.localizedDescription }
    }
}

struct BookCard: View {
    let book: Book

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            cover
                .frame(maxWidth: .infinity)
                .aspectRatio(0.7, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: .black.opacity(0.25), radius: 6, y: 3)
                .overlay(alignment: .topTrailing) {
                    if book.isRemoteOnly {
                        Image(systemName: "icloud.and.arrow.down.fill")
                            .font(.subheadline).foregroundStyle(.white)
                            .padding(7).background(.black.opacity(0.6), in: Circle()).padding(6)
                    }
                }
            Text(book.title).font(.subheadline.weight(.semibold)).lineLimit(2)
            if !book.author.isEmpty {
                Text(book.author).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            if book.isUnread {
                Text("Okunmadı")
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color.accentColor.opacity(0.15), in: Capsule())
                    .foregroundStyle(Color.accentColor)
            } else {
                ProgressView(value: book.progress).tint(book.isFinished ? .green : .accentColor)
                if let t = book.statusText {
                    Text(t).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
    }

    @ViewBuilder private var cover: some View {
        if let url = Paths.cover(for: book), let img = UIImage(contentsOfFile: url.path) {
            Image(uiImage: img).resizable().scaledToFill()
        } else {
            ZStack {
                LinearGradient(colors: [.indigo, .purple], startPoint: .topLeading, endPoint: .bottomTrailing)
                Text(book.title).font(.headline).foregroundStyle(.white).multilineTextAlignment(.center).padding(12)
            }
        }
    }
}


/// Kitap başlığı ve yazarını düzenler (gömülü bilgi bozuksa); diğer cihazlara senkronlanır.
struct BookInfoEditor: View {
    let book: Book
    @EnvironmentObject var library: LibraryStore
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var author: String

    init(book: Book) {
        self.book = book
        _title = State(initialValue: book.title)
        _author = State(initialValue: book.author)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Başlık") { TextField("Başlık", text: $title) }
                Section("Yazar") { TextField("Yazar", text: $author) }
            }
            .navigationTitle("Bilgileri düzenle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Vazgeç") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Kaydet") {
                        library.updateInfo(book.id, title: title, author: author)
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
