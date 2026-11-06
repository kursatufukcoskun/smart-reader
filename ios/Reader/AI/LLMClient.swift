import Foundation

enum LLMProvider: String, CaseIterable, Identifiable, Sendable {
    case claude, gemini
    var id: String { rawValue }
    var title: String {
        switch self {
        case .claude: return "Claude"
        case .gemini: return "Gemini"
        }
    }
    var keyName: String {
        switch self {
        case .claude: return Keychain.apiKeyName
        case .gemini: return Keychain.geminiKeyName
        }
    }
}

enum LLMTier { case fast, smart }

/// Bir isteğin hangi sağlayıcı ve modelle yapılacağı.
struct LLMConfig: Sendable {
    let provider: LLMProvider
    let model: String
    /// Dilden bağımsız kimlik.
    var baseID: String { "\(provider.rawValue):\(model)" }
    /// Yanıtın dili hedef dile bağlı olduğundan önbellek anahtarına dil eklenir (Türkçe'de eski anahtar korunur).
    var cacheID: String { baseID + TargetLanguage.current.cacheSuffix }
}

/// Seçili sağlayıcıya yönlendiren ince katman.
enum LLMClient {
    static func stream(_ c: LLMConfig, system: String, user: String, maxTokens: Int = 2048) -> AsyncThrowingStream<String, Error> {
        switch c.provider {
        case .claude: return ClaudeClient.stream(model: c.model, system: system, user: user, maxTokens: maxTokens)
        case .gemini: return GeminiClient.stream(model: c.model, system: system, user: user, maxTokens: maxTokens)
        }
    }

    /// Kitap bağlamıyla çok turlu sohbet (streaming).
    static func streamChat(_ c: LLMConfig, system: String, context: String, turns: [ChatTurn], maxTokens: Int = 2048) -> AsyncThrowingStream<String, Error> {
        switch c.provider {
        case .claude: return ClaudeClient.streamChat(model: c.model, system: system, context: context, turns: turns, maxTokens: maxTokens)
        case .gemini: return GeminiClient.streamChat(model: c.model, system: system, context: context, turns: turns, maxTokens: maxTokens)
        }
    }

    static func complete(_ c: LLMConfig, system: String, user: String, maxTokens: Int = 4096) async throws -> String {
        switch c.provider {
        case .claude: return try await ClaudeClient.complete(model: c.model, system: system, user: user, maxTokens: maxTokens)
        case .gemini: return try await GeminiClient.complete(model: c.model, system: system, user: user, maxTokens: maxTokens)
        }
    }
}

enum GeminiClient {
    private static func makeRequest(model: String, system: String, contents: [[String: Any]], maxTokens: Int, stream: Bool) throws -> URLRequest {
        guard let key = Keychain.get(Keychain.geminiKeyName), !key.isEmpty else { throw ClaudeError.missingKey }
        let name = model.hasPrefix("models/") ? String(model.dropFirst(7)) : model
        let method = stream ? "streamGenerateContent?alt=sse" : "generateContent"
        guard let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/\(name):\(method)") else {
            throw ClaudeError.badResponse
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 180
        req.setValue(key, forHTTPHeaderField: "x-goog-api-key")
        req.setValue("application/json", forHTTPHeaderField: "content-type")
        var body: [String: Any] = [
            "contents": contents,
            // Düşünen modellerde düşünme token'ları da bu sınıra dahil olduğu için pay bırakıyoruz.
            "generationConfig": ["maxOutputTokens": min(maxTokens * 4, 32768)],
        ]
        if !system.isEmpty { body["systemInstruction"] = ["parts": [["text": system]]] }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        return req
    }

    /// Bir yanıt nesnesinden metni (düşünme parçaları hariç) çıkarır.
    private static func text(from obj: [String: Any]) throws -> String {
        if let fb = obj["promptFeedback"] as? [String: Any], let reason = fb["blockReason"] as? String {
            throw ClaudeError.http(0, "İstek Gemini tarafından engellendi (\(reason)).")
        }
        guard let cands = obj["candidates"] as? [[String: Any]],
              let content = cands.first?["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]] else { return "" }
        return parts.compactMap { part -> String? in
            if part["thought"] as? Bool == true { return nil }
            return part["text"] as? String
        }.joined()
    }

    static func stream(model: String, system: String, user: String, maxTokens: Int = 2048) -> AsyncThrowingStream<String, Error> {
        stream(model: model, system: system, contents: [["role": "user", "parts": [["text": user]]]], maxTokens: maxTokens)
    }

    /// Çok turlu sohbet: bağlam hep aynı önekte kalır, böylece Gemini'nin örtük önbelleği devreye girebilir.
    static func streamChat(model: String, system: String, context: String, turns: [ChatTurn], maxTokens: Int = 2048) -> AsyncThrowingStream<String, Error> {
        var contents: [[String: Any]] = []
        for (i, t) in turns.enumerated() {
            if t.role == .user, i == 0 {
                contents.append(["role": "user", "parts": [["text": context], ["text": t.text]]])
            } else {
                contents.append(["role": t.role == .user ? "user" : "model", "parts": [["text": t.text]]])
            }
        }
        return stream(model: model, system: system, contents: contents, maxTokens: maxTokens)
    }

    static func stream(model: String, system: String, contents: [[String: Any]], maxTokens: Int) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let req = try makeRequest(model: model, system: system, contents: contents, maxTokens: maxTokens, stream: true)
                    let (bytes, resp) = try await URLSession.shared.bytes(for: req)
                    guard let http = resp as? HTTPURLResponse else { throw ClaudeError.badResponse }
                    if http.statusCode != 200 {
                        var body = ""
                        for try await line in bytes.lines { body += line }
                        throw ClaudeError.http(http.statusCode, ClaudeClient.apiMessage(body))
                    }
                    for try await line in bytes.lines {
                        guard line.hasPrefix("data:") else { continue }
                        let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                        guard let data = payload.data(using: .utf8),
                              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { continue }
                        let chunk = try text(from: obj)
                        if !chunk.isEmpty { continuation.yield(chunk) }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    static func complete(model: String, system: String, user: String, maxTokens: Int = 4096) async throws -> String {
        let req = try makeRequest(model: model, system: system, contents: [["role": "user", "parts": [["text": user]]]], maxTokens: maxTokens, stream: false)
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw ClaudeError.badResponse }
        guard http.statusCode == 200 else {
            throw ClaudeError.http(http.statusCode, ClaudeClient.apiMessage(String(decoding: data, as: UTF8.self)))
        }
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ClaudeError.badResponse }
        return try text(from: obj)
    }
}
