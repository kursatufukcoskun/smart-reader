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

                cards
                chartSection
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
