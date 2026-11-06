import SwiftUI

struct GoalSettingsSection: View {
    @EnvironmentObject var settings: ReaderSettings
    @EnvironmentObject var tracker: ReadingTracker
    @EnvironmentObject var vocab: VocabStore
    @State private var denied = false

    private var timeBinding: Binding<Date> {
        Binding(
            get: {
                Calendar.current.date(bySettingHour: settings.reminderHour, minute: settings.reminderMinute, second: 0, of: Date()) ?? Date()
            },
            set: {
                let c = Calendar.current.dateComponents([.hour, .minute], from: $0)
                settings.reminderHour = c.hour ?? 20
                settings.reminderMinute = c.minute ?? 0
                refresh()
            })
    }

    var body: some View {
        Section {
            Stepper(value: $settings.dailyGoalMinutes, in: 0...240, step: 5) {
                HStack {
                    Text("Günlük hedef")
                    Spacer()
                    Text(settings.dailyGoalMinutes == 0 ? "Kapalı" : "\(settings.dailyGoalMinutes) dk").foregroundStyle(.secondary)
                }
            }
            .onChange(of: settings.dailyGoalMinutes) { _, _ in refresh() }

            Toggle("Okuma hatırlatması", isOn: $settings.reminderEnabled)
                .onChange(of: settings.reminderEnabled) { _, on in if on { ask() } else { refresh() } }
            if settings.reminderEnabled {
                DatePicker("Saat", selection: timeBinding, displayedComponents: .hourAndMinute)
            }
            Toggle("Kelime tekrarı hatırlatması", isOn: $settings.vocabReminderEnabled)
                .onChange(of: settings.vocabReminderEnabled) { _, on in if on { ask() } else { refresh() } }
        } header: {
            Text("Hedef ve hatırlatmalar")
        } footer: {
            if denied {
                Text("Bildirim izni kapalı. Ayarlar ▸ Bildirimler bölümünden açın.").foregroundStyle(.red)
            } else {
                Text("Hedefinizi tamamladığınız günün hatırlatması gönderilmez. Kelime hatırlatması her akşam 18:30'da, tekrarı gelmiş kart varsa gelir.")
            }
        }
    }

    private func ask() {
        Task {
            denied = !(await NotificationScheduler.requestAuthorization())
            await NotificationScheduler.refresh(settings: settings, tracker: tracker, vocab: vocab)
        }
    }

    private func refresh() {
        Task { await NotificationScheduler.refresh(settings: settings, tracker: tracker, vocab: vocab) }
    }
}
