import Foundation

struct SpineItem: Sendable {
    let id: String
    let url: URL
}

struct TOCEntry: Identifiable, Sendable {
    let id = UUID()
    let title: String
    let level: Int
    let spineIndex: Int
    let fragment: String?
}

func normalizedPath(_ url: URL) -> String {
    url.resolvingSymlinksInPath().standardizedFileURL.path
}

struct EPUBPackage: Sendable {
    let rootDir: URL
    let opfURL: URL
    let title: String
    let author: String
    let spine: [SpineItem]
    let toc: [TOCEntry]
    let coverURL: URL?

    enum EPUBError: LocalizedError {
        case missingContainer, missingOPF, emptySpine
        var errorDescription: String? {
            switch self {
            case .missingContainer: return "container.xml bulunamadı."
            case .missingOPF: return "OPF dosyası okunamadı."
            case .emptySpine: return "Kitapta okunabilir bölüm yok."
            }
        }
    }

    static func load(from rootDir: URL) throws -> EPUBPackage {
        let containerURL = rootDir.appendingPathComponent("META-INF/container.xml")
        guard let containerData = try? Data(contentsOf: containerURL) else { throw EPUBError.missingContainer }
        let cp = ContainerParser()
        let p1 = XMLParser(data: containerData); p1.delegate = cp; p1.parse()
        guard let opfPath = cp.opfPath else { throw EPUBError.missingContainer }

        let opfURL = rootDir.appendingPathComponent(opfPath.removingPercentEncoding ?? opfPath).standardizedFileURL
        guard let opfData = try? Data(contentsOf: opfURL) else { throw EPUBError.missingOPF }
        let op = OPFParser()
        let p2 = XMLParser(data: opfData); p2.delegate = op; p2.parse()

        func resolve(_ href: String, base: URL) -> (url: URL, fragment: String?) {
            let parts = href.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)
            let raw = String(parts.first ?? "")
            let path = raw.removingPercentEncoding ?? raw
            let frag = parts.count > 1 ? String(parts[1]) : nil
            let url = base.deletingLastPathComponent().appendingPathComponent(path).standardizedFileURL
            return (url, frag)
        }

        var spine: [SpineItem] = []
        for idref in op.spineIDs {
            if let item = op.manifest[idref] {
                spine.append(SpineItem(id: idref, url: resolve(item.href, base: opfURL).url))
            }
        }
        guard !spine.isEmpty else { throw EPUBError.emptySpine }

        // İçindekiler: önce EPUB3 nav, yoksa NCX
        var tocURL: URL?
        if let nav = op.manifest.values.first(where: { $0.props.contains("nav") }) {
            tocURL = resolve(nav.href, base: opfURL).url
        } else if let ncxID = op.spineTOCID, let ncx = op.manifest[ncxID] {
            tocURL = resolve(ncx.href, base: opfURL).url
        } else if let ncx = op.manifest.values.first(where: { $0.type == "application/x-dtbncx+xml" }) {
            tocURL = resolve(ncx.href, base: opfURL).url
        }

        var toc: [TOCEntry] = []
        if let tocURL, let data = try? Data(contentsOf: tocURL) {
            let tp = TOCParser()
            let p3 = XMLParser(data: data); p3.delegate = tp; p3.parse()
            let spinePaths = spine.map { normalizedPath($0.url) }
            for raw in tp.entries where !raw.title.isEmpty && !raw.src.isEmpty {
                let r = resolve(raw.src, base: tocURL)
                if let idx = spinePaths.firstIndex(of: normalizedPath(r.url)) {
                    toc.append(TOCEntry(title: raw.title, level: raw.level, spineIndex: idx, fragment: r.fragment))
                }
            }
        }
        if toc.isEmpty {
            toc = spine.enumerated().map { TOCEntry(title: "Bölüm \($0.offset + 1)", level: 0, spineIndex: $0.offset, fragment: nil) }
        }

        // Kapak
        var cover: URL?
        if let item = op.manifest.values.first(where: { $0.props.contains("cover-image") }) {
            cover = resolve(item.href, base: opfURL).url
        } else if let cid = op.coverMetaID, let item = op.manifest[cid] {
            cover = resolve(item.href, base: opfURL).url
        }

        return EPUBPackage(rootDir: rootDir, opfURL: opfURL, title: op.title, author: op.author, spine: spine, toc: toc, coverURL: cover)
    }
}

// MARK: - XML parsers

private func localName(_ name: String) -> String {
    String(name.split(separator: ":").last ?? Substring(name))
}

private final class ContainerParser: NSObject, XMLParserDelegate {
    var opfPath: String?
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        if localName(elementName) == "rootfile", opfPath == nil {
            opfPath = attributeDict["full-path"]
        }
    }
}

private final class OPFParser: NSObject, XMLParserDelegate {
    var title = ""
    var author = ""
    var manifest: [String: (href: String, type: String, props: String)] = [:]
    var spineIDs: [String] = []
    var spineTOCID: String?
    var coverMetaID: String?
    private var buffer = ""

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        buffer = ""
        switch localName(elementName) {
        case "item":
            if let id = attributeDict["id"], let href = attributeDict["href"] {
                manifest[id] = (href, attributeDict["media-type"] ?? "", attributeDict["properties"] ?? "")
            }
        case "itemref":
            if let idref = attributeDict["idref"] { spineIDs.append(idref) }
        case "spine":
            spineTOCID = attributeDict["toc"]
        case "meta":
            if attributeDict["name"] == "cover", let c = attributeDict["content"] { coverMetaID = c }
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { buffer += string }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        let text = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
        switch localName(elementName) {
        case "title": if title.isEmpty { title = text }
        case "creator": if author.isEmpty { author = text }
        default: break
        }
        buffer = ""
    }
}

private final class TOCParser: NSObject, XMLParserDelegate {
    struct Raw { var title: String; var src: String; var level: Int }
    var entries: [Raw] = []

    // NCX
    private var navPointStack: [Int] = []
    private var textBuf = ""
    // EPUB3 nav
    private var inTocNav = false
    private var tocNavDone = false
    private var olDepth = 0
    private var aHref: String?
    private var aText = ""

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        switch localName(elementName) {
        case "navPoint":
            entries.append(Raw(title: "", src: "", level: navPointStack.count))
            navPointStack.append(entries.count - 1)
        case "text":
            textBuf = ""
        case "content":
            if let i = navPointStack.last { entries[i].src = attributeDict["src"] ?? "" }
        case "nav":
            let t = attributeDict["epub:type"] ?? attributeDict["type"] ?? ""
            if t.contains("toc"), !tocNavDone { inTocNav = true }
        case "ol":
            if inTocNav { olDepth += 1 }
        case "a":
            if inTocNav { aHref = attributeDict["href"]; aText = "" }
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        textBuf += string
        if aHref != nil { aText += string }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        switch localName(elementName) {
        case "text":
            if let i = navPointStack.last, entries[i].title.isEmpty {
                entries[i].title = textBuf.trimmingCharacters(in: .whitespacesAndNewlines)
            }
        case "navPoint":
            _ = navPointStack.popLast()
        case "nav":
            if inTocNav { inTocNav = false; tocNavDone = true }
        case "ol":
            if inTocNav { olDepth -= 1 }
        case "a":
            if inTocNav, let h = aHref {
                let clean = aText.split(whereSeparator: \.isWhitespace).joined(separator: " ")
                entries.append(Raw(title: clean, src: h, level: max(olDepth - 1, 0)))
                aHref = nil
            }
        default: break
        }
    }
}
