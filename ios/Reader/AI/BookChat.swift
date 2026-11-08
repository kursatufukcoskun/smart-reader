import Foundation
import SwiftUI

struct ChatTurn: Sendable {
    enum Role: Sendable { case user, assistant }
    var role: Role
    var text: String
}

/// Kullanıcının şu ana kadar okuduğu kitap metni.
struct BookContext {
    var text: String
    var words: Int
    var truncated: Bool
    var label: String

    static let empty = BookContext(text: "", words: 0, truncated: false, label: "")

    /// Bölümleri birleştirir; bütçeyi aşarsa en eski kısımları çıkarır.
    static func assemble(sections: [(title: String, body: String)], budget: Int, label: String) -> BookContext {
        var out = ""
        for s in sections where !s.body.isEmpty {
            out += "\n\n=== \(s.title) ===\n\(s.body)"
        }
        var truncated = false
        if out.count > budget {
            truncated = true
            out = "[… önceki kısımlar uzunluk nedeniyle çıkarıldı …]\n" + String(out.suffix(budget))
        }
        let words = out.split(whereSeparator: \.isWhitespace).count
        return BookContext(text: out, words: words, truncated: truncated, label: label)
    }
}

/// Kitapla sohbetin durumu. Bağlam sağlayıcısını okuyucu (EPUB/PDF) verir.
@MainActor
final class ChatModel: ObservableObject {
    struct Message: Identifiable {
        let id = UUID()
        var role: ChatTurn.Role
        var text: String
    }

    enum Tier: String, CaseIterable, Identifiable {
        case fast, smart
        var id: String { rawValue }
        var title: String { self == .fast ? "Hızlı" : "Güçlü" }
    }

    @Published var messages: [Message] = []
    @Published var isStreaming = false
    @Published var isLoadingContext = false
    @Published var error: String?
    @Published var info: BookContext = .empty
    @Published var tier: Tier = .smart
    /// Bir yanıtı okuma konumuna not olarak kaydeder (okuyucu bağlar).
    var saveNote: ((String) -> Void)?
    /// Okuyucudan gelen hazır soru: sohbet açılıp bağlam hazır olunca otomatik gönderilir.
    var pendingPrompt: String?

    let settings: ReaderSettings
    let bookTitle: String
    /// Verilen karakter bütçesiyle şu ana kadar okunan metni üretir.
    var contextProvider: ((Int) async -> BookContext)?

    private var task: Task<Void, Never>?

    init(settings: ReaderSettings, bookTitle: String) {
        self.settings = settings
        self.bookTitle = bookTitle
    }

    private var budget: Int { settings.provider == .gemini ? 1_800_000 : 450_000 }

    /// Okuma konumuna göre metni yeniler (sohbet açılırken ve isteğe bağlı çağrılır).
    func refreshContext() async {
        guard !isStreaming else { return }
        isLoadingContext = true
        info = await contextProvider?(budget) ?? .empty
        isLoadingContext = false
    }

    func send(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isStreaming else { return }
        guard !info.text.isEmpty else {
            error = "Okunan kısımdan metin çıkarılamadı (taranmış PDF olabilir)."
            return
        }
        error = nil
        messages.append(Message(role: .user, text: text))
        messages.append(Message(role: .assistant, text: ""))
        let replyID = messages[messages.count - 1].id
        let turns = messages.dropLast().map { ChatTurn(role: $0.role, text: $0.text) }
        let config = settings.config(tier == .fast ? .fast : .smart)
        let context = "Aşağıda kitabın, kullanıcının şu ana kadar okuduğu kısmı var:\n<kitap>\(info.text)\n</kitap>"
        let system = Prompts.chatSystem(bookTitle: bookTitle)

        isStreaming = true
        task = Task { [weak self] in
            var acc = ""
            do {
                for try await chunk in LLMClient.streamChat(config, system: system, context: context, turns: Array(turns)) {
                    acc += chunk
                    self?.setReply(replyID, acc)
                }
            } catch {
                if !Task.isCancelled { self?.error = error.localizedDescription }
            }
            if let self {
                // Boş kalan yanıtı sil ki bir sonraki istek geçersiz mesaj dizisi göndermesin.
                if acc.isEmpty { self.messages.removeAll { $0.id == replyID } ; self.dropDanglingUser() }
                self.isStreaming = false
            }
        }
    }

    private func setReply(_ id: UUID, _ text: String) {
        if let i = messages.firstIndex(where: { $0.id == id }) { messages[i].text = text }
    }

    /// Yanıtsız kalan son kullanıcı mesajını geri verir (tekrar denenebilsin diye metni korunur).
    private var lastFailedQuestion: String?
    private func dropDanglingUser() {
        if messages.last?.role == .user { lastFailedQuestion = messages.removeLast().text }
    }

    func retry() {
        if let q = lastFailedQuestion { lastFailedQuestion = nil; send(q) }
    }

    func stop() {
        task?.cancel()
        isStreaming = false
    }

    func clear() {
        task?.cancel()
        isStreaming = false
        messages = []
        error = nil
    }
}

/// XHTML/HTML dosyasından düz metin çıkarır (arka planda çağrılmalı).
enum HTMLText {
    static func extract(from url: URL) -> String {
        guard let data = try? Data(contentsOf: url) else { return "" }
        let raw = String(data: data, encoding: .utf8) ?? String(decoding: data, as: UTF8.self)
        return plain(from: raw)
    }

    static func plain(from html: String) -> String {
        var s = html
        s = replace(s, #"<(script|style|head)\b[\s\S]*?</\1\s*>"#, " ")
        s = replace(s, #"</(p|div|h[1-6]|li|blockquote|tr|section|article|figcaption|dd|dt)\s*>|<br\s*/?>"#, "\n")
        s = replace(s, #"<[^>]+>"#, "")
        s = decodeEntities(s)
        // satırları temizle, ardışık boş satırları teke indir
        var lines: [String] = []
        for l in s.components(separatedBy: .newlines) {
            let t = l.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            if t.isEmpty { if lines.last != "" { lines.append("") } } else { lines.append(t) }
        }
        return lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// HTML içinde `id`'si verilen öğenin (boş bir <a>/<span> ise onu içeren paragrafın) düz metni.
    static func elementText(id: String, in html: String) -> String? {
        let ns = html as NSString
        let esc = NSRegularExpression.escapedPattern(for: id)
        let pattern = #"<([a-zA-Z][a-zA-Z0-9]*)\b[^>]*\b(?:id|name)\s*=\s*[\"']"# + esc + #"[\"'][^>]*>"#
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: html, range: NSRange(location: 0, length: ns.length)) else { return nil }
        let tag = ns.substring(with: m.range(at: 1)).lowercased()
        var text = ""
        if tag != "a" && tag != "span", let inner = balanced(tag: tag, after: m.range, in: ns) { text = plain(from: inner) }
        if text.isEmpty {
            let before = ns.substring(to: m.range.location) as NSString
            if let br = try? NSRegularExpression(pattern: #"<(p|li|div|aside|dd|blockquote)\b[^>]*>"#, options: [.caseInsensitive]),
               let last = br.matches(in: before as String, range: NSRange(location: 0, length: before.length)).last {
                let t = before.substring(with: last.range(at: 1)).lowercased()
                if let inner = balanced(tag: t, after: last.range, in: ns) { text = plain(from: inner) }
            }
        }
        // geri bağlantı oklarını ve fazla boşluğu temizle
        let junk = CharacterSet(charactersIn: "↩︎↩⤴↑⏎").union(.whitespacesAndNewlines)
        text = text.trimmingCharacters(in: junk)
        text = text.split(whereSeparator: \.isNewline).joined(separator: "\n")
        return text.isEmpty ? nil : String(text.prefix(6000))
    }

    private static func balanced(tag: String, after open: NSRange, in ns: NSString) -> String? {
        guard let re = try? NSRegularExpression(pattern: "<(/?)" + tag + #"\b[^>]*>"#, options: [.caseInsensitive]) else { return nil }
        let start = open.location + open.length
        guard start <= ns.length else { return nil }
        var depth = 1
        var result: String?
        re.enumerateMatches(in: ns as String, range: NSRange(location: start, length: ns.length - start)) { m, _, stop in
            guard let m else { return }
            let closing = m.range(at: 1).length > 0
            if !closing && ns.substring(with: m.range).hasSuffix("/>") { return }
            depth += closing ? -1 : 1
            if depth == 0 {
                result = ns.substring(with: NSRange(location: start, length: m.range.location - start))
                stop.pointee = true
            }
        }
        return result
    }

    private static func replace(_ s: String, _ pattern: String, _ with: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return s }
        return re.stringByReplacingMatches(in: s, range: NSRange(location: 0, length: (s as NSString).length), withTemplate: with)
    }

    private static func decodeEntities(_ s: String) -> String {
        var out = s
        let named = ["&nbsp;": " ", "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&apos;": "'",
                     "&mdash;": "—", "&ndash;": "–", "&hellip;": "…", "&rsquo;": "’", "&lsquo;": "‘",
                     "&rdquo;": "”", "&ldquo;": "“"]
        for (k, v) in named { out = out.replacingOccurrences(of: k, with: v) }
        guard out.contains("&#"), let re = try? NSRegularExpression(pattern: #"&#(x?)([0-9a-fA-F]+);"#) else { return out }
        let ns = out as NSString
        var result = ""
        var last = 0
        for m in re.matches(in: out, range: NSRange(location: 0, length: ns.length)) {
            result += ns.substring(with: NSRange(location: last, length: m.range.location - last))
            let isHex = ns.substring(with: m.range(at: 1)) == "x"
            if let code = UInt32(ns.substring(with: m.range(at: 2)), radix: isHex ? 16 : 10), let u = Unicode.Scalar(code) {
                result.unicodeScalars.append(u)
            }
            last = m.range.location + m.range.length
        }
        result += ns.substring(from: last)
        return result
    }
}
