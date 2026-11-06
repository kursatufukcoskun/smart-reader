import SwiftUI

struct BookmarkList: View {
    let bookID: UUID
    var onSelect: (Bookmark) -> Void
    var onDelete: (Bookmark) -> Void
    @EnvironmentObject var store: BookmarkStore

    var body: some View {
        let items = store.forBook(bookID)
        if items.isEmpty {
            ContentUnavailableView("Yer imi yok", systemImage: "bookmark",
                                   description: Text("Araç çubuğundaki yer imi düğmesiyle bulunduğunuz yeri işaretleyin."))
        } else {
            List {
                ForEach(items) { b in
                    Button { onSelect(b) } label: {
                        HStack(alignment: .top, spacing: 12) {
                            Image(systemName: "bookmark.fill").foregroundStyle(.orange)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(b.snippet.isEmpty ? b.location : b.snippet)
                                    .font(.system(size: 15, design: .serif)).lineLimit(2).multilineTextAlignment(.leading)
                                Text(b.location).font(.caption2).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .onDelete { idx in idx.map { items[$0] }.forEach(onDelete) }
            }
        }
    }
}

struct ToastOverlay: ViewModifier {
    @Binding var message: String?
    func body(content: Content) -> some View {
        content.overlay(alignment: .top) {
            if let m = message {
                Text(m)
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule())
                    .shadow(radius: 6, y: 2)
                    .padding(.top, 60)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task {
                        try? await Task.sleep(nanoseconds: 1_600_000_000)
                        withAnimation { message = nil }
                    }
            }
        }
        .animation(.easeOut(duration: 0.2), value: message)
    }
}

extension View {
    func toast(_ message: Binding<String?>) -> some View { modifier(ToastOverlay(message: message)) }
}
