import Foundation
import CryptoKit

/// Kitabın cihazdan bağımsız, içerikten türetilen kimliği (senkron için).
/// EPUB: OPF dosyasının SHA-256'sı. PDF: ilk 2 MiB + dosya boyutunun (ondalık metin) SHA-256'sı.
/// Her ikisinde de özetin ilk 16 baytı UUID yapılır (sürüm 5, RFC 4122 varyantı).
enum BookIdentity {
    static func uuid(from digest: SHA256.Digest) -> UUID {
        var b = Array(digest.prefix(16))
        b[6] = (b[6] & 0x0F) | 0x50
        b[8] = (b[8] & 0x3F) | 0x80
        return UUID(uuid: b.withUnsafeBytes { $0.load(as: uuid_t.self) })
    }

    static func epubID(opf: URL) -> UUID? {
        guard let d = try? Data(contentsOf: opf) else { return nil }
        return uuid(from: SHA256.hash(data: d))
    }

    static func pdfID(file: URL) -> UUID? {
        guard let h = try? FileHandle(forReadingFrom: file) else { return nil }
        defer { try? h.close() }
        let head = (try? h.read(upToCount: 2 * 1024 * 1024)) ?? Data()
        let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int) ?? 0
        var data = head
        data.append(Data(String(size).utf8))
        return uuid(from: SHA256.hash(data: data))
    }
}

enum IDMigration {
    /// Kitabın dosyalarını yeni kimliğe taşır.
    static func relocate(_ book: inout Book, to newID: UUID) {
        let fm = FileManager.default
        let old = book
        var nb = book
        nb.id = newID
        if fm.fileExists(atPath: Paths.file(for: old).path) {
            try? fm.moveItem(at: Paths.file(for: old), to: Paths.file(for: nb))
        }
        if fm.fileExists(atPath: Paths.epubRoot(for: old).path) {
            try? fm.moveItem(at: Paths.epubRoot(for: old), to: Paths.epubRoot(for: nb))
        }
        if let c = old.coverFile {
            let newName = "\(newID)-cover.jpg"
            try? fm.moveItem(at: Paths.base.appendingPathComponent(c), to: Paths.base.appendingPathComponent(newName))
            nb.coverFile = newName
        }
        book = nb
    }

    /// Eski (rastgele kimlikli) kitapları bir kez içerik kimliğine çevirir; vurgu, defter ve okuma kayıtlarını da günceller.
    static func run() {
        let url = Paths.base.appendingPathComponent("books.json")
        guard let data = try? Data(contentsOf: url),
              var books = try? JSONDecoder().decode([Book].self, from: data) else { return }
        var taken = Set(books.map(\.id))
        var map: [UUID: UUID] = [:]
        var changed = false
        for i in books.indices where books[i].idChecked != true {
            var b = books[i]
            let nid: UUID?
            switch b.kind {
            case .epub:
                nid = (try? EPUBPackage.load(from: Paths.epubRoot(for: b))).flatMap { BookIdentity.epubID(opf: $0.opfURL) }
            case .pdf:
                nid = BookIdentity.pdfID(file: Paths.file(for: b))
            }
            if let nid, nid != b.id, !taken.contains(nid) {
                let oldID = b.id
                relocate(&b, to: nid)
                taken.insert(nid)
                map[oldID] = nid
            }
            b.idChecked = true
            books[i] = b
            changed = true
        }
        guard changed else { return }
        if let out = try? JSONEncoder().encode(books) { try? out.write(to: url, options: .atomic) }
        guard !map.isEmpty else { return }
        for (file, key) in [("highlights.json", "bookID"), ("vocab.json", "bookID"), ("reading-log.json", "bookID")] {
            remap(file: file, key: key, map: map)
        }
    }

    private static func remap(file: String, key: String, map: [UUID: UUID]) {
        let url = Paths.base.appendingPathComponent(file)
        guard let data = try? Data(contentsOf: url),
              var arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return }
        for i in arr.indices {
            if let s = arr[i][key] as? String, let u = UUID(uuidString: s), let n = map[u] { arr[i][key] = n.uuidString }
        }
        if let out = try? JSONSerialization.data(withJSONObject: arr) { try? out.write(to: url, options: .atomic) }
    }
}

enum DeviceID {
    static let current: String = {
        let k = "deviceID"
        if let s = UserDefaults.standard.string(forKey: k) { return s }
        let s = UUID().uuidString
        UserDefaults.standard.set(s, forKey: k)
        return s
    }()
}
