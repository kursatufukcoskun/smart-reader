import SwiftUI

private func md(_ s: String) -> AttributedString {
    let o = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
    return (try? AttributedString(markdown: s, options: o)) ?? AttributedString(s)
}

struct StudyView: View {
    @ObservedObject var model: ChapterStudyModel
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $tab) {
                    Text("Özet").tag(0)
                    Text("Quiz").tag(1)
                    Text("Soru-cevap").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(12)

                if let d = model.data {
                    switch tab {
                    case 0: SummaryTab(data: d)
                    case 1: QuizTab(questions: d.quiz)
                    default: QATab(items: d.qa)
                    }
                } else {
                    Spacer()
                    if model.isLoading {
                        VStack(spacing: 12) {
                            ProgressView()
                            Text("Çalışma seti hazırlanıyor… (yarım dakika kadar sürebilir)").foregroundStyle(.secondary)
                        }
                    } else if let e = model.error {
                        VStack(spacing: 12) {
                            Label(e, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red).multilineTextAlignment(.center)
                            Button("Tekrar dene") { Task { await model.load() } }.buttonStyle(.borderedProminent)
                        }
                        .padding()
                    }
                    Spacer()
                }
            }
            .navigationTitle(model.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Kapat") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Soru dili", selection: $model.language) {
                            Text("Türkçe").tag("Türkçe")
                            Text("English").tag("English")
                        }
                        Button { Task { await model.load(force: true) } } label: {
                            Label("Yeni sorular üret", systemImage: "arrow.clockwise")
                        }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .onChange(of: model.language) { _, _ in
                model.data = nil
                Task { await model.load() }
            }
            .task { await model.load() }
        }
    }
}

private struct SummaryTab: View {
    let data: StudyData

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if !data.summary.isEmpty {
                    Text("Bu bölümde neler öğrendik").font(.title3.bold())
                    ForEach(Array(data.summary.enumerated()), id: \.offset) { _, s in
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).padding(.top, 2)
                            Text(md(s)).font(.system(size: 17)).lineSpacing(4)
                        }
                    }
                }
                if !data.concepts.isEmpty {
                    Divider()
                    Text("Temel kavramlar").font(.title3.bold())
                    ForEach(data.concepts) { c in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(c.term).font(.headline)
                            Text(md(c.definition)).font(.system(size: 16)).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
                    }
                }
            }
            .padding(20)
            .textSelection(.enabled)
        }
    }
}

private struct QuizTab: View {
    let questions: [QuizQuestion]
    @State private var order: [QuizQuestion]
    @State private var index = 0
    @State private var picked: Int?
    @State private var wrong: [QuizQuestion] = []
    @State private var score = 0

    init(questions: [QuizQuestion]) {
        self.questions = questions
        _order = State(initialValue: questions.shuffled())
    }

    var body: some View {
        Group {
            if questions.isEmpty {
                ContentUnavailableView("Soru üretilemedi", systemImage: "questionmark.circle")
            } else if index >= order.count {
                result
            } else {
                question(order[index])
            }
        }
    }

    private func restart(_ qs: [QuizQuestion]) {
        order = qs.shuffled()
        index = 0; picked = nil; score = 0; wrong = []
    }

    private func question(_ q: QuizQuestion) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ProgressView(value: Double(index), total: Double(max(order.count, 1)))
                Text("Soru \(index + 1) / \(order.count)").font(.caption).foregroundStyle(.secondary)
                Text(md(q.question)).font(.system(size: 19, weight: .semibold)).lineSpacing(4)
                ForEach(Array(q.options.enumerated()), id: \.offset) { i, opt in
                    Button { if picked == nil { choose(i, q) } } label: {
                        HStack(alignment: .top, spacing: 12) {
                            Text(["A", "B", "C", "D", "E"][min(i, 4)]).font(.headline).frame(width: 26)
                            Text(md(opt)).multilineTextAlignment(.leading).font(.system(size: 17))
                            Spacer(minLength: 0)
                            if let p = picked {
                                if i == q.correct { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                                else if i == p { Image(systemName: "xmark.circle.fill").foregroundStyle(.red) }
                            }
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(background(i, q), in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(border(i, q), lineWidth: 2))
                    }
                    .buttonStyle(.plain)
                }
                if picked != nil {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(picked == q.correct ? "Doğru!" : "Yanlış", systemImage: picked == q.correct ? "checkmark.seal.fill" : "xmark.seal.fill")
                            .font(.headline).foregroundStyle(picked == q.correct ? .green : .red)
                        if !q.explanation.isEmpty { Text(md(q.explanation)).font(.system(size: 16)).lineSpacing(4) }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
                    Button { withAnimation { index += 1; picked = nil } } label: {
                        Text(index + 1 >= order.count ? "Sonucu gör" : "Sonraki soru").frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
            .padding(20)
        }
    }

    private func choose(_ i: Int, _ q: QuizQuestion) {
        picked = i
        if i == q.correct { score += 1 } else { wrong.append(q) }
    }

    private func background(_ i: Int, _ q: QuizQuestion) -> Color {
        guard let p = picked else { return Color(.secondarySystemFill) }
        if i == q.correct { return Color.green.opacity(0.18) }
        if i == p { return Color.red.opacity(0.15) }
        return Color(.secondarySystemFill)
    }
    private func border(_ i: Int, _ q: QuizQuestion) -> Color {
        guard let p = picked else { return .clear }
        if i == q.correct { return .green }
        if i == p { return .red }
        return .clear
    }

    private var result: some View {
        ScrollView {
            VStack(spacing: 18) {
                let pct = Double(score) / Double(max(order.count, 1))
                ZStack {
                    Circle().stroke(Color.accentColor.opacity(0.2), lineWidth: 12)
                    Circle().trim(from: 0, to: pct).stroke(pct >= 0.75 ? Color.green : Color.accentColor, style: StrokeStyle(lineWidth: 12, lineCap: .round)).rotationEffect(.degrees(-90))
                    Text("\(score)/\(order.count)").font(.system(size: 34, weight: .bold, design: .rounded))
                }
                .frame(width: 130, height: 130)
                Text(pct >= 0.9 ? "Harika, konuya hâkimsin 🎉" : pct >= 0.6 ? "İyi gidiyorsun" : "Özeti bir daha gözden geçirmekte fayda var")
                    .font(.headline)
                if !wrong.isEmpty {
                    Button { restart(wrong) } label: {
                        Label("Yanlışları tekrar çöz (\(wrong.count))", systemImage: "arrow.counterclockwise").frame(maxWidth: 320).padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                }
                Button { restart(questions) } label: { Text("Baştan başla").frame(maxWidth: 320).padding(.vertical, 6) }
                    .buttonStyle(.bordered)

                if !wrong.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Yanlış yaptıkların").font(.headline)
                        ForEach(wrong) { q in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(md(q.question)).font(.subheadline.weight(.semibold))
                                Text("Doğru: \(md(q.options[q.correct]))").font(.footnote).foregroundStyle(.green)
                                if !q.explanation.isEmpty { Text(md(q.explanation)).font(.footnote).foregroundStyle(.secondary) }
                            }
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
                        }
                    }
                }
            }
            .padding(20)
        }
    }
}

private struct QATab: View {
    let items: [QAItem]
    @State private var shown: Set<UUID> = []

    var body: some View {
        if items.isEmpty {
            ContentUnavailableView("Soru üretilemedi", systemImage: "questionmark.circle")
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Önce kendin cevapla, sonra kartı açıp karşılaştır.").font(.footnote).foregroundStyle(.secondary)
                    ForEach(items) { item in
                        let open = shown.contains(item.id)
                        VStack(alignment: .leading, spacing: 10) {
                            Text(md(item.question)).font(.system(size: 18, weight: .semibold)).lineSpacing(3)
                            if open {
                                Divider()
                                Text(md(item.answer)).font(.system(size: 16)).lineSpacing(4).textSelection(.enabled)
                            } else {
                                Label("Cevabı göster", systemImage: "eye").font(.footnote).foregroundStyle(Color.accentColor)
                            }
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 14))
                        .contentShape(Rectangle())
                        .onTapGesture {
                            withAnimation { if open { shown.remove(item.id) } else { shown.insert(item.id) } }
                        }
                    }
                }
                .padding(20)
            }
        }
    }
}
