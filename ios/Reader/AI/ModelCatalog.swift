import Foundation
import SwiftUI

/// Model seçim listesi: hazır öneriler + API'den çekilen güncel liste.
@MainActor
final class ModelCatalog: ObservableObject {
    @Published private(set) var live: [LLMProvider: [String]] = [:]
    private let d = UserDefaults.standard

    static let presets: [LLMProvider: [String]] = [
        .claude: ["claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1"],
        .gemini: ["gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.5-pro"],
    ]

    init() {
        for p in LLMProvider.allCases {
            if let saved = d.stringArray(forKey: "models.\(p.rawValue)"), !saved.isEmpty { live[p] = saved }
        }
    }

    func models(for p: LLMProvider) -> [String] {
        if let l = live[p], !l.isEmpty { return l }
        return Self.presets[p] ?? []
    }

    func isLive(_ p: LLMProvider) -> Bool { !(live[p] ?? []).isEmpty }

    /// Sağlayıcının model listesini API'den çeker ve saklar.
    func refresh(_ p: LLMProvider, key: String) async throws {
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { throw ClaudeError.missingKey }

        var req: URLRequest
        switch p {
        case .claude:
            req = URLRequest(url: URL(string: "https://api.anthropic.com/v1/models?limit=100")!)
            req.setValue(key, forHTTPHeaderField: "x-api-key")
            req.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        case .gemini:
            req = URLRequest(url: URL(string: "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")!)
            req.setValue(key, forHTTPHeaderField: "x-goog-api-key")
        }
        req.timeoutInterval = 30

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw ClaudeError.badResponse }
        guard http.statusCode == 200 else {
            throw ClaudeError.http(http.statusCode, ClaudeClient.apiMessage(String(decoding: data, as: UTF8.self)))
        }
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ClaudeError.badResponse }

        var ids: [String] = []
        switch p {
        case .claude:
            ids = (obj["data"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String }
        case .gemini:
            ids = (obj["models"] as? [[String: Any]] ?? []).compactMap { m in
                guard let name = m["name"] as? String, name.hasPrefix("models/gemini"),
                      (m["supportedGenerationMethods"] as? [String])?.contains("generateContent") == true else { return nil }
                return String(name.dropFirst("models/".count))
            }
        }
        guard !ids.isEmpty else { throw ClaudeError.badResponse }
        live[p] = ids
        d.set(ids, forKey: "models.\(p.rawValue)")
    }
}

/// Hem yazılabilen hem listeden seçilebilen model alanı.
struct ModelField: View {
    let title: String
    @Binding var text: String
    let options: [String]

    var body: some View {
        HStack(spacing: 8) {
            Text(title).foregroundStyle(.secondary)
            TextField("model adı", text: $text)
                .multilineTextAlignment(.trailing)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Menu {
                ForEach(options, id: \.self) { m in
                    Button {
                        text = m
                    } label: {
                        if m == text { Label(m, systemImage: "checkmark") } else { Text(m) }
                    }
                }
            } label: {
                Image(systemName: "chevron.up.chevron.down")
                    .padding(6)
            }
        }
    }
}
