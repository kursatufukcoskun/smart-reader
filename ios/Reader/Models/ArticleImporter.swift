import Foundation

/// Web makalesini indirip ana metnini ayıklar ve tek bölümlük bir EPUB dosyasına çevirir.
/// Böylece makale de kitaplıkta açılır: çeviri, vurgu, kelime defteri ve sohbet araçları aynen çalışır.
enum ArticleImporter {
    enum ArticleError: LocalizedError {
        case badURL
        case http(Int)
        case empty

        var errorDescription: String? {
            switch self {
            case .badURL: return "Geçerli bir web adresi gir (http veya https)."
            case .http(let c): return "Sayfa indirilemedi (HTTP \(c))."
            case .empty: return "Makale metni çıkarılamadı. Sayfa giriş ya da JavaScript gerektiriyor olabilir."
            }
        }
    }

    struct Block {
        enum Kind { case heading(Int), paragraph, quote, item, pre }
        var kind: Kind
        var text: String
    }

    struct Article {
        var title: String
        var author: String
        var blocks: [Block]
        var url: URL
    }

    /// Adresten içe aktarılabilir bir dosya hazırlar ve geçici klasördeki yolunu döner:
    /// adres PDF ya da EPUB ise dosya olduğu gibi indirilir; HTML ise makale metni ayıklanıp tek bölümlük EPUB üretilir.
    static func fetch(from raw: String) async throws -> URL {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", url.host != nil else { throw ArticleError.badURL }

        var req = URLRequest(url: url)
        req.timeoutInterval = 60
        req.setValue("Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                     forHTTPHeaderField: "User-Agent")
        req.setValue("application/pdf,application/epub+zip,text/html,application/xhtml+xml;q=0.9,*/*;q=0.5", forHTTPHeaderField: "Accept")
        let (tmp, resp) = try await URLSession.shared.download(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { throw ArticleError.http(http.statusCode) }

        // Dosya türü uzantıya değil içeriğe bakılarak anlaşılır
        let head = (try? FileHandle(forReadingFrom: tmp)).flatMap { try? $0.read(upToCount: 256) } ?? Data()
        let mime = ((resp as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
        let ext: String? = {
            if head.starts(with: Data("%PDF".utf8)) { return "pdf" }
            if head.starts(with: Data("PK".utf8)),
               String(decoding: head, as: UTF8.self).contains("application/epub+zip") || mime.contains("epub") { return "epub" }
            return nil
        }()

        if let ext {
            let name = fileName(url: url, response: resp, ext: ext)
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("dl-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let out = dir.appendingPathComponent(name)
            try FileManager.default.moveItem(at: tmp, to: out)
            return out
        }

        let data = try Data(contentsOf: tmp)
        try? FileManager.default.removeItem(at: tmp)
        let html = decode(data, response: resp)
        let article = try await Task.detached(priority: .userInitiated) { try parse(html: html, url: url) }.value
        return try await Task.detached(priority: .userInitiated) { try writeEPUB(article) }.value
    }

    /// İndirilen PDF/EPUB için dosya adı: Content-Disposition, yoksa adresin son parçası.
    private static func fileName(url: URL, response: URLResponse, ext: String) -> String {
        var base = ""
        if let cd = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Disposition"),
           let name = firstMatch(#"filename\*?=(?:UTF-8'')?["']?([^"';]+)"#, in: cd) {
            base = (name.removingPercentEncoding ?? name)
        }
        if base.isEmpty { base = url.deletingPathExtension().lastPathComponent.removingPercentEncoding ?? url.lastPathComponent }
        if base.isEmpty { base = url.host ?? "Belge" }
        base = (base as NSString).deletingPathExtension
        let safe = base.components(separatedBy: CharacterSet(charactersIn: "/\\:?*\"<>|\n")).joined(separator: " ")
            .trimmingCharacters(in: .whitespaces)
        return "\(safe.isEmpty ? "Belge" : String(safe.prefix(80))).\(ext)"
    }

    // MARK: - Çözümleme

    private static func decode(_ data: Data, response: URLResponse) -> String {
        if let name = response.textEncodingName {
            let cf = CFStringConvertIANACharSetNameToEncoding(name as CFString)
            if cf != kCFStringEncodingInvalidId,
               let s = String(data: data, encoding: String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(cf))) { return s }
        }
        return String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) ?? ""
    }

    private static func firstMatch(_ pattern: String, in s: String, group: Int = 1) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let m = re.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)),
              m.range(at: group).location != NSNotFound else { return nil }
        return (s as NSString).substring(with: m.range(at: group))
    }

    private static func meta(_ key: String, in html: String) -> String? {
        let k = NSRegularExpression.escapedPattern(for: key)
        let a = #"<meta[^>]+(?:property|name)\s*=\s*["']"# + k + #"["'][^>]*content\s*=\s*["']([^"']*)["']"#
        let b = #"<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']"# + k + #"["']"#
        return firstMatch(a, in: html) ?? firstMatch(b, in: html)
    }

    private static func clean(_ s: String) -> String {
        HTMLText.plain(from: s).split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    static func parse(html: String, url: URL) throws -> Article {
        var title = meta("og:title", in: html) ?? firstMatch(#"<title[^>]*>([\s\S]*?)</title>"#, in: html) ?? url.host ?? "Makale"
        title = clean(title)
        if title.isEmpty { title = url.host ?? "Makale" }
        let author = clean(meta("author", in: html) ?? meta("article:author", in: html) ?? meta("og:site_name", in: html) ?? url.host ?? "")

        // Gürültüyü at: yorumlar, betikler, gezinme, başlık/altlık, yan içerik, formlar, görsel bloklar
        var s = html.replacingOccurrences(of: #"<!--[\s\S]*?-->"#, with: " ", options: .regularExpression)
        s = s.replacingOccurrences(
            of: #"<(script|style|noscript|svg|iframe|form|nav|header|footer|aside|figure|button|select|template)\b[\s\S]*?</\1\s*>"#,
            with: " ", options: [.regularExpression, .caseInsensitive])

        // Ana içerik: en uzun <article>, yoksa <main>, yoksa <body>
        func longest(_ tag: String) -> String? {
            guard let re = try? NSRegularExpression(pattern: "<\(tag)\\b[^>]*>([\\s\\S]*?)</\(tag)\\s*>", options: [.caseInsensitive]) else { return nil }
            let ns = s as NSString
            return re.matches(in: s, range: NSRange(location: 0, length: ns.length))
                .map { ns.substring(with: $0.range(at: 1)) }
                .max { $0.count < $1.count }
        }
        let root = longest("article") ?? longest("main") ?? longest("body") ?? s

        guard let re = try? NSRegularExpression(pattern: #"<(h[1-6]|p|blockquote|pre|li)\b[^>]*>([\s\S]*?)</\1\s*>"#, options: [.caseInsensitive]) else {
            throw ArticleError.empty
        }
        let ns = root as NSString
        var blocks: [Block] = []
        for m in re.matches(in: root, range: NSRange(location: 0, length: ns.length)) {
            let tag = ns.substring(with: m.range(at: 1)).lowercased()
            let inner = ns.substring(with: m.range(at: 2))
            var text: String
            if tag == "pre" {
                text = HTMLText.plain(from: inner)
            } else {
                text = clean(inner)
            }
            let n = text.count
            let kind: Block.Kind
            switch tag {
            case "p":
                let endsSentence = text.last.map { ".!?:…”\"’)".contains($0) } ?? false
                guard n >= 30 || (n >= 15 && endsSentence) else { continue }
                kind = .paragraph
            case "li":
                guard n >= 20 else { continue }
                kind = .item
            case "blockquote":
                guard n >= 20 else { continue }
                kind = .quote
            case "pre":
                guard n >= 10 else { continue }
                kind = .pre
            default:
                guard (3...150).contains(n) else { continue }
                kind = .heading(Int(tag.dropFirst()) ?? 2)
            }
            if let last = blocks.last, last.text == text { continue }
            blocks.append(Block(kind: kind, text: text))
        }

        // Başlıkta yinelenen ilk <h1>'i at (başlığı kendimiz ekliyoruz)
        if case .heading(1)? = blocks.first?.kind, blocks.first?.text.lowercased() == title.lowercased() { blocks.removeFirst() }
        let total = blocks.reduce(0) { $0 + $1.text.count }
        guard total >= 500 else { throw ArticleError.empty }
        return Article(title: title, author: author, blocks: blocks, url: url)
    }

    // MARK: - EPUB üretimi

    private static func xml(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }

    static func chapterXHTML(_ a: Article) -> String {
        var body = "<h1>\(xml(a.title))</h1>\n"
        if !a.author.isEmpty { body += "<p><em>\(xml(a.author))</em></p>\n" }
        var inList = false
        for b in a.blocks {
            if case .item = b.kind {} else if inList { body += "</ul>\n"; inList = false }
            switch b.kind {
            case .heading(let l): let lv = min(max(l, 2), 4); body += "<h\(lv)>\(xml(b.text))</h\(lv)>\n"
            case .paragraph: body += "<p>\(xml(b.text))</p>\n"
            case .quote: body += "<blockquote><p>\(xml(b.text))</p></blockquote>\n"
            case .pre: body += "<pre>\(xml(b.text))</pre>\n"
            case .item:
                if !inList { body += "<ul>\n"; inList = true }
                body += "<li>\(xml(b.text))</li>\n"
            }
        }
        if inList { body += "</ul>\n" }
        body += "<p><a href=\"\(xml(a.url.absoluteString))\">\(xml(a.url.host ?? a.url.absoluteString))</a></p>\n"
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="utf-8"/><title>\(xml(a.title))</title></head>
        <body>
        \(body)</body></html>
        """
    }

    private static func writeEPUB(_ a: Article) throws -> URL {
        let fm = FileManager.default
        let work = fm.temporaryDirectory.appendingPathComponent("article-\(UUID().uuidString)")
        try fm.createDirectory(at: work.appendingPathComponent("META-INF"), withIntermediateDirectories: true)
        try fm.createDirectory(at: work.appendingPathComponent("OEBPS"), withIntermediateDirectories: true)
        try "application/epub+zip".write(to: work.appendingPathComponent("mimetype"), atomically: true, encoding: .ascii)
        try """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
        """.write(to: work.appendingPathComponent("META-INF/container.xml"), atomically: true, encoding: .utf8)
        try """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="bookid">urn:uuid:\(UUID().uuidString)</dc:identifier>
            <dc:title>\(xml(a.title))</dc:title>
            <dc:creator>\(xml(a.author))</dc:creator>
            <dc:language>en</dc:language>
            <dc:source>\(xml(a.url.absoluteString))</dc:source>
          </metadata>
          <manifest><item id="ch1" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
          <spine><itemref idref="ch1"/></spine>
        </package>
        """.write(to: work.appendingPathComponent("OEBPS/content.opf"), atomically: true, encoding: .utf8)
        try chapterXHTML(a).write(to: work.appendingPathComponent("OEBPS/chapter.xhtml"), atomically: true, encoding: .utf8)

        let safe = a.title.components(separatedBy: CharacterSet(charactersIn: "/\\:?*\"<>|\n")).joined(separator: " ")
            .trimmingCharacters(in: .whitespaces).prefix(60)
        let out = fm.temporaryDirectory.appendingPathComponent("\(safe.isEmpty ? "Makale" : String(safe)).epub")
        try? fm.removeItem(at: out)
        try ZipWriter.zipDirectory(work, to: out)
        try? fm.removeItem(at: work)
        return out
    }
}
