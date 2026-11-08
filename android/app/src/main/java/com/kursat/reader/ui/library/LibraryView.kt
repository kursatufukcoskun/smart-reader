package com.kursat.reader.ui.library

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.result.IntentSenderRequest
import com.kursat.reader.model.Book
import com.kursat.reader.model.BookmarkStore
import com.kursat.reader.model.DurationText
import com.kursat.reader.model.HighlightStore
import com.kursat.reader.model.LibraryStore
import com.kursat.reader.model.Paths
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.ReadingTracker
import com.kursat.reader.model.VocabStore
import com.kursat.reader.sync.GoogleAuth
import com.kursat.reader.sync.SyncManager
import com.kursat.reader.sync.SyncState
import com.kursat.reader.ui.shared.SettingsSheet
import com.kursat.reader.ui.shared.ThemePanel
import kotlinx.coroutines.launch

sealed class LibraryFilter {
    data object All : LibraryFilter()
    data object Reading : LibraryFilter()
    data object Unread : LibraryFilter()
    data object Finished : LibraryFilter()
    data class Tag(val tag: String) : LibraryFilter()

    val title: String
        get() = when (this) {
            All -> "Tümü"
            Reading -> "Okunuyor"
            Unread -> "Okunmadı"
            Finished -> "Bitti"
            is Tag -> tag
        }

    fun matches(b: Book): Boolean = when (this) {
        All -> true
        Reading -> !b.isUnread && !b.isFinished
        Unread -> b.isUnread
        Finished -> b.isFinished
        is Tag -> b.tags?.contains(tag) == true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryView(
    library: LibraryStore,
    settings: ReaderSettings,
    highlights: HighlightStore,
    vocab: VocabStore,
    bookmarks: BookmarkStore,
    tracker: ReadingTracker,
    sync: SyncManager,
    onRefreshNotifications: () -> Unit = {},
    onOpenBook: (String) -> Unit,
    onOpenRoute: (String) -> Unit
) {
    val books by library.books.collectAsState()
    val syncState by sync.state.collectAsState()
    val lastSync by sync.lastSync.collectAsState()
    val isSignedIn by GoogleAuth.isSignedIn.collectAsState()
    val userEmail by GoogleAuth.userEmail.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf<LibraryFilter>(LibraryFilter.All) }
    var search by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    var tagBook by remember { mutableStateOf<Book?>(null) }
    var editBook by remember { mutableStateOf<Book?>(null) }
    var wrapBook by remember { mutableStateOf<Book?>(null) }
    var showArticleDialog by remember { mutableStateOf(false) }
    var articleUrl by remember { mutableStateOf("") }
    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val sort by library.sort.collectAsState()
    var findingCoverId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (GoogleAuth.handleAuthorizationResult(context, res.data)) {
            sync.syncSoon()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        isImporting = true
        scope.launch {
            for (uri in uris) {
                try {
                    library.importBook(uri)
                } catch (e: Exception) {
                    importError = e.message ?: "İçe aktarma hatası"
                }
            }
            isImporting = false
            sync.syncSoon()
        }
    }

    val sortedBooks = remember(books, sort) { library.sortedBooks }
    val allTags = remember(books) { library.allTags }

    val visibleBooks = remember(sortedBooks, filter, search) {
        sortedBooks.filter { b ->
            filter.matches(b) && (
                search.isBlank() ||
                    b.title.contains(search, ignoreCase = true) ||
                    b.author.contains(search, ignoreCase = true) ||
                    (b.tags ?: emptyList()).any { it.contains(search, ignoreCase = true) }
                )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kitaplık", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = {
                            if (isSignedIn) {
                                sync.syncSoon()
                            } else {
                                showSettings = true
                            }
                        }
                    ) {
                        when (syncState) {
                            SyncState.Syncing -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            SyncState.Idle -> Icon(Icons.Default.CloudDone, contentDescription = "Senkron", tint = Color(0xFF34C759))
                            is SyncState.Error -> Icon(Icons.Default.CloudOff, contentDescription = "Senkron hatası", tint = MaterialTheme.colorScheme.error)
                            SyncState.SignedOut -> Icon(Icons.Default.CloudOff, contentDescription = "Senkron kapalı")
                        }
                    }
                    IconButton(onClick = { onOpenRoute("stats") }) {
                        Icon(Icons.Default.BarChart, contentDescription = "İstatistikler")
                    }
                    IconButton(onClick = { onOpenRoute("highlights") }) {
                        Icon(Icons.Default.DriveFileRenameOutline, contentDescription = "Vurgular ve notlar")
                    }
                    IconButton(onClick = { onOpenRoute("vocab") }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Kelime defteri")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Sırala")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            com.kursat.reader.model.LibraryStore.Sort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.title + if (s == sort) "  ✓" else "") },
                                    onClick = { library.setSort(s); sortMenu = false }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ayarlar")
                    }
                    Box {
                        IconButton(onClick = { addMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Kitap ekle")
                        }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Dosyadan ekle (EPUB, PDF)") },
                                onClick = {
                                    addMenu = false
                                    launcher.launch(arrayOf("application/epub+zip", "application/pdf", "application/octet-stream"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Web adresinden ekle…") },
                                onClick = {
                                    addMenu = false
                                    // Panoda bir web adresi varsa hazır gelsin
                                    val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                                        ?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                    articleUrl = com.kursat.reader.model.ArticleImporter.firstUrl(clip).orEmpty()
                                    showArticleDialog = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Arama çubuğu
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Kitap, yazar veya etiket ara") },
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
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 170.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        GoalBanner(tracker = tracker, settings = settings)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        DailyHighlightCard(onOpen = { h ->
                            (context.applicationContext as com.kursat.reader.ReaderApplication).highlights.pendingJump = h
                            onOpenBook(h.bookID)
                        })
                    }

                    if (books.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            val chips = listOf(
                                LibraryFilter.All,
                                LibraryFilter.Reading,
                                LibraryFilter.Unread,
                                LibraryFilter.Finished
                            ) + allTags.map { LibraryFilter.Tag(it) }

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(chips) { f ->
                                    FilterChip(
                                        selected = filter == f,
                                        onClick = { filter = f },
                                        label = { Text(f.title) },
                                        leadingIcon = if (f is LibraryFilter.Tag) {
                                            { Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }
                    }

                    items(visibleBooks, key = { it.id }) { book ->
                        BookCardItem(
                            book = book,
                            isLoadingCover = findingCoverId == book.id,
                            onClick = { onOpenBook(book.id) },
                            onEditTags = { tagBook = book },
                            onEditInfo = { editBook = book },
                            onWrapUp = { wrapBook = book },
                            onFetchCover = {
                                scope.launch {
                                    findingCoverId = book.id
                                    try {
                                        library.fetchCover(book.id)
                                    } catch (e: Exception) {
                                        infoMessage = e.message ?: "Kapak bulunamadı."
                                    } finally {
                                        findingCoverId = null
                                    }
                                }
                            },
                            onDelete = { bookToDelete = book }
                        )
                    }
                }
            }

            if (books.isEmpty() && !isImporting) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Kitaplık boş", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Sağ üstteki + ile EPUB veya PDF ekleyin.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = {
                        launcher.launch(arrayOf("application/epub+zip", "application/pdf", "application/octet-stream"))
                    }) {
                        Text("Kitap ekle")
                    }
                }
            } else if (visibleBooks.isEmpty() && books.isNotEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Eşleşen kitap yok", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (isImporting) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("İçe aktarılıyor…")
                    }
                }
            }
        }
    }

    // Silme onayı (vurgu/defter kalsın mı seçeneğiyle)
    bookToDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text("“${b.title}” silinsin mi?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (isSignedIn)
                            "İlk iki seçenek kitabı yalnızca bu cihazdan kaldırır; Drive'da kalır ve “bulutta” olarak görünür. Okuma istatistikleri her durumda korunur."
                        else
                            "Okuma istatistikleri her durumda korunur. Kalan vurgu ve kelimeler, kitabı sonradan tekrar eklerseniz yine görünür."
                    )
                    Button(
                        onClick = {
                            library.delete(b)
                            val f = filter
                            if (f is LibraryFilter.Tag && !library.allTags.contains(f.tag)) {
                                filter = LibraryFilter.All
                            }
                            bookToDelete = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kitabı sil, vurgu ve kelimeler kalsın")
                    }
                    TextButton(
                        onClick = {
                            highlights.deleteBook(b.id)
                            vocab.deleteBook(b.id)
                            bookmarks.deleteBook(b.id)
                            library.delete(b)
                            val f = filter
                            if (f is LibraryFilter.Tag && !library.allTags.contains(f.tag)) {
                                filter = LibraryFilter.All
                            }
                            bookToDelete = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kitabı, vurguları ve kelimeleriyle sil", color = MaterialTheme.colorScheme.error)
                    }
                    if (isSignedIn) {
                        TextButton(
                            onClick = {
                                highlights.deleteBook(b.id)
                                vocab.deleteBook(b.id)
                                bookmarks.deleteBook(b.id)
                                library.deleteEverywhere(b)
                                sync.syncSoon()
                                val f = filter
                                if (f is LibraryFilter.Tag && !library.allTags.contains(f.tag)) {
                                    filter = LibraryFilter.All
                                }
                                bookToDelete = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Her yerden sil (Drive ve diğer cihazlar dahil)", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) { Text("Vazgeç") }
            }
        )
    }

    wrapBook?.let { b ->
        com.kursat.reader.ui.shared.BookWrapUpSheet(book = b, onDismiss = { wrapBook = null })
    }

    // Web makalesi ekle
    if (showArticleDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showArticleDialog = false },
            title = { Text("Web adresinden ekle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "PDF ya da EPUB bağlantısıysa dosya olduğu gibi eklenir; web sayfasıysa makale metni ayıklanıp tek bölümlük bir kitap olarak eklenir. Tarayıcıdan Paylaş ▸ bu uygulama ile de ekleyebilirsin.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = articleUrl, onValueChange = { articleUrl = it },
                        label = { Text("https://…") }, singleLine = true
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = articleUrl.isNotBlank(),
                    onClick = {
                        val address = articleUrl
                        showArticleDialog = false
                        isImporting = true
                        scope.launch {
                            try {
                                library.importArticle(address)
                            } catch (e: Exception) {
                                importError = e.message ?: "Makale eklenemedi"
                            }
                            isImporting = false
                            sync.syncSoon()
                        }
                    }
                ) { Text("Ekle") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showArticleDialog = false }) { Text("Vazgeç") }
            }
        )
    }

    // Başlık / yazar düzenleyici
    editBook?.let { b ->
        var title by remember(b.id) { mutableStateOf(b.title) }
        var author by remember(b.id) { mutableStateOf(b.author) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editBook = null },
            title = { Text("Bilgileri düzenle") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = title, onValueChange = { title = it }, label = { Text("Başlık") }, singleLine = true
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = author, onValueChange = { author = it }, label = { Text("Yazar") }, singleLine = true
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = title.isNotBlank(),
                    onClick = { library.updateInfo(b.id, title, author); editBook = null }
                ) { Text("Kaydet") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editBook = null }) { Text("Vazgeç") }
            }
        )
    }

    // Etiket düzenleyici
    tagBook?.let { b ->
        TagEditorSheet(
            bookID = b.id,
            library = library,
            onDismiss = { tagBook = null }
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            onRefreshNotifications = onRefreshNotifications,
            syncSection = {
                if (isSignedIn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Durum")
                        when (val st = syncState) {
                            SyncState.Syncing -> Text("Senkronlanıyor…", color = MaterialTheme.colorScheme.primary)
                            is SyncState.Error -> Text(st.message, color = MaterialTheme.colorScheme.error)
                            else -> Text(
                                if (!userEmail.isNullOrBlank()) "Bağlı ($userEmail)" else "Bağlı",
                                color = Color(0xFF34C759),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    val transferNote by sync.transferNote.collectAsState()
                    transferNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!lastSync.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Son senkron", style = MaterialTheme.typography.bodySmall)
                            Text(lastSync!!.take(19).replace("T", " "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { sync.syncSoon() },
                            enabled = syncState != SyncState.Syncing
                        ) {
                            Text("Şimdi senkronla")
                        }
                        TextButton(onClick = { sync.signOut() }) {
                            Text("Bağlantıyı kes", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val resIntent = GoogleAuth.authorizeWithPlayServices(context)
                                    if (resIntent != null) {
                                        val pi = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                            resIntent.getParcelableExtra("pendingIntent", android.app.PendingIntent::class.java)
                                        } else {
                                            @Suppress("DEPRECATION")
                                            resIntent.getParcelableExtra("pendingIntent")
                                        }
                                        if (pi != null) {
                                            authLauncher.launch(IntentSenderRequest.Builder(pi).build())
                                        }
                                    } else {
                                        sync.syncSoon()
                                    }
                                } catch (e: Exception) {
                                    infoMessage = "Google girişi başarısız: ${e.message}\n\nNot: Google Cloud Console'da com.kursat.reader paketi ve debug SHA-1 (5B:FD:67:58:0D:CC:BE:B4:47:F9:BB:AE:F8:1D:CB:D9:F2:59:EF:16) için Android OAuth istemcisi tanımlanmalıdır."
                                }
                            }
                        }
                    ) {
                        Text("Google ile bağlan")
                    }
                    if (syncState is SyncState.Error) {
                        Text(
                            (syncState as SyncState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            onDismiss = { showSettings = false }
        )
    }

    importError?.let { err ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("İçe aktarma hatası") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("Tamam") }
            }
        )
    }

    infoMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { infoMessage = null },
            title = { Text("Bilgi") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { infoMessage = null }) { Text("Tamam") }
            }
        )
    }
}

@Composable
fun GoalBanner(
    tracker: ReadingTracker,
    settings: ReaderSettings
) {
    val entries by tracker.entries.collectAsState()
    val settingsState by settings.state.collectAsState()

    val goalSeconds = settingsState.dailyGoalMinutes * 60.0
    val today = remember(entries) { tracker.todaySeconds }
    val streak = remember(entries) { tracker.streak() }

    if (settingsState.dailyGoalMinutes > 0 || streak > 0) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (settingsState.dailyGoalMinutes > 0) {
                    val progress = (today / goalSeconds.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                    val done = today >= goalSeconds
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                        CircularProgressIndicator(
                            progress = { progress },
                            strokeWidth = 5.dp,
                            color = if (done) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            modifier = Modifier.fillMaxSize()
                        )
                        Icon(
                            imageVector = if (done) Icons.Default.Check else Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = if (done) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bugün ${DurationText.text(today)} / ${settingsState.dailyGoalMinutes} dk",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (done) "Hedefi tamamladın 🎉" else "Hedefe ${DurationText.text(goalSeconds - today)} kaldı",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (streak > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = Color(0xFFFF9500),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$streak gün",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF9500)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCardItem(
    book: Book,
    isLoadingCover: Boolean,
    onClick: () -> Unit,
    onEditTags: () -> Unit,
    onEditInfo: () -> Unit,
    onWrapUp: () -> Unit,
    onFetchCover: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    // Kapak: önbellekten anında, yoksa arka planda küçültülerek çözülür (kaydırmayı yavaşlatmaz)
    val coverFile = remember(book.coverFile) { Paths.cover(book) }
    var coverBitmap by remember(book.coverFile) { mutableStateOf(CoverCache.peek(coverFile)) }
    LaunchedEffect(book.coverFile) {
        if (coverBitmap == null && coverFile != null) coverBitmap = CoverCache.load(coverFile)
    }

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (isLoadingCover) 0.5f else 1f)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .shadow(6.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (book.isRemoteOnly) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).zIndex(1f)
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "Bulutta",
                            tint = Color.White,
                            modifier = Modifier.padding(6.dp).size(18.dp)
                        )
                    }
                }
                val cover = coverBitmap
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF3F51B5), Color(0xFF7B1FA2))
                                )
                            )
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = book.title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (book.author.isNotBlank()) {
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (book.isUnread) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = "Okunmadı",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            } else {
                LinearProgressIndicator(
                    progress = { book.progress.toFloat().coerceIn(0f, 1f) },
                    color = if (book.isFinished) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )
                book.statusText?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Kitap karnesi") },
                leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onWrapUp()
                }
            )
            DropdownMenuItem(
                text = { Text("Bilgileri düzenle") },
                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onEditInfo()
                }
            )
            DropdownMenuItem(
                text = { Text("Etiketler") },
                leadingIcon = { Icon(Icons.Default.Label, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onEditTags()
                }
            )
            DropdownMenuItem(
                text = { Text("Kapak ara") },
                leadingIcon = { Icon(Icons.Default.ImageSearch, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onFetchCover()
                }
            )
            DropdownMenuItem(
                text = { Text("Sil", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditorSheet(
    bookID: String,
    library: LibraryStore,
    onDismiss: () -> Unit
) {
    val books by library.books.collectAsState()
    val currentBook = books.firstOrNull { it.id.equals(bookID, ignoreCase = true) }
    val currentTags = currentBook?.tags.orEmpty()
    val allTags = remember(books) { library.allTags }
    var newTag by remember { mutableStateOf("") }

    fun addNewTag() {
        val t = newTag.trim()
        if (t.isEmpty()) return
        val next = if (currentTags.contains(t)) currentTags else currentTags + t
        library.setTags(bookID, next)
        newTag = ""
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Etiketler", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss) { Text("Bitti") }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    placeholder = { Text("ör. okunacak, teknik, roman") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { addNewTag() },
                    enabled = newTag.trim().isNotEmpty()
                ) {
                    Text("Ekle")
                }
            }

            if (allTags.isEmpty()) {
                Text("Henüz etiket yok", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                allTags.forEach { t ->
                    val checked = currentTags.contains(t)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = if (checked) currentTags - t else currentTags + t
                                library.setTags(bookID, next)
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t, style = MaterialTheme.typography.bodyLarge)
                        if (checked) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

