import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var settings: ReaderSettings
    @Environment(\.dismiss) private var dismiss
    @State private var claudeKey = ""
    @State private var geminiKey = ""
    @State private var status: String?
    @State private var testing = false
    @State private var refreshing: LLMProvider?
    @StateObject private var catalog = ModelCatalog()
    @EnvironmentObject var sync: SyncManager

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Sağlayıcı", selection: $settings.provider) {
                        ForEach(LLMProvider.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                } header: {
                    Text("Çeviri ve özet için kullanılacak model")
                } footer: {
                    Text("İstediğiniz zaman değiştirebilirsiniz. Önbellek sağlayıcıya göre ayrı tutulur.")
                }

                Section {
                    SecureField("sk-ant-…", text: $claudeKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    ModelField(title: "Hızlı model", text: $settings.fastModel, options: catalog.models(for: .claude))
                    ModelField(title: "Güçlü model", text: $settings.smartModel, options: catalog.models(for: .claude))
                    refreshButton(.claude, key: claudeKey)
                } header: {
                    Text("Claude (Anthropic)")
                } footer: {
                    Text("Model adını elle yazabilir veya ⇅ ile listeden seçebilirsiniz.")
                }

                Section {
                    SecureField("AIza…", text: $geminiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    ModelField(title: "Hızlı model", text: $settings.geminiFastModel, options: catalog.models(for: .gemini))
                    ModelField(title: "Güçlü model", text: $settings.geminiSmartModel, options: catalog.models(for: .gemini))
                    refreshButton(.gemini, key: geminiKey)
                } header: {
                    Text("Gemini (Google)")
                } footer: {
                    Text("Anahtarı Google AI Studio'dan alabilirsiniz. Liste eskiyse \"Güncel modelleri getir\" ile yenileyin.")
                }

                Section {
                    Button {
                        Task { await saveAndTest() }
                    } label: {
                        HStack {
                            Text("Kaydet ve \(settings.provider.title) bağlantısını test et")
                            if testing { ProgressView() }
                        }
                    }
                    if let status { Text(status).font(.footnote).foregroundStyle(.secondary) }
                } footer: {
                    Text("Hızlı model: kelime, paragraf ve sayfa çevirisi. Güçlü model: özetler. Anahtarlar yalnızca bu cihazın Keychain'inde saklanır.")
                }

                LanguageSettingsSection()

                GoalSettingsSection()

                Section {
                    SyncSettingsRows()
                } header: {
                    Text("Senkronizasyon (Google Drive)")
                } footer: {
                    Text("İlerleme, vurgular, notlar, kelime defteri, okuma istatistikleri ve okuma ayarları Drive'ınızdaki gizli uygulama klasörüne yazılır. Kitap dosyaları ve API anahtarları senkronlanmaz; kitabı her cihaza ayrıca ekleyin.")
                }

                Section("Okuma görünümü") {
                    ThemePanel().frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("Ayarlar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Bitti") { save(); dismiss() } } }
            .onAppear {
                claudeKey = Keychain.get(Keychain.apiKeyName) ?? ""
                geminiKey = Keychain.get(Keychain.geminiKeyName) ?? ""
            }
        }
    }

    private func refreshButton(_ p: LLMProvider, key: String) -> some View {
        Button {
            Task {
                refreshing = p
                defer { refreshing = nil }
                do {
                    try await catalog.refresh(p, key: key)
                    status = "\(p.title): \(catalog.models(for: p).count) model listelendi."
                } catch {
                    status = error.localizedDescription
                }
            }
        } label: {
            HStack {
                Text(catalog.isLive(p) ? "Güncel modelleri yenile" : "Güncel modelleri getir")
                if refreshing == p { ProgressView() }
            }
        }
        .disabled(key.trimmingCharacters(in: .whitespaces).isEmpty)
    }

    private func save() {
        Keychain.set(claudeKey.trimmingCharacters(in: .whitespacesAndNewlines), for: Keychain.apiKeyName)
        Keychain.set(geminiKey.trimmingCharacters(in: .whitespacesAndNewlines), for: Keychain.geminiKeyName)
    }

    private func saveAndTest() async {
        save()
        testing = true
        defer { testing = false }
        do {
            let reply = try await LLMClient.complete(settings.config(.fast), system: "", user: "Reply with exactly: OK", maxTokens: 16)
            let r = reply.trimmingCharacters(in: .whitespacesAndNewlines)
            status = "\(settings.provider.title) bağlantısı başarılı (\(r.isEmpty ? "boş yanıt" : r))."
        } catch {
            status = error.localizedDescription
        }
    }
}


/// Hedef dil, seslendirme dili ve ses motoru ayarları.
struct LanguageSettingsSection: View {
    @EnvironmentObject var settings: ReaderSettings
    @State private var cacheBytes: Int64 = 0

    private static let voices = ["Kore", "Puck", "Charon", "Aoede", "Fenrir", "Leda", "Orus", "Zephyr", "Sulafat", "Gacrux", "Algieba", "Achernar"]

    var body: some View {
        Section {
            Picker("Hedef dil", selection: $settings.targetLanguage) {
                ForEach(TargetLanguage.allCases) { Text($0.title).tag($0) }
            }
            Picker("Seslendirme dili", selection: $settings.ttsLanguageCode) {
                Text("Hedef dili izle (\(settings.targetLanguage.title))").tag("")
                ForEach(TargetLanguage.allCases) { Text($0.title).tag($0.rawValue) }
            }
            Picker("Ses motoru", selection: $settings.ttsEngine) {
                ForEach(TTSEngine.allCases) { Text($0.title).tag($0) }
            }
            Toggle("Çeviriyi sayfada da göster", isOn: $settings.ttsShowInPage)
            if settings.ttsEngine == .gemini {
                Picker("Gemini sesi", selection: $settings.geminiVoice) {
                    ForEach(Self.voices, id: \.self) { Text($0).tag($0) }
                }
                HStack {
                    Text("Model")
                    TextField("gemini-3.8-flash-tts", text: $settings.geminiTTSModel)
                        .multilineTextAlignment(.trailing)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Stepper(value: $settings.ttsBudgetUSD, in: 0...100, step: 5) {
                    Text(settings.ttsBudgetUSD == 0 ? "Aylık bütçe: sınırsız" : "Aylık bütçe: $\(Int(settings.ttsBudgetUSD))")
                }
                let spent = TTSUsage.dollarsThisMonth(model: settings.geminiTTSModel)
                Text("Bu ay üretilen ses: \(Int(TTSUsage.secondsThisMonth / 60)) dk · tahmini $\(String(format: "%.2f", spent))")
                    .font(.footnote).foregroundStyle(.secondary)
                HStack {
                    Text("Ses önbelleği: \(ByteCountFormatter.string(fromByteCount: cacheBytes, countStyle: .file))")
                        .font(.footnote).foregroundStyle(.secondary)
                    Spacer()
                    Button("Temizle", role: .destructive) { GeminiTTS.clearCache(); cacheBytes = GeminiTTS.cacheSize() }
                        .font(.footnote)
                }
            }
        } header: {
            Text("Diller ve seslendirme")
        } footer: {
            Text("Hedef dil; sözlük, açıklama, çeviri ve özetlerin dilidir. Seslendirme dili farklıysa metin okunmadan önce o dile çevrilir. Sistem sesi ücretsizdir; Gemini sesi daha doğaldır, yalnızca okunan paragraflar üretilir ve cihazda saklanır.")
        }
        .onAppear { cacheBytes = GeminiTTS.cacheSize() }
    }
}
