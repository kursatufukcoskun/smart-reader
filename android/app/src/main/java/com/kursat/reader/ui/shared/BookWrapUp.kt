package com.kursat.reader.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kursat.reader.ReaderApplication
import com.kursat.reader.ai.AIAssistant
import com.kursat.reader.ai.AIRequest
import com.kursat.reader.ai.Prompts
import com.kursat.reader.ai.ResponseCache
import com.kursat.reader.model.Book
import com.kursat.reader.model.DurationText
import com.kursat.reader.model.LLMTier
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Kitap karnesi: okuma süresi, gün/oturum sayısı, vurgu-not-kelime sayıları ve vurgulardan yapay zekâ kapanış özeti. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookWrapUpSheet(book: Book, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as ReaderApplication
    val allEntries by app.tracker.entries.collectAsState()
    val vocabEntries by app.vocab.entries.collectAsState()
    val ai = remember { AIAssistant() }

    val entries = remember(allEntries, book.id) { allEntries.filter { it.bookID.equals(book.id, ignoreCase = true) } }
    val seconds = entries.sumOf { it.seconds }
    val sessions = entries.sumOf { it.sessions }
    val days = entries.map { it.day }.toSet().size
    val span = run {
        val dates = entries.mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }
        val lo = dates.minOrNull()
        val hi = dates.maxOrNull()
        if (lo == null || hi == null) 0 else (ChronoUnit.DAYS.between(lo, hi) + 1).toInt()
    }
    val marks = remember(book.id) { app.highlights.forBook(book.id).filter { !it.isInsight } }
    val notes = marks.count { it.note.isNotEmpty() }
    val words = vocabEntries.count { it.bookID.equals(book.id, ignoreCase = true) && !it.isCard }
    val cards = vocabEntries.count { it.bookID.equals(book.id, ignoreCase = true) && it.isCard }

    fun summarize() {
        val sb = StringBuilder()
        for (h in marks) {
            sb.append("- \"").append(h.text.replace('\n', ' ')).append('"')
            if (h.note.isNotEmpty()) sb.append(" — not: ").append(h.note)
            sb.append('\n')
        }
        val body = sb.toString().take(60_000)
        val cfg = app.settings.current.config(LLMTier.smart)
        ai.start(
            AIRequest(
                title = "Kapanış özeti: ${book.title}",
                system = Prompts.summarySystem,
                user = Prompts.wrapUp(book.title, book.author, body),
                config = cfg,
                maxTokens = 2048,
                cacheKey = ResponseCache.key("wrapup", cfg.cacheID, book.id, body)
            )
        )
    }

    @Composable
    fun Tile(value: String, label: String, modifier: Modifier) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), tonalElevation = 3.dp, modifier = modifier) {
            Column(
                modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🎉", fontSize = 44.sp)
            Text("Kitabı tamamladın!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(book.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (book.author.isNotEmpty()) {
                Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Tile(DurationText.text(seconds), "okuma süresi", Modifier.weight(1f))
                Tile("${if (span > 0) span else days}", if (span > 1) "günde ($days gün okudun)" else "gün", Modifier.weight(1f))
                Tile("$sessions", "oturum", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Tile("${marks.size}", "vurgu ($notes not)", Modifier.weight(1f))
                Tile("$words", "öğrenilen kelime", Modifier.weight(1f))
                Tile("$cards", "tekrar kartı", Modifier.weight(1f))
            }
            Button(onClick = { summarize() }, enabled = marks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Vurgularımdan kapanış özeti oluştur")
            }
            if (marks.isEmpty()) {
                Text(
                    "Bu kitaptan hiç vurgu yapmadığın için özet oluşturulamıyor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.padding(bottom = 24.dp)) {}
        }
    }
    AIPanelSheet(ai = ai, vocab = app.vocab)
}
