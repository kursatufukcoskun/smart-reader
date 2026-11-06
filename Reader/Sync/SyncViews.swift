import SwiftUI

struct SyncButton: View {
    @EnvironmentObject var sync: SyncManager

    var body: some View {
        Button {
            sync.syncSoon()
        } label: {
            switch sync.state {
            case .syncing: ProgressView()
            case .error: Image(systemName: "exclamationmark.icloud")
            case .signedOut: Image(systemName: "icloud.slash")
            case .idle: Image(systemName: "checkmark.icloud")
            }
        }
        .disabled(!sync.isSignedIn)
    }
}

struct SyncSettingsRows: View {
    @EnvironmentObject var sync: SyncManager

    var body: some View {
        if sync.isSignedIn {
            LabeledContent("Durum") {
                switch sync.state {
                case .syncing: Text("Senkronlanıyor…")
                case .error(let m): Text(m).foregroundStyle(.red).multilineTextAlignment(.trailing)
                default: Text("Bağlı").foregroundStyle(.green)
                }
            }
            if let note = sync.transferNote {
                Text(note).font(.footnote).foregroundStyle(.secondary)
            }
            if let d = sync.lastSync {
                LabeledContent("Son senkron", value: d.formatted(.relative(presentation: .named)))
            }
            Button("Şimdi senkronla") { sync.syncSoon() }
                .disabled(sync.state == .syncing)
            Button("Bağlantıyı kes", role: .destructive) { sync.signOut() }
        } else {
            Button {
                Task { await sync.signIn() }
            } label: {
                Label("Google ile bağlan", systemImage: "icloud.and.arrow.up")
            }
            if case .error(let m) = sync.state {
                Text(m).font(.footnote).foregroundStyle(.red)
            }
        }
    }
}
