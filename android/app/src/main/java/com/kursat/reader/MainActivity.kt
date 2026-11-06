package com.kursat.reader

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kursat.reader.ai.KeyStore
import com.kursat.reader.model.BookKind
import com.kursat.reader.model.BookmarkStore
import com.kursat.reader.model.FontInstaller
import com.kursat.reader.model.HighlightStore
import com.kursat.reader.model.LibraryStore
import com.kursat.reader.model.NotificationScheduler
import com.kursat.reader.model.Paths
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.ReadingTracker
import com.kursat.reader.model.VocabStore
import com.kursat.reader.sync.GoogleAuth
import com.kursat.reader.sync.SyncManager
import com.kursat.reader.ui.library.LibraryView
import com.kursat.reader.ui.notes.HighlightsView
import com.kursat.reader.ui.notes.VocabView
import com.kursat.reader.ui.reader.EPUBLoader
import com.kursat.reader.ui.reader.PDFLoader
import com.kursat.reader.ui.stats.StatsView
import com.kursat.reader.widget.WidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class ReaderApplication : Application() {
    lateinit var library: LibraryStore
    lateinit var settings: ReaderSettings
    lateinit var highlights: HighlightStore
    lateinit var vocab: VocabStore
    lateinit var bookmarks: BookmarkStore
    lateinit var tracker: ReadingTracker
    lateinit var sync: SyncManager

    override fun onCreate() {
        super.onCreate()
        Paths.init(this)
        KeyStore.init(this)
        GoogleAuth.init(this)
        FontInstaller.install(this)
        library = LibraryStore(this)
        settings = ReaderSettings(this)
        highlights = HighlightStore()
        vocab = VocabStore()
        bookmarks = BookmarkStore()
        tracker = ReadingTracker(this)
        sync = SyncManager(
            context = this,
            library = library,
            highlights = highlights,
            vocab = vocab,
            bookmarks = bookmarks,
            tracker = tracker,
            settings = settings
        )
        NotificationScheduler.refresh(this, settings, tracker, vocab)
        WidgetBridge.push(this, library, tracker, settings)
    }

    fun refreshNotificationsAndWidget() {
        NotificationScheduler.refresh(this, settings, tracker, vocab)
        WidgetBridge.push(this, library, tracker, settings)
    }
}

class MainActivity : ComponentActivity() {
    private var pendingOpenBookId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        val app = application as ReaderApplication
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReaderAppRoot(
                        app = app,
                        pendingOpenBookId = pendingOpenBookId,
                        onConsumedPendingOpenBook = { pendingOpenBookId = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val app = application as ReaderApplication

        // Test / debug için: --es importPath /sdcard/Download/sample.epub
        intent.getStringExtra("importPath")?.let { path ->
            val file = File(path)
            if (file.exists()) {
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val b = app.library.importBookFromPath(file)
                        pendingOpenBookId = b.id
                    } catch (_: Exception) {
                        // Zaten kitaplıkta varsa ilk kitabı aç
                        app.library.books.value.firstOrNull()?.let {
                            pendingOpenBookId = it.id
                        }
                    }
                }
            }
        }

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "reader" && data.host == "book") {
                    val id = data.lastPathSegment
                    if (!id.isNullOrBlank()) {
                        pendingOpenBookId = id
                    }
                } else if (data.scheme == "content" || data.scheme == "file") {
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            val b = app.library.importBook(data)
                            pendingOpenBookId = b.id
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (stream != null) {
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            val b = app.library.importBook(stream)
                            pendingOpenBookId = b.id
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderAppRoot(
    app: ReaderApplication,
    pendingOpenBookId: String?,
    onConsumedPendingOpenBook: () -> Unit
) {
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    app.tracker.appActive = true
                    app.sync.syncSoon()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    app.tracker.appActive = false
                    app.library.save()
                    app.highlights.save()
                    app.vocab.save()
                    app.bookmarks.save()
                    app.tracker.save()
                    app.refreshNotificationsAndWidget()
                    app.sync.syncSoon()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pendingOpenBookId) {
        val id = pendingOpenBookId ?: return@LaunchedEffect
        if (app.library.book(id) != null) {
            app.library.touch(id)
            navController.navigate("reader/$id") {
                launchSingleTop = true
            }
            onConsumedPendingOpenBook()
        }
    }

    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryView(
                library = app.library,
                settings = app.settings,
                highlights = app.highlights,
                vocab = app.vocab,
                bookmarks = app.bookmarks,
                tracker = app.tracker,
                sync = app.sync,
                onRefreshNotifications = { app.refreshNotificationsAndWidget() },
                onOpenBook = { id ->
                    app.library.touch(id)
                    navController.navigate("reader/$id")
                },
                onOpenRoute = { route ->
                    navController.navigate(route)
                }
            )
        }
        composable("reader/{bookId}") { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
            val allBooks by app.library.books.collectAsState()
            val book = allBooks.firstOrNull { it.id.equals(bookId, ignoreCase = true) }
            if (book == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Kitap bulunamadı")
                        TextButton(onClick = { navController.popBackStack() }) { Text("Geri dön") }
                    }
                }
            } else if (book.isRemoteOnly) {
                com.kursat.reader.ui.library.CloudDownloadScreen(
                    book = book,
                    sync = app.sync,
                    onBack = { navController.popBackStack() }
                )
            } else {
                when (book.kind) {
                    BookKind.epub -> {
                        EPUBLoader(
                            book = book,
                            library = app.library,
                            settings = app.settings,
                            highlights = app.highlights,
                            vocab = app.vocab,
                            bookmarks = app.bookmarks,
                            tracker = app.tracker,
                            onBack = {
                                app.refreshNotificationsAndWidget()
                                navController.popBackStack()
                            }
                        )
                    }
                    BookKind.pdf -> {
                        PDFLoader(
                            book = book,
                            library = app.library,
                            settings = app.settings,
                            highlights = app.highlights,
                            vocab = app.vocab,
                            bookmarks = app.bookmarks,
                            tracker = app.tracker,
                            onBack = {
                                app.refreshNotificationsAndWidget()
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
        composable("vocab") {
            VocabView(
                vocab = app.vocab,
                onBack = { navController.popBackStack() }
            )
        }
        composable("highlights") {
            HighlightsView(
                store = app.highlights,
                onJumpToBook = { bookID ->
                    app.library.touch(bookID)
                    navController.navigate("reader/$bookID")
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("stats") {
            StatsView(
                tracker = app.tracker,
                library = app.library,
                settings = app.settings,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
