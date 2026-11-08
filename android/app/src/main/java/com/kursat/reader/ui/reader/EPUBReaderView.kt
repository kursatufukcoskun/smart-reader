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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.launch
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
    val penMode by model.penMode.collectAsState()
    val penResult by model.penResult.collectAsState()
    val footnote by model.footnote.collectAsState()
    val returnPoint by model.returnPoint.collectAsState()
    val remoteOffer by model.remoteOffer.collectAsState()
    val bookmarkItems by model.bookmarks.items.collectAsState()
    val isBookmarkedHere = remember(bookmarkItems, chapterIndex, book.scrollFraction) {
        bookmarkItems.any {
            it.bookID.equals(book.id, ignoreCase = true) && it.isNote != true && it.chapter == chapterIndex &&
                kotlin.math.abs((it.fraction ?: 0.0) - book.scrollFraction) < 0.012
        }
    }
    val resumeDays by model.resumeDays.collectAsState()
    val glossesHidden by model.glossesHidden.collectAsState()
    val isGlossing by model.isGlossing.collectAsState()
    val openChat by model.openChat.collectAsState()

    var chrome by remember { mutableStateOf(false) }
    com.kursat.reader.ui.shared.ImmersiveBars(hidden = !chrome)
    var showTOC by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showWrapUp by remember { mutableStateOf(false) }
    DisposableEffect(model) {
        val inner = model.search.open
        model.search.open = { hit -> showSearch = false; inner(hit) }
        onDispose { model.search.open = inner }
    }
    var showTheme by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    LaunchedEffect(openChat) {
        if (openChat) { showChat = true; model.chatOpened() }
    }
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

    com.kursat.reader.ui.shared.KeepScreenAwake(settings, model.speech)
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
                                if (isBookmarkedHere) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Yer imi",
                                tint = settingsState.theme.textColor
                            )
                        }
                        val speechOn by model.speech.state.collectAsState()
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
                                    text = { Text("Şimdiye kadar ne oldu?") },
                                    onClick = { showAiMenu = false; model.askBook(com.kursat.reader.ai.Prompts.recap) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Karakter ve kavram rehberi") },
                                    onClick = { showAiMenu = false; model.askBook(com.kursat.reader.ai.Prompts.cast) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Karakter ve ilişki haritası") },
                                    onClick = { showAiMenu = false; model.showRelationMap() }
                                )
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
                        val speechStateTop by model.speech.state.collectAsState()
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                if (isTranslatingPage || isGlossing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Diğer", tint = settingsState.theme.textColor)
                                }
                            }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                DropdownMenuItem(text = { Text("Kitapta ara") }, onClick = { showOverflow = false; showSearch = true })
                                DropdownMenuItem(
                                    text = { Text("Kalem modu" + if (penMode) "  ✓" else "") },
                                    onClick = { showOverflow = false; model.togglePenMode() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sesli oku" + if (speechStateTop != com.kursat.reader.speech.SpeechController.State.idle) "  ✓" else "") },
                                    onClick = { showOverflow = false; model.toggleSpeech() }
                                )
                                DropdownMenuItem(text = { Text("Görünüm ve yazı") }, onClick = { showOverflow = false; showTheme = true })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Ekrandakini çevir") }, onClick = { showOverflow = false; model.translateVisible() })
                                DropdownMenuItem(
                                    text = { Text(if (translationsHidden) "Çevirileri göster" else "Çevirileri gizle") },
                                    onClick = { showOverflow = false; model.toggleTranslations() }
                                )
                                DropdownMenuItem(text = { Text("Zor kelimelere ipucu ekle") }, onClick = { showOverflow = false; model.glossVisible() })
                                DropdownMenuItem(
                                    text = { Text(if (glossesHidden) "İpuçlarını göster" else "İpuçlarını gizle") },
                                    onClick = { showOverflow = false; model.toggleGlosses() }
                                )
                                DropdownMenuItem(
                                    text = { Text("İpuçlarını sil", color = MaterialTheme.colorScheme.error) },
                                    onClick = { showOverflow = false; model.clearGlosses() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Çevirileri sil", color = MaterialTheme.colorScheme.error) },
                                    onClick = { showOverflow = false; model.clearTranslations() }
                                )
                            }
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
                    onXray = { model.perform(SelectionAction.xray) },
                    onDictionary = { model.perform(SelectionAction.dictionary) },
                    onExplain = { model.perform(SelectionAction.explain) },
                    onTranslate = { model.perform(SelectionAction.translateParagraph) },
                    onHighlight = { model.perform(SelectionAction.highlight) },
                    onNote = { model.perform(SelectionAction.note) },
                    onGrammar = { model.perform(SelectionAction.grammar) }
                )
            }


            // Kalem paleti (kalem modunda üstte)
            AnimatedVisibility(
                visible = penMode,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (chrome) 64.dp else 12.dp)
            ) {
                com.kursat.reader.ui.shared.PenPalette(
                    style = settingsState.penStyle,
                    color = settingsState.highlightColor,
                    onStyle = { st -> settings.update { it.copy(penStyle = st) } },
                    onColor = { c -> settings.update { it.copy(highlightColor = c) } },
                    onClose = { model.setPenMode(false) }
                )
            }

            // Kalemle çizince çıkan kısa menü
            AnimatedVisibility(
                visible = penResult != null && !hasSelection,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (chrome) 68.dp else 24.dp)
            ) {
                com.kursat.reader.ui.shared.PenResultBar(
                    showDictionary = penResult?.let { com.kursat.reader.ui.shared.QuickDictionary.term(it.highlight.text) } != null,
                    onDictionary = { model.penAction(SelectionAction.dictionary) },
                    onTranslate = { model.penAction(SelectionAction.translateParagraph) },
                    onExplain = { model.penAction(SelectionAction.explain) },
                    onNote = { model.penAction(SelectionAction.note) },
                    onUndo = { model.undoPen() }
                )
            }

            // "Buraya atladın · Kalıcı olsun mu?" çubuğu (sürüklenebilir)
            if (returnPoint != null) {
                com.kursat.reader.ui.shared.DraggableJumpBar(
                    from = model.returnLabel,
                    onKeep = { model.keepJump() },
                    onBack = { model.returnFromJump() },
                    bottomPadding = if (chrome) 130.dp else 84.dp
                )
            }


            // Başka cihazda daha yeni konum varsa sor
            if (remoteOffer != null) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = (if (chrome) 64.dp else 12.dp) + (if (penMode) 56.dp else 0.dp))
                ) {
                    com.kursat.reader.ui.shared.RemoteProgressBar(
                        label = model.remoteLabel,
                        onAccept = { model.acceptRemote() },
                        onKeep = { model.keepLocalPosition() }
                    )
                }
            }

            // Uzun aradan sonra "son kaldığın yeri hatırlat" önerisi
            resumeDays?.let { d ->
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = (if (chrome) 64.dp else 12.dp) + (if (penMode) 56.dp else 0.dp) + (if (remoteOffer != null) 66.dp else 0.dp))
                ) {
                    com.kursat.reader.ui.shared.ResumeBar(days = d, onAsk = { model.askResume() }, onDismiss = { model.dismissResume() })
                }
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
                        if (model.isBookEnd) showWrapUp = true else model.prepareStudy()
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (model.isBookEnd) "Kitabı bitirdin: karneni gör" else "Bölümü pekiştir", fontWeight = FontWeight.SemiBold)
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

    com.kursat.reader.ui.shared.RelationMapDialog(model.relationMap, onRegenerate = { model.showRelationMap() })
    if (showWrapUp) {
        com.kursat.reader.ui.shared.BookWrapUpSheet(book = book, onDismiss = { showWrapUp = false })
    }
    if (showSearch) {
        com.kursat.reader.ui.shared.BookSearchSheet(
            model = model.search,
            onDismiss = { showSearch = false }
        )
    }
    footnote?.let { info ->
        com.kursat.reader.ui.shared.FootnoteSheet(
            info = info,
            onTranslate = { model.translateFootnote(info) },
            onShow = { model.showFootnoteInText(info) },
            onDismiss = { model.dismissFootnote() }
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
                        val generating by model.isGeneratingInsights.collectAsState()
                        Column(modifier = Modifier.fillMaxSize()) {
                            com.kursat.reader.ui.shared.InsightsHeader(generating) { model.generateInsights() }
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                                        if (h.isInsight) {
                                            Text("✨", modifier = Modifier.width(20.dp))
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .width(4.dp)
                                                    .height(44.dp)
                                                    .background(h.color.color, RoundedCornerShape(2.dp))
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            if (h.isInsight) {
                                                Text("Yapay zekâ içgörüsü", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                            }
                                            Text(h.text, maxLines = if (h.isInsight) 8 else 3, overflow = TextOverflow.Ellipsis)
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
                                                maxLines = if (b.isNote == true) 6 else 2,
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
    val appCtx = LocalContext.current.applicationContext
    val cardScope = androidx.compose.runtime.rememberCoroutineScope()
    var makingCard by remember(initial.id) { mutableStateOf(false) }
    var cardInfo by remember(initial.id) { mutableStateOf<String?>(null) }

    /** Vurgudan (ve notundan) yapay zekâ ile tek soru/cevap kartı üretip kelime defterine ekler. */
    fun makeCard() {
        val app = appCtx as? com.kursat.reader.ReaderApplication ?: return
        makingCard = true
        cardInfo = null
        cardScope.launch {
            try {
                val cfg = app.settings.current.config(com.kursat.reader.model.LLMTier.fast)
                val out = com.kursat.reader.ai.LLMClient.complete(
                    cfg,
                    system = com.kursat.reader.ai.Prompts.assistantSystem,
                    user = com.kursat.reader.ai.Prompts.flashcard(initial.text, note, initial.bookTitle),
                    maxTokens = 512
                )
                val card = com.kursat.reader.ai.Prompts.parseFlashcard(out)
                if (card == null) {
                    cardInfo = "Kart üretilemedi, tekrar dene."
                } else {
                    app.vocab.addCard(card.first, card.second, initial.text, initial.bookID, initial.bookTitle)
                    cardInfo = "Karta eklendi: ${card.first}"
                }
            } catch (e: Exception) {
                cardInfo = e.message ?: "Kart üretilemedi."
            } finally {
                makingCard = false
            }
        }
    }

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
            if (!initial.isInsight) {
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
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Not") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = {
                    val app = appCtx as? com.kursat.reader.ReaderApplication
                    val author = app?.library?.book(initial.bookID)?.author.orEmpty()
                    val theme = app?.settings?.current?.theme ?: com.kursat.reader.model.ReaderTheme.light
                    com.kursat.reader.ui.shared.QuoteCard.share(appCtx, initial.text, initial.bookTitle, author, theme)
                }) { Text("Alıntı kartı olarak paylaş") }
                androidx.compose.material3.OutlinedButton(onClick = { makeCard() }, enabled = !makingCard) {
                    Text(if (makingCard) "Kart hazırlanıyor…" else "Bu vurgudan kart yap")
                }
                cardInfo?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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

