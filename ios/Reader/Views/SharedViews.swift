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
                        Button {
                            vocab.add(draft, explanation: ai.text)
                        } label: {
                            Label(saved ? "Defterde (güncelle)" : "Deftere ekle",
                                  systemImage: saved ? "checkmark.circle.fill" : "plus.circle.fill")
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(saved ? .green : .accentColor)
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
    var explain: () -> Void
    var translate: () -> Void
    var highlight: () -> Void
    var note: () -> Void
    var grammar: () -> Void

    var body: some View {
        HStack(spacing: 0) {
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

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 10) {
                ForEach(ReaderTheme.allCases) { t in
                    Button { settings.theme = t } label: {
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
                Text("Sayfa çevirisi orijinalin altında ya da yanında (geniş ekranda) görünür.")
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
