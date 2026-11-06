package com.kursat.reader.sync

import android.content.Context
import android.content.SharedPreferences
import com.kursat.reader.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import com.kursat.reader.epub.ZipWriter
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class ItemsFile<T>(
    val version: Int = 1,
    val items: List<T>,
    val deleted: Map<String, String>
)

@Serializable
data class ProgressRecord(
    val position: Int,
    val scrollFraction: Double,
    val progress: Double,
    val updatedAt: String
)

@Serializable
data class ProgressFile(
    val version: Int = 1,
    val books: Map<String, ProgressRecord>
)

@Serializable
data class LogFile(
    val version: Int = 1,
    val entries: List<ReadingEntry>
)

@Serializable
data class SettingsFile(
    val version: Int = 1,
    val updatedAt: String,
    val theme: String,
    val font: String,
    val fontScale: Double,
    val lineHeight: Double,
    val margin: Double,
    val readingMode: String,
    val columns: String,
    val warmth: Double,
    val edgeTap: Boolean,
    val highlightColor: String
)

object SyncMerge {
    /**
     * SYNC.md & iOS SyncMerge.items birebir karşılığı:
     * Her kayıtta damgası yeni olan kazanır; silme kaydı kayıttan yeniyse kaydı düşürür.
     */
    fun <T> items(
        local: List<T>,
        remote: List<T>,
        localDeleted: Map<String, String>,
        remoteDeleted: Map<String, String>,
        idOf: (T) -> String,
        stampOf: (T) -> Instant
    ): Pair<List<T>, Map<String, String>> {
        val deleted = mutableMapOf<String, Instant>()
        for ((k, v) in localDeleted) {
            deleted[k.uppercase()] = IsoDate.parse(v)
        }
        for ((k, v) in remoteDeleted) {
            val uk = k.uppercase()
            val rv = IsoDate.parse(v)
            val cur = deleted[uk]
            if (cur == null || rv.isAfter(cur)) {
                deleted[uk] = rv
            }
        }

        val map = mutableMapOf<String, T>()
        for (l in local) {
            map[idOf(l).uppercase()] = l
        }
        for (r in remote) {
            val rk = idOf(r).uppercase()
            val l = map[rk]
            if (l != null && !stampOf(r).isAfter(stampOf(l))) continue
            map[rk] = r
        }

        val kept = map.values.filter { item ->
            val d = deleted[idOf(item).uppercase()] ?: return@filter true
            stampOf(item).isAfter(d)
        }.sortedBy { idOf(it).uppercase() }

        val deletedStrings = deleted
            .mapValues { (_, inst) -> Instant.ofEpochSecond(inst.epochSecond).toString() }
            .toSortedMap()

        return kept to deletedStrings
    }
}

sealed class SyncState {
    data object SignedOut : SyncState()
    data object Idle : SyncState()
    data object Syncing : SyncState()
    data class Error(val message: String) : SyncState()
}

class SyncManager(
    private val context: Context,
    private val library: LibraryStore,
    private val highlights: HighlightStore,
    private val vocab: VocabStore,
    private val bookmarks: BookmarkStore,
    private val tracker: ReadingTracker,
    private val settings: ReaderSettings
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reader_sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<SyncState>(
        if (GoogleAuth.isSignedIn.value) SyncState.Idle else SyncState.SignedOut
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _lastSync = MutableStateFlow<String?>(prefs.getString("lastSync", null))
    val lastSync: StateFlow<String?> = _lastSync.asStateFlow()

    @Volatile private var busy = false
    @Volatile private var transferring = false
    @Volatile private var pendingResync = false

    /** Büyük dosya aktarımı sürerken gösterilen not ("Yükleniyor: …"). */
    private val _transferNote = MutableStateFlow<String?>(null)
    val transferNote: StateFlow<String?> = _transferNote.asStateFlow()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val bigClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        settings.onSettingsChanged = {
            syncSoon()
        }
        scope.launch {
            GoogleAuth.isSignedIn.collect { signedIn ->
                if (signedIn && _state.value == SyncState.SignedOut) {
                    _state.value = SyncState.Idle
                } else if (!signedIn) {
                    _state.value = SyncState.SignedOut
                }
            }
        }
    }

    fun signOut() {
        GoogleAuth.signOut()
        _state.value = SyncState.SignedOut
    }

    fun syncSoon() {
        if (!GoogleAuth.isSignedIn.value) return
        scope.launch {
            syncNow()
        }
    }

    suspend fun syncNow() = withContext(Dispatchers.IO) {
        if (!GoogleAuth.isSignedIn.value) return@withContext
        // Bir senkron sürerken yeni istek gelirse (ör. yeni vurgu) bitince bir tur daha yapılır.
        if (busy) {
            pendingResync = true
            return@withContext
        }
        busy = true
        _state.value = SyncState.Syncing
        var files: Map<String, String> = emptyMap()
        try {
          do {
            pendingResync = false
            files = listFiles()

            // 1. highlights.json
            run {
                val name = "highlights.json"
                val (remote, raw) = fetch<ItemsFile<Highlight>>(name, files)
                val (items, del) = SyncMerge.items(
                    local = highlights.items.value,
                    remote = remote?.items ?: emptyList(),
                    localDeleted = highlights.deleted,
                    remoteDeleted = remote?.deleted ?: emptyMap(),
                    idOf = { it.id },
                    stampOf = { it.stamp }
                )
                withContext(Dispatchers.Main) {
                    highlights.replaceFromSync(items, del)
                }
                push(ItemsFile(items = items, deleted = del), name, files, raw)
            }

            // 2. vocab.json
            run {
                val name = "vocab.json"
                val (remote, raw) = fetch<ItemsFile<VocabEntry>>(name, files)
                val (items, del) = SyncMerge.items(
                    local = vocab.entries.value,
                    remote = remote?.items ?: emptyList(),
                    localDeleted = vocab.deleted,
                    remoteDeleted = remote?.deleted ?: emptyMap(),
                    idOf = { it.id },
                    stampOf = { it.stamp }
                )
                withContext(Dispatchers.Main) {
                    vocab.replaceFromSync(items, del)
                }
                push(ItemsFile(items = items, deleted = del), name, files, raw)
            }

            // 3. bookmarks.json
            run {
                val name = "bookmarks.json"
                val (remote, raw) = fetch<ItemsFile<Bookmark>>(name, files)
                val (items, del) = SyncMerge.items(
                    local = bookmarks.items.value,
                    remote = remote?.items ?: emptyList(),
                    localDeleted = bookmarks.deleted,
                    remoteDeleted = remote?.deleted ?: emptyMap(),
                    idOf = { it.id },
                    stampOf = { it.stamp }
                )
                withContext(Dispatchers.Main) {
                    bookmarks.replaceFromSync(items, del)
                }
                push(ItemsFile(items = items, deleted = del), name, files, raw)
            }

            // 3b. library.json
            run {
                val name = "library.json"
                val (remote, raw) = fetch<ItemsFile<LibraryEntry>>(name, files)
                val (items, del) = SyncMerge.items(
                    local = library.books.value.map { it.syncEntry() },
                    remote = remote?.items ?: emptyList(),
                    localDeleted = library.deleted,
                    remoteDeleted = remote?.deleted ?: emptyMap(),
                    idOf = { it.id },
                    stampOf = { IsoDate.parse(it.updatedAt) }
                )
                withContext(Dispatchers.Main) {
                    library.applyRemoteLibrary(items, del)
                }
                push(ItemsFile(items = items, deleted = del), name, files, raw)
            }

            // 4. progress.json
            run {
                val name = "progress.json"
                val (remote, raw) = fetch<ProgressFile>(name, files)
                val merged = (remote?.books ?: emptyMap()).mapKeys { it.key.uppercase() }.toMutableMap()
                for (b in library.books.value) {
                    val key = b.id.uppercase()
                    val localStamp = IsoDate.parseOrNull(b.progressUpdatedAt) ?: Instant.EPOCH
                    val r = merged[key]
                    val remoteStamp = r?.let { IsoDate.parse(it.updatedAt) }
                    if (r != null && remoteStamp != null && remoteStamp.isAfter(localStamp)) {
                        withContext(Dispatchers.Main) {
                            library.applyRemoteProgress(b.id, r.position, r.scrollFraction, r.updatedAt)
                        }
                    } else if (b.progressUpdatedAt != null) {
                        merged[key] = ProgressRecord(
                            position = b.position,
                            scrollFraction = b.scrollFraction,
                            progress = b.progress,
                            updatedAt = b.progressUpdatedAt
                        )
                    }
                }
                push(ProgressFile(books = merged.toSortedMap()), name, files, raw)
            }

            // 5. reading-log.json
            run {
                val name = "reading-log.json"
                val (remote, raw) = fetch<LogFile>(name, files)
                val merged = withContext(Dispatchers.Main) {
                    tracker.mergeRemote(remote?.entries ?: emptyList())
                }
                val sorted = merged.sortedBy { it.id }
                push(LogFile(entries = sorted), name, files, raw)
            }

            // 6. settings.json
            run {
                val name = "settings.json"
                val (remote, raw) = fetch<SettingsFile>(name, files)
                val localStampStr = settings.settingsStamp
                val localStamp = IsoDate.parseOrNull(localStampStr) ?: Instant.EPOCH
                val remoteStamp = remote?.let { IsoDate.parse(it.updatedAt) }
                if (remote != null && remoteStamp != null && remoteStamp.isAfter(localStamp)) {
                    withContext(Dispatchers.Main) {
                        settings.applyFromRemote(remote.updatedAt) { cur ->
                            // Okuma görünümü (tema, yazı, boyut, kolon…) bilerek uygulanmaz: telefon ve iPad ekranları farklı.
                            cur.copy(
                                highlightColor = HighlightColor.entries.firstOrNull { it.name == remote.highlightColor } ?: cur.highlightColor
                            )
                        }
                    }
                } else if (localStamp.isAfter(Instant.EPOCH) && (remoteStamp == null || localStamp.isAfter(remoteStamp))) {
                    val s = settings.current
                    val sf = SettingsFile(
                        updatedAt = localStampStr ?: IsoDate.now(),
                        theme = s.theme.name,
                        font = s.font.rawValue,
                        fontScale = s.fontScale,
                        lineHeight = s.lineHeight,
                        margin = s.margin,
                        readingMode = s.readingMode.name,
                        columns = s.columns.name,
                        warmth = s.warmth,
                        edgeTap = s.edgeTap,
                        highlightColor = s.highlightColor.name
                    )
                    push(sf, name, files, raw)
                }
            }

          } while (pendingResync)

            val now = IsoDate.now()
            _lastSync.value = now
            prefs.edit().putString("lastSync", now).apply()
            _state.value = SyncState.Idle
        } catch (e: Exception) {
            _state.value = SyncState.Error(e.message ?: "Senkron hatası")
            busy = false
            return@withContext
        }
        busy = false

        // Büyük dosya aktarımları ayrı çalışır; sürerken vurgu/ilerleme senkronu engellenmez.
        if (transferring) return@withContext
        transferring = true
        try {
            syncBookFiles(files)
        } catch (e: Exception) {
            _state.value = SyncState.Error(e.message ?: "Senkron hatası")
        } finally {
            transferring = false
            _transferNote.value = null
        }
    }

    /** Kitap ve kapak dosyalarını yükler/indirir; her yerden silinenleri Drive'dan kaldırır. */
    private suspend fun syncBookFiles(files: Map<String, String>) {
        // 1) Her yerden silinen kitapların Drive dosyaları
        for (id in library.deleted.keys) {
            for (kind in BookKind.entries) files[Paths.driveBookName(id, kind)]?.let { deleteFile(it) }
            files[Paths.driveCoverName(id)]?.let { deleteFile(it) }
        }
        // 2) Yerel kitaplar ve kapaklar buluta
        for (b in library.books.value.filterNot { it.isRemoteOnly }) {
            val name = Paths.driveBookName(b.id, b.kind)
            if (files[name] == null) {
                var tmp: File? = null
                try {
                    val (file, mime) = when (b.kind) {
                        BookKind.epub -> {
                            _transferNote.value = "Yükleniyor: ${b.title}"
                            val t = File(context.cacheDir, "up-${UUID.randomUUID()}")
                            tmp = t
                            ZipWriter.zipDirectory(Paths.epubRoot(b), t)
                            t to "application/epub+zip"
                        }
                        BookKind.pdf -> Paths.file(b) to "application/pdf"
                    }
                    if (file.exists()) {
                        _transferNote.value = "Yükleniyor: ${b.title}"
                        uploadFile(name, null, file, mime)
                    }
                } finally {
                    tmp?.delete()
                }
            }
            val cover = Paths.cover(b)
            if (files[Paths.driveCoverName(b.id)] == null && cover != null && cover.exists()) {
                uploadFile(Paths.driveCoverName(b.id), null, cover, "image/jpeg")
            }
        }
        // 3) Bulutta olup kapağı henüz inmemiş kitaplar
        for (b in library.books.value.filter { it.coverFile == null }) {
            val fid = files[Paths.driveCoverName(b.id)] ?: continue
            val local = "${b.id}-cover.jpg"
            downloadToFile(fid, File(Paths.base, local))
            withContext(Dispatchers.Main) { library.setCover(b.id, local) }
        }
    }

    /** Bulutta duran kitabı bu cihaza indirir (kitaba dokununca çağrılır). */
    suspend fun downloadBook(id: String) = withContext(Dispatchers.IO) {
        val b = library.book(id) ?: return@withContext
        if (!b.isRemoteOnly) return@withContext
        if (!GoogleAuth.isSignedIn.value) throw Exception("Google oturumu kapalı. Ayarlar'dan yeniden bağlanın.")
        val files = listFiles()
        val fid = files[Paths.driveBookName(b.id, b.kind)]
            ?: throw Exception("Kitap dosyası Drive'da bulunamadı. Diğer cihazın yüklemesini bekleyin.")
        val tmp = File(context.cacheDir, "dl-${UUID.randomUUID()}")
        try {
            downloadToFile(fid, tmp)
            library.installDownloaded(b.id, tmp)
        } finally {
            if (tmp.exists() && b.kind == BookKind.epub) tmp.delete()
        }
    }

    private suspend fun deleteFile(id: String) {
        val token = GoogleAuth.validAccessToken()
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$id")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw Exception("Drive HTTP ${resp.code}")
        }
    }

    private suspend fun downloadToFile(id: String, dest: File) {
        val token = GoogleAuth.validAccessToken()
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$id?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        bigClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Drive HTTP ${resp.code}")
            val body = resp.body ?: throw Exception("Boş yanıt")
            body.byteStream().use { input -> FileOutputStream(dest).use { out -> input.copyTo(out) } }
        }
    }

    /** Devam ettirilebilir (resumable) yükleme; dosya diskten akıtılır. */
    private suspend fun uploadFile(name: String, existingID: String?, file: File, mime: String) {
        val token = GoogleAuth.validAccessToken()
        val startReq = if (existingID != null) {
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$existingID?uploadType=resumable")
                .patch("{}".toRequestBody("application/json; charset=UTF-8".toMediaType()))
        } else {
            val meta = """{"name":"$name","parents":["appDataFolder"]}"""
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
                .post(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        }.header("Authorization", "Bearer $token")
            .header("X-Upload-Content-Type", mime)
            .build()
        val location = httpClient.newCall(startReq).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Drive HTTP ${resp.code}")
            resp.header("Location") ?: throw Exception("Yükleme adresi alınamadı")
        }
        val put = Request.Builder()
            .url(location)
            .put(file.asRequestBody(mime.toMediaType()))
            .build()
        bigClient.newCall(put).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Drive HTTP ${resp.code}")
        }
    }

    private suspend inline fun <reified T> fetch(
        name: String,
        files: Map<String, String>
    ): Pair<T?, String?> {
        val id = files[name] ?: return null to null
        val raw = download(id)
        val parsed = runCatching { json.decodeFromString<T>(raw) }.getOrNull()
        return parsed to raw
    }

    private suspend inline fun <reified T> push(
        value: T,
        name: String,
        files: Map<String, String>,
        remoteRaw: String?
    ) {
        val payload = json.encodeToString(value)
        if (payload == remoteRaw) return
        if (remoteRaw != null) {
            val same = runCatching {
                json.parseToJsonElement(payload) == json.parseToJsonElement(remoteRaw)
            }.getOrDefault(false)
            if (same) return
        }
        upload(name, files[name], payload)
    }

    @Serializable
    private data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)

    @Serializable
    private data class DriveFile(val id: String, val name: String)

    private suspend fun listFiles(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var pageToken: String? = null
        do {
            val token = GoogleAuth.validAccessToken()
            val url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder" +
                "&fields=nextPageToken,files(id,name)&orderBy=modifiedTime%20desc&pageSize=1000" +
                (pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Drive HTTP ${resp.code}")
                val list = json.decodeFromString<FileList>(resp.body?.string().orEmpty())
                for (f in list.files) {
                    if (!out.containsKey(f.name)) out[f.name] = f.id
                }
                pageToken = list.nextPageToken
            }
        } while (pageToken != null)
        return out
    }

    private suspend fun download(id: String): String {
        val token = GoogleAuth.validAccessToken()
        val url = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Drive HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private suspend fun upload(name: String, existingID: String?, payload: String) {
        val token = GoogleAuth.validAccessToken()
        if (existingID != null) {
            val url = "https://www.googleapis.com/upload/drive/v3/files/$existingID?uploadType=media"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .patch(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Drive HTTP ${resp.code}")
            }
        } else {
            val boundary = "sync-${UUID.randomUUID()}"
            val meta = """{"name":"$name","parents":["appDataFolder"]}"""
            val bodyStr = buildString {
                append("--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append("$meta\r\n")
                append("--$boundary\r\n")
                append("Content-Type: application/json\r\n\r\n")
                append(payload)
                append("\r\n--$boundary--")
            }
            val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(bodyStr.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Drive HTTP ${resp.code}")
            }
        }
    }
}
