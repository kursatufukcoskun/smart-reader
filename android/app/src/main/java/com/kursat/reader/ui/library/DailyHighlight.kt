package com.kursat.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kursat.reader.ReaderApplication
import com.kursat.reader.model.Highlight
import com.kursat.reader.ui.shared.QuoteCard
import java.time.LocalDate

/** Kitaplık ekranında her gün eski bir vurguyla yeniden karşılaşma kartı. */
@Composable
fun DailyHighlightCard(onOpen: (Highlight) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApplication
    val items by app.highlights.items.collectAsState()
    val books by app.library.books.collectAsState()
    val settings by app.settings.state.collectAsState()
    var shift by remember { mutableIntStateOf(0) }

    val pool = remember(items) { items.filter { !it.isInsight && it.text.length in 30..400 }.sortedBy { it.id } }
    if (pool.isEmpty()) return
    val day = LocalDate.now().toEpochDay().toInt()
    val h = pool[Math.floorMod(day * 7 + shift, pool.size)]
    val author = books.firstOrNull { it.id.equals(h.bookID, ignoreCase = true) }?.author.orEmpty()

    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Günün vurgusu", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { shift += 1 }) { Text("Başka") }
            }
            Text(
                h.text.replace('\n', ' '),
                fontFamily = FontFamily.Serif,
                fontSize = 17.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            if (h.note.isNotEmpty()) {
                Text("📝 ${h.note}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(h.bookTitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onOpen(h) }) { Text("Kitapta aç") }
                TextButton(onClick = { QuoteCard.share(context, h.text, h.bookTitle, author, settings.theme) }) { Text("Paylaş") }
            }
        }
    }
}
