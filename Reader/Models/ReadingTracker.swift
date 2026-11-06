import Foundation
import SwiftUI
import UIKit

enum StatsPeriod: String, CaseIterable, Identifiable {
    case week, month, year
    var id: String { rawValue }
    var title: String {
        switch self {
        case .week: return "Hafta"
        case .month: return "Ay"
        case .year: return "Yıl"
        }
    }
}

/// Bir kitabın bir gündeki okuma kaydı.
struct ReadingEntry: Codable, Identifiable, Hashable {
    var day: String          // yyyy-MM-dd
    var bookID: UUID
    var seconds: Double = 0
    var words: Int = 0
    var sessions: Int = 0
    /// Kaydı yazan cihaz (iki cihazın aynı gün/kitap sayaçları birbirini ezmesin)
    var device: String?
    var id: String { "\(day)|\(bookID.uuidString)|\(device ?? "")" }
}

struct StatBucket: Identifiable {
    var id: Date { start }
    var start: Date
    var seconds: Double
    var words: Int
    var byBook: [UUID: Double]   // saniye
    var pages: Double { Double(words) / Book.wordsPerPage }
}

enum Duration {
    /// "4 dk", "1 sa 12 dk", "<1 dk"
    static func text(_ seconds: Double) -> String {
        let m = Int((seconds / 60).rounded())
        if seconds < 30 { return "<1 dk" }
        if m < 60 { return "\(max(m, 1)) dk" }
        let h = m / 60, r = m % 60
        return r == 0 ? "\(h) sa" : "\(h) sa \(r) dk"
    }
}

/// Aktif okuma süresini ve okunan kelimeleri kaydeder.
@MainActor
final class ReadingTracker: ObservableObject {
    @Published private(set) var entries: [ReadingEntry] = []

    private var currentBook: UUID?
    private var lastActivity = Date.distantPast
    private var lastPos: Double?
    private var timer: Timer?
    private var appActive = true
    private var saveTask: Task<Void, Never>?
    private let tick: Double = 5
    private let idleLimit: TimeInterval = 120
    private var url: URL { Paths.base.appendingPathComponent("reading-log.json") }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
    static func dayKey(_ d: Date) -> String { dayFormatter.string(from: d) }
    static func date(from key: String) -> Date? { dayFormatter.date(from: key) }

    init() {
        load()
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.appActive = true }
        }
        nc.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.appActive = false; self?.save() }
        }
    }

    // MARK: Oturum

    func begin(bookID: UUID, position: Double) {
        end()
        currentBook = bookID
        lastPos = position
        lastActivity = Date()
        bump(bookID) { $0.sessions += 1 }
        timer = Timer.scheduledTimer(withTimeInterval: tick, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.onTick() }
        }
    }

    func end() {
        timer?.invalidate()
        timer = nil
        currentBook = nil
        lastPos = nil
        save()
    }

    /// Kullanıcı etkileşimi (idle sayacını sıfırlar).
    func touch() { lastActivity = Date() }

    /// Okuma konumu değişti (kelime cinsinden). Yalnızca makul ileri adımlar "okunan" sayılır.
    func moved(to pos: Double) {
        touch()
        guard let book = currentBook else { return }
        if let last = lastPos {
            let d = pos - last
            if d > 0 && d <= 1500 { bump(book) { $0.words += Int(d.rounded()) } }
        }
        lastPos = pos
    }

    /// Atlama (içindekiler, bölüm değiştirme): okunan kelime olarak sayılmaz.
    func jumped(to pos: Double) {
        lastPos = pos
        touch()
    }

    private func onTick() {
        guard let book = currentBook, appActive,
              Date().timeIntervalSince(lastActivity) < idleLimit else { return }
        bump(book) { $0.seconds += tick }
    }

    private func bump(_ bookID: UUID, _ change: (inout ReadingEntry) -> Void) {
        let day = Self.dayKey(Date())
        let dev = DeviceID.current
        if let i = entries.firstIndex(where: { $0.day == day && $0.bookID == bookID && $0.device == dev }) {
            change(&entries[i])
        } else {
            var e = ReadingEntry(day: day, bookID: bookID, device: dev)
            change(&e)
            entries.append(e)
        }
        scheduleSave()
    }

    // MARK: Hız ve tahmin

    /// Son 30 günün ortalama okuma hızı (kelime/dk). Veri azsa 200.
    var wordsPerMinute: Double {
        let cutoff = Self.dayKey(Calendar.current.date(byAdding: .day, value: -30, to: Date()) ?? Date())
        let recent = entries.filter { $0.day >= cutoff }
        let sec = recent.reduce(0) { $0 + $1.seconds }
        let words = recent.reduce(0) { $0 + $1.words }
        guard sec >= 180, words >= 100 else { return 200 }
        return min(600, max(80, Double(words) / (sec / 60)))
    }

    var hasSpeedData: Bool {
        let cutoff = Self.dayKey(Calendar.current.date(byAdding: .day, value: -30, to: Date()) ?? Date())
        let recent = entries.filter { $0.day >= cutoff }
        return recent.reduce(0) { $0 + $1.seconds } >= 180 && recent.reduce(0) { $0 + $1.words } >= 100
    }

    /// Bugün okunan saniye (tüm kitaplar, tüm cihazlar).
    var todaySeconds: Double {
        let k = Self.dayKey(Date())
        return entries.filter { $0.day == k }.reduce(0) { $0 + $1.seconds }
    }

    func secondsToRead(words: Double) -> Double { max(0, words) / wordsPerMinute * 60 }

    // MARK: İstatistik

    func entries(for bookID: UUID?) -> [ReadingEntry] {
        bookID == nil ? entries : entries.filter { $0.bookID == bookID }
    }

    func buckets(_ period: StatsPeriod, bookID: UUID?, now: Date = Date()) -> [StatBucket] {
        let cal = Calendar.current
        let list = entries(for: bookID)
        var starts: [Date] = []
        let monthly = period == .year
        switch period {
        case .week, .month:
            let n = period == .week ? 7 : 30
            let today = cal.startOfDay(for: now)
            starts = (0..<n).reversed().compactMap { cal.date(byAdding: .day, value: -$0, to: today) }
        case .year:
            let thisMonth = cal.date(from: cal.dateComponents([.year, .month], from: now)) ?? now
            starts = (0..<12).reversed().compactMap { cal.date(byAdding: .month, value: -$0, to: thisMonth) }
        }
        return starts.map { s in
            let e = monthly ? cal.date(byAdding: .month, value: 1, to: s)! : cal.date(byAdding: .day, value: 1, to: s)!
            let k1 = Self.dayKey(s), k2 = Self.dayKey(e)
            let inRange = list.filter { $0.day >= k1 && $0.day < k2 }
            var by: [UUID: Double] = [:]
            for x in inRange { by[x.bookID, default: 0] += x.seconds }
            return StatBucket(start: s, seconds: inRange.reduce(0) { $0 + $1.seconds },
                              words: inRange.reduce(0) { $0 + $1.words }, byBook: by)
        }
    }

    /// Ardışık okunan gün sayısı (bugün henüz okumadıysanız dünden sayılır).
    func streak(bookID: UUID? = nil) -> Int {
        let cal = Calendar.current
        let days = Set(entries(for: bookID).filter { $0.seconds >= 60 }.map(\.day))
        var d = cal.startOfDay(for: Date())
        if !days.contains(Self.dayKey(d)) { d = cal.date(byAdding: .day, value: -1, to: d) ?? d }
        var n = 0
        while days.contains(Self.dayKey(d)) {
            n += 1
            d = cal.date(byAdding: .day, value: -1, to: d) ?? d
        }
        return n
    }

    func totalSeconds(bookID: UUID) -> Double { entries.filter { $0.bookID == bookID }.reduce(0) { $0 + $1.seconds } }

    func removeBook(_ id: UUID) {
        entries.removeAll { $0.bookID == id }
        scheduleSave()
    }

    // MARK: Kayıt

    private func load() {
        guard let data = try? Data(contentsOf: url),
              let d = try? JSONDecoder().decode([ReadingEntry].self, from: data) else { return }
        entries = d.map { var e = $0; if e.device == nil { e.device = DeviceID.current }; return e }
    }

    /// Uzaktan gelen kayıtları birleştirir; aynı kayıtta sayaçlar yalnızca artar, o yüzden büyük olan kazanır.
    func mergeRemote(_ remote: [ReadingEntry]) -> [ReadingEntry] {
        var map = Dictionary(uniqueKeysWithValues: entries.map { ($0.id, $0) })
        for r in remote {
            if let l = map[r.id] {
                if r.seconds > l.seconds || (r.seconds == l.seconds && r.words > l.words) { map[r.id] = r }
            } else {
                map[r.id] = r
            }
        }
        entries = Array(map.values)
        save()
        return entries
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            if !Task.isCancelled { self?.save() }
        }
    }

    func save() {
        if let data = try? JSONEncoder().encode(entries) { try? data.write(to: url, options: .atomic) }
    }
}
