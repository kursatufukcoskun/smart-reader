import Foundation
import Compression

/// Açılmış EPUB klasöründen yeniden EPUB (ZIP) üretir. `mimetype` ilk girdi ve sıkıştırmasızdır.
enum ZipWriter {
    enum ZipWriteError: LocalizedError {
        case tooLarge
        var errorDescription: String? { "Kitap dosyası çok büyük." }
    }

    private static let crcTable: [UInt32] = (0..<256).map { i in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        return c
    }

    private static func crc32(_ d: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        d.withUnsafeBytes { (buf: UnsafeRawBufferPointer) in
            for b in buf { c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        }
        return ~c
    }

    private static func deflate(_ raw: Data) -> Data? {
        let cap = raw.count + raw.count / 8 + 1024
        var dst = [UInt8](repeating: 0, count: cap)
        let n = raw.withUnsafeBytes { (src: UnsafeRawBufferPointer) -> Int in
            dst.withUnsafeMutableBufferPointer { d in
                compression_encode_buffer(d.baseAddress!, cap, src.bindMemory(to: UInt8.self).baseAddress!, raw.count, nil, COMPRESSION_ZLIB)
            }
        }
        return n > 0 ? Data(dst[0..<n]) : nil
    }

    static func zipDirectory(_ dir: URL, to out: URL) throws {
        let fm = FileManager.default
        let root = dir.resolvingSymlinksInPath().path
        var rels: [String] = []
        if let en = fm.enumerator(at: dir, includingPropertiesForKeys: [.isRegularFileKey]) {
            for case let u as URL in en {
                guard (try? u.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else { continue }
                let p = u.resolvingSymlinksInPath().path
                if p.hasPrefix(root + "/") { rels.append(String(p.dropFirst(root.count + 1))) }
            }
        }
        rels.sort { a, b in
            if (a == "mimetype") != (b == "mimetype") { return a == "mimetype" }
            return a < b
        }

        var body = Data()
        var central = Data()
        for rel in rels {
            let raw = try Data(contentsOf: dir.appendingPathComponent(rel))
            let crc = crc32(raw)
            var method: UInt16 = 0
            var payload = raw
            if rel != "mimetype", raw.count > 64, let c = deflate(raw), c.count < raw.count { method = 8; payload = c }
            let name = Data(rel.utf8)
            guard body.count < 0xFFFF_0000, payload.count < 0xFFFF_0000 else { throw ZipWriteError.tooLarge }
            let offset = UInt32(body.count)

            body.le32(0x04034b50); body.le16(20); body.le16(0x0800); body.le16(method)
            body.le16(0); body.le16(0x0021)
            body.le32(crc); body.le32(UInt32(payload.count)); body.le32(UInt32(raw.count))
            body.le16(UInt16(name.count)); body.le16(0)
            body.append(name); body.append(payload)

            central.le32(0x02014b50); central.le16(20); central.le16(20); central.le16(0x0800); central.le16(method)
            central.le16(0); central.le16(0x0021)
            central.le32(crc); central.le32(UInt32(payload.count)); central.le32(UInt32(raw.count))
            central.le16(UInt16(name.count)); central.le16(0); central.le16(0)
            central.le16(0); central.le16(0); central.le32(0); central.le32(offset)
            central.append(name)
        }
        var eocd = Data()
        eocd.le32(0x06054b50); eocd.le16(0); eocd.le16(0)
        eocd.le16(UInt16(rels.count)); eocd.le16(UInt16(rels.count))
        eocd.le32(UInt32(central.count)); eocd.le32(UInt32(body.count)); eocd.le16(0)

        var all = body
        all.append(central); all.append(eocd)
        try all.write(to: out, options: .atomic)
    }
}

private extension Data {
    mutating func le16(_ v: UInt16) { append(UInt8(v & 0xFF)); append(UInt8(v >> 8)) }
    mutating func le32(_ v: UInt32) { for i in 0..<4 { append(UInt8((v >> (8 * UInt32(i))) & 0xFF)) } }
}
