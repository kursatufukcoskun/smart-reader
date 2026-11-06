import Foundation
import Compression

/// Bağımlılıksız, minimal ZIP okuyucu (stored + deflate). EPUB için yeterli.
struct ZipArchive {
    struct Entry {
        let name: String
        let method: Int
        let compressedSize: Int
        let uncompressedSize: Int
        let localHeaderOffset: Int
    }

    enum ZipError: LocalizedError {
        case invalid, unsupported(String), inflateFailed(String)
        var errorDescription: String? {
            switch self {
            case .invalid: return "Geçersiz ZIP/EPUB dosyası."
            case .unsupported(let s): return "Desteklenmeyen ZIP özelliği: \(s)"
            case .inflateFailed(let n): return "Dosya açılamadı: \(n)"
            }
        }
    }

    private let bytes: [UInt8]
    let entries: [Entry]

    private static func u16(_ b: [UInt8], _ o: Int) -> Int { Int(b[o]) | (Int(b[o + 1]) << 8) }
    private static func u32(_ b: [UInt8], _ o: Int) -> Int { u16(b, o) | (u16(b, o + 2) << 16) }

    init(url: URL) throws {
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        let b = [UInt8](data)
        guard b.count >= 22 else { throw ZipError.invalid }

        // End of central directory kaydını sondan ara.
        var eocd = -1
        var i = b.count - 22
        let lowest = max(0, b.count - 22 - 65535)
        while i >= lowest {
            if Self.u32(b, i) == 0x06054b50 { eocd = i; break }
            i -= 1
        }
        guard eocd >= 0 else { throw ZipError.invalid }

        let count = Self.u16(b, eocd + 10)
        var offset = Self.u32(b, eocd + 16)
        var result: [Entry] = []
        result.reserveCapacity(count)

        for _ in 0..<count {
            guard offset + 46 <= b.count, Self.u32(b, offset) == 0x02014b50 else { throw ZipError.invalid }
            let method = Self.u16(b, offset + 10)
            let csize = Self.u32(b, offset + 20)
            let usize = Self.u32(b, offset + 24)
            let nlen = Self.u16(b, offset + 28)
            let elen = Self.u16(b, offset + 30)
            let clen = Self.u16(b, offset + 32)
            let lho = Self.u32(b, offset + 42)
            guard offset + 46 + nlen <= b.count else { throw ZipError.invalid }
            if csize == 0xFFFFFFFF || usize == 0xFFFFFFFF || lho == 0xFFFFFFFF { throw ZipError.unsupported("zip64") }
            let name = String(decoding: b[(offset + 46)..<(offset + 46 + nlen)], as: UTF8.self)
            result.append(Entry(name: name, method: method, compressedSize: csize, uncompressedSize: usize, localHeaderOffset: lho))
            offset += 46 + nlen + elen + clen
        }
        bytes = b
        entries = result
    }

    func data(for entry: Entry) throws -> Data {
        let lh = entry.localHeaderOffset
        guard lh + 30 <= bytes.count, Self.u32(bytes, lh) == 0x04034b50 else { throw ZipError.invalid }
        let start = lh + 30 + Self.u16(bytes, lh + 26) + Self.u16(bytes, lh + 28)
        let end = start + entry.compressedSize
        guard end <= bytes.count else { throw ZipError.invalid }
        if entry.uncompressedSize == 0 { return Data() }

        switch entry.method {
        case 0:
            return Data(bytes[start..<end])
        case 8:
            var dst = [UInt8](repeating: 0, count: entry.uncompressedSize)
            let written = bytes.withUnsafeBufferPointer { src in
                dst.withUnsafeMutableBufferPointer { d in
                    compression_decode_buffer(d.baseAddress!, d.count, src.baseAddress! + start, entry.compressedSize, nil, COMPRESSION_ZLIB)
                }
            }
            guard written == entry.uncompressedSize else { throw ZipError.inflateFailed(entry.name) }
            return Data(dst)
        default:
            throw ZipError.unsupported("sıkıştırma yöntemi \(entry.method)")
        }
    }

    func extractAll(to dir: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let rootPath = dir.standardizedFileURL.path
        for entry in entries where !entry.name.hasSuffix("/") {
            let target = dir.appendingPathComponent(entry.name).standardizedFileURL
            // zip-slip koruması
            guard target.path.hasPrefix(rootPath + "/") else { continue }
            try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data(for: entry).write(to: target)
        }
    }
}
