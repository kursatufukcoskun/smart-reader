package com.kursat.reader.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kursat.reader.ai.*
import com.kursat.reader.model.VocabStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterStudySheet(
    model: ChapterStudyModel,
    onDismiss: () -> Unit
) {
    val data by model.data.collectAsState()
    val isLoading by model.isLoading.collectAsState()
    val error by model.error.collectAsState()
    val language by model.language.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(language) {
        model.load(force = false)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Bölümü Pekiştir",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        model.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilterChip(
                    selected = language == "İngilizce",
                    onClick = {
                        model.setLanguage(if (language == "Türkçe") "İngilizce" else "Türkçe")
                    },
                    label = { Text(language) }
                )
                if (data != null) {
                    IconButton(onClick = { scope.launch { model.load(force = true) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Yeniden Üret")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Kapat")
                }
            }

            HorizontalDivider()

            val currentData = data
            when {
                currentData != null -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Özet & Kavramlar") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Quiz (${currentData.quiz.size})") })
                        Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Kartlar (${currentData.qa.size})") })
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (tab) {
                            0 -> StudyOverviewTab(currentData)
                            1 -> StudyQuizTab(currentData.quiz)
                            2 -> StudyQATab(currentData.qa)
                        }
                    }
                }
                isLoading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Çalışma paketi hazırlanıyor…", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Özet, kavramlar, 8 soruluk quiz ve soru-cevap kartları üretiliyor",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { scope.launch { model.load(force = true) } }) {
                            Text("Tekrar Dene")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyOverviewTab(data: StudyData) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Summarize, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Ana Noktalar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            data.summary.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (data.concepts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Önemli Kavramlar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                data.concepts.forEach { c ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(c.term, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(c.definition, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyQuizTab(quiz: List<QuizQuestion>) {
    var index by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateMapOf<Int, Int>() }
    val revealed = remember { mutableStateMapOf<Int, Boolean>() }

    if (quiz.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Soru üretilemedi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val q = quiz[index]
    val picked = selected[index]
    val isRevealed = revealed[index] == true
    val correctCount = quiz.indices.count { i -> selected[i] == quiz[i].correct }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Soru ${index + 1} / ${quiz.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Doğru: $correctCount / ${revealed.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(
            progress = { (index + 1).toFloat() / quiz.size.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )

        Text(q.question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        q.options.forEachIndexed { optIdx, opt ->
            val bg = when {
                !isRevealed -> if (picked == optIdx) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                optIdx == q.correct -> Color(0xFF4CAF50).copy(alpha = 0.22f)
                picked == optIdx -> MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
            Surface(
                onClick = {
                    if (!isRevealed) {
                        selected[index] = optIdx
                        revealed[index] = true
                    }
                },
                shape = RoundedCornerShape(10.dp),
                color = bg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "${'A' + optIdx}.",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(opt, modifier = Modifier.weight(1f))
                    if (isRevealed) {
                        if (optIdx == q.correct) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
                        } else if (picked == optIdx) {
                            Icon(Icons.Default.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (isRevealed && q.explanation.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF2196F3).copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF2196F3))
                    Text(q.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { if (index > 0) index-- }, enabled = index > 0) {
                Text("Önceki")
            }
            if (index < quiz.size - 1) {
                Button(onClick = { index++ }) {
                    Text("Sonraki")
                }
            } else {
                OutlinedButton(onClick = {
                    selected.clear()
                    revealed.clear()
                    index = 0
                }) {
                    Text("Sıfırla")
                }
            }
        }
    }
}

@Composable
private fun StudyQATab(cards: List<QAItem>) {
    var index by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }

    if (cards.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Kart üretilemedi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val card = cards[index]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Kart ${index + 1} / ${cards.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable { flipped = !flipped },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 3.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    if (flipped) "CEVAP" else "SORU",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (flipped) card.answer else card.question,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Çevirmek için dokun",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            OutlinedButton(
                onClick = { flipped = false; if (index > 0) index-- },
                enabled = index > 0
            ) {
                Text("Önceki")
            }
            Button(
                onClick = { flipped = false; if (index < cards.size - 1) index++ },
                enabled = index < cards.size - 1
            ) {
                Text("Sonraki")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardWordsSheet(
    model: HardWordsModel,
    vocab: VocabStore,
    onDismiss: () -> Unit
) {
    val items by model.items.collectAsState()
    val isLoading by model.isLoading.collectAsState()
    val error by model.error.collectAsState()
    val vocabEntries by vocab.entries.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        model.load()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.85f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Bölümün Zor Kelimeleri",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        model.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (items.isNotEmpty()) {
                    TextButton(onClick = {
                        items.forEach { w ->
                            vocab.add(model.draft(w), model.explanation(w))
                        }
                    }) {
                        Text("Tümünü Ekle")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Kapat")
                }
            }

            HorizontalDivider()

            when {
                items.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items, key = { it.id }) { w ->
                            val draft = model.draft(w)
                            val saved = vocabEntries.any {
                                it.bookID?.equals(draft.bookID, ignoreCase = true) == true &&
                                    it.word.equals(draft.word, ignoreCase = true)
                            }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(w.word, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            if (w.pos.isNotEmpty()) {
                                                Text(w.pos, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Text(w.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                        if (w.context.isNotEmpty()) {
                                            Text("“${w.context}”", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            if (!saved) vocab.add(draft, model.explanation(w))
                                        },
                                        enabled = !saved
                                    ) {
                                        Icon(
                                            if (saved) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                                            contentDescription = "Deftere Ekle",
                                            tint = if (saved) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text("Bölüm taranıyor, zor kelimeler seçiliyor…")
                        }
                    }
                }
                error != null -> {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { scope.launch { model.load() } }) {
                                Text("Tekrar Dene")
                            }
                        }
                    }
                }
            }
        }
    }
}
