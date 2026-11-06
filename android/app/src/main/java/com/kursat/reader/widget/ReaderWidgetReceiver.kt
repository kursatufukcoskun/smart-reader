package com.kursat.reader.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kursat.reader.MainActivity
import com.kursat.reader.model.LibraryStore
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.ReadingTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

@Serializable
data class WidgetSnapshot(
    val bookID: String? = null,
    val bookTitle: String? = null,
    val bookAuthor: String? = null,
    val progress: Double = 0.0,
    val pageText: String? = null,
    val todayMinutes: Int = 0,
    val dailyGoalMinutes: Int = 20,
    val streak: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

object WidgetBridge {
    private const val PREFS_NAME = "reader_widget_bridge"
    private const val KEY_SNAPSHOT = "widget.snapshot.v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun push(
        context: Context,
        library: LibraryStore,
        tracker: ReadingTracker,
        settings: ReaderSettings
    ) {
        val current = library.sortedBooks.firstOrNull()
        val snap = WidgetSnapshot(
            bookID = current?.id,
            bookTitle = current?.title,
            bookAuthor = current?.author,
            progress = current?.progress ?: 0.0,
            pageText = current?.pageText,
            todayMinutes = (tracker.todaySeconds / 60.0).roundToInt(),
            dailyGoalMinutes = settings.current.dailyGoalMinutes,
            streak = tracker.streak(),
            updatedAt = System.currentTimeMillis()
        )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, json.encodeToString(snap))
            .apply()

        CoroutineScope(Dispatchers.Main).launch {
            runCatching { ReaderGlanceWidget().updateAll(context) }
        }
    }

    fun read(context: Context): WidgetSnapshot {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null) ?: return WidgetSnapshot()
        return runCatching { json.decodeFromString<WidgetSnapshot>(raw) }.getOrDefault(WidgetSnapshot())
    }
}

class ReaderWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReaderGlanceWidget()
}

class ReaderGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetBridge.read(context)
        provideContent {
            ReaderWidgetContent(context, snap)
        }
    }

    @Composable
    private fun ReaderWidgetContent(context: Context, snap: WidgetSnapshot) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            if (snap.bookID != null) {
                data = Uri.parse("reader://book/${snap.bookID}")
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFFFAF6EE)))
                .padding(14.dp)
                .clickable(actionStartActivity(launchIntent))
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.Top
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        text = "Bugün ${snap.todayMinutes}/${snap.dailyGoalMinutes} dk",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFD97706)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    if (snap.streak > 0) {
                        Text(
                            text = "🔥 ${snap.streak} gün",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFFDC2626)),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }

                Spacer(GlanceModifier.height(8.dp))

                if (snap.bookTitle != null) {
                    Text(
                        text = snap.bookTitle,
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF1F1C16)),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 2
                    )
                    if (!snap.bookAuthor.isNullOrBlank()) {
                        Text(
                            text = snap.bookAuthor,
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF6B6257)),
                                fontSize = 12.sp
                            ),
                            maxLines = 1
                        )
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    val pct = (snap.progress * 100).roundToInt()
                    val pageInfo = if (snap.pageText != null) {
                        "%$pct · ${snap.pageText}"
                    } else {
                        "%$pct tamamlandı"
                    }
                    Text(
                        text = pageInfo,
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF57534E)),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                } else {
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        text = "Okumaya başlamak için bir kitap açın",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF6B6257)),
                            fontSize = 13.sp
                        )
                    )
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}
