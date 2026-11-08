import SwiftUI
import Charts

private enum Metric: String, CaseIterable, Identifiable {
    case time, pages
    var id: String { rawValue }
    var title: String { self == .time ? "Süre" : "Sayfa" }
}

struct StatsView: View {
    @EnvironmentObject var tracker: ReadingTracker
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var settings: ReaderSettings

    @State private var period: StatsPeriod = .week
    @State private var metric: Metric = .time
    @State private var bookFilter: UUID?
    @State private var selectedDate: Date?

    private var buckets: [StatBucket] { tracker.buckets(period, bookID: bookFilter) }
    private var totalSeconds: Double { buckets.reduce(0) { $0 + $1.seconds } }
    private var totalWords: Int { buckets.reduce(0) { $0 + $1.words } }
    private var activeDays: Int { buckets.filter { $0.seconds >= 60 }.count }
    private var unit: Calendar.Component { period == .year ? .month : .day }

    private var selected: StatBucket? {
        guard let d = selectedDate else { return nil }
        return buckets.first { Calendar.current.isDate($0.start, equalTo: d, toGranularity: unit) }
    }

    private func value(_ b: StatBucket) -> Double { metric == .time ? b.seconds / 60 : b.pages }

    private var periodWPM: Double? {
        guard totalSeconds >= 180, totalWords >= 100 else { return nil }
        return Double(totalWords) / (totalSeconds / 60)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Picker("Dönem", selection: $period) {
                    ForEach(StatsPeriod.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .onChange(of: period) { _, _ in selectedDate = nil }

                if let id = bookFilter, let b = library.book(id) {
                    HStack {
                        Label(b.title, systemImage: "book.closed.fill").lineLimit(1)
                        Spacer()
                        Button("Filtreyi kaldır") { withAnimation { bookFilter = nil } }
                            .font(.footnote)
                    }
                    .padding(10)
                    .background(.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
                }

                YearlyGoalCard()
                cards
                chartSection
                ReadingHeatmapCard(bookID: bookFilter)
                if let s = selected { detail(s) }
                booksSection
            }
            .padding(20)
        }
        .environment(\.locale, Locale(identifier: "tr_TR"))
        .navigationTitle("İstatistikler")
        .animation(.easeInOut(duration: 0.25), value: period)
        .animation(.easeInOut(duration: 0.25), value: metric)
        .animation(.easeInOut(duration: 0.25), value: bookFilter)
    }

    // MARK: Kartlar (dokununca grafik metriği değişir)

    private var cards: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
            card("Okuma süresi", Duration.text(totalSeconds), "clock.fill", .blue, active: metric == .time) { metric = .time }
            card("Okunan sayfa", "\(Int((Double(totalWords) / Book.wordsPerPage).rounded()))", "doc.text.fill", .green, active: metric == .pages) { metric = .pages }
            card("Okuma hızı", periodWPM.map { "\(Int($0.rounded())) kel/dk" } ?? "—", "speedometer", .orange, active: false) {}
            card("Seri", "\(tracker.streak(bookID: bookFilter)) gün", "flame.fill", .red, active: false) {}
            card("Aktif gün", "\(activeDays)", "calendar", .purple, active: false) {}
            card("Günlük ort.", Duration.text(activeDays == 0 ? 0 : totalSeconds / Double(activeDays)), "chart.bar.fill", .teal, active: false) {}
        }
    }

    private func card(_ title: String, _ value: String, _ icon: String, _ color: Color, active: Bool, tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            VStack(alignment: .leading, spacing: 6) {
                Label(title, systemImage: icon).font(.footnote).foregroundStyle(color)
                Text(value).font(.system(size: 24, weight: .bold, design: .rounded)).foregroundStyle(.primary)
                    .minimumScaleFactor(0.6).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(color.opacity(active ? 0.9 : 0), lineWidth: 2))
        }
        .buttonStyle(.plain)
    }

    // MARK: Grafik

    private var chartSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(metric == .time ? "Dakika" : "Sayfa").font(.headline)
                Spacer()
                Picker("Metrik", selection: $metric) {
                    ForEach(Metric.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented).frame(width: 160)
            }
            Chart(buckets) { b in
                BarMark(x: .value("Tarih", b.start, unit: unit), y: .value("Değer", value(b)))
                    .cornerRadius(4)
                    .foregroundStyle(metric == .time ? Color.blue.gradient : Color.green.gradient)
                    .opacity(selected == nil || selected?.start == b.start ? 1 : 0.35)
            }
            .chartXSelection(value: $selectedDate)
            .chartXAxis {
                switch period {
                case .week:
                    AxisMarks(values: .stride(by: .day)) { _ in
                        AxisValueLabel(format: .dateTime.weekday(.abbreviated))
                    }
                case .month:
                    AxisMarks(values: .stride(by: .day, count: 5)) { _ in
                        AxisGridLine(); AxisValueLabel(format: .dateTime.day())
                    }
                case .year:
                    AxisMarks(values: .stride(by: .month)) { _ in
                        AxisValueLabel(format: .dateTime.month(.narrow))
                    }
                }
            }
            .frame(height: 220)
            Text("Bir çubuğa dokunun: o günün (ayın) ayrıntısı altta açılır.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .padding(16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func detail(_ s: StatBucket) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(period == .year
                 ? s.start.formatted(.dateTime.month(.wide).year().locale(Locale(identifier: "tr_TR")))
                 : s.start.formatted(.dateTime.weekday(.wide).day().month(.wide).locale(Locale(identifier: "tr_TR"))))
                .font(.headline)
            HStack(spacing: 18) {
                Label(Duration.text(s.seconds), systemImage: "clock")
                Label("\(Int(s.pages.rounded())) sayfa", systemImage: "doc.text")
                if s.seconds >= 180, s.words >= 50 {
                    Label("\(Int((Double(s.words) / (s.seconds / 60)).rounded())) kel/dk", systemImage: "speedometer")
                }
            }
            .font(.subheadline).foregroundStyle(.secondary)
            let books = s.byBook.filter { $0.value > 0 }.sorted { $0.value > $1.value }
            if books.isEmpty {
                Text("Bu dönemde okuma kaydı yok.").font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(books, id: \.key) { id, sec in
                HStack {
                    Text(library.book(id)?.title ?? "Silinmiş kitap").lineLimit(1)
                    Spacer()
                    Text(Duration.text(sec)).foregroundStyle(.secondary)
                }
                .font(.subheadline)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    // MARK: Kitaplar (dokununca filtreler)

    private var booksSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Kitaplar").font(.headline)
            ForEach(library.sortedBooks) { b in
                let sec = tracker.entries(for: b.id).reduce(0) { $0 + $1.seconds }
                let active = bookFilter == b.id
                Button { bookFilter = active ? nil : b.id; selectedDate = nil } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(b.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                            Spacer()
                            Text(Duration.text(sec)).font(.subheadline).foregroundStyle(.secondary)
                        }
                        ProgressView(value: b.isUnread ? 0 : b.progress)
                        HStack {
                            Text(b.statusText ?? "")
                            Spacer()
                            Text(remaining(b)).foregroundStyle(.secondary)
                        }
                        .font(.caption)
                    }
                    .padding(12)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.accentColor.opacity(active ? 0.9 : 0), lineWidth: 2))
                }
                .buttonStyle(.plain)
            }
            if library.books.isEmpty {
                Text("Henüz kitap yok.").foregroundStyle(.secondary)
            }
        }
    }

    private func remaining(_ b: Book) -> String {
        if b.isFinished { return "" }
        if b.isUnread { return "Henüz başlanmadı" }
        let words: Double
        switch b.kind {
        case .epub: words = max(0, Double(b.totalWords) - b.wordPosition)
        case .pdf:
            let left = max(0, (b.pagesTotal ?? 0) - (b.pageNow ?? 0))
            words = Double(left) * Book.wordsPerPage
        }
        guard words > 0 else { return "" }
        return "Bitmesine ~\(Duration.text(tracker.secondsToRead(words: words)))"
    }
}


/// Bu yıl bitirilen kitaplar ve yıllık hedef. Bitiş günü, kitabın okuma günlüğündeki son günden alınır.
struct YearlyGoalCard: View {
    @EnvironmentObject var library: LibraryStore
    @EnvironmentObject var tracker: ReadingTracker
    @EnvironmentObject var settings: ReaderSettings

    private var year: Int { Calendar.current.component(.year, from: Date()) }

    private var finished: [(book: Book, date: Date)] {
        library.books.compactMap { b -> (Book, Date)? in
            guard b.progress >= 0.98, !b.isRemoteOnly else { return nil }
            let days = tracker.entries(for: b.id).compactMap { ReadingTracker.date(from: $0.day) }
            guard let d = days.max() ?? b.progressUpdatedAt ?? b.lastOpened,
                  Calendar.current.component(.year, from: d) == year else { return nil }
            return (b, d)
        }
        .sorted { $0.1 > $1.1 }
    }

    var body: some View {
        let list = finished
        let goal = settings.yearlyGoal
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(goal > 0 ? "Bu yıl \(list.count) / \(goal) kitap" : "Bu yıl \(list.count) kitap bitirdin", systemImage: "books.vertical.fill")
                    .font(.headline)
                Spacer()
                Stepper("Yıllık hedef", value: $settings.yearlyGoal, in: 0...200)
                    .labelsHidden()
                    .accessibilityLabel("Yıllık kitap hedefi")
            }
            if goal > 0 {
                ProgressView(value: min(Double(list.count) / Double(goal), 1))
                    .tint(list.count >= goal ? .green : .accentColor)
                Text(list.count >= goal ? "Hedefe ulaştın 🎉" : "Hedefe \(goal - list.count) kitap kaldı · hedefi sağdaki düğmelerle değiştirebilirsin")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                Text("Hedef kapalı. Sağdaki + ile bir yıllık hedef belirle.").font(.footnote).foregroundStyle(.secondary)
            }
            if !list.isEmpty {
                Divider()
                ForEach(Array(list.prefix(8)), id: \.book.id) { item in
                    HStack(alignment: .firstTextBaseline) {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(item.book.title).font(.subheadline).lineLimit(1)
                            if !item.book.author.isEmpty { Text(item.book.author).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                        }
                        Spacer()
                        Text(item.date.formatted(.dateTime.day().month(.abbreviated))).font(.caption).foregroundStyle(.secondary)
                    }
                }
                if list.count > 8 { Text("… ve \(list.count - 8) kitap daha").font(.caption).foregroundStyle(.secondary) }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 16))
    }
}


/// Son 52 haftanın her günü, okunan dakikaya göre açıktan koyuya renkli bir kare (GitHub tarzı yıllık takvim).
struct ReadingHeatmapCard: View {
    @EnvironmentObject var tracker: ReadingTracker
    var bookID: UUID?

    private let cell: CGFloat = 12
    private let gap: CGFloat = 3

    private var cal: Calendar {
        var c = Calendar.current
        c.firstWeekday = 2   // hafta Pazartesi başlar
        return c
    }

    /// Gün anahtarı → dakika
    private var minutes: [String: Double] {
        var m: [String: Double] = [:]
        for e in tracker.entries(for: bookID) { m[e.day, default: 0] += e.seconds / 60 }
        return m
    }

    private func level(_ min: Double) -> Int {
        if min < 1 { return 0 }
        if min < 10 { return 1 }
        if min < 20 { return 2 }
        if min < 40 { return 3 }
        return 4
    }

    private func color(_ lvl: Int) -> Color {
        lvl == 0 ? Color.secondary.opacity(0.15) : Color.green.opacity([0, 0.30, 0.50, 0.75, 1.0][lvl])
    }

    var body: some View {
        let today = cal.startOfDay(for: Date())
        let thisWeek = cal.dateInterval(of: .weekOfYear, for: today)?.start ?? today
        let start = cal.date(byAdding: .weekOfYear, value: -52, to: thisWeek) ?? thisWeek
        let data = minutes
        let weeks = 53
        let total = data.filter { k, _ in
            guard let d = ReadingTracker.date(from: k) else { return false }
            return d >= start && d <= today
        }
        let activeDays = total.values.filter { $0 >= 1 }.count
        let totalHours = total.values.reduce(0, +) / 60

        VStack(alignment: .leading, spacing: 10) {
            Label("Okuma takvimi", systemImage: "square.grid.3x3.fill").font(.headline)
            Text("Son 365 günde \(activeDays) gün okudun · \(String(format: "%.0f", totalHours)) saat")
                .font(.footnote).foregroundStyle(.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(alignment: .leading, spacing: 4) {
                    // Ay etiketleri
                    HStack(spacing: gap) {
                        ForEach(0..<weeks, id: \.self) { w in
                            let first = cal.date(byAdding: .weekOfYear, value: w, to: start) ?? start
                            let prev = cal.date(byAdding: .weekOfYear, value: w - 1, to: start) ?? start
                            let newMonth = w == 0 || cal.component(.month, from: first) != cal.component(.month, from: prev)
                            Text(newMonth ? first.formatted(.dateTime.month(.abbreviated)) : "")
                                .font(.system(size: 9)).foregroundStyle(.secondary)
                                .fixedSize()
                                .frame(width: cell, alignment: .leading)
                        }
                    }
                    HStack(alignment: .top, spacing: gap) {
                        ForEach(0..<weeks, id: \.self) { w in
                            VStack(spacing: gap) {
                                ForEach(0..<7, id: \.self) { d in
                                    let date = cal.date(byAdding: .day, value: w * 7 + d, to: start) ?? start
                                    if date > today {
                                        Color.clear.frame(width: cell, height: cell)
                                    } else {
                                        let mins = data[ReadingTracker.dayKey(date)] ?? 0
                                        RoundedRectangle(cornerRadius: 2.5)
                                            .fill(color(level(mins)))
                                            .frame(width: cell, height: cell)
                                            .accessibilityLabel("\(date.formatted(date: .abbreviated, time: .omitted)): \(Int(mins)) dakika")
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            .defaultScrollAnchor(.trailing)
            HStack(spacing: 4) {
                Text("Az").font(.caption2).foregroundStyle(.secondary)
                ForEach(0..<5, id: \.self) { l in
                    RoundedRectangle(cornerRadius: 2.5).fill(color(l)).frame(width: 11, height: 11)
                }
                Text("Çok").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 16))
    }
}
