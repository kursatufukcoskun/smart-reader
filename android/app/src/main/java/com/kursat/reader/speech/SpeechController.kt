package com.kursat.reader.speech

import android.content.Context
import android.util.Log
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.kursat.reader.ai.LLMClient
import com.kursat.reader.ai.ResponseCache
import com.kursat.reader.model.LanguageDetect
import com.kursat.reader.model.LLMTier
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.TTSEngine
import com.kursat.reader.model.TargetLanguage
import com.kursat.reader.ai.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.coroutineContext

/** Okunacak bir paragraf. */
data class SpeechUnit(val id: Int, val text: String)

/** Okuyucunun (EPUB/PDF) seslendirmeye verdiği bağlantılar. */
class SpeechSource(
    /** Mevcut bölümün/sayfanın paragrafları ve ekranda görünen ilk paragrafın sırası. */
    val paragraphs: suspend () -> Pair<List<SpeechUnit>, Int>,
    /** Okunan paragrafı işaretler (null = işareti kaldır). */
    val highlight: (Int?) -> Unit,
    /** Sonraki bölüme/sayfaya geçer; kitap bittiyse false. */
    val advance: suspend () -> Boolean,
    val title: () -> String,
    val subtitle: () -> String,
    /** Çevrilerek okunan paragrafın çevirisini sayfada gösterir. */
    val showTranslation: (Int, String) -> Unit = { _, _ -> }
)

/** Sistem sesi veya Gemini ile paragraf paragraf, tembel (önden birkaç paragraf hazırlayarak) okuma. */
class SpeechController(context: Context, private val settings: ReaderSettings) {
    enum class State { idle, playing, paused }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State.idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    /** Çevrilerek okunan paragrafın çevirisi (oynatıcıda altyazı olarak gösterilir). */
    private val _caption = MutableStateFlow<String?>(null)
    val caption: StateFlow<String?> = _caption.asStateFlow()

    var source: SpeechSource? = null

    private var units: List<SpeechUnit> = emptyList()
    private var runner: Job? = null
    private val textJobs = HashMap<Int, Deferred<String>>()
    private val audioJobs = HashMap<Int, Deferred<File>>()
    private var geminiFailed = false
    /** Çeviri başarısız olduysa aynı paragraf, devam edilince yeniden denenir. */
    private var retryCurrent = false
    /** Bölümün/sayfanın baskın dili: kısa paragraflarda (başlık, madde) tek tek tespit güvenilmez. */
    private var sourceLang: TargetLanguage? = null
    private val lookahead = 3

    // Sistem sesi
    private var tts: TextToSpeech? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null
    private var utterance: CompletableDeferred<Boolean>? = null
    private var interruptedForPause = false

    // Dosya oynatıcı
    private var player: MediaPlayer? = null
    private var playerDone: CompletableDeferred<Unit>? = null

    // Ses odağı
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    val title: String get() = source?.title?.invoke().orEmpty()
    val subtitle: String get() = source?.subtitle?.invoke().orEmpty()
    val isActive: Boolean get() = _state.value != State.idle

    companion object {
        @Volatile var active: SpeechController? = null
    }

    fun dismissNotice() { _notice.value = null }

    // MARK: - Kontrol

    fun start() {
        val src = source ?: return
        if (_state.value != State.idle) return
        geminiFailed = false
        _notice.value = null
        active = this
        requestFocus()
        _state.value = State.playing
        SpeechService.start(app)
        runner = scope.launch {
            val (list, start) = src.paragraphs()
            if (list.isEmpty()) {
                _notice.value = "Bu sayfada okunacak metin yok."
                stop()
                return@launch
            }
            units = list
            updateSourceLang()
            _index.value = start
            _count.value = list.size
            run()
        }
    }

    fun toggle() {
        when (_state.value) {
            State.idle -> start()
            State.playing -> pause()
            State.paused -> resume()
        }
    }

    fun pause() {
        if (_state.value != State.playing) return
        _state.value = State.paused
        if (utterance != null) {
            interruptedForPause = true
            tts?.stop()
        }
        player?.let { if (it.isPlaying) it.pause() }
        SpeechService.refresh()
    }

    fun resume() {
        if (_state.value != State.paused) return
        _state.value = State.playing
        player?.let { runCatching { it.start() } }
        SpeechService.refresh()
    }

    fun stop() {
        runner?.cancel()
        runner = null
        stopOutput()
        source?.highlight(null)
        textJobs.values.forEach { it.cancel() }
        audioJobs.values.forEach { it.cancel() }
        textJobs.clear()
        audioJobs.clear()
        units = emptyList()
        _busy.value = null
        _caption.value = null
        _state.value = State.idle
        _index.value = 0
        _count.value = 0
        abandonFocus()
        SpeechService.stop(app)
        if (active === this) active = null
    }

    /** Okuyucu başka yere gidildiğinde (ör. kullanıcı bölüm değiştirdi) çağrılır. */
    fun stopIfActive() { if (_state.value != State.idle) stop() }

    /** Okuyucu kapanırken: sesi bırakır. */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ttsReady = null
    }

    fun skip(delta: Int) {
        if (_state.value == State.idle || units.isEmpty()) return
        _index.value = (_index.value + delta).coerceIn(0, units.size)
        restart()
    }

    fun setRate(r: Double) {
        settings.update { it.copy(ttsRate = r) }
        applyPlayerRate()
    }

    /** Ayar değişince (ses, dil, motor) sıradaki paragraflar yeniden hazırlansın. */
    fun settingsChanged() {
        if (_state.value == State.idle) return
        textJobs.values.forEach { it.cancel() }
        audioJobs.values.forEach { it.cancel() }
        textJobs.clear()
        audioJobs.clear()
    }

    private fun restart() {
        runner?.cancel()
        stopOutput()
        if (_state.value == State.paused) _state.value = State.playing
        runner = scope.launch { run() }
    }

    // MARK: - Ana döngü

    private suspend fun run() {
        val src = source ?: return
        while (coroutineContext.isActive) {
            if (_index.value >= units.size) {
                if (!src.advance() || !coroutineContext.isActive) break
                val (list, _) = src.paragraphs()
                if (!coroutineContext.isActive) return
                textJobs.values.forEach { it.cancel() }
                audioJobs.values.forEach { it.cancel() }
                textJobs.clear()
                audioJobs.clear()
                units = list
                updateSourceLang()
                _count.value = list.size
                _index.value = 0
                continue
            }
            src.highlight(units[_index.value].id)
            SpeechService.refresh()
            speakUnit(_index.value)
            if (!coroutineContext.isActive) return
            if (retryCurrent) {
                retryCurrent = false
                waitWhilePaused()
                continue
            }
            _index.value = _index.value + 1
        }
        if (coroutineContext.isActive) stop()
    }

    private suspend fun waitWhilePaused() {
        while (_state.value == State.paused && coroutineContext.isActive) delay(200)
    }

    private suspend fun speakUnit(i: Int) {
        val s = settings.current
        val engine = effectiveEngine()
        Log.i("ReaderSpeech", "unit $i engine=$engine tts=${s.ttsLanguage} target=${s.targetLanguage} detected=$sourceLang provider=${s.provider} model=${s.config(LLMTier.fast).model} text='${units[i].text.take(60)}'")
        prefetch(i, engine)
        try {
            if (needsTranslation(units[i].text)) _busy.value = "Çevriliyor…"
            val text = try {
                textJob(i).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Çeviri olmadan orijinal dili okumak istenen şey değil: dur ve nedenini göster.
                Log.e("ReaderSpeech", "translation failed", e)
                _busy.value = null
                if (!coroutineContext.isActive) return
                textJobs.remove(i)
                retryCurrent = true
                _notice.value = "Çeviri yapılamadı, okuma duraklatıldı: ${e.message}"
                pause()
                return
            }
            if (!coroutineContext.isActive) return
            Log.i("ReaderSpeech", "resolved unit $i translated=${text != units[i].text} -> '${text.take(60)}'")
            val translated = text != units[i].text
            _caption.value = if (translated) text else null
            if (translated && settings.current.ttsShowInPage) source?.showTranslation(units[i].id, text)
            if (engine == TTSEngine.gemini) {
                val job = audioJob(i, text)
                if (!GeminiTTS.cacheFile(text, s.geminiVoice, s.geminiTTSModel).exists()) _busy.value = "Ses hazırlanıyor…"
                val file = job.await()
                _busy.value = null
                waitWhilePaused()
                if (!coroutineContext.isActive) return
                playFile(file)
            } else {
                _busy.value = null
                speakSystem(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _busy.value = null
            if (!coroutineContext.isActive) return
            if (engine == TTSEngine.gemini) {
                geminiFailed = true
                _notice.value = "Gemini sesi alınamadı, sistem sesine geçildi: ${e.message}"
                val text = runCatching { textJob(i).await() }.getOrDefault(units[i].text)
                speakSystem(text)
            } else {
                _notice.value = e.message
                speakSystem(units[i].text) // çeviri başarısızsa orijinal metni oku
            }
        }
    }

    private fun effectiveEngine(): TTSEngine {
        val s = settings.current
        if (s.ttsEngine != TTSEngine.gemini || geminiFailed) return TTSEngine.system
        if (s.ttsBudgetUSD > 0 && TTSUsage.dollarsThisMonth(app, s.geminiTTSModel) >= s.ttsBudgetUSD) {
            _notice.value = "Aylık Gemini ses bütçesi doldu, sistem sesine geçildi."
            geminiFailed = true
            return TTSEngine.system
        }
        return TTSEngine.gemini
    }

    // MARK: - Çeviri ve hazırlık

    private fun updateSourceLang() {
        sourceLang = LanguageDetect.detect(units.joinToString(" ") { it.text }.take(4000))
    }

    private fun needsTranslation(text: String): Boolean {
        val d = sourceLang ?: LanguageDetect.detect(text) ?: return false
        return d != settings.current.ttsLanguage
    }

    private fun textJob(i: Int): Deferred<String> = textJobs.getOrPut(i) {
        val text = units[i].text
        val s = settings.current
        val target = s.ttsLanguage
        val cfg = s.config(LLMTier.fast)
        val src = sourceLang
        scope.async(Dispatchers.IO) { resolveText(text, target, cfg, src) }
    }

    private suspend fun resolveText(text: String, target: TargetLanguage, cfg: com.kursat.reader.model.LLMConfig, chapterLang: TargetLanguage?): String {
        val d = chapterLang ?: LanguageDetect.detect(text)
        if (d == null || d == target) return text
        if (text.count { it.isLetter() } < 2) return text
        val key = ResponseCache.key("ttstr", cfg.baseID, target.code, text)
        ResponseCache.get(key)?.let { return it }
        val out = LLMClient.complete(cfg, Prompts.translatorSystem(target), Prompts.translateParagraph(text, target), 2048).trim()
        if (out.isEmpty()) return text
        ResponseCache.set(key, out)
        return out
    }

    private fun audioJob(i: Int, text: String): Deferred<File> = audioJobs.getOrPut(i) {
        val s = settings.current
        val voice = s.geminiVoice
        val model = s.geminiTTSModel
        scope.async(Dispatchers.IO) { GeminiTTS.audioFile(app, text, voice, model) }
    }

    /** Sıradaki birkaç paragrafı arka planda hazırlar (tembel üretim penceresi). */
    private fun prefetch(i: Int, engine: TTSEngine) {
        textJobs.keys.filter { it < i }.forEach { textJobs.remove(it) }
        audioJobs.keys.filter { it < i }.forEach { audioJobs.remove(it) }
        val end = minOf(units.size - 1, i + lookahead)
        if (i + 1 > end) return
        val s = settings.current
        for (j in (i + 1)..end) {
            val t = textJob(j)
            if (engine == TTSEngine.gemini && !audioJobs.containsKey(j)) {
                val voice = s.geminiVoice
                val model = s.geminiTTSModel
                audioJobs[j] = scope.async(Dispatchers.IO) { GeminiTTS.audioFile(app, t.await(), voice, model) }
            }
        }
    }

    // MARK: - Çıkış: sistem sesi

    private suspend fun ensureTts(): TextToSpeech? {
        tts?.let { return it }
        val ready = ttsReady ?: CompletableDeferred<Boolean>().also { d ->
            ttsReady = d
            tts = TextToSpeech(app) { status -> d.complete(status == TextToSpeech.SUCCESS) }
        }
        if (!ready.await()) {
            tts?.shutdown(); tts = null; ttsReady = null
            return null
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utterance?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utterance?.complete(false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utterance?.complete(false) }
        })
        return tts
    }

    private fun chunks(text: String, max: Int = 3500): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '。'), max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out.add(rest.trim())
        return out
    }

    private suspend fun speakSystem(text: String) {
        val t = ensureTts() ?: run { _notice.value = "Sistem sesi başlatılamadı."; return }
        val s = settings.current
        val langResult = t.setLanguage(s.ttsLanguage.locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            _notice.value = "Sistem sesinde ${s.ttsLanguage.title} yüklü değil. Telefon Ayarları ▸ Metin okuma bölümünden ses verisini indirin veya Gemini sesini seçin."
        }
        t.voices
            ?.filter { it.locale.language == s.ttsLanguage.code && !it.features.contains("notInstalled") }
            ?.maxByOrNull { it.quality * 10 - (if (it.isNetworkConnectionRequired) 5 else 0) }
            ?.let { t.voice = it }
        t.setSpeechRate(s.ttsRate.toFloat())
        while (coroutineContext.isActive) {
            var finished = true
            for ((k, part) in chunks(text).withIndex()) {
                val done = CompletableDeferred<Boolean>()
                utterance = done
                interruptedForPause = false
                if (t.speak(part, TextToSpeech.QUEUE_FLUSH, null, "u${System.nanoTime()}-$k") != TextToSpeech.SUCCESS) {
                    utterance = null; return
                }
                val ok = done.await()
                utterance = null
                if (!ok) { finished = false; break }
            }
            if (finished || !coroutineContext.isActive) break
            // Duraklatma nedeniyle kesildi: devam edilince paragrafı baştan oku
            if (interruptedForPause) {
                interruptedForPause = false
                waitWhilePaused()
            } else break
        }
        utterance = null
    }

    // MARK: - Çıkış: dosya

    private suspend fun playFile(file: File) {
        val done = CompletableDeferred<Unit>()
        playerDone = done
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ -> done.complete(Unit); true }
            mp.prepare()
            player = mp
            mp.start()
            applyPlayerRate()
            done.await()
        } finally {
            if (player === mp) player = null
            runCatching { mp.release() }
            if (playerDone === done) playerDone = null
        }
    }

    private fun applyPlayerRate() {
        val p = player ?: return
        runCatching {
            val wasPlaying = p.isPlaying
            p.playbackParams = PlaybackParams().setSpeed(settings.current.ttsRate.toFloat())
            if (!wasPlaying && _state.value == State.paused) p.pause()
        }
    }

    private fun stopOutput() {
        interruptedForPause = false
        tts?.stop()
        utterance?.complete(false)
        player?.let { runCatching { it.stop() } }
        playerDone?.complete(Unit)
    }

    // MARK: - Ses odağı

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    scope.launch { pause() }
                }
            }
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
