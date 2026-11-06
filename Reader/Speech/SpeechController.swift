import SwiftUI
import AVFoundation
import MediaPlayer

/// Okunacak bir paragraf.
struct SpeechUnit: Identifiable, Sendable {
    let id: Int
    let text: String
}

/// Okuyucunun (EPUB/PDF) seslendirmeye verdiği bağlantılar.
struct SpeechSource {
    /// Mevcut bölümün/sayfanın paragrafları ve ekranda görünen ilk paragrafın sırası.
    var paragraphs: () async -> (units: [SpeechUnit], start: Int)
    /// Okunan paragrafı işaretler (nil = işareti kaldır).
    var highlight: (Int?) -> Void
    /// Sonraki bölüme/sayfaya geçer; kitap bittiyse false.
    var advance: () async -> Bool
    var title: () -> String
    var subtitle: () -> String
    /// Çevrilerek okunan paragrafın çevirisini sayfada gösterir.
    var showTranslation: (Int, String) -> Void = { _, _ in }
}

/// Sistem sesi veya Gemini ile paragraf paragraf, tembel (önden birkaç paragraf hazırlayarak) okuma.
@MainActor
final class SpeechController: NSObject, ObservableObject, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate {
    enum State { case idle, playing, paused }

    @Published private(set) var state: State = .idle
    /// "Çevriliyor…" / "Ses hazırlanıyor…"
    @Published private(set) var busy: String?
    @Published var notice: String?
    @Published private(set) var index = 0
    @Published private(set) var count = 0
    /// Çevrilerek okunan paragrafın çevirisi (oynatıcıda altyazı olarak gösterilir).
    @Published private(set) var caption: String?

    weak var settings: ReaderSettings?
    var source: SpeechSource?

    private var units: [SpeechUnit] = []
    private var runner: Task<Void, Never>?
    private let synth = AVSpeechSynthesizer()
    private var player: AVAudioPlayer?
    private var systemCont: CheckedContinuation<Void, Never>?
    private var playerCont: CheckedContinuation<Void, Never>?
    private var currentUtterance: AVSpeechUtterance?
    private var textTasks: [Int: Task<String, Error>] = [:]
    private var audioTasks: [Int: Task<URL, Error>] = [:]
    /// Gemini bu oturumda hata verdiyse sistem sesine düşülür.
    private var geminiFailed = false
    /// Çeviri başarısız olduysa aynı paragraf, devam edilince yeniden denenir.
    private var retryCurrent = false
    /// Bölümün/sayfanın baskın dili: kısa paragraflarda (başlık, madde) tek tek tespit güvenilmez.
    private var sourceLang: TargetLanguage?
    private var remoteReady = false
    private let lookahead = 3

    override init() {
        super.init()
        synth.delegate = self
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            Task { @MainActor in self?.pause() }
        }
    }

    var isActive: Bool { state != .idle }

    // MARK: - Kontrol

    func start() {
        guard state == .idle, let source else { return }
        geminiFailed = false
        notice = nil
        configureSession()
        setupRemote()
        state = .playing
        runner = Task { [weak self] in
            let r = await source.paragraphs()
            guard let self, !Task.isCancelled else { return }
            if r.units.isEmpty {
                self.notice = "Bu sayfada okunacak metin yok."
                self.stop()
                return
            }
            self.units = r.units
            self.updateSourceLang()
            self.index = r.start
            self.count = r.units.count
            await self.run()
        }
    }

    func toggle() {
        switch state {
        case .idle: start()
        case .playing: pause()
        case .paused: resume()
        }
    }

    func pause() {
        guard state == .playing else { return }
        state = .paused
        if synth.isSpeaking { synth.pauseSpeaking(at: .immediate) }
        player?.pause()
        updateNowPlaying()
    }

    func resume() {
        guard state == .paused else { return }
        state = .playing
        if synth.isPaused { synth.continueSpeaking() }
        if let p = player, !p.isPlaying { p.play() }
        updateNowPlaying()
    }

    func stop() {
        runner?.cancel()
        runner = nil
        stopOutput()
        source?.highlight(nil)
        for t in textTasks.values { t.cancel() }
        for t in audioTasks.values { t.cancel() }
        textTasks = [:]
        audioTasks = [:]
        units = []
        busy = nil
        caption = nil
        state = .idle
        index = 0
        count = 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        setRemote(enabled: false)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Okuyucu başka yere gidildiğinde (ör. kullanıcı bölüm değiştirdi) çağrılır.
    func stopIfActive() { if state != .idle { stop() } }

    func skip(_ delta: Int) {
        guard state != .idle, !units.isEmpty else { return }
        index = max(0, min(units.count, index + delta))
        restart()
    }

    /// Ayar değişince (ses, dil, motor) sıradaki paragraflar yeniden hazırlansın.
    func settingsChanged() {
        guard state != .idle else { return }
        for t in textTasks.values { t.cancel() }
        for t in audioTasks.values { t.cancel() }
        textTasks = [:]
        audioTasks = [:]
    }

    private func restart() {
        runner?.cancel()
        stopOutput()
        if state == .paused { state = .playing }
        runner = Task { [weak self] in await self?.run() }
    }

    // MARK: - Ana döngü

    private func run() async {
        while !Task.isCancelled {
            if index >= units.count {
                guard let source, await source.advance(), !Task.isCancelled else { break }
                let r = await source.paragraphs()
                if Task.isCancelled { return }
                for t in textTasks.values { t.cancel() }
                for t in audioTasks.values { t.cancel() }
                textTasks = [:]
                audioTasks = [:]
                units = r.units
                updateSourceLang()
                count = units.count
                index = 0
                continue
            }
            source?.highlight(units[index].id)
            updateNowPlaying()
            await speakUnit(at: index)
            if Task.isCancelled { return }
            if retryCurrent {
                retryCurrent = false
                await waitWhilePaused()
                continue
            }
            index += 1
        }
        if !Task.isCancelled { stop() }
    }

    private func waitWhilePaused() async {
        while state == .paused && !Task.isCancelled { try? await Task.sleep(nanoseconds: 200_000_000) }
    }

    private func speakUnit(at i: Int) async {
        guard let settings else { return }
        let engine = effectiveEngine(settings)
        prefetch(from: i, engine: engine)
        do {
            if needsTranslation(units[i].text, settings) { busy = "Çevriliyor…" }
            let text: String
            do {
                text = try await textTask(i, settings).value
            } catch {
                // Çeviri olmadan orijinal dili okumak istenen şey değil: dur ve nedenini göster.
                busy = nil
                if Task.isCancelled { return }
                textTasks[i] = nil
                retryCurrent = true
                notice = "Çeviri yapılamadı, okuma duraklatıldı: \(error.localizedDescription)"
                pause()
                return
            }
            if Task.isCancelled { return }
            let translated = text != units[i].text
            caption = translated ? text : nil
            if translated && settings.ttsShowInPage { source?.showTranslation(units[i].id, text) }
            if engine == .gemini {
                let t = audioTask(i, text: text, settings)
                if !isReady(text: text, settings) { busy = "Ses hazırlanıyor…" }
                let url = try await t.value
                busy = nil
                if Task.isCancelled { return }
                await waitWhilePaused()
                if Task.isCancelled { return }
                await play(url, rate: settings.ttsRate)
            } else {
                busy = nil
                await waitWhilePaused()
                if Task.isCancelled { return }
                await speakSystem(text, settings)
            }
        } catch {
            busy = nil
            if Task.isCancelled { return }
            if engine == .gemini {
                geminiFailed = true
                notice = "Gemini sesi alınamadı, sistem sesine geçildi: \(error.localizedDescription)"
                let text = (try? await textTask(i, settings).value) ?? units[i].text
                await waitWhilePaused()
                if Task.isCancelled { return }
                await speakSystem(text, settings)
            } else {
                notice = error.localizedDescription
                // Çeviri başarısızsa orijinal metni oku
                await waitWhilePaused()
                if Task.isCancelled { return }
                await speakSystem(units[i].text, settings)
            }
        }
    }

    private func effectiveEngine(_ s: ReaderSettings) -> TTSEngine {
        guard s.ttsEngine == .gemini, !geminiFailed else { return .system }
        if s.ttsBudgetUSD > 0, TTSUsage.dollarsThisMonth(model: s.geminiTTSModel) >= s.ttsBudgetUSD {
            if !geminiFailed { notice = "Aylık Gemini ses bütçesi doldu, sistem sesine geçildi." }
            geminiFailed = true
            return .system
        }
        return .gemini
    }

    // MARK: - Çeviri ve hazırlık

    private func updateSourceLang() {
        sourceLang = TargetLanguage.detect(String(units.map(\.text).joined(separator: " ").prefix(4000)))
    }

    private func needsTranslation(_ text: String, _ s: ReaderSettings) -> Bool {
        if let d = sourceLang ?? TargetLanguage.detect(text) { return d != s.ttsLanguage }
        return false
    }

    private func textTask(_ i: Int, _ s: ReaderSettings) -> Task<String, Error> {
        if let t = textTasks[i] { return t }
        let text = units[i].text
        let target = s.ttsLanguage
        let cfg = s.config(.fast)
        let src = sourceLang
        let t = Task<String, Error> { try await Self.resolveText(text, target: target, cfg: cfg, chapterLang: src) }
        textTasks[i] = t
        return t
    }

    nonisolated private static func resolveText(_ text: String, target: TargetLanguage, cfg: LLMConfig, chapterLang: TargetLanguage?) async throws -> String {
        guard let d = chapterLang ?? TargetLanguage.detect(text), d != target else { return text }
        guard text.unicodeScalars.filter({ CharacterSet.letters.contains($0) }).count >= 2 else { return text }
        let key = ResponseCache.key("ttstr", cfg.baseID, target.rawValue, text)
        if let c = ResponseCache.shared.get(key) { return c }
        let out = try await LLMClient.complete(
            cfg, system: Prompts.translatorSystem(to: target),
            user: Prompts.translateParagraph(paragraph: text, to: target), maxTokens: 2048)
        let t = out.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return text }
        ResponseCache.shared.set(key, t)
        return t
    }

    private func isReady(text: String, _ s: ReaderSettings) -> Bool {
        FileManager.default.fileExists(atPath: GeminiTTS.cacheURL(text: text, voice: s.geminiVoice, model: s.geminiTTSModel).path)
    }

    private func audioTask(_ i: Int, text: String, _ s: ReaderSettings) -> Task<URL, Error> {
        if let t = audioTasks[i] { return t }
        let voice = s.geminiVoice, model = s.geminiTTSModel
        let t = Task<URL, Error> { try await GeminiTTS.audioFile(text: text, voice: voice, model: model) }
        audioTasks[i] = t
        return t
    }

    /// Sıradaki birkaç paragrafı arka planda hazırlar (tembel üretim penceresi).
    private func prefetch(from i: Int, engine: TTSEngine) {
        guard let settings else { return }
        for k in textTasks.keys where k < i { textTasks[k] = nil }
        for k in audioTasks.keys where k < i { audioTasks[k] = nil }
        let end = min(units.count - 1, i + lookahead)
        guard i + 1 <= end else { return }
        for j in (i + 1)...end {
            let t = textTask(j, settings)
            if engine == .gemini, audioTasks[j] == nil {
                let voice = settings.geminiVoice, model = settings.geminiTTSModel
                audioTasks[j] = Task<URL, Error> {
                    let text = try await t.value
                    return try await GeminiTTS.audioFile(text: text, voice: voice, model: model)
                }
            }
        }
    }

    // MARK: - Çıkış (sistem sesi / dosya)

    private func speakSystem(_ text: String, _ s: ReaderSettings) async {
        let u = AVSpeechUtterance(string: text)
        u.voice = Self.bestVoice(for: s.ttsLanguage)
        u.rate = Float(max(0.3, min(0.8, 0.5 + (s.ttsRate - 1) * 0.25)))
        u.postUtteranceDelay = 0.15
        currentUtterance = u
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            systemCont = c
            synth.speak(u)
            if state == .paused { synth.pauseSpeaking(at: .immediate) }
        }
    }

    private func play(_ url: URL, rate: Double) async {
        guard let p = try? AVAudioPlayer(contentsOf: url) else { return }
        p.delegate = self
        p.enableRate = true
        p.rate = Float(rate)
        p.prepareToPlay()
        player = p
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            playerCont = c
            if !p.play() { playerCont = nil; c.resume() }
        }
    }

    func setRate(_ r: Double) {
        settings?.ttsRate = r
        player?.rate = Float(r)
    }

    private func stopOutput() {
        currentUtterance = nil
        synth.stopSpeaking(at: .immediate)
        if let c = systemCont { systemCont = nil; c.resume() }
        player?.stop()
        player = nil
        if let c = playerCont { playerCont = nil; c.resume() }
    }

    static func bestVoice(for lang: TargetLanguage) -> AVSpeechSynthesisVoice? {
        let prefix = lang.rawValue
        let voices = AVSpeechSynthesisVoice.speechVoices().filter { $0.language.lowercased().hasPrefix(prefix) }
        let exact = voices.filter { $0.language == lang.voiceTag }
        let pool = exact.isEmpty ? voices : exact
        return pool.max(by: { $0.quality.rawValue < $1.quality.rawValue }) ?? AVSpeechSynthesisVoice(language: lang.voiceTag)
    }

    // MARK: - Delegeler

    nonisolated func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish u: AVSpeechUtterance) {
        Task { @MainActor in self.systemFinished(u) }
    }
    nonisolated func speechSynthesizer(_ s: AVSpeechSynthesizer, didCancel u: AVSpeechUtterance) {
        Task { @MainActor in self.systemFinished(u) }
    }
    private func systemFinished(_ u: AVSpeechUtterance) {
        guard u === currentUtterance, let c = systemCont else { return }
        systemCont = nil
        c.resume()
    }

    nonisolated func audioPlayerDidFinishPlaying(_ p: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            guard p === self.player, let c = self.playerCont else { return }
            self.playerCont = nil
            c.resume()
        }
    }

    // MARK: - Oturum, kilit ekranı

    private func configureSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .spokenAudio)
        try? s.setActive(true)
    }

    private func setupRemote() {
        if !remoteReady {
            remoteReady = true
            let c = MPRemoteCommandCenter.shared()
            c.playCommand.addTarget { [weak self] _ in Task { @MainActor in self?.resume() }; return .success }
            c.pauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.pause() }; return .success }
            c.togglePlayPauseCommand.addTarget { [weak self] _ in Task { @MainActor in self?.toggle() }; return .success }
            c.nextTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.skip(1) }; return .success }
            c.previousTrackCommand.addTarget { [weak self] _ in Task { @MainActor in self?.skip(-1) }; return .success }
        }
        setRemote(enabled: true)
    }

    private func setRemote(enabled: Bool) {
        let c = MPRemoteCommandCenter.shared()
        for cmd in [c.playCommand, c.pauseCommand, c.togglePlayPauseCommand, c.nextTrackCommand, c.previousTrackCommand] {
            cmd.isEnabled = enabled
        }
    }

    private func updateNowPlaying() {
        guard let source else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: source.title(),
            MPMediaItemPropertyArtist: source.subtitle(),
            MPNowPlayingInfoPropertyPlaybackRate: state == .playing ? 1.0 : 0.0,
        ]
    }
}

// MARK: - Oynatıcı çubuğu

struct SpeechBar: View {
    @ObservedObject var speech: SpeechController
    @EnvironmentObject var settings: ReaderSettings

    var body: some View {
        VStack(spacing: 6) {
            if let caption = speech.caption {
                Text(caption)
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
                    .lineLimit(4)
                    .multilineTextAlignment(.center)
            }
            if let note = speech.notice {
                Text(note)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .onTapGesture { speech.notice = nil }
            }
            HStack(spacing: 18) {
                Button { speech.stop() } label: { Image(systemName: "xmark") }
                Button { speech.skip(-1) } label: { Image(systemName: "backward.fill") }
                Button { speech.toggle() } label: {
                    Image(systemName: speech.state == .playing ? "pause.fill" : "play.fill")
                        .font(.title2)
                        .frame(width: 36)
                }
                Button { speech.skip(1) } label: { Image(systemName: "forward.fill") }
                Menu {
                    Picker("Hız", selection: Binding(get: { settings.ttsRate }, set: { speech.setRate($0) })) {
                        ForEach([0.8, 1.0, 1.15, 1.3, 1.5, 1.75, 2.0], id: \.self) { Text(String(format: "%.2g×", $0)).tag($0) }
                    }
                    Picker("Ses", selection: Binding(get: { settings.ttsEngine }, set: { settings.ttsEngine = $0; speech.settingsChanged() })) {
                        ForEach(TTSEngine.allCases) { Text($0.title).tag($0) }
                    }
                } label: {
                    Text(String(format: "%.2g×", settings.ttsRate)).font(.footnote.monospacedDigit())
                }
                if let busy = speech.busy {
                    HStack(spacing: 4) { ProgressView().controlSize(.small); Text(busy).font(.caption2) }
                        .foregroundStyle(.secondary)
                } else if speech.count > 0 {
                    Text("\(min(speech.index + 1, speech.count))/\(speech.count)")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 18).padding(.vertical, 10)
        .background(.regularMaterial, in: Capsule())
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
        .padding(.horizontal, 12)
    }
}
