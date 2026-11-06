import SwiftUI

// MARK: - Düzenleme sayfası (renk + not)

struct HighlightEditor: View {
    @State var draft: Highlight
    var focusNote: Bool = false
    var onSave: (Highlight) -> Void
    var onDelete: (Highlight) -> Void

    @Environment(\.dismiss) private var dismiss
    @FocusState private var noteFocused: Bool
    @State private var deleted = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(draft.text)
                        .font(.system(size: 16, design: .serif))
                        .italic()
                        .padding(.vertical, 2)
                    if !draft.location.isEmpty {
                        Text(draft.location).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section("Renk") {
                    HStack(spacing: 16) {
                        ForEach(HighlightColor.allCases) { c in
                            Button { draft.color = c } label: {
                                Circle().fill(c.color)
                                    .frame(width: 36, height: 36)
                                    .overlay(Circle().stroke(Color.primary.opacity(draft.color == c ? 0.9 : 0), lineWidth: 3))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }
                Section("Not") {
                    TextEditor(text: $draft.note)
                        .frame(minHeight: 140)
                        .focused($noteFocused)
                }
                Section {
                    Button(role: .destructive) {
                        deleted = true
                        onDelete(draft)
                        dismiss()
                    } label: { Label("Vurguyu sil", systemImage: "trash") }
                }
            }
            .navigationTitle("Vurgu")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Bitti") { dismiss() } } }
            .onAppear { if focusNote { noteFocused = true } }
            .onDisappear { if !deleted { onSave(draft) } }
        }
    }
}

// MARK: - Satır

struct HighlightRow: View {
    let h: Highlight
    var showBook = false

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            RoundedRectangle(cornerRadius: 2).fill(h.color.color).frame(width: 4)
            VStack(alignment: .leading, spacing: 5) {
                Text(h.text).font(.system(size: 16, design: .serif)).lineLimit(4).multilineTextAlignment(.leading)
                if !h.note.isEmpty {
                    Label(h.note, systemImage: "note.text")
                        .font(.subheadline).foregroundStyle(.secondary).lineLimit(3)
                        .multilineTextAlignment(.leading)
                }
                Text(showBook ? "\(h.bookTitle) · \(h.location)" : h.location)
                    .font(.caption2).foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
    }
}

// MARK: - Bir kitabın vurguları (okuyucu içinde)

struct BookHighlightsList: View {
    let bookID: UUID
    var onSelect: (Highlight) -> Void
    var onDelete: (Highlight) -> Void
    @EnvironmentObject var store: HighlightStore

    var body: some View {
        let items = store.forBook(bookID)
        if items.isEmpty {
            ContentUnavailableView("Henüz vurgu yok", systemImage: "highlighter",
                                   description: Text("Bir metni seçip fosforlu kalem düğmesine dokunun."))
        } else {
            List {
                ForEach(items) { h in
                    Button { onSelect(h) } label: { HighlightRow(h: h) }.buttonStyle(.plain)
                }
                .onDelete { idx in idx.map { items[$0] }.forEach(onDelete) }
            }
        }
    }
}

// MARK: - Tüm kitapların vurguları

struct HighlightsView: View {
    @EnvironmentObject var store: HighlightStore
    @State private var search = ""
    @State private var editing: Highlight?
    @State private var openBook: UUID?

    private var filtered: [Highlight] {
        store.items.filter {
            search.isEmpty || $0.text.localizedCaseInsensitiveContains(search)
            || $0.note.localizedCaseInsensitiveContains(search)
            || $0.bookTitle.localizedCaseInsensitiveContains(search)
        }
    }

    private var groups: [(title: String, items: [Highlight])] {
        Dictionary(grouping: filtered, by: \.bookID)
            .map { (title: $0.value.first?.bookTitle ?? "", items: $0.value.sorted { $0.sortKey < $1.sortKey }) }
            .sorted { $0.title < $1.title }
    }

    var body: some View {
        List {
            ForEach(groups, id: \.title) { g in
                Section(g.title) {
                    ForEach(g.items) { h in
                        Button {
                            store.pendingJump = h
                            openBook = h.bookID
                        } label: { HighlightRow(h: h) }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .leading) {
                                Button { editing = h } label: { Label("Düzenle", systemImage: "pencil") }.tint(.orange)
                            }
                            .contextMenu {
                                Button { editing = h } label: { Label("Renk ve notu düzenle", systemImage: "pencil") }
                                Button(role: .destructive) { store.delete([h.id]) } label: { Label("Sil", systemImage: "trash") }
                            }
                    }
                    .onDelete { idx in store.delete(idx.map { g.items[$0].id }) }
                }
            }
        }
        .searchable(text: $search, prompt: "Vurgu, not veya kitap ara")
        .navigationTitle("Vurgular ve notlar")
        .overlay {
            if store.items.isEmpty {
                ContentUnavailableView("Henüz vurgu yok", systemImage: "highlighter",
                                       description: Text("Okurken bir metni seçip fosforlu kalem düğmesine dokunun."))
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                ShareLink(item: store.exportMarkdown(), subject: Text("Vurgular ve notlar")) {
                    Image(systemName: "square.and.arrow.up")
                }
                .disabled(store.items.isEmpty)
            }
        }
        .navigationDestination(item: $openBook) { id in ReaderContainer(bookID: id) }
        .sheet(item: $editing) { h in
            HighlightEditor(draft: h, focusNote: false,
                            onSave: { store.update($0) },
                            onDelete: { store.delete([$0.id]) })
                .presentationDetents([.large])
        }
    }
}
