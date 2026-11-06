import SwiftUI

/// EPUB'u arka planda açıp okuyucu modelini hazırlar.
struct EPUBLoader: View {
    let book: Book
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: ReaderSettings
    @EnvironmentObject var highlights: HighlightStore
    @EnvironmentObject var tracker: ReadingTracker
    @EnvironmentObject var bookmarks: BookmarkStore
    @State private var model: EPUBReaderModel?
    @State private var error: String?

    var body: some View {
        Group {
            if let model {
                EPUBReaderView(model: model)
            } else if let error {
                ContentUnavailableView("Kitap açılamadı", systemImage: "exclamationmark.triangle", description: Text(error))
            } else {
                ProgressView("Kitap açılıyor…")
            }
        }
        .task {
            guard model == nil else { return }
            do {
                let root = Paths.epubRoot(for: book)
                let pkg = try await Task.detached { try EPUBPackage.load(from: root) }.value
                let m = EPUBReaderModel(book: book, package: pkg, settings: settings, library: library, highlights: highlights, bookmarks: bookmarks, tracker: tracker)
                if let h = highlights.pendingJump, h.bookID == book.id {
                    highlights.pendingJump = nil
                    m.jumpAfterOpen(h)
                }
                model = m
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

struct EPUBReaderView: View {
    @ObservedObject var model: EPUBReaderModel
    @EnvironmentObject var settings: ReaderSettings
    @State private var showTOC = false
    @State private var showTheme = false
    @State private var showChat = false
    @State private var tocTab = 0
    @State private var barMode = 0
    /// Tam ekran okuma: araç çubukları varsayılan olarak gizli, ortaya dokununca açılır
    @State private var chrome = false
    @EnvironmentObject var tracker: ReadingTracker

    var body: some View {
        ZStack(alignment: .bottom) {
            settings.theme.color.ignoresSafeArea()
            WebViewHost(webView: model.webView)
                .ignoresSafeArea()
            if chrome {
                bottomBar
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .toolbar(chrome ? .visible : .hidden, for: .navigationBar)
        .statusBarHidden(!chrome)
        .persistentSystemOverlays(chrome ? .automatic : .hidden)
        .animation(.easeInOut(duration: 0.2), value: chrome)
        .readerChrome()
        .background(SwipeBackGuard(disabled: settings.readingMode == .paged))
        .overlay(alignment: .bottom) {
            if model.hasSelection {
                SelectionBar(
                    explain: { model.perform(.explain) },
                    translate: { model.perform(.translateParagraph) },
                    highlight: { model.perform(.highlight) },
                    note: { model.perform(.note) },
                    grammar: { model.perform(.grammar) })
                .padding(.bottom, chrome ? 70 : 28)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.hasSelection)
        .onAppear {
            model.start()
            model.onCenterTap = { chrome.toggle() }
        }
        .onDisappear { model.stop() }
        .navigationTitle(model.package.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        .toolbarBackground(settings.theme.color, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbarColorScheme(settings.theme.isDark ? .dark : .light, for: .navigationBar)
        .preferredColorScheme(settings.theme.isDark ? .dark : .light)
        .toast($model.toast)
        .sheet(item: $model.hardWords) { HardWordsView(model: $0).presentationDetents([.large]) }
        .sheet(item: $model.study) { StudyView(model: $0).presentationDetents([.large]) }
        .overlay(alignment: .bottom) {
            if model.atChapterEnd && !model.hasSelection {
                Button { model.prepareStudy() } label: {
                    Label("Bölümü pekiştir", systemImage: "graduationcap.fill")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 18).padding(.vertical, 11)
                        .background(.regularMaterial, in: Capsule())
                        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
                }
                .buttonStyle(.plain)
                .padding(.bottom, chrome ? 70 : 28)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.atChapterEnd)
        .overlay(alignment: .bottom) {
            if model.speech.isActive {
                SpeechBar(speech: model.speech)
                    .padding(.bottom, chrome ? 62 : 16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.speech.isActive)
        .onDisappear { model.speech.stop() }
        .sheet(isPresented: $showTOC) { tocSheet }
        .sheet(isPresented: $showChat) { ChatView(chat: model.chat).presentationDetents([.large]) }
        .sheet(item: $model.editing) { e in
            HighlightEditor(draft: e.highlight, focusNote: e.focusNote,
                            onSave: { model.saveHighlight($0) },
                            onDelete: { model.deleteHighlight($0) })
                .presentationDetents([.large])
        }
        .aiSheet(model.ai)
        .alert("Bilgi", isPresented: Binding(get: { model.alertMessage != nil }, set: { if !$0 { model.alertMessage = nil } })) {
            Button("Tamam", role: .cancel) {}
        } message: { Text(model.alertMessage ?? "") }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Button { showTOC = true } label: { Image(systemName: "list.bullet") }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { model.toggleBookmark() } label: { Image(systemName: "bookmark") }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button { model.translateVisible() } label: { Label("Ekrandakini çevir", systemImage: "character.bubble") }
                Button { model.toggleTranslations() } label: {
                    Label(model.translationsHidden ? "Çevirileri göster" : "Çevirileri gizle",
                          systemImage: model.translationsHidden ? "eye" : "eye.slash")
                }
                Button(role: .destructive) { model.clearTranslations() } label: { Label("Çevirileri sil", systemImage: "trash") }
            } label: {
                if model.isTranslatingPage { ProgressView() } else { Image(systemName: "character.bubble") }
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { model.toggleSpeech() } label: {
                Image(systemName: model.speech.isActive ? "speaker.wave.2.fill" : "speaker.wave.2")
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { showChat = true } label: { Image(systemName: "bubble.left.and.text.bubble.right") }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button { model.summarizeChapter() } label: { Label("Bölümü özetle", systemImage: "text.alignleft") }
                Button { model.prepareStudy() } label: { Label("Bölümü pekiştir (özet + quiz)", systemImage: "graduationcap") }
                Button { model.prepareHardWords() } label: { Label("Zor kelimeler", systemImage: "list.bullet.rectangle") }
            } label: { Image(systemName: "sparkles") }
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { showTheme = true } label: { Image(systemName: "textformat.size") }
                .popover(isPresented: $showTheme) {
                    ThemePanel().presentationCompactAdaptation(.popover)
                }
        }
    }

    private var bottomBar: some View {
        HStack {
            Button { model.previousChapter() } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                .disabled(model.chapterIndex == 0)
            Spacer()
            Button { barMode = (barMode + 1) % 3; tracker.touch() } label: {
                Text(barText)
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(settings.theme.textColor.opacity(0.6))
                    .frame(minWidth: 220, minHeight: 44)
            }
            .buttonStyle(.plain)
            Spacer()
            Button { model.nextChapter() } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                .disabled(model.chapterIndex >= model.package.spine.count - 1)
        }
        .padding(.horizontal, 12)
        .frame(height: 50)
        .background(settings.theme.color)
    }

    /// Alt çubuk: dokununca değişir (sayfa+bölüm süresi → kitap süresi → bölüm/sayfa).
    private var barText: String {
        let b = model.book
        let pages = b.pageText.map { "\($0) · %\(Int((b.progress * 100).rounded()))" } ?? chapterLabel
        let slow = tracker.hasSpeedData ? "" : " (tahmini)"
        switch barMode {
        case 0:
            guard b.chapterWords != nil else { return chapterLabel }
            return "\(pages) · bölüm ~\(Duration.text(tracker.secondsToRead(words: model.chapterWordsLeft)))"
        case 1:
            guard b.chapterWords != nil else { return chapterLabel }
            return "Kitap bitimine ~\(Duration.text(tracker.secondsToRead(words: model.bookWordsLeft)))\(slow)"
        default:
            return chapterLabel
        }
    }

    private var chapterLabel: String {
        var t = "Bölüm \(model.chapterIndex + 1) / \(model.package.spine.count)"
        if settings.readingMode == .paged, model.pageTotal > 0 {
            t += "  ·  Sayfa \(model.pageCur) / \(model.pageTotal)"
        }
        return t
    }

    private var tocSheet: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $tocTab) {
                    Text("İçindekiler").tag(0)
                    Text("Vurgular").tag(1)
                    Text("Yer imleri").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(12)

                if tocTab == 0 {
                    List(model.package.toc) { entry in
                        Button {
                            model.openTOC(entry)
                            showTOC = false
                        } label: {
                            Text(entry.title)
                                .padding(.leading, CGFloat(entry.level) * 18)
                                .foregroundStyle(entry.spineIndex == model.chapterIndex ? Color.accentColor : Color.primary)
                        }
                    }
                } else if tocTab == 1 {
                    BookHighlightsList(
                        bookID: model.book.id,
                        onSelect: { h in model.goTo(h); showTOC = false },
                        onDelete: { model.deleteHighlight($0) })
                } else {
                    BookmarkList(
                        bookID: model.book.id,
                        onSelect: { b in model.goTo(b); showTOC = false },
                        onDelete: { model.bookmarks.delete([$0.id]) })
                }
            }
            .navigationTitle(["İçindekiler", "Vurgular ve notlar", "Yer imleri"][tocTab])
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Kapat") { showTOC = false } }
            }
        }
    }
}
