import SwiftUI

private func chatMarkdown(_ s: String) -> AttributedString {
    let opts = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
    return (try? AttributedString(markdown: s, options: opts)) ?? AttributedString(s)
}

struct ChatView: View {
    @ObservedObject var chat: ChatModel
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var savedNotes: Set<UUID> = []
    @FocusState private var focused: Bool

    private let suggestions = [
        "Şu ana kadar olanları özetle",
        "Kim kim? Karakterleri listele",
        "Son okuduğum bölümde ne oldu?",
        "Kaldığım yeri hatırlat",
    ]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                infoBar
                Divider()
                messageList
                Divider()
                composer
            }
            .navigationTitle("Kitapla sohbet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Kapat") { dismiss() } }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Menu {
                        Picker("Model", selection: $chat.tier) {
                            ForEach(ChatModel.Tier.allCases) { Text($0.title + (($0 == .fast) ? " (ucuz)" : " (daha iyi)")).tag($0) }
                        }
                        Button { Task { await chat.refreshContext() } } label: {
                            Label("Okuma konumunu güncelle", systemImage: "arrow.clockwise")
                        }
                        Button(role: .destructive) { chat.clear() } label: {
                            Label("Sohbeti temizle", systemImage: "trash")
                        }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .task {
                await chat.refreshContext()
                if let p = chat.pendingPrompt {
                    chat.pendingPrompt = nil
                    chat.send(p)
                }
            }
        }
    }

    private var infoBar: some View {
        HStack(spacing: 8) {
            if chat.isLoadingContext {
                ProgressView().controlSize(.small)
                Text("Okuduğun kısım hazırlanıyor…")
            } else {
                Image(systemName: "eye.slash")
                Text("Spoiler yok: \(chat.info.label) · ~\(chat.info.words.formatted()) kelime")
            }
            Spacer()
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 16).padding(.vertical, 8)
        .overlay(alignment: .bottomLeading) {
            if chat.info.truncated {
                Text("Kitap uzun olduğu için en eski kısımlar çıkarıldı.")
                    .font(.caption2).foregroundStyle(.orange)
                    .padding(.horizontal, 16).offset(y: 12)
            }
        }
        .padding(.bottom, chat.info.truncated ? 14 : 0)
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    if chat.messages.isEmpty { emptyState }
                    ForEach(chat.messages) { m in bubble(m).id(m.id) }
                    if let err = chat.error {
                        Label(err, systemImage: "exclamationmark.triangle.fill")
                            .font(.callout).foregroundStyle(.red)
                        Button("Tekrar dene") { chat.retry() }.buttonStyle(.bordered)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(16)
            }
            .onChange(of: chat.messages.last?.text) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            .onChange(of: chat.messages.count) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
        }
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Okuduğun kısım hakkında soru sor. Kitabın devamını bilmem, sadece şu ana kadar okuduklarına dayanırım.")
                .foregroundStyle(.secondary)
            ForEach(suggestions, id: \.self) { s in
                Button { chat.send(s) } label: {
                    Label(s, systemImage: "sparkle").frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.bordered)
                .disabled(chat.isLoadingContext)
            }
        }
        .padding(.top, 8)
    }

    @ViewBuilder private func bubble(_ m: ChatModel.Message) -> some View {
        if m.role == .user {
            HStack {
                Spacer(minLength: 60)
                Text(m.text)
                    .padding(12)
                    .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 16))
                    .foregroundStyle(.white)
            }
        } else {
            HStack(alignment: .top) {
                if m.text.isEmpty && chat.isStreaming {
                    ProgressView().padding(12)
                } else {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(chatMarkdown(m.text))
                            .font(.system(size: 17)).lineSpacing(4)
                            .textSelection(.enabled)
                        if !chat.isStreaming, chat.saveNote != nil {
                            let saved = savedNotes.contains(m.id)
                            Button {
                                chat.saveNote?(m.text)
                                savedNotes.insert(m.id)
                            } label: {
                                Label(saved ? "Konuma not olarak kaydedildi" : "Bu konuma not olarak kaydet",
                                      systemImage: saved ? "checkmark.circle.fill" : "pin")
                                    .font(.footnote)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(saved ? Color.green : Color.accentColor)
                            .disabled(saved)
                        }
                    }
                    .padding(12)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
                }
                Spacer(minLength: 40)
            }
        }
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Bir şey sor…", text: $input, axis: .vertical)
                .lineLimit(1...5)
                .focused($focused)
                .textFieldStyle(.roundedBorder)
                .onSubmit { submit() }
            if chat.isStreaming {
                Button { chat.stop() } label: { Image(systemName: "stop.circle.fill").font(.system(size: 30)) }
            } else {
                Button { submit() } label: { Image(systemName: "arrow.up.circle.fill").font(.system(size: 30)) }
                    .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || chat.isLoadingContext)
            }
        }
        .padding(12)
    }

    private func submit() {
        let t = input
        input = ""
        chat.send(t)
    }
}
