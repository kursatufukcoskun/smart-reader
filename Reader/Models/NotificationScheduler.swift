import Foundation
import UserNotifications

/// Günlük okuma hatırlatması ve kelime tekrarı bildirimleri (yerel).
@MainActor
enum NotificationScheduler {
    private static let ids = (0..<3).map { "daily-reminder-\($0)" } + ["vocab-due"]

    static func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
    }

    /// Bekleyen bildirimleri güncel duruma göre yeniden kurar. Arka plana giderken ve ayar değişince çağrılır.
    static func refresh(settings: ReaderSettings, tracker: ReadingTracker, vocab: VocabStore) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ids)
        guard settings.reminderEnabled || settings.vocabReminderEnabled else { return }
        let granted = await center.notificationSettings().authorizationStatus
        guard granted == .authorized || granted == .provisional else { return }

        let cal = Calendar.current
        let now = Date()
        let goal = Double(settings.dailyGoalMinutes) * 60

        if settings.reminderEnabled {
            let streak = tracker.streak()
            let goalMet = goal > 0 && tracker.todaySeconds >= goal
            for offset in 0..<3 {
                var comps = cal.dateComponents([.year, .month, .day], from: cal.date(byAdding: .day, value: offset, to: now)!)
                comps.hour = settings.reminderHour
                comps.minute = settings.reminderMinute
                guard let fire = cal.date(from: comps), fire > now else { continue }
                if offset == 0 && goalMet { continue }
                let c = UNMutableNotificationContent()
                c.title = "Okuma zamanı"
                if offset == 0 && streak > 0 && tracker.todaySeconds < 60 {
                    c.body = "🔥 \(streak) günlük serin bozulmasın. Bugün biraz oku."
                } else if goal > 0 {
                    c.body = "Bugünkü hedefin \(settings.dailyGoalMinutes) dk. Kaldığın yerden devam edelim mi?"
                } else {
                    c.body = "Kaldığın yerden devam etmeye ne dersin?"
                }
                c.sound = .default
                let trig = UNCalendarNotificationTrigger(dateMatching: cal.dateComponents([.year, .month, .day, .hour, .minute], from: fire), repeats: false)
                try? await center.add(UNNotificationRequest(identifier: "daily-reminder-\(offset)", content: c, trigger: trig))
            }
        }

        if settings.vocabReminderEnabled {
            var comps = cal.dateComponents([.year, .month, .day], from: now)
            comps.hour = 18
            comps.minute = 30
            var fire = cal.date(from: comps) ?? now
            if fire <= now { fire = cal.date(byAdding: .day, value: 1, to: fire) ?? fire }
            let due = vocab.entries.filter { $0.dueAt <= fire }.count
            if due > 0 {
                let c = UNMutableNotificationContent()
                c.title = "Kelime tekrarı"
                c.body = "\(due) kelime tekrar bekliyor."
                c.sound = .default
                let trig = UNCalendarNotificationTrigger(dateMatching: cal.dateComponents([.year, .month, .day, .hour, .minute], from: fire), repeats: false)
                try? await center.add(UNNotificationRequest(identifier: "vocab-due", content: c, trigger: trig))
            }
        }
    }
}
