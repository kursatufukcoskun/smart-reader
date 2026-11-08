package com.kursat.reader.ui.shared

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.kursat.reader.model.HighlightColor
import com.kursat.reader.model.HighlightStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Hızlı sözlük

object QuickDictionary {
    /** Seçimden sözlükte aranabilecek kelime/ifade (en çok 3 kelime); değilse null. */
    fun term(text: String): String? {
        val t = text.trim { !it.isLetterOrDigit() }
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (t.isEmpty() || words.size > 3) return null
        return t
    }

    private fun intentFor(term: String) = Intent(Intent.ACTION_PROCESS_TEXT).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_PROCESS_TEXT, term)
        putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    }

    /**
     * Cihazdaki sözlük/çeviri uygulamalarını (PROCESS_TEXT) seçici ile açar.
     * İşleyen uygulama yoksa false döner; çağıran yapay zekâ açıklamasına düşer.
     */
    fun open(context: Context, term: String): Boolean {
        val intent = intentFor(term)
        val handlers = try {
            context.packageManager.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        if (handlers.isEmpty()) return false
        return try {
            val chooser = Intent.createChooser(intent, "Sözlük").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }
}

// MARK: - Kitap içi arama

data class SearchHit(
    val location: String,
    val before: String,
    val match: String,
    val after: String,
    // EPUB
    val chapter: Int? = null,
    val occurrence: Int? = null,
    // PDF
    val page: Int? = null,
    val wordRange: IntRange? = null
)

/** Büyük/küçük harf ve aksan duyarsız arama için katlanmış metin + özgün konum eşlemesi. */
class FoldedText(val original: String) {
    val folded: String
    private val map: IntArray

    init {
        val sb = StringBuilder()
        val idx = ArrayList<Int>(original.length)
        var lastSpace = true
        for (i in original.indices) {
            val ch = original[i]
            if (ch.isWhitespace()) {
                if (lastSpace) continue
                sb.append(' '); idx.add(i); lastSpace = true
            } else {
                for (c in foldChar(ch)) { sb.append(c); idx.add(i) }
                lastSpace = false
            }
        }
        folded = sb.toString()
        map = idx.toIntArray()
    }

    fun origStart(foldedIndex: Int): Int = map[foldedIndex.coerceIn(0, map.size - 1)]
    fun origEnd(foldedEndExclusive: Int): Int =
        if (foldedEndExclusive <= 0) 0 else map[(foldedEndExclusive - 1).coerceIn(0, map.size - 1)] + 1

    companion object {
        private val combining = Regex("\\p{M}+")
        fun foldChar(ch: Char): String =
            Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).replace(combining, "").lowercase()

        fun foldQuery(q: String): String {
            val sb = StringBuilder()
            var lastSpace = true
            for (ch in q) {
                if (ch.isWhitespace()) { if (!lastSpace) { sb.append(' '); lastSpace = true } }
                else { sb.append(foldChar(ch)); lastSpace = false }
            }
            return sb.toString().trim()
        }
    }
}

object SearchText {
    fun collapse(s: String): String = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    data class Match(val occurrence: Int, val before: String, val match: String, val after: String, val start: Int, val end: Int)

    fun matches(text: FoldedText, query: String, limit: Int): List<Match> {
        val q = FoldedText.foldQuery(query)
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Match>()
        var from = 0
        var n = 0
        val orig = text.original
        while (out.size < limit) {
            val i = text.folded.indexOf(q, from)
            if (i < 0) break
            val s = text.origStart(i)
            val e = text.origEnd(i + q.length)
            out.add(
                Match(
                    n,
                    orig.substring(max(0, s - 50), s),
                    orig.substring(s, e),
                    orig.substring(e, min(orig.length, e + 70)),
                    s,
                    e
                )
            )
            n++
            from = i + q.length
        }
        return out
    }
}

class BookSearchModel(private val scope: CoroutineScope) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val hits: StateFlow<List<SearchHit>> = _hits.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Sorguyu arar (kitaba özgü); en çok `limit` sonuç döner. */
    var searcher: suspend (String, Int) -> List<SearchHit> = { _, _ -> emptyList() }
    var open: (SearchHit) -> Unit = {}
    private var job: Job? = null

    fun setQuery(q: String) {
        _query.value = q
        job?.cancel()
        val clean = SearchText.collapse(q)
        if (clean.length < 2) {
            _hits.value = emptyList(); _message.value = null; _searching.value = false
            return
        }
        _searching.value = true
        _message.value = null
        job = scope.launch {
            delay(350)
            val r = searcher(clean, LIMIT)
            _hits.value = r
            _searching.value = false
            _message.value = when {
                r.isEmpty() -> "Sonuç yok"
                r.size >= LIMIT -> "İlk $LIMIT sonuç gösteriliyor"
                else -> "${r.size} sonuç"
            }
        }
    }

    companion object { const val LIMIT = 200 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSearchSheet(model: BookSearchModel, onDismiss: () -> Unit) {
    val query by model.query.collectAsState()
    val hits by model.hits.collectAsState()
    val searching by model.searching.collectAsState()
    val message by model.message.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().height(560.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { model.setQuery(it) },
                label = { Text("Kitapta ara") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (searching) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(hits) { hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { model.open(hit) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            hit.location,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            buildAnnotatedString {
                                append(hit.before)
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) {
                                    append(hit.match)
                                }
                                append(hit.after)
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// MARK: - Dipnot penceresi

data class FootnoteInfo(val text: String, val chapter: Int, val fragment: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootnoteSheet(info: FootnoteInfo, onTranslate: () -> Unit, onShow: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Dipnot", style = MaterialTheme.typography.titleMedium)
            Text(
                info.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                OutlinedButton(onClick = onTranslate, modifier = Modifier.weight(1f)) { Text("Çevir") }
                OutlinedButton(onClick = onShow, modifier = Modifier.weight(1f)) { Text("Metinde göster") }
            }
        }
    }
}

// MARK: - Atlama çubuğu ("Buraya atladın · Kalıcı olsun mu?")

/** Sürüklenebilir çubuk: yazıyı kapatıyorsa tutup başka yere bırakılır, yeri hatırlanır. Uzun basmak yerine "↺" ile sıfırlanır. */
@Composable
fun DraggableJumpBar(from: String, onKeep: () -> Unit, onBack: () -> Unit, bottomPadding: Dp) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("jump_bar", Context.MODE_PRIVATE) }
    var offX by remember { mutableFloatStateOf(prefs.getFloat("x", 0f)) }
    var offY by remember { mutableFloatStateOf(prefs.getFloat("y", 0f)) }
    var barW by remember { mutableFloatStateOf(0f) }
    var barH by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val bottomPx = with(density) { bottomPadding.toPx() }
        val topMargin = with(density) { 60.dp.toPx() }
        val maxX = max(0f, (maxW - barW) / 2f - 8f)
        val minY = -max(0f, maxH - bottomPx - barH - topMargin)
        val maxY = max(0f, bottomPx - 8f)
        fun cx(x: Float) = x.coerceIn(-maxX, maxX)
        fun cy(y: Float) = y.coerceIn(minY, maxY)

        Surface(
            shape = CircleShape,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(bottom = bottomPadding)
                .offset { IntOffset(cx(offX).roundToInt(), cy(offY).roundToInt()) }
                .onSizeChanged { barW = it.width.toFloat(); barH = it.height.toFloat() }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            offX = cx(offX); offY = cy(offY)
                            prefs.edit().putFloat("x", offX).putFloat("y", offY).apply()
                        }
                    ) { change, drag ->
                        change.consume()
                        offX += drag.x
                        offY += drag.y
                    }
                }
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column {
                    Text("Buraya atladın", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Kalıcı olsun mu?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = onBack) { Text("Geri dön") }
                Button(onClick = onKeep) { Text("Burada kal") }
                TextButton(
                    onClick = {
                        offX = 0f; offY = 0f
                        prefs.edit().putFloat("x", 0f).putFloat("y", 0f).apply()
                    }
                ) { Text("↺", fontSize = 16.sp) }
            }
        }
    }
}

// MARK: - Kalem (stylus) paleti ve çizim sonrası menü

private fun penColor(c: HighlightColor): Color = when (c) {
    HighlightColor.yellow -> Color(0xFFFFD60A)
    HighlightColor.green -> Color(0xFF34C759)
    HighlightColor.blue -> Color(0xFF0A84FF)
    HighlightColor.pink -> Color(0xFFFF375F)
    HighlightColor.orange -> Color(0xFFFF9500)
}

@Composable
fun PenPalette(
    style: HighlightStyle,
    color: HighlightColor,
    onStyle: (HighlightStyle) -> Unit,
    onColor: (HighlightColor) -> Unit,
    onClose: () -> Unit
) {
    Surface(shape = CircleShape, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HighlightStyle.entries.forEach { s ->
                FilterChip(selected = style == s, onClick = { onStyle(s) }, label = { Text(s.title) })
            }
            HighlightColor.entries.forEach { c ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(penColor(c), CircleShape)
                        .then(
                            if (color == c) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable { onColor(c) }
                )
            }
            TextButton(onClick = onClose) { Text("✕", fontSize = 16.sp) }
        }
    }
}

@Composable
fun PenResultBar(
    showDictionary: Boolean,
    onDictionary: () -> Unit,
    onTranslate: () -> Unit,
    onExplain: () -> Unit,
    onNote: () -> Unit,
    onUndo: () -> Unit
) {
    Surface(shape = CircleShape, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showDictionary) TextButton(onClick = onDictionary) { Text("Sözlük") }
            TextButton(onClick = onTranslate) { Text("Çevir") }
            TextButton(onClick = onExplain) { Text("Açıkla") }
            TextButton(onClick = onNote) { Text("Not al") }
            TextButton(onClick = onUndo) { Text("Geri al") }
        }
    }
}

// MARK: - Başka cihazdaki konum

@Composable
fun RemoteProgressBar(label: String, onAccept: () -> Unit, onKeep: () -> Unit) {
    Surface(shape = CircleShape, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column {
                Text("Diğer cihazda kaldığın yer", style = MaterialTheme.typography.titleSmall)
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            FilledTonalButton(onClick = onKeep) { Text("Burada kal") }
            Button(onClick = onAccept) { Text("Oraya geç") }
        }
    }
}

// MARK: - Uzun aradan sonra "son kaldığın yeri hatırlat"

@Composable
fun ResumeBar(days: Int, onAsk: () -> Unit, onDismiss: () -> Unit) {
    Surface(shape = CircleShape, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column {
                Text("$days gün aradan sonra döndün", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Kaldığın yeri 3 cümleyle hatırlatayım mı?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onAsk) { Text("Hatırlat") }
            TextButton(onClick = onDismiss) { Text("✕", fontSize = 16.sp) }
        }
    }
}

// MARK: - Yapay zekâ içgörüleri (vurgular listesi başlığı)

@Composable
fun InsightsHeader(generating: Boolean, label: String = "Bu bölümün içgörülerini çıkar", onGenerate: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onGenerate, enabled = !generating, modifier = Modifier.fillMaxWidth()) {
            if (generating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  İçgörüler hazırlanıyor…")
            } else {
                Text("✨ $label")
            }
        }
        Text(
            "Yapay zekâ metinden 3-5 içgörü çıkarır; vurgularından ayrı, ✨ ile işaretli görünür.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
