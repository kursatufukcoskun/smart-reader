import Foundation
import SwiftUI
import Combine
import UIKit
import AuthenticationServices

// MARK: Dosya biçimleri (platformdan bağımsız JSON, bkz. SYNC.md)

struct ItemsFile<T: Codable>: Codable {
    var version = 1
    var items: [T]
    var deleted: [String: Date]
}

struct LibraryEntry: Codable, Identifiable, Equatable {
    var id: UUID
    var title: String
    var author: String
    var kind: String
    var addedAt: Date
    var updatedAt: Date
    var tags: [String]?
    var chapterWords: [Int]?
    var pagesTotal: Int?
}

struct ProgressRecord: Codable, Equatable {
    var position: Int
    var scrollFraction: Double
    var progress: Double
    var updatedAt: Date
}
struct ProgressFile: Codable {
    var version = 1
    var books: [String: ProgressRecord]
}

struct LogFile: Codable {
    var version = 1
    var entries: [ReadingEntry]
}

struct SettingsFile: Codable, Equatable {
    var version = 1
    var updatedAt: Date
    var theme: String
    var font: String
    var fontScale: Double
    var lineHeight: Double
    var margin: Double
    var readingMode: String
    var columns: String
    var warmth: Double
    var edgeTap: Bool
    var highlightColor: String
}

enum SyncMerge {
    /// Her kayıtta damgası yeni olan kazanır; silme kaydı, kayıttan yeniyse kaydı düşürür.
    static func items<T: Identifiable>(local: [T], remote: [T], localDeleted: [UUID: Date], remoteDeleted: [UUID: Date],
                                       stamp: (T) -> Date) -> (items: [T], deleted: [UUID: Date]) where T.ID == UUID {
        var deleted = localDeleted
        for (k, v) in remoteDeleted { deleted[k] = max(deleted[k] ?? .distantPast, v) }
        var map: [UUID: T] = [:]
        for l in local { map[l.id] = l }
        for r in remote {
            if let l = map[r.id], stamp(l) >= stamp(r) { continue }
            map[r.id] = r
        }
        let kept = map.values.filter { item in
            guard let d = deleted[item.id] else { return true }
            return stamp(item) > d
        }
        return (kept.sorted { $0.id.uuidString < $1.id.uuidString }, deleted)
    }
}

@MainActor
final class SyncManager: ObservableObject {
    enum State: Equatable { case signedOut, idle, syncing, error(String) }

    @Published private(set) var state: State = .signedOut
    /// Büyük dosya aktarımı sürerken gösterilen not ("Yükleniyor: …").
    @Published private(set) var transferNote: String?
    @Published private(set) var lastSync: Date? = UserDefaults.standard.object(forKey: "lastSync") as? Date

    let auth = GoogleAuth()
    private var library: LibraryStore?
    private var highlights: HighlightStore?
    private var vocab: VocabStore?
    private var bookmarks: BookmarkStore?
    private var tracker: ReadingTracker?
    private var settings: ReaderSettings?
    private var bag = Set<AnyCancellable>()
    private var applyingRemoteSettings = false
    private var lastHighlightColor: HighlightColor?
    private var busy = false
    private var transferring = false
    private var pendingResync = false

    private var settingsStamp: Date {
        get { (UserDefaults.standard.object(forKey: "settingsUpdatedAt") as? Date) ?? .distantPast }
        set { UserDefaults.standard.set(newValue, forKey: "settingsUpdatedAt") }
    }

    private static let enc: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.sortedKeys]
        return e
    }()
    private static let dec: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    var isSignedIn: Bool { auth.isSignedIn }

    func attach(library: LibraryStore, highlights: HighlightStore, vocab: VocabStore, bookmarks: BookmarkStore,
                tracker: ReadingTracker, settings: ReaderSettings) {
        guard self.library == nil else { return }
        self.library = library; self.highlights = highlights; self.vocab = vocab; self.bookmarks = bookmarks
        self.tracker = tracker; self.settings = settings
        state = auth.isSignedIn ? .idle : .signedOut
        lastHighlightColor = settings.highlightColor
        settings.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                // Yalnızca vurgu rengi cihazlar arası senkronlanır; yazı boyutu, tema vb. cihaza özeldir.
                guard let self, !self.applyingRemoteSettings, settings.highlightColor != self.lastHighlightColor else { return }
                self.lastHighlightColor = settings.highlightColor
                self.settingsStamp = Date()
            }
            .store(in: &bag)
        if auth.isSignedIn { Task { await syncNow() } }
    }

    func signIn() async {
        do {
            try await auth.signIn()
            state = .idle
            await syncNow()
        } catch {
            if case SyncError.cancelled = error { return }
            if let e = error as? ASWebAuthenticationSessionError, e.code == .canceledLogin { return }
            state = .error(error.localizedDescription)
        }
    }

    func signOut() {
        auth.signOut()
        state = .signedOut
    }

    /// Uygulama arka plana giderken veya ön plana gelirken çağrılır.
    func syncSoon(inBackgroundTask: Bool = false) {
        guard auth.isSignedIn else { return }
        var bg: UIBackgroundTaskIdentifier = .invalid
        if inBackgroundTask {
            bg = UIApplication.shared.beginBackgroundTask(withName: "sync") { }
        }
        Task {
            await syncNow()
            if bg != .invalid { UIApplication.shared.endBackgroundTask(bg) }
        }
    }

    func syncNow() async {
        guard auth.isSignedIn, library != nil else { return }
        // Bir senkron sürerken yeni istek gelirse (ör. yeni vurgu) bitince bir tur daha yapılır.
        if busy { pendingResync = true; return }
        guard let library, let highlights, let vocab, let bookmarks, let tracker, let settings else { return }
        busy = true
        state = .syncing
        var files: [String: String] = [:]
        let drive = DriveClient(auth: auth)
        do {
            repeat {
                pendingResync = false
                files = try await drive.listFiles()

                try await syncCollection(name: "highlights.json", files: files, drive: drive,
                                         local: highlights.items, localDeleted: highlights.deleted, stamp: { $0.stamp }) {
                    highlights.replaceFromSync(items: $0, deleted: $1)
                }
                try await syncCollection(name: "vocab.json", files: files, drive: drive,
                                         local: vocab.entries, localDeleted: vocab.deleted, stamp: { $0.stamp }) {
                    vocab.replaceFromSync(entries: $0, deleted: $1)
                }
                try await syncCollection(name: "bookmarks.json", files: files, drive: drive,
                                         local: bookmarks.items, localDeleted: bookmarks.deleted, stamp: { $0.stamp }) {
                    bookmarks.replaceFromSync(items: $0, deleted: $1)
                }
                try await syncLibrary(files: files, drive: drive, library: library)
                try await syncProgress(files: files, drive: drive, library: library)
                try await syncLog(files: files, drive: drive, tracker: tracker)
                try await syncSettings(files: files, drive: drive, settings: settings)
            } while pendingResync

            lastSync = Date()
            UserDefaults.standard.set(lastSync, forKey: "lastSync")
            state = .idle
            busy = false
        } catch {
            busy = false
            if case SyncError.signedOut = error { state = .signedOut } else { state = .error(error.localizedDescription) }
            return
        }

        // Büyük dosya aktarımları ayrı çalışır; sürerken vurgu/ilerleme senkronu engellenmez.
        guard !transferring else { return }
        transferring = true
        defer { transferring = false; transferNote = nil }
        do {
            try await syncBookFiles(files: files, drive: drive, library: library)
        } catch {
            if case SyncError.signedOut = error { state = .signedOut } else { state = .error(error.localizedDescription) }
        }
    }

    // MARK: Parçalar

    private func fetch<T: Decodable>(_ type: T.Type, name: String, files: [String: String], drive: DriveClient) async throws -> (value: T?, raw: Data?) {
        guard let id = files[name] else { return (nil, nil) }
        let data = try await drive.download(id: id)
        return ((try? Self.dec.decode(T.self, from: data)), data)
    }

    private func push<T: Encodable>(_ value: T, name: String, files: [String: String], drive: DriveClient, remoteRaw: Data?) async throws {
        let data = try Self.enc.encode(value)
        if data == remoteRaw { return }
        try await drive.upload(name: name, existingID: files[name], data: data)
    }

    private func syncCollection<T: Codable & Identifiable>(name: String, files: [String: String], drive: DriveClient,
                                                           local: [T], localDeleted: [UUID: Date], stamp: (T) -> Date,
                                                           apply: ([T], [UUID: Date]) -> Void) async throws where T.ID == UUID {
        let (remote, raw) = try await fetch(ItemsFile<T>.self, name: name, files: files, drive: drive)
        let remoteDeleted = Dictionary(uniqueKeysWithValues: (remote?.deleted ?? [:]).compactMap { k, v in UUID(uuidString: k).map { ($0, v) } })
        let merged = SyncMerge.items(local: local, remote: remote?.items ?? [], localDeleted: localDeleted,
                                     remoteDeleted: remoteDeleted, stamp: stamp)
        apply(merged.items, merged.deleted)
        let file = ItemsFile(items: merged.items, deleted: Dictionary(uniqueKeysWithValues: merged.deleted.map { ($0.key.uuidString, $0.value) }))
        try await push(file, name: name, files: files, drive: drive, remoteRaw: raw)
    }

    private func syncLibrary(files: [String: String], drive: DriveClient, library: LibraryStore) async throws {
        let name = "library.json"
        let (remote, raw) = try await fetch(ItemsFile<LibraryEntry>.self, name: name, files: files, drive: drive)
        let remoteDeleted = Dictionary(uniqueKeysWithValues: (remote?.deleted ?? [:]).compactMap { k, v in UUID(uuidString: k).map { ($0, v) } })
        let merged = SyncMerge.items(local: library.books.map(\.syncEntry), remote: remote?.items ?? [],
                                     localDeleted: library.deleted, remoteDeleted: remoteDeleted, stamp: { $0.updatedAt })
        library.applyRemoteLibrary(merged.items, deleted: merged.deleted)
        let file = ItemsFile(items: merged.items, deleted: Dictionary(uniqueKeysWithValues: merged.deleted.map { ($0.key.uuidString, $0.value) }))
        try await push(file, name: name, files: files, drive: drive, remoteRaw: raw)
    }

    /// Kitap ve kapak dosyalarını yükler/indirir; her yerden silinenleri Drive'dan kaldırır.
    private func syncBookFiles(files: [String: String], drive: DriveClient, library: LibraryStore) async throws {
        // 1) Her yerden silinen kitapların Drive dosyaları
        for (id, _) in library.deleted {
            for kind in [Book.Kind.epub, .pdf] {
                if let fid = files[Paths.driveBookName(id, kind)] { try await drive.delete(id: fid) }
            }
            if let fid = files[Paths.driveCoverName(id)] { try await drive.delete(id: fid) }
        }
        // 2) Yerel kitaplar ve kapaklar buluta
        for b in library.books where !b.isRemoteOnly {
            let name = Paths.driveBookName(b.id, b.kind)
            if files[name] == nil {
                transferNote = "Yükleniyor: \(b.title)"
                let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("up-\(UUID().uuidString)")
                defer { try? FileManager.default.removeItem(at: tmp) }
                let mime: String
                switch b.kind {
                case .epub:
                    mime = "application/epub+zip"
                    let root = Paths.epubRoot(for: b)
                    try await Task.detached(priority: .utility) { try ZipWriter.zipDirectory(root, to: tmp) }.value
                case .pdf:
                    mime = "application/pdf"
                    let src = Paths.file(for: b)
                    guard FileManager.default.fileExists(atPath: src.path) else { continue }
                    try FileManager.default.copyItem(at: src, to: tmp)
                }
                try await drive.uploadFile(name: name, existingID: nil, file: tmp, mime: mime)
            }
            if files[Paths.driveCoverName(b.id)] == nil, let c = Paths.cover(for: b),
               let data = try? Data(contentsOf: c) {
                try await drive.upload(name: Paths.driveCoverName(b.id), existingID: nil, data: data)
            }
        }
        // 3) Bulutta olup kapağı henüz inmemiş kitaplar
        for b in library.books where b.coverFile == nil {
            guard let fid = files[Paths.driveCoverName(b.id)] else { continue }
            let data = try await drive.download(id: fid)
            let local = "\(b.id)-cover.jpg"
            try data.write(to: Paths.base.appendingPathComponent(local))
            library.setCover(b.id, file: local)
        }
    }

    /// Bulutta duran kitabı bu cihaza indirir (kitaba dokununca çağrılır).
    func downloadBook(_ id: UUID) async throws {
        guard let library, let b = library.book(id), b.isRemoteOnly else { return }
        guard auth.isSignedIn else { throw SyncError.signedOut }
        let drive = DriveClient(auth: auth)
        let files = try await drive.listFiles()
        guard let fid = files[Paths.driveBookName(id, b.kind)] else { throw SyncError.missingBook }
        let tmp = try await drive.downloadFile(id: fid)
        try await library.installDownloaded(id, from: tmp)
    }

    private func syncProgress(files: [String: String], drive: DriveClient, library: LibraryStore) async throws {
        let name = "progress.json"
        let (remote, raw) = try await fetch(ProgressFile.self, name: name, files: files, drive: drive)
        var merged = remote?.books ?? [:]
        for b in library.books {
            let key = b.id.uuidString
            let localStamp = b.progressUpdatedAt ?? .distantPast
            let remoteRec = merged[key]
            if let r = remoteRec, r.updatedAt > localStamp {
                // Bu cihazda hiç okunmadıysa ya da fark önemsizse sessizce uygula; aksi halde yerel konumu
                // koru ve okuyucuda "Diğer cihazda şurada kaldın" diye sor.
                if b.progressUpdatedAt == nil || !library.isSignificant(b, position: r.position, scrollFraction: r.scrollFraction) {
                    library.clearRemoteOffer(b.id)
                    library.applyRemoteProgress(b.id, position: r.position, scrollFraction: r.scrollFraction, updatedAt: r.updatedAt)
                } else {
                    library.offerRemoteProgress(b.id, .init(position: r.position, scrollFraction: r.scrollFraction,
                                                            progress: r.progress, updatedAt: r.updatedAt))
                }
            } else {
                library.clearRemoteOffer(b.id)
                if b.progressUpdatedAt != nil {
                    merged[key] = ProgressRecord(position: b.position, scrollFraction: b.scrollFraction,
                                                 progress: b.progress, updatedAt: localStamp)
                }
            }
        }
        try await push(ProgressFile(books: merged), name: name, files: files, drive: drive, remoteRaw: raw)
    }

    private func syncLog(files: [String: String], drive: DriveClient, tracker: ReadingTracker) async throws {
        let name = "reading-log.json"
        let (remote, raw) = try await fetch(LogFile.self, name: name, files: files, drive: drive)
        let merged = tracker.mergeRemote(remote?.entries ?? [])
        let sorted = merged.sorted { $0.id < $1.id }
        try await push(LogFile(entries: sorted), name: name, files: files, drive: drive, remoteRaw: raw)
    }

    private func syncSettings(files: [String: String], drive: DriveClient, settings: ReaderSettings) async throws {
        let name = "settings.json"
        let (remote, raw) = try await fetch(SettingsFile.self, name: name, files: files, drive: drive)
        if let r = remote, r.updatedAt > settingsStamp {
            applyingRemoteSettings = true
            // Okuma görünümü (tema, yazı, boyut, kolon…) bilerek uygulanmaz: telefon ve iPad ekranları farklı.
            settings.highlightColor = HighlightColor(rawValue: r.highlightColor) ?? settings.highlightColor
            settingsStamp = r.updatedAt
            // objectWillChange bildirimleri sonraki turda gelir; bayrağı o zamana kadar açık tut.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.applyingRemoteSettings = false }
        } else if remote == nil || settingsStamp > (remote?.updatedAt ?? .distantPast) {
            guard settingsStamp > .distantPast else { return }
            let f = SettingsFile(updatedAt: settingsStamp, theme: settings.theme.rawValue, font: settings.font.rawValue,
                                 fontScale: settings.fontScale, lineHeight: settings.lineHeight, margin: settings.margin,
                                 readingMode: settings.readingMode.rawValue, columns: settings.columns.rawValue,
                                 warmth: settings.warmth, edgeTap: settings.edgeTap, highlightColor: settings.highlightColor.rawValue)
            try await push(f, name: name, files: files, drive: drive, remoteRaw: raw)
        }
    }
}
