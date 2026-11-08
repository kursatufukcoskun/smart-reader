package com.kursat.reader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kursat.reader.model.ReadingTracker
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Son 52 haftanın her günü, okunan dakikaya göre açıktan koyuya renkli bir kare (GitHub tarzı yıllık takvim). */
@Composable
fun ReadingHeatmapCard(tracker: ReadingTracker, bookID: String?) {
    val entries by tracker.entries.collectAsState()
    val today = LocalDate.now()
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(52)
    val weeks = 53

    val minutes: Map<String, Double> = remember(entries, bookID) {
        val m = HashMap<String, Double>()
        tracker.entriesFor(bookID).forEach { e -> m[e.day] = (m[e.day] ?: 0.0) + e.seconds / 60.0 }
        m
    }
    val activeDays = remember(minutes, start) {
        minutes.count { (k, v) -> v >= 1 && runCatching { LocalDate.parse(k) }.getOrNull()?.let { !it.isBefore(start) && !it.isAfter(today) } == true }
    }
    val totalHours = remember(minutes, start) {
        minutes.filter { (k, _) -> runCatching { LocalDate.parse(k) }.getOrNull()?.let { !it.isBefore(start) && !it.isAfter(today) } == true }
            .values.sum() / 60.0
    }

    fun level(m: Double) = when {
        m < 1 -> 0
        m < 10 -> 1
        m < 20 -> 2
        m < 40 -> 3
        else -> 4
    }
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val green = Color(0xFF34C759)
    fun color(l: Int) = when (l) {
        0 -> empty
        1 -> green.copy(alpha = 0.30f)
        2 -> green.copy(alpha = 0.50f)
        3 -> green.copy(alpha = 0.75f)
        else -> green
    }

    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val cell = 12.dp
    val gap = 3.dp
    val labelH = 16.dp
    val trLocale = remember { Locale("tr", "TR") }

    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Okuma takvimi", style = MaterialTheme.typography.titleMedium)
            Text(
                "Son 365 günde $activeDays gün okudun · ${"%.0f".format(totalHours)} saat",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.horizontalScroll(scroll)) {
                Canvas(
                    modifier = Modifier
                        .width((cell + gap) * weeks)
                        .height(labelH + (cell + gap) * 7)
                ) {
                    val c = cell.toPx()
                    val g = gap.toPx()
                    val top = labelH.toPx()
                    for (w in 0 until weeks) {
                        val first = start.plusWeeks(w.toLong())
                        val prevMonth = start.plusWeeks((w - 1).toLong()).monthValue
                        if (w == 0 || first.monthValue != prevMonth) {
                            drawText(
                                measurer,
                                first.month.getDisplayName(DateTextStyle.SHORT, trLocale),
                                topLeft = Offset(w * (c + g), 0f),
                                style = TextStyle(fontSize = 9.sp, color = labelColor)
                            )
                        }
                        for (d in 0 until 7) {
                            val date = start.plusDays((w * 7 + d).toLong())
                            if (date.isAfter(today)) continue
                            val m = minutes[date.toString()] ?: 0.0
                            drawRoundRect(
                                color = color(level(m)),
                                topLeft = Offset(w * (c + g), top + d * (c + g)),
                                size = Size(c, c),
                                cornerRadius = CornerRadius(c * 0.22f)
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Az", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (l in 0..4) {
                    Canvas(modifier = Modifier.width(11.dp).height(11.dp)) {
                        drawRoundRect(color(l), size = size, cornerRadius = CornerRadius(size.width * 0.22f))
                    }
                }
                Text("Çok", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
