package com.kursat.reader.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kursat.reader.model.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private enum class StatsMetric(val label: String) {
    TIME("Süre (dk)"),
    PAGES("Sayfa")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsView(
    tracker: ReadingTracker,
    library: LibraryStore,
    settings: ReaderSettings,
    onBack: () -> Unit
) {
    val entries by tracker.entries.collectAsState()
    val books by library.books.collectAsState()
    val settingsState by settings.state.collectAsState()

    var period by remember { mutableStateOf(StatsPeriod.week) }
    var metric by remember { mutableStateOf(StatsMetric.TIME) }
    var selectedBookID by remember { mutableStateOf<String?>(null) }
    var selectedBucketIndex by remember { mutableStateOf<Int?>(null) }

    val selectedBook = books.firstOrNull { it.id.equals(selectedBookID, ignoreCase = true) }
    val buckets = remember(entries, period, selectedBookID) {
        tracker.buckets(period, selectedBookID)
    }

    val weekBuckets = remember(entries, selectedBookID) {
        tracker.buckets(StatsPeriod.week, selectedBookID)
    }
    val todayBucket = weekBuckets.lastOrNull()
    val todaySec = todayBucket?.seconds ?: 0.0
    val todayPages = todayBucket?.pages?.roundToInt() ?: 0
    val weekSec = weekBuckets.sumOf { it.seconds }
    val weekPages = weekBuckets.sumOf { it.pages }.roundToInt()
    val filteredEntries = remember(entries, selectedBookID) { tracker.entriesFor(selectedBookID) }
    val totalSec = filteredEntries.sumOf { it.seconds }
    val totalPages = (filteredEntries.sumOf { it.words } / Book.WORDS_PER_PAGE).roundToInt()
    val streak = remember(entries, selectedBookID) { tracker.streak(selectedBookID) }
    val wpm = remember(entries) { tracker.wordsPerMinute.roundToInt() }

    val trLocale = remember { Locale("tr", "TR") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Okuma İstatistikleri", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard(
                            title = "Bugün",
                            value = DurationText.text(todaySec),
                            subtitle = "$todayPages sayfa",
                            icon = Icons.Default.WbSunny,
                            color = Color(0xFFFF9800),
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            title = "Bu Hafta",
                            value = DurationText.text(weekSec),
                            subtitle = "$weekPages sayfa",
                            icon = Icons.Default.CalendarViewWeek,
                            color = Color(0xFF2196F3),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard(
                            title = "Seri",
                            value = "$streak gün",
                            subtitle = "Günlük hedef: ${settingsState.dailyGoalMinutes} dk",
                            icon = Icons.Default.LocalFireDepartment,
                            color = Color(0xFFF44336),
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            title = "Okuma Hızı",
                            value = "$wpm klm/dk",
                            subtitle = if (tracker.hasSpeedData) "~${max(1, (wpm / Book.WORDS_PER_PAGE).roundToInt())} syf/dk" else "Tahmini hız",
                            icon = Icons.Default.Speed,
                            color = Color(0xFF4CAF50),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard(
                            title = "Toplam Süre",
                            value = DurationText.text(totalSec),
                            subtitle = "${books.count { it.isFinished }} biten kitap",
                            icon = Icons.Default.Schedule,
                            color = Color(0xFF9C27B0),
                            modifier = Modifier.weight(1f)
                        )
                        StatCard(
                            title = "Toplam Sayfa",
                            value = "$totalPages",
                            subtitle = "~250 kelime/sayfa",
                            icon = Icons.Default.MenuBook,
                            color = Color(0xFF3F51B5),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Aktivite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (selectedBook != null) {
                                    Text(
                                        selectedBook.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            SingleChoiceSegmentedButtonRow {
                                StatsMetric.entries.forEachIndexed { idx, m ->
                                    SegmentedButton(
                                        selected = metric == m,
                                        onClick = { metric = m },
                                        shape = SegmentedButtonDefaults.itemShape(idx, StatsMetric.entries.size)
                                    ) {
                                        Text(m.label, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            StatsPeriod.entries.forEachIndexed { idx, p ->
                                SegmentedButton(
                                    selected = period == p,
                                    onClick = { period = p; selectedBucketIndex = null },
                                    shape = SegmentedButtonDefaults.itemShape(idx, StatsPeriod.entries.size)
                                ) {
                                    Text(p.title)
                                }
                            }
                        }

                        val picked = selectedBucketIndex?.let { buckets.getOrNull(it) }
                        if (picked != null) {
                            val fullFmt = remember(period) {
                                DateTimeFormatter.ofPattern(
                                    if (period == StatsPeriod.year) "MMMM yyyy" else "d MMMM EEEE",
                                    trLocale
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(picked.start.format(fullFmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (metric == StatsMetric.TIME) DurationText.text(picked.seconds) else "${picked.pages.roundToInt()} sayfa",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        val shortFmt = remember(period) {
                            DateTimeFormatter.ofPattern(
                                when (period) {
                                    StatsPeriod.week -> "EEE"
                                    StatsPeriod.month -> "d"
                                    StatsPeriod.year -> "MMM"
                                },
                                trLocale
                            )
                        }
                        val values = buckets.map { if (metric == StatsMetric.TIME) it.seconds / 60.0 else it.pages }
                        val maxVal = max(1.0, values.maxOrNull() ?: 1.0)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            buckets.forEachIndexed { idx, b ->
                                val v = values[idx]
                                val frac = (v / maxVal).toFloat().coerceIn(0.03f, 1f)
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clickable { selectedBucketIndex = idx },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.75f)
                                            .fillMaxHeight(frac * 0.82f)
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                            .background(
                                                if (selectedBucketIndex == idx) MaterialTheme.colorScheme.tertiary
                                                else if (v > 0.0) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                            )
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    if (period != StatsPeriod.month || idx % 5 == 0) {
                                        Text(
                                            b.start.format(shortFmt),
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Spacer(Modifier.height(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Kitaplara Göre", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (selectedBookID != null) {
                                TextButton(onClick = { selectedBookID = null }) {
                                    Text("Tümünü Göster")
                                }
                            }
                        }

                        if (books.isEmpty()) {
                            Text("Henüz kitap yok.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            books.forEach { book ->
                                val bookEntries = tracker.entriesFor(book.id)
                                val bookSec = bookEntries.sumOf { it.seconds }
                                val bookPages = (bookEntries.sumOf { it.words } / Book.WORDS_PER_PAGE).roundToInt()
                                val wordsLeft = max(0.0, book.totalWords.toDouble() - book.wordPosition)
                                val etaSec = if (wordsLeft > 0 && !book.isFinished) tracker.secondsToRead(wordsLeft) else 0.0

                                Surface(
                                    onClick = {
                                        selectedBookID = if (selectedBookID == book.id) null else book.id
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selectedBookID == book.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Text(DurationText.text(bookSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    book.pageText ?: "$bookPages syf okundu",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (etaSec > 0) {
                                                    Text(
                                                        "· bitmesine ~${DurationText.text(etaSec)}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            "%${(book.progress * 100).roundToInt()}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
