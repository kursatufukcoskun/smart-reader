package com.kursat.reader.ui.notes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccessTimeFilled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.kursat.reader.model.ReviewGrade
import com.kursat.reader.model.VocabEntry
import com.kursat.reader.model.VocabStore
import com.kursat.reader.ui.shared.highlightWordInContext
import com.kursat.reader.ui.shared.parseInlineMarkdown
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabView(
    vocab: VocabStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as com.kursat.reader.ReaderApplication
    val ai = remember { com.kursat.reader.ai.AIAssistant() }
    val entries by vocab.entries.collectAsState()
    var search by remember { mutableStateOf("") }
    var onlyDue by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<VocabEntry?>(null) }
    var studying by remember { mutableStateOf(false) }

    val dueCount = remember(entries) { entries.count { it.isDue } }
    // Bugün tekrar edilecek (vadesi gelmiş) kelimeler; en gecikenler önde, en çok 15.
    val storyWords = remember(entries) {
        entries.filter { it.isDue && !it.isCard }.sortedBy { it.dueAt }.take(15)
    }
    fun makeStory() {
        if (storyWords.size < 3) return
        val cfg = app.settings.current.config(com.kursat.reader.model.LLMTier.smart)
        ai.start(
            com.kursat.reader.ai.AIRequest(
                title = "Bugünkü kelimelerle hikâye",
                system = com.kursat.reader.ai.Prompts.assistantSystem,
                user = com.kursat.reader.ai.Prompts.vocabStory(storyWords.map { it.word to it.gloss }),
                config = cfg,
                maxTokens = 2048,
                cacheKey = null
            )
        )
    }

    val filtered = remember(entries, search, onlyDue) {
        entries.filter { e ->
            (!onlyDue || e.isDue) && (
                search.isBlank() ||
                    e.word.contains(search, ignoreCase = true) ||
                    e.gloss.contains(search, ignoreCase = true) ||
                    e.bookTitle.contains(search, ignoreCase = true)
                )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kelime defteri", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            try {
                                val file = File(context.cacheDir, "kelime-defteri.txt")
                                file.writeText(vocab.exportText(), Charsets.UTF_8)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, vocab.exportText())
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Kelime defteri (Anki)"))
                            } catch (_: Exception) {
                            }
                        },
                        enabled = entries.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Anki dışa aktar")
                    }
                    IconButton(onClick = { makeStory() }, enabled = storyWords.size >= 3) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Bugünkü kelimelerle hikâye")
                    }
                    IconButton(onClick = { onlyDue = !onlyDue }) {
                        Icon(
                            imageVector = if (onlyDue) Icons.Default.AccessTimeFilled else Icons.Default.AccessTime,
                            contentDescription = "Yalnızca tekrarı gelenler",
                            tint = if (onlyDue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TextButton(
                        onClick = { studying = true },
                        enabled = dueCount > 0
                    ) {
                        Icon(Icons.Default.Style, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (dueCount > 0) "Çalış ($dueCount)" else "Çalış")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Kelime, anlam veya kitap ara") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Temizle")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )

            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Defter boş", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Okurken bir kelimeyi seçip “Açıkla”ya basın, sonra “Deftere ekle”ye dokunun.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            } else if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Eşleşen kelime yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { e ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = e }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(e.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (e.isDue) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFFF9500))
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "Kutu ${e.box}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Text(
                                    e.gloss,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    e.bookTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { vocab.delete(listOf(e.id)) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Sil")
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { selected = null }) { Text("Kapat") }
                    IconButton(
                        onClick = {
                            vocab.delete(listOf(entry.id))
                            selected = null
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Sil", tint = MaterialTheme.colorScheme.error)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.word, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    if (!entry.isCard) com.kursat.reader.ui.shared.PronounceButton(entry.word, entry.paragraph)
                }
                if (!entry.isCard) Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = highlightWordInContext(entry.snippet, entry.word),
                        fontFamily = FontFamily.Serif,
                        fontSize = 17.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                SelectionContainer {
                    Text(
                        text = parseInlineMarkdown(entry.explanation),
                        fontSize = 18.sp,
                        lineHeight = 25.sp
                    )
                }
                Text(
                    text = "${entry.bookTitle} · ${entry.addedAt.take(10)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (studying) {
        ReviewDialog(
            vocab = vocab,
            onDismiss = { studying = false }
        )
    }
    com.kursat.reader.ui.shared.AIPanelSheet(ai = ai, vocab = vocab)
}

@Composable
fun ReviewDialog(
    vocab: VocabStore,
    onDismiss: () -> Unit
) {
    val entries by vocab.entries.collectAsState()
    var queue by remember { mutableStateOf<List<String>>(emptyList()) }
    var started by remember { mutableStateOf(false) }
    var showBack by remember { mutableStateOf(false) }
    var total by remember { mutableIntStateOf(0) }
    var done by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (!started) {
            val q = entries.filter { it.isDue }.shuffled().map { it.id }
            queue = q
            total = q.size
            started = true
        }
    }

    val current: VocabEntry? = queue.firstOrNull()?.let { id ->
        entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Kapat") }
                    Text("Tekrar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(64.dp))
                }

                if (current != null) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total.coerceAtLeast(1).toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clickable { showBack = true }
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = current.word,
                                    fontFamily = FontFamily.Serif,
                                    fontSize = if (current.isCard) 26.sp else 40.sp,
                                    fontWeight = if (current.isCard) FontWeight.SemiBold else FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (!current.isCard) com.kursat.reader.ui.shared.PronounceButton(current.word, current.paragraph)
                            }
                            if (!current.isCard) Text(
                                text = highlightWordInContext(current.snippet, current.word),
                                fontFamily = FontFamily.Serif,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            if (showBack) {
                                HorizontalDivider()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = parseInlineMarkdown(current.explanation),
                                        fontSize = 18.sp,
                                        lineHeight = 25.sp
                                    )
                                }
                            }
                        }
                    }

                    if (showBack) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            GradeButton("Bilmiyorum", Color(0xFFFF3B30), Modifier.weight(1f)) {
                                vocab.review(current.id, ReviewGrade.again)
                                queue = queue.drop(1) + current.id
                                showBack = false
                            }
                            GradeButton("Zor", Color(0xFFFF9500), Modifier.weight(1f)) {
                                vocab.review(current.id, ReviewGrade.hard)
                                queue = queue.drop(1)
                                done += 1
                                showBack = false
                            }
                            GradeButton("Biliyorum", Color(0xFF34C759), Modifier.weight(1f)) {
                                vocab.review(current.id, ReviewGrade.good)
                                queue = queue.drop(1)
                                done += 1
                                showBack = false
                            }
                            GradeButton("Kolay", Color(0xFF007AFF), Modifier.weight(1f)) {
                                vocab.review(current.id, ReviewGrade.easy)
                                queue = queue.drop(1)
                                done += 1
                                showBack = false
                            }
                        }
                    } else {
                        Button(
                            onClick = { showBack = true },
                            modifier = Modifier.width(280.dp)
                        ) {
                            Text("Cevabı göster")
                        }
                    }
                } else if (started) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF34C759),
                            modifier = Modifier.size(64.dp)
                        )
                        Text("Tamamlandı", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "$done kart çalışıldı. Gerisi zamanı gelince tekrar çıkar.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onDismiss) { Text("Bitir") }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun GradeButton(
    title: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = modifier
    ) {
        Text(title, maxLines = 1)
    }
}

