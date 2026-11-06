import SwiftUI

extension Color {
    init(hex: String) {
        var s = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        if s.count == 3 { s = s.map { "\($0)\($0)" }.joined() }
        var v: UInt64 = 0
        Scanner(string: s).scanHexInt64(&v)
        self.init(
            red: Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8) & 0xFF) / 255,
            blue: Double(v & 0xFF) / 255
        )
    }
}

enum ReaderTheme: String, CaseIterable, Identifiable {
    case light, sepia, dark, black, eink
    var id: String { rawValue }

    var title: String {
        switch self {
        case .light: return "Açık"
        case .sepia: return "Sepya"
        case .dark: return "Koyu"
        case .black: return "Siyah"
        case .eink: return "E-ink"
        }
    }
    var bgHex: String {
        switch self {
        case .light: return "#FFFFFF"
        case .sepia: return "#F4ECD8"
        case .dark: return "#1C1C1E"
        case .black: return "#000000"
        case .eink: return "#E8E7E2"
        }
    }
    var textHex: String {
        switch self {
        case .light: return "#1A1A1A"
        case .sepia: return "#3B2F1E"
        case .dark: return "#D8D8DA"
        case .black: return "#B8B8BC"
        case .eink: return "#161616"
        }
    }
    var linkHex: String {
        switch self {
        case .light: return "#0A66C2"
        case .sepia: return "#8A4B08"
        case .dark: return "#7DB7FF"
        case .black: return "#6AA8F5"
        case .eink: return "#000000"
        }
    }
    var translationHex: String {
        switch self {
        case .light: return "#9A3412"
        case .sepia: return "#7C2D12"
        case .dark: return "#FDBA74"
        case .black: return "#FDBA74"
        case .eink: return "#2E2E2E"
        }
    }
    var isDark: Bool { self == .dark || self == .black }
    var isEink: Bool { self == .eink }
    var color: Color { Color(hex: bgHex) }
    var textColor: Color { Color(hex: textHex) }
}

enum ReadingMode: String, CaseIterable, Identifiable {
    case scroll, paged
    var id: String { rawValue }
    var title: String {
        switch self {
        case .scroll: return "Sonsuz kaydırma"
        case .paged: return "Sayfa sayfa"
        }
    }
}

enum TranslationLayout: String, CaseIterable, Identifiable {
    case below, side
    var id: String { rawValue }
    var title: String { self == .below ? "Altında" : "Yan yana" }
}

enum ColumnMode: String, CaseIterable, Identifiable {
    case single, double
    var id: String { rawValue }
    var title: String { self == .single ? "Tek kolon" : "Çift kolon" }
}

enum ReaderFont: String, CaseIterable, Identifiable {
    case bookerly, georgia, palatino, iowan, newYork, sans, avenir
    var id: String { rawValue }

    var title: String {
        switch self {
        case .bookerly: return "Bookerly"
        case .georgia: return "Georgia"
        case .palatino: return "Palatino"
        case .iowan: return "Iowan Old Style"
        case .newYork: return "New York"
        case .sans: return "SF Pro"
        case .avenir: return "Avenir Next"
        }
    }
    var css: String {
        switch self {
        case .bookerly: return "'BookerlyReader', Georgia, serif"
        case .georgia: return "Georgia, 'Times New Roman', serif"
        case .palatino: return "Palatino, 'Palatino Linotype', serif"
        case .iowan: return "'Iowan Old Style', Georgia, serif"
        case .newYork: return "ui-serif, 'New York', Georgia, serif"
        case .sans: return "-apple-system, 'SF Pro Text', Helvetica, sans-serif"
        case .avenir: return "'Avenir Next', Helvetica, sans-serif"
        }
    }
}

final class ReaderSettings: ObservableObject {
    private let d = UserDefaults.standard

    @Published var theme: ReaderTheme { didSet { d.set(theme.rawValue, forKey: "theme") } }
    @Published var font: ReaderFont { didSet { d.set(font.rawValue, forKey: "font") } }
    /// 1.0 = 18px taban
    @Published var fontScale: Double { didSet { d.set(fontScale, forKey: "fontScale") } }
    @Published var lineHeight: Double { didSet { d.set(lineHeight, forKey: "lineHeight") } }
    /// Yan boşluk, genişliğin yüzdesi
    @Published var margin: Double { didSet { d.set(margin, forKey: "margin") } }
    @Published var provider: LLMProvider { didSet { d.set(provider.rawValue, forKey: "provider") } }
    // Claude modelleri (eski anahtarlar korunuyor)
    @Published var fastModel: String { didSet { d.set(fastModel, forKey: "fastModel") } }
    @Published var smartModel: String { didSet { d.set(smartModel, forKey: "smartModel") } }
    // Gemini modelleri
    @Published var geminiFastModel: String { didSet { d.set(geminiFastModel, forKey: "geminiFastModel") } }
    @Published var geminiSmartModel: String { didSet { d.set(geminiSmartModel, forKey: "geminiSmartModel") } }
    @Published var translationLayout: TranslationLayout { didSet { d.set(translationLayout.rawValue, forKey: "translationLayout") } }
    /// Günlük okuma hedefi (dakika), 0 = kapalı
    @Published var dailyGoalMinutes: Int { didSet { d.set(dailyGoalMinutes, forKey: "dailyGoalMinutes") } }
    @Published var reminderEnabled: Bool { didSet { d.set(reminderEnabled, forKey: "reminderEnabled") } }
    @Published var reminderHour: Int { didSet { d.set(reminderHour, forKey: "reminderHour") } }
    @Published var reminderMinute: Int { didSet { d.set(reminderMinute, forKey: "reminderMinute") } }
    @Published var vocabReminderEnabled: Bool { didSet { d.set(vocabReminderEnabled, forKey: "vocabReminderEnabled") } }
    @Published var columns: ColumnMode { didSet { d.set(columns.rawValue, forKey: "columns") } }
    @Published var highlightColor: HighlightColor { didSet { d.set(highlightColor.rawValue, forKey: "highlightColor") } }
    @Published var readingMode: ReadingMode { didSet { d.set(readingMode.rawValue, forKey: "readingMode") } }
    /// Kaydırma modunda kenara dokununca ekran boyu atlama
    @Published var edgeTap: Bool { didSet { d.set(edgeTap, forKey: "edgeTap") } }
    /// Sıcak ışık filtresi (0 = kapalı ... 1 = en sıcak)
    @Published var warmth: Double { didSet { d.set(warmth, forKey: "warmth") } }
    /// Çeviri, sözlük ve açıklamaların dili
    @Published var targetLanguage: TargetLanguage { didSet { d.set(targetLanguage.rawValue, forKey: "targetLanguage") } }
    /// Seslendirme dili; boşsa hedef dili izler
    @Published var ttsLanguageCode: String { didSet { d.set(ttsLanguageCode, forKey: "ttsLanguageCode") } }
    @Published var ttsEngine: TTSEngine { didSet { d.set(ttsEngine.rawValue, forKey: "ttsEngine") } }
    /// Konuşma hızı çarpanı (1.0 = normal)
    @Published var ttsRate: Double { didSet { d.set(ttsRate, forKey: "ttsRate") } }
    @Published var geminiVoice: String { didSet { d.set(geminiVoice, forKey: "geminiVoice") } }
    @Published var geminiTTSModel: String { didSet { d.set(geminiTTSModel, forKey: "geminiTTSModel") } }
    /// Aylık Gemini ses bütçesi (USD), 0 = sınırsız
    @Published var ttsBudgetUSD: Double { didSet { d.set(ttsBudgetUSD, forKey: "ttsBudgetUSD") } }
    /// Çevrilerek okunan paragrafın çevirisini sayfaya da ekle (sayfa düzenini kaydırır, varsayılan kapalı)
    @Published var ttsShowInPage: Bool { didSet { d.set(ttsShowInPage, forKey: "ttsShowInPage") } }

    /// Seslendirmenin gerçek dili.
    var ttsLanguage: TargetLanguage { TargetLanguage(rawValue: ttsLanguageCode) ?? targetLanguage }

    init() {
        theme = ReaderTheme(rawValue: d.string(forKey: "theme") ?? "") ?? .sepia
        font = ReaderFont(rawValue: d.string(forKey: "font") ?? "") ?? .bookerly
        let fs = d.double(forKey: "fontScale")
        fontScale = fs == 0 ? 1.1 : fs
        let lh = d.double(forKey: "lineHeight")
        lineHeight = lh == 0 ? 1.55 : lh
        let m = d.double(forKey: "margin")
        margin = m == 0 ? 8 : m
        fastModel = d.string(forKey: "fastModel") ?? "claude-haiku-5-5"
        smartModel = d.string(forKey: "smartModel") ?? "claude-sonnet-5-5"
        provider = LLMProvider(rawValue: d.string(forKey: "provider") ?? "") ?? .claude
        geminiFastModel = d.string(forKey: "geminiFastModel") ?? "gemini-2.5-flash"
        geminiSmartModel = d.string(forKey: "geminiSmartModel") ?? "gemini-2.5-pro"
        warmth = d.double(forKey: "warmth")
        targetLanguage = TargetLanguage(rawValue: d.string(forKey: "targetLanguage") ?? "") ?? .tr
        ttsLanguageCode = d.string(forKey: "ttsLanguageCode") ?? ""
        ttsEngine = TTSEngine(rawValue: d.string(forKey: "ttsEngine") ?? "") ?? .system
        let tr = d.double(forKey: "ttsRate")
        ttsRate = tr == 0 ? 1.0 : tr
        geminiVoice = d.string(forKey: "geminiVoice") ?? "Kore"
        geminiTTSModel = d.string(forKey: "geminiTTSModel") ?? "gemini-3.8-flash-tts"
        ttsBudgetUSD = d.object(forKey: "ttsBudgetUSD") as? Double ?? 10
        ttsShowInPage = d.bool(forKey: "ttsShowInPage")
        readingMode = ReadingMode(rawValue: d.string(forKey: "readingMode") ?? "") ?? .scroll
        edgeTap = d.bool(forKey: "edgeTap")
        columns = ColumnMode(rawValue: d.string(forKey: "columns") ?? "") ?? .single
        translationLayout = TranslationLayout(rawValue: d.string(forKey: "translationLayout") ?? "") ?? .below
        dailyGoalMinutes = d.object(forKey: "dailyGoalMinutes") as? Int ?? 20
        reminderEnabled = d.bool(forKey: "reminderEnabled")
        reminderHour = d.object(forKey: "reminderHour") as? Int ?? 20
        reminderMinute = d.object(forKey: "reminderMinute") as? Int ?? 0
        vocabReminderEnabled = d.bool(forKey: "vocabReminderEnabled")
        highlightColor = HighlightColor(rawValue: d.string(forKey: "highlightColor") ?? "") ?? .yellow
    }

    /// Seçili sağlayıcı için hızlı/güçlü model yapılandırması.
    func config(_ tier: LLMTier) -> LLMConfig {
        switch (provider, tier) {
        case (.claude, .fast): return LLMConfig(provider: .claude, model: fastModel)
        case (.claude, .smart): return LLMConfig(provider: .claude, model: smartModel)
        case (.gemini, .fast): return LLMConfig(provider: .gemini, model: geminiFastModel)
        case (.gemini, .smart): return LLMConfig(provider: .gemini, model: geminiSmartModel)
        }
    }

    /// EPUB sayfasına enjekte edilen CSS.
    var css: String {
        let t = theme
        let imgFilter: String
        if t.isEink { imgFilter = "img, svg { filter: grayscale(1) contrast(1.2) !important; }" }
        else if t.isDark { imgFilter = "img { filter: brightness(0.85) contrast(1.05); }" }
        else { imgFilter = "" }
        // E-ink: animasyon yok, bağlantılar altı çizili siyah, tam sayfa atlama
        let noteLine = t.isDark ? "rgba(255,255,255,0.6)" : "rgba(0,0,0,0.45)"
        let einkCSS = t.isEink ? """
        * { transition: none !important; animation: none !important; scroll-behavior: auto !important; }
        body a, body a * { text-decoration: underline !important; }
        ::selection { background: rgba(0, 0, 0, 0.25) !important; }
        """ : ""
        let fontFace = font == .bookerly ? FontInstaller.bookerlyCSS : ""
        return """
        \(fontFace)
        html { -webkit-text-size-adjust: none !important; font-size: \(Int((18 * fontScale).rounded()))px !important; background: \(t.bgHex) !important; }
        body { background: \(t.bgHex) !important; color: \(t.textHex) !important;
               font-family: \(font.css) !important; font-size: 1rem !important;
               line-height: \(lineHeight) !important;
               margin: 0 !important; padding: 1.5rem \(margin)% 5rem !important; max-width: none !important; }
        body * { color: inherit !important; background-color: transparent !important;
                 font-family: inherit !important; line-height: inherit !important; }
        body a, body a * { color: \(t.linkHex) !important; }
        p { margin: 0 0 0.9em; }
        img, svg, video { max-width: 100% !important; height: auto !important; }
        \(imgFilter)
        ::selection { background: rgba(255, 190, 60, 0.45); }
        body .llm-tr { color: \(t.translationHex) !important; font-size: 0.93em; margin: -0.2em 0 1.1em; padding-left: 0.8em;
                       border-left: 3px solid \(t.translationHex); font-family: -apple-system, 'SF Pro Text', sans-serif !important; }
        .llm-hide .llm-tr { display: none !important; }
        .llm-row { display: grid; grid-template-columns: 1fr 1fr; gap: 1.4em; align-items: start; margin: 0 0 1.1em; }
        .llm-row > :first-child { margin: 0 !important; }
        body .llm-row > .llm-tr { margin: 0; }
        .llm-hide .llm-row { display: block; }
        .llm-hl { border-radius: 2px; }
        body .llm-speak { background-color: \(t.isDark ? "rgba(125,183,255,0.20)" : "rgba(255,196,0,0.24)") !important; border-radius: 4px; box-shadow: 0 0 0 4px \(t.isDark ? "rgba(125,183,255,0.20)" : "rgba(255,196,0,0.24)"); }
        body .llm-hl[data-color="yellow"] { background-color: rgba(255,214,10,0.42) !important; }
        body .llm-hl[data-color="green"] { background-color: rgba(52,199,89,0.38) !important; }
        body .llm-hl[data-color="blue"] { background-color: rgba(10,132,255,0.32) !important; }
        body .llm-hl[data-color="pink"] { background-color: rgba(255,55,95,0.32) !important; }
        body .llm-hl[data-color="orange"] { background-color: rgba(255,149,0,0.40) !important; }
        body .llm-hl[data-note="1"] { border-bottom: 2px solid \(noteLine); }
        \(einkCSS)
        """
    }
}
