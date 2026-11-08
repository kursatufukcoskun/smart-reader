import SwiftUI
import AVFoundation
import NaturalLanguage

/// Kelime ve cümle telaffuzu: sistem sesiyle, dili bağlam cümlesinden tahmin ederek okur.
@MainActor
final class Pronouncer {
    static let shared = Pronouncer()
    private let synth = AVSpeechSynthesizer()

    /// İlk anlamlı örnekten baskın dil kodu (ör. "en", "de"); emin olamazsa nil.
    static func languageCode(_ samples: [String]) -> String? {
        for s in samples where s.count >= 3 {
            let rec = NLLanguageRecognizer()
            rec.processString(s)
            if let l = rec.dominantLanguage { return l.rawValue }
        }
        return nil
    }

    func speak(_ text: String, context: String? = nil) {
        let t = text.replacingOccurrences(of: "…", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        let u = AVSpeechUtterance(string: t)
        if let code = Self.languageCode([context ?? "", t])?.lowercased() {
            let base = code.split(separator: "-").first.map(String.init) ?? code
            let voices = AVSpeechSynthesisVoice.speechVoices()
            u.voice = voices.first { $0.language.lowercased() == code } ?? voices.first { $0.language.lowercased().hasPrefix(base) }
        }
        u.rate = 0.45
        synth.speak(u)
    }
}

/// 🔊 Küçük telaffuz düğmesi.
struct PronounceButton: View {
    let text: String
    var context: String?
    var symbol = "speaker.wave.2.fill"

    var body: some View {
        Button { Pronouncer.shared.speak(text, context: context) } label: { Image(systemName: symbol) }
            .buttonStyle(.plain)
            .foregroundStyle(Color.accentColor)
            .accessibilityLabel("Telaffuzu dinle")
    }
}
