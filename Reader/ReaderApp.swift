import SwiftUI

@main
struct ReaderApp: App {
    @StateObject private var library = LibraryStore()
    @StateObject private var settings = ReaderSettings()
    @StateObject private var vocab = VocabStore()
    @StateObject private var highlights = HighlightStore()
    @StateObject private var bookmarks = BookmarkStore()
    @StateObject private var tracker = ReadingTracker()
    @StateObject private var sync = SyncManager()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        FontInstaller.install()
        IDMigration.run()
    }

    var body: some Scene {
        WindowGroup {
            LibraryView()
                .environmentObject(library)
                .environmentObject(settings)
                .environmentObject(vocab)
                .environmentObject(highlights)
                .environmentObject(bookmarks)
                .environmentObject(tracker)
                .environmentObject(sync)
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        sync.syncSoon()
                    case .background:
                        library.save(); highlights.save(); vocab.save(); bookmarks.save(); tracker.save()
                        Task { await NotificationScheduler.refresh(settings: settings, tracker: tracker, vocab: vocab) }
                        WidgetBridge.update(library: library, tracker: tracker, settings: settings)
                        sync.syncSoon(inBackgroundTask: true)
                    default: break
                    }
                }
        }
    }
}
