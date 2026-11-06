package com.kursat.reader.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.kursat.reader.MainActivity
import com.kursat.reader.ReaderApplication
import java.util.Calendar
import java.util.concurrent.TimeUnit

object NotificationScheduler {
    private const val CHANNEL_ID = "reader_reminders"
    private const val DAILY_WORK_NAME = "reader.daily.reading"
    private const val VOCAB_WORK_NAME = "reader.vocab.due"

    fun refresh(
        context: Context,
        settings: ReaderSettings,
        tracker: ReadingTracker,
        vocab: VocabStore
    ) {
        ensureChannel(context)
        val s = settings.current
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DAILY_WORK_NAME)
        wm.cancelUniqueWork(VOCAB_WORK_NAME)

        if (s.reminderEnabled) {
            val delayMs = delayUntil(s.reminderHour, s.reminderMinute)
            val req = PeriodicWorkRequestBuilder<DailyReadingReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(DAILY_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        if (s.vocabReminderEnabled && vocab.dueCount > 0) {
            val delayMs = delayUntil(18, 30)
            val req = PeriodicWorkRequestBuilder<VocabReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            wm.enqueueUniquePeriodicWork(VOCAB_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    private fun delayUntil(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Okuma ve Kelime Hatırlatıcıları",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            mgr.createNotificationChannel(channel)
        }
    }

    fun showNotification(context: Context, id: Int, title: String, body: String) {
        ensureChannel(context)
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        mgr.notify(id, notif)
    }
}

class DailyReadingReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val app = applicationContext as? ReaderApplication ?: return Result.success()
        val goal = app.settings.current.dailyGoalMinutes
        if (goal > 0 && app.tracker.todaySeconds >= goal * 60.0) {
            return Result.success()
        }
        val streak = app.tracker.streak()
        val body = if (streak > 0) {
            "$streak günlük serini koru — bugün $goal dakika hedefini tamamla."
        } else {
            "Bugün $goal dakika okumaya ne dersin?"
        }
        NotificationScheduler.showNotification(applicationContext, 1001, "Okuma zamanı", body)
        return Result.success()
    }
}

class VocabReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val app = applicationContext as? ReaderApplication ?: return Result.success()
        val due = app.vocab.dueCount
        if (due > 0) {
            NotificationScheduler.showNotification(
                applicationContext,
                1002,
                "Kelime tekrarı",
                "Tekrar zamanı gelen $due kelime var."
            )
        }
        return Result.success()
    }
}
