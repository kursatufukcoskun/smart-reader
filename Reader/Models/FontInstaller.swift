import Foundation

/// Pakete eklenen yazı tiplerini (Bookerly) WebView'in okuyabileceği klasöre kopyalar.
enum FontInstaller {
    static let dir: URL = {
        let u = Paths.base.appendingPathComponent("Fonts", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }()

    private static let files: [(file: String, weight: Int, style: String)] = [
        ("BookerlyLCD_Rg", 400, "normal"),
        ("BookerlyLCD_Bd", 700, "normal"),
        ("BookerlyLCD_It", 400, "italic"),
        ("BookerlyLCD_BdIt", 700, "italic"),
    ]

    /// Uygulama açılırken bir kez çağrılır.
    static func install() {
        let fm = FileManager.default
        for f in files {
            let dest = dir.appendingPathComponent(f.file + ".ttf")
            if fm.fileExists(atPath: dest.path) { continue }
            let src = Bundle.main.url(forResource: f.file, withExtension: "ttf")
                ?? Bundle.main.url(forResource: f.file, withExtension: "ttf", subdirectory: "Fonts")
            if let src { try? fm.copyItem(at: src, to: dest) }
        }
    }

    /// Bookerly dosyaları yoksa boş döner (yazı tipi Georgia'ya düşer).
    static var bookerlyCSS: String {
        files.compactMap { f -> String? in
            let u = dir.appendingPathComponent(f.file + ".ttf")
            guard FileManager.default.fileExists(atPath: u.path) else { return nil }
            return "@font-face { font-family: 'BookerlyReader'; src: url('\(u.absoluteString)'); font-weight: \(f.weight); font-style: \(f.style); }"
        }.joined(separator: "\n")
    }
}
