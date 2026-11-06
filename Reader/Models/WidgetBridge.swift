import Foundation
import UIKit
import WidgetKit

/// Ana ekran widget'ının okuyacağı özeti App Group klasörüne yazar.
@MainActor
enum WidgetBridge {
    static let group = "group.com.kursat.reader"

    private struct Snapshot: Codable {
        var bookID: String?
        var title: String?
        var author: String?
        var progress: Double
        var status: String?
        var hasCover: Bool
        var todayMinutes: Int
        var goalMinutes: Int
        var streak: Int
        var updatedAt: Date
    }

    static func update(library: LibraryStore, tracker: ReadingTracker, settings: ReaderSettings) {
        guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return }
        let books = library.sortedBooks
        let book = books.first(where: { $0.lastReadAt != nil && !$0.isFinished }) ?? books.first

        var hasCover = false
        let coverURL = dir.appendingPathComponent("widget-cover.jpg")
        if let b = book, let u = Paths.cover(for: b), let img = UIImage(contentsOfFile: u.path),
           let jpg = img.scaled(maxDimension: 360).jpegData(compressionQuality: 0.8) {
            try? jpg.write(to: coverURL, options: .atomic)
            hasCover = true
        } else {
            try? FileManager.default.removeItem(at: coverURL)
        }

        let snap = Snapshot(bookID: book?.id.uuidString, title: book?.title, author: book?.author,
                            progress: book?.isUnread == true ? 0 : (book?.progress ?? 0),
                            status: book?.statusText, hasCover: hasCover,
                            todayMinutes: Int(tracker.todaySeconds / 60), goalMinutes: settings.dailyGoalMinutes,
                            streak: tracker.streak(), updatedAt: Date())
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        if let data = try? enc.encode(snap) {
            try? data.write(to: dir.appendingPathComponent("widget.json"), options: .atomic)
        }
        WidgetCenter.shared.reloadAllTimelines()
    }
}
