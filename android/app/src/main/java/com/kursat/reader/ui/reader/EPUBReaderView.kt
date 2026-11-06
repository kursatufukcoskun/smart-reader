package com.kursat.reader.ui.reader

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kursat.reader.epub.EPUBPackage
import com.kursat.reader.model.Book
import com.kursat.reader.model.BookmarkStore
import com.kursat.reader.model.DurationText
import com.kursat.reader.model.Highlight
import com.kursat.reader.model.HighlightColor
import com.kursat.reader.model.HighlightStore
import com.kursat.reader.model.LibraryStore
import com.kursat.reader.model.Paths
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.ReadingMode
import com.kursat.reader.model.ReadingTracker
import com.kursat.reader.model.VocabStore
import com.kursat.reader.ui.ai.BookChatSheet
import com.kursat.reader.ui.ai.ChapterStudySheet
import com.kursat.reader.ui.ai.HardWordsSheet
import com.kursat.reader.ui.shared.AIPanelSheet
import com.kursat.reader.ui.shared.ReaderChromeBox
import com.kursat.reader.ui.shared.SelectionBar
import com.kursat.reader.ui.shared.ThemePanel
import com.kursat.reader.ui.shared.ToastHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun EPUBLoader(
    book: Book,
    library: LibraryStore,
    settings: ReaderSettings,
    highlights: HighlightStore,
    vocab: VocabStore,
    bookmarks: BookmarkStore,
    tracker: ReadingTracker,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var model by remember(book.id) { mutableStateOf<EPUBReaderModel?>(null) }
    var error by remember(book.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(book.id) {
        if (model != null) return@LaunchedEffect
        try {
            val root = Paths.epubRoot(book)
            val pkg = withContext(Dispatchers.IO) { EPUBPackage.load(root) }
            val m = EPUBReaderModel(
                context = context,
                initialBook = book,
                pkg = pkg,
                settings = settings,
                library = library,
                highlights = highlights,
                bookmarks = bookmarks,
                tracker = tracker
            )
            val jump = highlights.pendingJump
            if (jump != null && jump.bookID.equals(book.id, ignoreCase = true)) {
                highlights.pendingJump = null
                m.jumpAfterOpen(jump)
            }
            model = m
        } catch (e: Exception) {
            error = e.message ?: "Kitap açılamadı."
        }
    }

    when {
        model != null -> {
            EPUBReaderView(
                model = model!!,
                settings = settings,
                vocab = vocab,
                tracker = tracker,
                onBack = onBack
            )
        }
        error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Kitap açılamadı", style = MaterialTheme.typography.titleMedium)
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onBack) { Text("Geri dön") }
                }
            }
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text("Kitap açılıyor…")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EPUBReaderView(
    model: EPUBReaderModel,
    settings: ReaderSettings,
    vocab: VocabStore,
    tracker: ReadingTracker,
    onBack: () -> Unit
) {
    val settingsState by settings.state.collectAsState()
    val book by model.book.collectAsState()
    val chapterIndex by model.chapterIndex.collectAsState()
    val hasSelection by model.hasSelection.collectAsState()
    val isTranslatingPage by model.isTranslatingPage.collectAsState()
    val translationsHidden by model.translationsHidden.collectAsState()
    val alertMessage by model.alertMessage.collectAsState()
    val toast by model.toast.collectAsState()
    val pageCur by model.pageCur.collectAsState()
    val pageTotal by model.pageTotal.collectAsState()
    val editing by model.editing.collectAsState()
    val studyModel by model.study.collectAsState()
    val hardWordsModel by model.hardWords.collectAsState()

    var chrome by remember { mutableStateOf(false) }
    com.kursat.reader.ui.shared.ImmersiveBars(hidden = !chrome)
    var showTOC by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var tocTab by remember { mutableIntStateOf(0) }
    var barMode by remember { mutableIntStateOf(0) }
    var showTranslateMenu by remember { mutableStateOf(false) }
    var showAiMenu by remember { mutableStateOf(false) }

    // Sayfa modunda soldan sağa kaydırma sistemin "geri" hareketiyle çakışmasın diye geri hareketini yakala.
    // Okuyucudan çıkmak için üst çubuktaki geri butonunu kullanırız (veya çubuk kapalıysa önce çubuğu açarız).
    BackHandler(enabled = settingsState.readingMode == ReadingMode.paged && !chrome) {
        chrome = true
    }

    DisposableEffect(model) {
        model.start()
        model.onCenterTap = { chrome = !chrome }
        onDispose {
            model.stop()
        }
    }

    ReaderChromeBox(settings = settings) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(settingsState.theme.color)
        ) {
            AndroidView(
                factory = {
                    model.webView.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Üst araç çubuğu
            AnimatedVisibility(
                visible = chrome,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                TopAppBar(
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            text = model.pkg.title.ifBlank { book.title },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = settingsState.theme.textColor
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Kitaplık",
                                tint = settingsState.theme.textColor
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showTOC = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = "İçindekiler",
                                tint = settingsState.theme.textColor
                            )
                        }
                        IconButton(onClick = { model.toggleBookmark() }) {
                            Icon(
                                Icons.Default.BookmarkBorder,
                                contentDescription = "Yer imi",
                                tint = settingsState.theme.textColor
                            )
                        }
                        Box {
                            IconButton(onClick = { showTranslateMenu = true }) {
                                if (isTranslatingPage) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Default.Translate,
                                        contentDescription = "Çeviri",
                                        tint = settingsState.theme.textColor
                                    )
                                }
                            }
                            DropdownMenu(
                                expanded = showTranslateMenu,
                                onDismissRequest = { showTranslateMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Ekrandakini çevir") },
                                    onClick = {
                                        showTranslateMenu = false
                                        model.translateVisible()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (translationsHidden) "Çevirileri göster" else "Çevirileri gizle") },
                                    onClick = {
                                        showTranslateMenu = false
                                        model.toggleTranslations()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Çevirileri sil", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showTranslateMenu = false
                                        model.clearTranslations()
                                    }
                                )
                            }
                        }
                        val speechOn by model.speech.state.collectAsState()
                        IconButton(onClick = { model.toggleSpeech() }) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = "Sesli oku",
                                tint = if (speechOn != com.kursat.reader.speech.SpeechController.State.idle) MaterialTheme.colorScheme.primary else settingsState.theme.textColor
                            )
                        }
                        IconButton(onClick = { showChat = true }) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = "Kitapla sohbet",
                                tint = settingsState.theme.textColor
                            )
                        }
                        Box {
                            IconButton(onClick = { showAiMenu = true }) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = "Yapay zekâ",
                                    tint = settingsState.theme.textColor
                                )
                            }
                            DropdownMenu(
                                expanded = showAiMenu,
                                onDismissRequest = { showAiMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Bölümü özetle") },
                                    onClick = {
                                        showAiMenu = false
                                        model.summarizeChapter()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bölümü pekiştir (özet + quiz)") },
                                    onClick = {
                                        showAiMenu = false
                                        model.prepareStudy()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Zor kelimeler") },
                                    onClick = {
                                        showAiMenu = false
                                        model.prepareHardWords()
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { showTheme = true }) {
                            Icon(
                                Icons.Default.FormatSize,
                                contentDescription = "Görünüm",
                                tint = settingsState.theme.textColor
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = settingsState.theme.color.copy(alpha = 0.96f)
                    )
                )
            }

            // Alt bilgi çubuğu
            AnimatedVisibility(
                visible = chrome,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val chapterLabel = buildString {
                    append("Bölüm ${chapterIndex + 1} / ${model.pkg.spine.size}")
                    if (settingsState.readingMode == ReadingMode.paged && pageTotal > 0) {
                        append("  ·  Sayfa $pageCur / $pageTotal")
                    }
                }
                val pagesLabel = book.pageText?.let {
                    "$it · %${(book.progress * 100.0).roundToInt()}"
                } ?: chapterLabel
                val slow = if (tracker.hasSpeedData) "" else " (tahmini)"
                val barText = when (barMode) {
                    0 -> if (book.chapterWords != null) {
                        "$pagesLabel · bölüm ~${DurationText.text(tracker.secondsToRead(model.chapterWordsLeft))}"
                    } else chapterLabel
                    1 -> if (book.chapterWords != null) {
                        "Kitap bitimine ~${DurationText.text(tracker.secondsToRead(model.bookWordsLeft))}$slow"
                    } else chapterLabel
                    else -> chapterLabel
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(settingsState.theme.color.copy(alpha = 0.96f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { model.previousChapter() },
                        enabled = chapterIndex > 0
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Önceki bölüm",
                            tint = settingsState.theme.textColor
                        )
                    }
                    Text(
                        text = barText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = settingsState.theme.textColor.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clickable {
                                barMode = (barMode + 1) % 3
                                tracker.touch()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    IconButton(
                        onClick = { model.nextChapter() },
                        enabled = chapterIndex < model.pkg.spine.size - 1
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Sonraki bölüm",
                            tint = settingsState.theme.textColor
                        )
                    }
                }
            }

            // Seslendirme oynatıcısı
            val speechState by model.speech.state.collectAsState()
            AnimatedVisibility(
                visible = speechState != com.kursat.reader.speech.SpeechController.State.idle,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chrome) 68.dp else 20.dp)
            ) {
                com.kursat.reader.ui.shared.SpeechBar(model.speech, settings)
            }

            // Seçim çubuğu
            AnimatedVisibility(
                visible = hasSelection,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chrome) 68.dp else 24.dp)
            ) {
                SelectionBar(
                    onExplain = { model.perform(SelectionAction.explain) },
                    onTranslate = { model.perform(SelectionAction.translateParagraph) },
                    onHighlight = { model.perform(SelectionAction.highlight) },
                    onNote = { model.perform(SelectionAction.note) },
                    onGrammar = { model.perform(SelectionAction.grammar) }
                )
            }

            // Bölüm sonu "Bölümü pekiştir" önerisi
            AnimatedVisibility(
                visible = model.atChapterEnd && !hasSelection,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chrome) 68.dp else 24.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.clickable {
                        model.prepareStudy()
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Bölümü pekiştir", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            ToastHost(
                message = toast,
                onDismiss = { model.showToast(null) },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    AIPanelSheet(ai = model.ai, vocab = vocab)

    if (showChat) {
        BookChatSheet(
            model = model.chat,
            onDismiss = { showChat = false }
        )
    }

    studyModel?.let { sm ->
        ChapterStudySheet(
            model = sm,
            onDismiss = { model.dismissStudy() }
        )
    }

    hardWordsModel?.let { hwm ->
        HardWordsSheet(
            model = hwm,
            vocab = vocab,
            onDismiss = { model.dismissHardWords() }
        )
    }

    if (showTheme) {
        ModalBottomSheet(onDismissRequest = { showTheme = false }) {
            ThemePanel(settings = settings, showsTextControls = true)
        }
    }

    if (showTOC) {
        ModalBottomSheet(onDismissRequest = { showTOC = false }) {
            Column(modifier = Modifier.fillMaxWidth().height(540.dp)) {
                SecondaryTabRow(selectedTabIndex = tocTab) {
                    Tab(selected = tocTab == 0, onClick = { tocTab = 0 }, text = { Text("İçindekiler") })
                    Tab(selected = tocTab == 1, onClick = { tocTab = 1 }, text = { Text("Vurgular") })
                    Tab(selected = tocTab == 2, onClick = { tocTab = 2 }, text = { Text("Yer imleri") })
                }
                when (tocTab) {
                    0 -> {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                            items(model.pkg.toc) { entry ->
                                val active = entry.spineIndex == chapterIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            model.openTOC(entry)
                                            showTOC = false
                                        }
                                        .padding(
                                            start = (16 + entry.level * 18).dp,
                                            end = 16.dp,
                                            top = 12.dp,
                                            bottom = 12.dp
                                        )
                                ) {
                                    Text(
                                        text = entry.title,
                                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                    1 -> {
                        val bookHighlights = model.highlights.forBook(book.id)
                        if (bookHighlights.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Henüz vurgu yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(bookHighlights) { h ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                model.goTo(h)
                                                showTOC = false
                                            }
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(4.dp)
                                                .height(44.dp)
                                                .background(h.color.color, RoundedCornerShape(2.dp))
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(h.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            if (h.note.isNotEmpty()) {
                                                Text(
                                                    h.note,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                h.location,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        IconButton(onClick = { model.deleteHighlight(h) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Sil")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        val bookMarks = model.bookmarks.forBook(book.id)
                        if (bookMarks.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Yer imi yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(bookMarks) { b ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                model.goTo(b)
                                                showTOC = false
                                            }
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(Icons.Default.Bookmark, contentDescription = null, tint = Color(0xFFFF9500))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = b.snippet.ifEmpty { b.location },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = b.location,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        IconButton(onClick = { model.bookmarks.delete(listOf(b.id)) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Sil")
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

    editing?.let { edit ->
        HighlightEditorSheet(
            initial = edit.highlight,
            onSave = {
                model.saveHighlight(it)
                model.setEditing(null)
            },
            onDelete = {
                model.deleteHighlight(it)
                model.setEditing(null)
            },
            onDismiss = { model.setEditing(null) }
        )
    }

    alertMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { model.dismissAlert() },
            title = { Text("Bilgi") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { model.dismissAlert() }) { Text("Tamam") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightEditorSheet(
    initial: Highlight,
    onSave: (Highlight) -> Unit,
    onDelete: (Highlight) -> Unit,
    onDismiss: () -> Unit
) {
    var color by remember(initial.id) { mutableStateOf(initial.color) }
    var note by remember(initial.id) { mutableStateOf(initial.note) }

    ModalBottomSheet(
        onDismissRequest = {
            onSave(initial.copy(color = color, note = note))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = initial.text,
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodyMedium
            )
            if (initial.location.isNotEmpty()) {
                Text(
                    text = initial.location,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HighlightColor.entries.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c.color)
                            .border(
                                width = if (color == c) 3.dp else 0.dp,
                                color = if (color == c) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { color = c }
                    )
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Not") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = { onDelete(initial) }) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Vurguyu sil", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { onSave(initial.copy(color = color, note = note)) }) {
                    Text("Bitti", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

