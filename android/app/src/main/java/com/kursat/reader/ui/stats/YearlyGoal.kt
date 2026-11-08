package com.kursat.reader.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kursat.reader.model.Book
import com.kursat.reader.model.LibraryStore
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.ReadingTracker
import java.time.LocalDate
import java.time.ZoneId

/** Bu yıl bitirilen kitaplar ve yıllık hedef. Bitiş günü, kitabın okuma günlüğündeki son günden alınır. */
@Composable
fun YearlyGoalCard(library: LibraryStore, tracker: ReadingTracker, settings: ReaderSettings) {
    val books by library.books.collectAsState()
    val entries by tracker.entries.collectAsState()
    val state by settings.state.collectAsState()
    val year = LocalDate.now().year

    val finished: List<Pair<Book, LocalDate>> = remember(books, entries, year) {
        books.mapNotNull { b ->
            if (b.progress < 0.98 || b.isRemoteOnly) return@mapNotNull null
            val logDay = entries.filter { it.bookID.equals(b.id, ignoreCase = true) }
                .mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }.maxOrNull()
            val day = logDay ?: b.lastReadAt?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: return@mapNotNull null
            if (day.year == year) b to day else null
        }.sortedByDescending { it.second }
    }
    val goal = state.yearlyGoal

    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (goal > 0) "Bu yıl ${finished.size} / $goal kitap" else "Bu yıl ${finished.size} kitap bitirdin",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { settings.update { it.copy(yearlyGoal = (it.yearlyGoal - 1).coerceAtLeast(0)) } }) { Text("−") }
                    TextButton(onClick = { settings.update { it.copy(yearlyGoal = (it.yearlyGoal + 1).coerceAtMost(200)) } }) { Text("+") }
                }
            }
            if (goal > 0) {
                LinearProgressIndicator(
                    progress = { (finished.size.toFloat() / goal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (finished.size >= goal) "Hedefe ulaştın 🎉" else "Hedefe ${goal - finished.size} kitap kaldı · hedefi −/+ ile değiştirebilirsin",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Hedef kapalı. + ile bir yıllık hedef belirle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (finished.isNotEmpty()) {
                HorizontalDivider()
                finished.take(8).forEach { (b, d) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✓", color = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(b.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (b.author.isNotEmpty()) {
                                Text(b.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(
                            "${d.dayOfMonth}.${d.monthValue}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (finished.size > 8) {
                    Text("… ve ${finished.size - 8} kitap daha", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
