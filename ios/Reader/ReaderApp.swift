import SwiftUI
import UIKit

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
                .onAppear { settings.applySystem(dark: SystemAppearance.isDark) }
                .onReceive(Timer.publish(every: 15, on: .main, in: .common).autoconnect()) { _ in
                    settings.applySystem(dark: SystemAppearance.isDark)
                }
                .onAppear {
                    #if targetEnvironment(macCatalyst)
                    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.forEach {
                        $0.sizeRestrictions?.minimumSize = CGSize(width: 720, height: 540)
                    }
                    #endif
                }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .inactive:
                        // Mac'te pencere kapanınca uygulama arka plana düşmeyebilir; veriyi şimdiden yaz
                        #if targetEnvironment(macCatalyst)
                        library.save(); highlights.save(); vocab.save(); bookmarks.save(); tracker.save()
                        #endif
                    case .active:
                        settings.applySystem(dark: SystemAppearance.isDark)
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
