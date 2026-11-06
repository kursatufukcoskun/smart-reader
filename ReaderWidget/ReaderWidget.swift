import WidgetKit
import SwiftUI

// Uygulamanın yazdığı özet (Reader/Models/WidgetBridge.swift ile aynı biçim)
struct WidgetSnapshot: Codable {
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

enum SharedStore {
    static let group = "group.com.kursat.reader"
    static var dir: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) }

    static func load() -> WidgetSnapshot? {
        guard let url = dir?.appendingPathComponent("widget.json"), let data = try? Data(contentsOf: url) else { return nil }
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return try? d.decode(WidgetSnapshot.self, from: data)
    }

    static func cover() -> UIImage? {
        guard let p = dir?.appendingPathComponent("widget-cover.jpg").path else { return nil }
        return UIImage(contentsOfFile: p)
    }
}

struct ReadingEntry: TimelineEntry {
    let date: Date
    let snap: WidgetSnapshot?
    let cover: UIImage?
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> ReadingEntry {
        ReadingEntry(date: Date(), snap: WidgetSnapshot(bookID: nil, title: "The Quiet Harbour", author: "", progress: 0.42,
                                                        status: "Sayfa 120 / 290 · %42", hasCover: false,
                                                        todayMinutes: 12, goalMinutes: 20, streak: 5, updatedAt: Date()), cover: nil)
    }
    func getSnapshot(in context: Context, completion: @escaping (ReadingEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : ReadingEntry(date: Date(), snap: SharedStore.load(), cover: SharedStore.cover()))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<ReadingEntry>) -> Void) {
        let e = ReadingEntry(date: Date(), snap: SharedStore.load(), cover: SharedStore.cover())
        completion(Timeline(entries: [e], policy: .after(Date().addingTimeInterval(30 * 60))))
    }
}

struct CoverView: View {
    let image: UIImage?
    var body: some View {
        if let image {
            Image(uiImage: image).resizable().scaledToFill()
        } else {
            ZStack {
                LinearGradient(colors: [.indigo, .purple], startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: "book.closed.fill").foregroundStyle(.white.opacity(0.85)).font(.title2)
            }
        }
    }
}

struct GoalRing: View {
    let snap: WidgetSnapshot
    var body: some View {
        let frac = snap.goalMinutes > 0 ? min(1, Double(snap.todayMinutes) / Double(snap.goalMinutes)) : 0
        HStack(spacing: 8) {
            if snap.goalMinutes > 0 {
                ZStack {
                    Circle().stroke(Color.accentColor.opacity(0.25), lineWidth: 5)
                    Circle().trim(from: 0, to: frac)
                        .stroke(frac >= 1 ? Color.green : Color.accentColor, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                }
                .frame(width: 30, height: 30)
                Text("\(snap.todayMinutes)/\(snap.goalMinutes) dk").font(.caption.weight(.semibold))
            }
            Spacer(minLength: 0)
            if snap.streak > 0 {
                Label("\(snap.streak)", systemImage: "flame.fill").font(.caption.weight(.semibold)).foregroundStyle(.orange)
            }
        }
    }
}

struct ReaderWidgetView: View {
    @Environment(\.widgetFamily) var family
    let entry: ReadingEntry

    var url: URL? {
        if let id = entry.snap?.bookID { return URL(string: "reader://book/\(id)") }
        return URL(string: "reader://library")
    }

    var body: some View {
        Group {
            if let s = entry.snap, s.title != nil {
                switch family {
                case .systemMedium: medium(s)
                default: small(s)
                }
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "books.vertical").font(.largeTitle).foregroundStyle(.secondary)
                    Text("Henüz kitap yok").font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .widgetURL(url)
        .containerBackground(.fill.tertiary, for: .widget)
    }

    func small(_ s: WidgetSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 8) {
                CoverView(image: entry.cover)
                    .frame(width: 46, height: 66)
                    .clipShape(RoundedRectangle(cornerRadius: 5))
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.title ?? "").font(.footnote.weight(.semibold)).lineLimit(3)
                    Text(s.status ?? "").font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            Spacer(minLength: 0)
            ProgressView(value: s.progress)
            GoalRing(snap: s)
        }
    }

    func medium(_ s: WidgetSnapshot) -> some View {
        HStack(spacing: 14) {
            CoverView(image: entry.cover)
                .frame(width: 84)
                .frame(maxHeight: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 6) {
                Text("Okumaya devam et").font(.caption2).foregroundStyle(.secondary)
                Text(s.title ?? "").font(.headline).lineLimit(2)
                if let a = s.author, !a.isEmpty { Text(a).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                Spacer(minLength: 0)
                Text(s.status ?? "").font(.caption)
                ProgressView(value: s.progress)
                GoalRing(snap: s)
            }
        }
    }
}

struct ReadingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "ReadingWidget", provider: Provider()) { entry in
            ReaderWidgetView(entry: entry)
        }
        .configurationDisplayName("Okumaya devam et")
        .description("Son okuduğun kitap, ilerleme, günlük hedef ve seri.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct ReaderWidgetBundle: WidgetBundle {
    var body: some Widget { ReadingWidget() }
}
