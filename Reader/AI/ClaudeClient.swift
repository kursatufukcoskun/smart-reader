import Foundation
import Security
import CryptoKit

enum Keychain {
    private static let service = "ReaderApp"

    static func set(_ value: String, for key: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(base as CFDictionary)
        guard !value.isEmpty else { return }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        SecItemAdd(add as CFDictionary, nil)
    }

    static func get(_ key: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static let apiKeyName = "anthropic_api_key"
    static let geminiKeyName = "gemini_api_key"
}

enum ClaudeError: LocalizedError {
    case missingKey
    case http(Int, String)
    case badResponse

    var errorDescription: String? {
        switch self {
        case .missingKey: return "Seçili sağlayıcının API anahtarı yok. Kitaplık ▸ Ayarlar bölümünden ekleyin."
        case .http(let code, let msg): return "API hatası (\(code)): \(msg)"
        case .badResponse: return "API'den beklenmeyen yanıt geldi."
        }
    }
}

enum ClaudeClient {
    static let endpoint = URL(string: "https://api.anthropic.com/v1/messages")!

    private static func makeRequest(model: String, system: String, messages: [[String: Any]], maxTokens: Int, stream: Bool) throws -> URLRequest {
        guard let key = Keychain.get(Keychain.apiKeyName), !key.isEmpty else { throw ClaudeError.missingKey }
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.timeoutInterval = 180
        req.setValue(key, forHTTPHeaderField: "x-api-key")
        req.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        req.setValue("application/json", forHTTPHeaderField: "content-type")
        var body: [String: Any] = [
            "model": model,
            "max_tokens": maxTokens,
            "stream": stream,
            "messages": messages,
        ]
        if !system.isEmpty { body["system"] = system }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        return req
    }

    static func apiMessage(_ body: String) -> String {
        if let data = body.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let err = obj["error"] as? [String: Any], let m = err["message"] as? String { return m }
        return String(body.prefix(300))
    }

    /// Metni parça parça (streaming) döndürür.
    static func stream(model: String, system: String, user: String, maxTokens: Int = 2048) -> AsyncThrowingStream<String, Error> {
        stream(model: model, system: system, messages: [["role": "user", "content": user]], maxTokens: maxTokens)
    }

    /// Çok turlu sohbet: kitap metni ilk kullanıcı mesajında, önbelleğe alınabilir blok olarak gider.
    static func streamChat(model: String, system: String, context: String, turns: [ChatTurn], maxTokens: Int = 2048) -> AsyncThrowingStream<String, Error> {
        var messages: [[String: Any]] = []
        for (i, t) in turns.enumerated() {
            if t.role == .user, i == 0 {
                messages.append(["role": "user", "content": [
                    ["type": "text", "text": context, "cache_control": ["type": "ephemeral"]],
                    ["type": "text", "text": t.text],
                ]])
            } else {
                messages.append(["role": t.role == .user ? "user" : "assistant", "content": t.text])
            }
        }
        return stream(model: model, system: system, messages: messages, maxTokens: maxTokens)
    }

    static func stream(model: String, system: String, messages: [[String: Any]], maxTokens: Int) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let req = try makeRequest(model: model, system: system, messages: messages, maxTokens: maxTokens, stream: true)
                    let (bytes, resp) = try await URLSession.shared.bytes(for: req)
                    guard let http = resp as? HTTPURLResponse else { throw ClaudeError.badResponse }
                    if http.statusCode != 200 {
                        var body = ""
                        for try await line in bytes.lines { body += line }
                        throw ClaudeError.http(http.statusCode, apiMessage(body))
                    }
                    for try await line in bytes.lines {
                        guard line.hasPrefix("data:") else { continue }
                        let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                        guard let data = payload.data(using: .utf8),
                              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                              let type = obj["type"] as? String else { continue }
                        if type == "content_block_delta",
                           let delta = obj["delta"] as? [String: Any],
                           let text = delta["text"] as? String {
                            continuation.yield(text)
                        } else if type == "error" {
                            let msg = (obj["error"] as? [String: Any])?["message"] as? String ?? "bilinmeyen hata"
                            throw ClaudeError.http(0, msg)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Tek seferde tam yanıt döndürür.
    static func complete(model: String, system: String, user: String, maxTokens: Int = 4096) async throws -> String {
        let req = try makeRequest(model: model, system: system, messages: [["role": "user", "content": user]], maxTokens: maxTokens, stream: false)
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw ClaudeError.badResponse }
        guard http.statusCode == 200 else {
            throw ClaudeError.http(http.statusCode, apiMessage(String(decoding: data, as: UTF8.self)))
        }
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let content = obj["content"] as? [[String: Any]] else { throw ClaudeError.badResponse }
        return content.compactMap { $0["text"] as? String }.joined()
    }
}

/// Aynı isteği iki kez ödememek için disk önbelleği.
final class ResponseCache: @unchecked Sendable {
    static let shared = ResponseCache()
    private let lock = NSLock()
    private var store: [String: String] = [:]
    private let url = Paths.base.appendingPathComponent("llm-cache.json")

    private init() {
        if let data = try? Data(contentsOf: url),
           let d = try? JSONDecoder().decode([String: String].self, from: data) { store = d }
    }

    static func key(_ parts: String...) -> String {
        let joined = parts.joined(separator: "\u{1F}")
        return SHA256.hash(data: Data(joined.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    func get(_ key: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return store[key]
    }

    func set(_ key: String, _ value: String) {
        lock.lock()
        store[key] = value
        let snapshot = store
        lock.unlock()
        DispatchQueue.global(qos: .utility).async { [url] in
            if let data = try? JSONEncoder().encode(snapshot) { try? data.write(to: url, options: .atomic) }
        }
    }
}
