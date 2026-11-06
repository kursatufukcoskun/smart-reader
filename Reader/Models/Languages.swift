import Foundation
import NaturalLanguage

/// Çeviri, sözlük ve seslendirme için seçilebilen diller.
enum TargetLanguage: String, CaseIterable, Identifiable, Codable, Sendable {
    case tr, en, de, fr, es, it, pt, nl, ru, ar, ja, ko, zh
    var id: String { rawValue }

    /// Ayarlarda görünen ad (kendi dilinde değil, Türkçe).
    var title: String {
        switch self {
        case .tr: return "Türkçe"
        case .en: return "İngilizce"
        case .de: return "Almanca"
        case .fr: return "Fransızca"
        case .es: return "İspanyolca"
        case .it: return "İtalyanca"
        case .pt: return "Portekizce"
        case .nl: return "Felemenkçe"
        case .ru: return "Rusça"
        case .ar: return "Arapça"
        case .ja: return "Japonca"
        case .ko: return "Korece"
        case .zh: return "Çince"
        }
    }

    /// İstemlerde (prompt) kullanılan İngilizce ad.
    var englishName: String {
        switch self {
        case .tr: return "Turkish"
        case .en: return "English"
        case .de: return "German"
        case .fr: return "French"
        case .es: return "Spanish"
        case .it: return "Italian"
        case .pt: return "Portuguese"
        case .nl: return "Dutch"
        case .ru: return "Russian"
        case .ar: return "Arabic"
        case .ja: return "Japanese"
        case .ko: return "Korean"
        case .zh: return "Chinese"
        }
    }

    /// AVSpeechSynthesisVoice için BCP-47 dil etiketi.
    var voiceTag: String {
        switch self {
        case .tr: return "tr-TR"
        case .en: return "en-US"
        case .de: return "de-DE"
        case .fr: return "fr-FR"
        case .es: return "es-ES"
        case .it: return "it-IT"
        case .pt: return "pt-BR"
        case .nl: return "nl-NL"
        case .ru: return "ru-RU"
        case .ar: return "ar-SA"
        case .ja: return "ja-JP"
        case .ko: return "ko-KR"
        case .zh: return "zh-CN"
        }
    }

    /// Önbellek anahtarına eklenen ek. Türkçe'de boş (eski önbellek korunur).
    var cacheSuffix: String { self == .tr ? "" : ":\(rawValue)" }

    /// Uygulamanın o an seçili hedef dili (her iş parçacığından okunabilir).
    static var current: TargetLanguage {
        TargetLanguage(rawValue: UserDefaults.standard.string(forKey: "targetLanguage") ?? "") ?? .tr
    }

    /// Metnin baskın dili; emin değilse nil.
    static func detect(_ text: String) -> TargetLanguage? {
        let letters = text.unicodeScalars.filter { CharacterSet.letters.contains($0) }.count
        guard letters >= 12 else { return nil }
        let rec = NLLanguageRecognizer()
        rec.processString(text)
        guard let lang = rec.dominantLanguage else { return nil }
        let base = lang.rawValue.split(separator: "-").first.map(String.init) ?? lang.rawValue
        if let m = TargetLanguage(rawValue: base) { return m }
        if lang.rawValue.hasPrefix("zh") { return .zh }
        return nil
    }
}

/// Seslendirme motoru.
enum TTSEngine: String, CaseIterable, Identifiable {
    case system, gemini
    var id: String { rawValue }
    var title: String { self == .system ? "Sistem sesi" : "Gemini (doğal ses)" }
}
