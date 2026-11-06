import Foundation
import SwiftUI

struct AIRequest {
    var title: String
    var system: String
    var user: String
    var config: LLMConfig
    var maxTokens: Int = 2048
    var cacheKey: String?
    var vocab: VocabDraft?
}

/// Okuyucunun altından açılan yanıt panelini yönetir (streaming).
@MainActor
final class AIAssistant: ObservableObject {
    @Published var isPresented = false {
        didSet { if !isPresented { cancel() } }
    }
    @Published var title = ""
    @Published var text = ""
    @Published var isStreaming = false
    @Published var error: String?
    /// Son isteğin kelime defteri bilgisi (yalnızca kısa seçimlerde dolu)
    @Published var vocabDraft: VocabDraft?

    private var task: Task<Void, Never>?
    private var lastRequest: AIRequest?

    func start(_ req: AIRequest) {
        task?.cancel()
        lastRequest = req
        vocabDraft = req.vocab
        title = req.title
        text = ""
        error = nil
        isPresented = true

        if let key = req.cacheKey, let cached = ResponseCache.shared.get(key) {
            text = cached
            isStreaming = false
            return
        }

        isStreaming = true
        task = Task { [weak self] in
            var acc = ""
            do {
                for try await chunk in LLMClient.stream(req.config, system: req.system, user: req.user, maxTokens: req.maxTokens) {
                    acc += chunk
                    self?.text = acc
                }
                if let key = req.cacheKey, !acc.isEmpty { ResponseCache.shared.set(key, acc) }
            } catch {
                if !Task.isCancelled { self?.error = error.localizedDescription }
            }
            if !Task.isCancelled { self?.isStreaming = false }
        }
    }

    func retry() {
        if let r = lastRequest { start(r) }
    }

    func cancel() {
        task?.cancel()
        task = nil
        isStreaming = false
    }
}
