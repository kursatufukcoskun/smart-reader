package com.kursat.reader.model

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class StatsPeriod(val title: String) {
    week("Hafta"),
    month("Ay"),
    year("Yıl")
}

@Serializable
data class ReadingEntry(
    val day: String,          // yyyy-MM-dd
    val bookID: String,
    val seconds: Double = 0.0,
    val words: Int = 0,
    val sessions: Int = 0,
    val device: String? = null
) {
    val id: String get() = "$day|${bookID.uppercase()}|${device.orEmpty()}"
}

data class StatBucket(
    val start: LocalDate,
    val seconds: Double,
    val words: Int,
    val byBook: Map<String, Double>
) {
    val pages: Double get() = words.toDouble() / Book.WORDS_PER_PAGE
}

object DurationText {
    /** "4 dk", "1 sa 12 dk", "<1 dk" */
    fun text(seconds: Double): String {
        val m = (seconds / 60.0).roundToInt()
        if (seconds < 30.0) return "<1 dk"
        if (m < 60) return "${max(m, 1)} dk"
        val h = m / 60
        val r = m % 60
        return if (r == 0) "$h sa" else "$h sa $r dk"
    }
}

class ReadingTracker(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _entries = MutableStateFlow<List<ReadingEntry>>(emptyList())
    val entries: StateFlow<List<ReadingEntry>> = _entries.asStateFlow()

    private var currentBook: String? = null
    private var lastActivityMs: Long = 0L
    private var lastPos: Double? = null
    private var timerJob: Job? = null
    private var saveJob: Job? = null
    var appActive: Boolean = true
        set(value) {
            field = value
            if (!value) save()
        }

    private val tickSeconds = 5.0
    private val idleLimitMs = 120_000L
    private val logFile: File get() = File(Paths.base, "reading-log.json")

    companion object {
        private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        fun dayKey(d: LocalDate = LocalDate.now()): String = d.format(dayFormatter)
    }

    init {
        load()
    }

    fun begin(bookID: String, position: Double) {
        end()
        currentBook = bookID.uppercase()
        lastPos = position
        lastActivityMs = System.currentTimeMillis()
        bump(bookID.uppercase()) { it.copy(sessions = it.sessions + 1) }
        timerJob = scope.launch {
            while (isActive) {
                delay((tickSeconds * 1000).toLong())
                onTick()
            }
        }
    }

    fun end() {
        timerJob?.cancel()
        timerJob = null
        currentBook = null
        lastPos = null
        save()
    }

    /** Kullanıcı etkileşimi (idle sayacını sıfırlar). */
    fun touch() {
        lastActivityMs = System.currentTimeMillis()
    }

    /** Okuma konumu değişti (kelime cinsinden). Yalnızca makul ileri adımlar "okunan" sayılır. */
    fun moved(to: Double) {
        touch()
        val book = currentBook ?: return
        val last = lastPos
        if (last != null) {
            val d = to - last
            if (d > 0 && d <= 1500) {
                bump(book) { it.copy(words = it.words + d.roundToInt()) }
            }
        }
        lastPos = to
    }

    /** Atlama (içindekiler, bölüm değiştirme): okunan kelime olarak sayılmaz. */
    fun jumped(to: Double) {
        lastPos = to
        touch()
    }

    private fun onTick() {
        val book = currentBook ?: return
        if (!appActive) return
        if (System.currentTimeMillis() - lastActivityMs >= idleLimitMs) return
        bump(book) { it.copy(seconds = it.seconds + tickSeconds) }
    }

    private fun bump(bookID: String, change: (ReadingEntry) -> ReadingEntry) {
        val day = dayKey()
        val dev = DeviceID.current(context)
        val list = _entries.value.toMutableList()
        val i = list.indexOfFirst {
            it.day == day && it.bookID.equals(bookID, ignoreCase = true) && it.device == dev
        }
        if (i >= 0) {
            list[i] = change(list[i])
        } else {
            val e = change(ReadingEntry(day = day, bookID = bookID.uppercase(), device = dev))
            list.add(e)
        }
        _entries.value = list
        scheduleSave()
    }

    /** Son 30 günün ortalama okuma hızı (kelime/dk). Veri azsa 200. */
    val wordsPerMinute: Double
        get() {
            val cutoff = dayKey(LocalDate.now().minusDays(30))
            val recent = _entries.value.filter { it.day >= cutoff }
            val sec = recent.sumOf { it.seconds }
            val words = recent.sumOf { it.words }
            if (sec < 180.0 || words < 100) return 200.0
            return min(600.0, max(80.0, words.toDouble() / (sec / 60.0)))
        }

    val hasSpeedData: Boolean
        get() {
            val cutoff = dayKey(LocalDate.now().minusDays(30))
            val recent = _entries.value.filter { it.day >= cutoff }
            return recent.sumOf { it.seconds } >= 180.0 && recent.sumOf { it.words } >= 100
        }

    /** Bugün okunan saniye (tüm kitaplar, tüm cihazlar). */
    val todaySeconds: Double
        get() {
            val k = dayKey()
            return _entries.value.filter { it.day == k }.sumOf { it.seconds }
        }

    fun secondsToRead(words: Double): Double = max(0.0, words) / wordsPerMinute * 60.0

    fun entriesFor(bookID: String?): List<ReadingEntry> {
        if (bookID == null) return _entries.value
        return _entries.value.filter { it.bookID.equals(bookID, ignoreCase = true) }
    }

    fun buckets(period: StatsPeriod, bookID: String?, today: LocalDate = LocalDate.now()): List<StatBucket> {
        val list = entriesFor(bookID)
        val monthly = period == StatsPeriod.year
        val starts: List<LocalDate> = when (period) {
            StatsPeriod.week, StatsPeriod.month -> {
                val n = if (period == StatsPeriod.week) 7 else 30
                ((n - 1) downTo 0).map { today.minusDays(it.toLong()) }
            }
            StatsPeriod.year -> {
                val thisMonth = YearMonth.from(today).atDay(1)
                (11 downTo 0).map { thisMonth.minusMonths(it.toLong()) }
            }
        }
        return starts.map { s ->
            val e = if (monthly) s.plusMonths(1) else s.plusDays(1)
            val k1 = dayKey(s)
            val k2 = dayKey(e)
            val inRange = list.filter { it.day >= k1 && it.day < k2 }
            val by = mutableMapOf<String, Double>()
            for (x in inRange) {
                val key = x.bookID.uppercase()
                by[key] = (by[key] ?: 0.0) + x.seconds
            }
            StatBucket(
                start = s,
                seconds = inRange.sumOf { it.seconds },
                words = inRange.sumOf { it.words },
                byBook = by
            )
        }
    }

    /** Ardışık okunan gün sayısı (bugün henüz okumadıysanız dünden sayılır). */
    fun streak(bookID: String? = null): Int {
        val byDay = entriesFor(bookID).groupBy { it.day }.mapValues { (_, v) -> v.sumOf { it.seconds } }
        val days = byDay.filter { it.value >= 60.0 }.keys
        var d = LocalDate.now()
        if (!days.contains(dayKey(d))) {
            d = d.minusDays(1)
        }
        var n = 0
        while (days.contains(dayKey(d))) {
            n += 1
            d = d.minusDays(1)
        }
        return n
    }

    fun mergeRemote(remote: List<ReadingEntry>): List<ReadingEntry> {
        val map = _entries.value.associateBy { it.id }.toMutableMap()
        for (r in remote) {
            val norm = r.copy(bookID = r.bookID.uppercase())
            val l = map[norm.id]
            if (l != null) {
                if (norm.seconds > l.seconds || (norm.seconds == l.seconds && norm.words > l.words)) {
                    map[norm.id] = norm
                }
            } else {
                map[norm.id] = norm
            }
        }
        val merged = map.values.toList()
        _entries.value = merged
        save()
        return merged
    }

    private fun load() {
        if (!logFile.exists()) return
        try {
            val dev = DeviceID.current(context)
            val raw = logFile.readText(Charsets.UTF_8)
            val decoded = json.decodeFromString<List<ReadingEntry>>(raw)
            _entries.value = decoded.map {
                it.copy(
                    bookID = it.bookID.uppercase(),
                    device = it.device ?: dev
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(3000)
            save()
        }
    }

    fun save() {
        try {
            val data = json.encodeToString(_entries.value)
            val tmp = File(Paths.base, "reading-log.json.tmp")
            tmp.writeText(data, Charsets.UTF_8)
            tmp.renameTo(logFile)
        } catch (_: Exception) {
        }
    }
}

