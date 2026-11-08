import SwiftUI
import WebKit

// MARK: - AI panel

struct AIPanelView: View {
    @ObservedObject var ai: AIAssistant
    @EnvironmentObject var vocab: VocabStore

    private var rendered: AttributedString {
        let opts = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: ai.text, options: opts)) ?? AttributedString(ai.text)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let err = ai.error {
                        Label(err, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                        Button("Tekrar dene") { ai.retry() }
                            .buttonStyle(.borderedProminent)
                    }
                    if ai.text.isEmpty && ai.isStreaming && ai.error == nil {
                        HStack { ProgressView(); Text("Yapay zekâ düşünüyor…").foregroundStyle(.secondary) }
                    }
                    Text(rendered)
                        .font(.system(size: 18))
                        .lineSpacing(5)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let draft = ai.vocabDraft, !ai.isStreaming, !ai.text.isEmpty, ai.error == nil {
                        let saved = vocab.contains(draft)
                        HStack(spacing: 14) {
                            Button {
                                vocab.add(draft, explanation: ai.text)
                            } label: {
                                Label(saved ? "Defterde (güncelle)" : "Deftere ekle",
                                      systemImage: saved ? "checkmark.circle.fill" : "plus.circle.fill")
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(saved ? .green : .accentColor)
                            PronounceButton(text: draft.word, context: draft.paragraph).font(.title2)
                        }
                    }
                }
                .padding(20)
            }
            .navigationTitle(ai.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Kapat") { ai.isPresented = false }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if ai.isStreaming { ProgressView() }
                    Button {
                        UIPasteboard.general.string = ai.text
                    } label: { Image(systemName: "doc.on.doc") }
                    .disabled(ai.text.isEmpty)
                }
            }
        }
    }
}

private struct AISheet: ViewModifier {
    @ObservedObject var ai: AIAssistant
    func body(content: Content) -> some View {
        content.sheet(isPresented: $ai.isPresented) {
            AIPanelView(ai: ai)
                .presentationDetents([.fraction(0.45), .large])
                .presentationBackgroundInteraction(.enabled(upThrough: .fraction(0.45)))
                .presentationDragIndicator(.visible)
        }
    }
}

extension View {
    func aiSheet(_ ai: AIAssistant) -> some View { modifier(AISheet(ai: ai)) }
}

// MARK: - Seçim çubuğu

struct SelectionBar: View {
    var xray: () -> Void
    var dictionary: () -> Void
    var explain: () -> Void
    var translate: () -> Void
    var highlight: () -> Void
    var note: () -> Void
    var grammar: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: dictionary) {
                Image(systemName: "character.book.closed").padding(.horizontal, 16).padding(.vertical, 12)
            }
            .accessibilityLabel("Sözlük")
            Divider().frame(height: 24)
            Button(action: xray) {
                Image(systemName: "person.text.rectangle").padding(.horizontal, 16).padding(.vertical, 12)
            }
            .accessibilityLabel("Kim/Ne bu?")
            Divider().frame(height: 24)
            Button(action: explain) {
                Label("Açıkla", systemImage: "text.magnifyingglass")
                    .padding(.horizontal, 18).padding(.vertical, 12)
            }
            Divider().frame(height: 24)
            Button(action: translate) {
                Label("Paragrafı Çevir", systemImage: "character.bubble")
                    .padding(.horizontal, 18).padding(.vertical, 12)
            }
            Divider().frame(height: 24)
            Button(action: highlight) {
                Image(systemName: "highlighter").padding(.horizontal, 16).padding(.vertical, 12)
            }
            Divider().frame(height: 24)
            Button(action: note) {
                Image(systemName: "note.text.badge.plus").padding(.horizontal, 16).padding(.vertical, 12)
            }
            Divider().frame(height: 24)
            Button(action: grammar) {
                Image(systemName: "text.badge.checkmark").padding(.horizontal, 16).padding(.vertical, 12)
            }
        }
        .font(.system(size: 16, weight: .medium))
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }
}

// MARK: - Tema paneli

struct ThemePanel: View {
    @EnvironmentObject var settings: ReaderSettings
    var showsTextControls = true

    private var systemIsDark: Bool { SystemAppearance.isDark }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Toggle("Sistem görünümüyle eşle", isOn: Binding(
                get: { settings.followSystem },
                set: { settings.setFollowSystem($0, systemDark: systemIsDark) }))
            if settings.followSystem {
                Text("Şu an \(systemIsDark ? "koyu" : "açık") görünüm: seçtiğin tema bu görünüm için kaydedilir; görünüm değişince diğeri otomatik gelir.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            HStack(spacing: 10) {
                ForEach(ReaderTheme.allCases) { t in
                    Button { settings.chooseTheme(t) } label: {
                        VStack(spacing: 6) {
                            Circle()
                                .fill(t.color)
                                .overlay(Circle().stroke(settings.theme == t ? Color.accentColor : .gray.opacity(0.4), lineWidth: settings.theme == t ? 3 : 1))
                                .overlay(Text("Aa").font(.system(size: 15, weight: .semibold)).foregroundStyle(t.textColor))
                                .frame(width: 46, height: 46)
                            Text(t.title).font(.caption).foregroundStyle(.primary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            HStack {
                Image(systemName: "sun.max")
                Slider(value: $settings.warmth, in: 0...1)
                Image(systemName: "flame.fill").foregroundStyle(.orange)
            }
            Divider()
            VStack(alignment: .leading, spacing: 10) {
                Picker("Çeviri düzeni", selection: $settings.translationLayout) {
                    ForEach(TranslationLayout.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                Text("Altında/yan yana: orijinalin yanında görünür. Yerine: orijinal gizlenir, çeviri ikonuna dokununca orijinali görürsün (dar ekranda daha rahat).")
                    .font(.caption).foregroundStyle(.secondary)
                Picker("Kolon", selection: $settings.columns) {
                    ForEach(ColumnMode.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                Text(settings.columns == .double
                     ? "EPUB'da \"Sayfa sayfa\" modunda iki sütun yan yana; PDF'te iki sayfa yan yana görünür."
                     : "Tek sütun / tek sayfa.")
                    .font(.caption).foregroundStyle(.secondary)
                Picker("Okuma modu", selection: $settings.readingMode) {
                    ForEach(ReadingMode.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                if settings.readingMode == .scroll {
                    Toggle("Kenara dokununca sayfa atla", isOn: $settings.edgeTap)
                } else {
                    Text("Sağ/sol kenara dokunun veya yana kaydırın.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Toggle("Okurken ekranı açık tut", isOn: $settings.keepAwake)
            }
            if !showsTextControls {
                Toggle("Kenar boşluklarını kırp", isOn: $settings.cropMargins)
            }
            if showsTextControls {
                Divider()
                Picker("Yazı tipi", selection: $settings.font) {
                    ForEach(ReaderFont.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.menu)

                HStack {
                    Image(systemName: "textformat.size.smaller")
                    Slider(value: $settings.fontScale, in: 0.7...2.0, step: 0.05)
                    Image(systemName: "textformat.size.larger")
                }
                HStack {
                    Image(systemName: "text.justify.leading")
                    Slider(value: $settings.lineHeight, in: 1.2...2.2, step: 0.05)
                    Image(systemName: "text.justify")
                }
                HStack {
                    Image(systemName: "arrow.left.and.right.square")
                    Slider(value: $settings.margin, in: 2...22, step: 1)
                    Image(systemName: "arrow.left.and.right.square.fill")
                }
                Toggle("Biyonik okuma (kelime başları kalın)", isOn: $settings.bionic)
                Toggle("Satır odaklayıcı (şerit dışını karart)", isOn: $settings.readingRuler)
                Picker("İpucu yoğunluğu", selection: $settings.glossLevel) {
                    ForEach(GlossLevel.allCases) { Text("Kelime ipucu: \($0.title)").tag($0) }
                }
                .pickerStyle(.menu)
                Picker("Hizalama", selection: $settings.textAlign) {
                    ForEach(TextAlignMode.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                Picker("Tireleme", selection: $settings.hyphenation) {
                    ForEach(HyphenMode.allCases) { Text("Tireleme: \($0.title)").tag($0) }
                }
                .pickerStyle(.menu)
            }
        }
        .padding(20)
        .frame(width: 340)
    }
}

// MARK: - Göz konforu: e-ink gri tonlama + sıcak ışık filtresi

private struct ReaderChrome: ViewModifier {
    @EnvironmentObject var settings: ReaderSettings

    func body(content: Content) -> some View {
        content
            .grayscale(settings.theme.isEink ? 1 : 0)
            .overlay {
                if settings.warmth > 0 {
                    Color(red: 1.0, green: 0.62, blue: 0.25)
                        .opacity(0.08 + settings.warmth * 0.30)
                        .blendMode(.multiply)
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                }
            }
    }
}

extension View {
    func readerChrome() -> some View { modifier(ReaderChrome()) }
}

// MARK: - Sayfa modunda "soldan sağa kaydırınca kitaptan çık" hareketini kapatır

struct SwipeBackGuard: UIViewControllerRepresentable {
    var disabled: Bool

    func makeUIViewController(context: Context) -> Holder { Holder() }
    func updateUIViewController(_ vc: Holder, context: Context) {
        vc.disabled = disabled
        vc.apply()
    }

    final class Holder: UIViewController {
        var disabled = false

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            apply()
        }
        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            setEnabled(true)   // kitaplığa dönünce normal hale getir
        }
        func apply() { setEnabled(!disabled) }

        private func setEnabled(_ on: Bool) {
            guard let nav = navigationController else { return }
            nav.interactivePopGestureRecognizer?.isEnabled = on
            if #available(iOS 26.0, *) { nav.interactiveContentPopGestureRecognizer?.isEnabled = on }
        }
    }
}

// MARK: - WKWebView sarmalayıcı

struct WebViewHost: UIViewRepresentable {
    let webView: WKWebView
    func makeUIView(context: Context) -> WKWebView { webView }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

/// Zayıf referanslı script handler (WKUserContentController güçlü tutar).
final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

func jsString(_ s: String) -> String {
    guard let data = try? JSONEncoder().encode(s), let str = String(data: data, encoding: .utf8) else { return "\"\"" }
    return str
}


/// Başka cihazda daha yeni bir konum varsa çıkan soru.
struct RemoteProgressBar: View {
    var label: String
    var accept: () -> Void
    var keep: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text("Diğer cihazda kaldığın yer").font(.subheadline.weight(.semibold))
                Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Button(action: keep) {
                Text("Burada kal")
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Color.secondary.opacity(0.18), in: Capsule())
            }
            Button(action: accept) {
                Text("Oraya geç")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Color.accentColor, in: Capsule())
            }
        }
        .buttonStyle(.plain)
        .padding(.leading, 18).padding(.trailing, 10).padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }
}

/// Başka bir yere atlayınca çıkan soru: kalıcı mı, geçici mi?
struct JumpBar: View {
    /// Dönülecek yerin etiketi ("Bölüm 3", "Sayfa 12")
    var from: String
    var keep: () -> Void
    var back: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "line.3.horizontal")
                .font(.caption.weight(.bold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text("Buraya atladın").font(.subheadline.weight(.semibold))
                Text("Kalıcı olsun mu?").font(.caption).foregroundStyle(.secondary)
            }
            Button(action: back) {
                Label("Geri dön", systemImage: "arrow.uturn.backward")
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Color.secondary.opacity(0.18), in: Capsule())
            }
            .accessibilityHint(from)
            Button(action: keep) {
                Text("Burada kal")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Color.accentColor, in: Capsule())
            }
        }
        .buttonStyle(.plain)
        .padding(.leading, 14).padding(.trailing, 10).padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }
}

/// Sürüklenebilir "Buraya atladın" çubuğu: yazıyı kapatıyorsa tutup başka yere bırakılır, yeri hatırlanır.
/// Uzun basınca "Konumu sıfırla" çıkar.
struct DraggableJumpBar: View {
    var from: String
    var keep: () -> Void
    var back: () -> Void
    /// Çubuğun varsayılan (alt orta) konumunun alt boşluğu
    var bottomPadding: CGFloat

    @AppStorage("jumpBarX") private var savedX: Double = 0
    @AppStorage("jumpBarY") private var savedY: Double = 0
    @GestureState private var drag: CGSize = .zero
    @State private var size = CGSize(width: 330, height: 52)

    private static var windowSize: CGSize {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first?.keyWindow?.bounds.size ?? UIScreen.main.bounds.size
    }

    /// Çubuk pencerenin dışına çıkmasın (Mac'te pencere küçülse de erişilebilir kalır).
    private func clamp(_ x: Double, _ y: Double) -> CGSize {
        let win = Self.windowSize
        let maxX = max(0, (win.width - size.width) / 2 - 8)
        let minY = -max(0, win.height - bottomPadding - size.height - 60)
        let maxY = max(0, bottomPadding - 8)
        return CGSize(width: min(max(x, -maxX), maxX), height: min(max(y, minY), maxY))
    }

    var body: some View {
        let off = clamp(savedX + drag.width, savedY + drag.height)
        JumpBar(from: from, keep: keep, back: back)
            .background(GeometryReader { g in
                Color.clear
                    .onAppear { size = g.size }
                    .onChange(of: g.size) { _, s in size = s }
            })
            .padding(.bottom, bottomPadding)
            .offset(off)
            .gesture(
                DragGesture(minimumDistance: 6)
                    .updating($drag) { v, state, _ in state = v.translation }
                    .onEnded { v in
                        let c = clamp(savedX + v.translation.width, savedY + v.translation.height)
                        savedX = c.width
                        savedY = c.height
                    })
            .contextMenu {
                Button { savedX = 0; savedY = 0 } label: { Label("Konumu sıfırla", systemImage: "arrow.counterclockwise") }
            }
    }
}


/// Sistemin açık/koyu görünümü (okuyucudaki `preferredColorScheme` geçersiz kılmasından etkilenmez: ekran düzeyinde okunur).
enum SystemAppearance {
    static var isDark: Bool {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        return (scene?.screen.traitCollection.userInterfaceStyle ?? UITraitCollection.current.userInterfaceStyle) == .dark
    }
}


/// Okuyucu açıkken ekranın kendiliğinden kapanmasını engeller (ayardan kapatılabilir; sesli okurken ekran kapanabilir).
struct KeepScreenAwake: ViewModifier {
    @EnvironmentObject var settings: ReaderSettings
    @ObservedObject var speech: SpeechController

    private func apply() {
        UIApplication.shared.isIdleTimerDisabled = settings.keepAwake && !speech.isActive
    }

    func body(content: Content) -> some View {
        content
            .onAppear { apply() }
            .onChange(of: settings.keepAwake) { _, _ in apply() }
            .onChange(of: speech.isActive) { _, _ in apply() }
            .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }
}

extension View {
    func keepScreenAwake(speech: SpeechController) -> some View { modifier(KeepScreenAwake(speech: speech)) }
}


/// Kitaba uzun aradan sonra dönünce çıkan "son kaldığın yeri hatırlat" önerisi.
struct ResumeBar: View {
    var days: Int
    var ask: () -> Void
    var dismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text("\(days) gün aradan sonra döndün").font(.subheadline.weight(.semibold))
                Text("Kaldığın yeri 3 cümleyle hatırlatayım mı?").font(.caption).foregroundStyle(.secondary)
            }
            Button(action: ask) {
                Text("Hatırlat")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Color.accentColor, in: Capsule())
            }
            Button(action: dismiss) {
                Image(systemName: "xmark.circle.fill").font(.system(size: 20)).foregroundStyle(.secondary)
            }
            .accessibilityLabel("Kapat")
        }
        .buttonStyle(.plain)
        .padding(.leading, 18).padding(.trailing, 10).padding(.vertical, 8)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    }
}


/// Okuyucu içeriğinin yok sayacağı güvenli alan kenarları. Mac'te (Catalyst) üst kenar yok sayılmaz:
/// sistemin başlık çubuğu (kapat/küçült/büyüt düğmeleri) içeriğin altında kalmasın diye içerik onun altından başlar.
let readerContentIgnoredEdges: Edge.Set = {
    #if targetEnvironment(macCatalyst)
    return [.bottom, .horizontal]
    #else
    return .all
    #endif
}()
