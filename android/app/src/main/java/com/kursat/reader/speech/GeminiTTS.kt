package com.kursat.reader.speech

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Base64
import com.kursat.reader.ai.ClaudeClient
import com.kursat.reader.ai.KeyStore
import com.kursat.reader.ai.LLMException
import com.kursat.reader.ai.ResponseCache
import com.kursat.reader.model.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.min

/** Gemini ses üretimi (TTS). Üretilen sesler diske AAC olarak kaydedilir; aynı paragraf ikinci kez ücretlendirilmez. */
object GeminiTTS {
    const val SAMPLE_RATE = 24_000

    val cacheDir: File get() = File(Paths.base, "tts").apply { mkdirs() }

    fun cacheFile(text: String, voice: String, model: String): File =
        File(cacheDir, ResponseCache.key("gemini-tts", model, voice, text) + ".m4a")

    /** Metni seslendirir ve önbellek dosyasını döndürür. */
    suspend fun audioFile(context: Context, text: String, voice: String, model: String): File {
        val file = cacheFile(text, voice, model)
        if (file.exists() && file.length() > 0) return file
        return withContext(Dispatchers.IO) {
            val pcm = synthesize(text, voice, model)
            writeM4A(pcm, file)
            TTSUsage.add(context, pcm.size / 2.0 / SAMPLE_RATE)
            file
        }
    }

    // MARK: - API

    private fun buildRequest(text: String, voice: String, model: String): Request {
        val key = KeyStore.get(KeyStore.API_KEY_GEMINI) ?: throw LLMException.MissingKey()
        val name = model.removePrefix("models/")
        val legacy = name.contains("2.5")
        val url = if (legacy) "https://generativelanguage.googleapis.com/v1beta/models/$name:generateContent"
        else "https://generativelanguage.googleapis.com/v1beta/interactions"
        val body = if (legacy) {
            JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
        } else {
            JSONObject()
                .put("model", name)
                .put("input", JSONArray().put(JSONObject()
                    .put("type", "user_input")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))))
                .put("response_format", JSONObject().put("type", "audio"))
                .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        }
        return Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("x-goog-api-key", key)
            .header("content-type", "application/json")
            .build()
    }

    /** Yanıt ağacındaki base64 ses verisini bulur. */
    private fun findAudio(any: Any?): String? {
        when (any) {
            is JSONObject -> {
                val d = any.optString("data")
                if (d.length > 400) return d
                val keys = any.keys()
                while (keys.hasNext()) findAudio(any.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until any.length()) findAudio(any.opt(i))?.let { return it }
        }
        return null
    }

    /** 24 kHz, mono, 16 bit PCM döndürür (WAV başlığı varsa atılır). */
    fun synthesize(text: String, voice: String, model: String): ByteArray {
        val req = buildRequest(text, voice, model)
        ClaudeClient.httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (resp.code != 200) throw LLMException.Http(resp.code, ClaudeClient.apiMessage(bodyStr))
            val b64 = findAudio(JSONObject(bodyStr)) ?: throw LLMException.Http(0, "Gemini ses verisi döndürmedi.")
            return stripWavHeader(Base64.decode(b64, Base64.DEFAULT))
        }
    }

    private fun stripWavHeader(d: ByteArray): ByteArray {
        if (d.size <= 44 || String(d, 0, 4, Charsets.ISO_8859_1) != "RIFF") return d
        var i = 12
        while (i + 8 <= d.size) {
            val id = String(d, i, 4, Charsets.ISO_8859_1)
            val size = (d[i + 4].toInt() and 0xFF) or ((d[i + 5].toInt() and 0xFF) shl 8) or
                ((d[i + 6].toInt() and 0xFF) shl 16) or ((d[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") return d.copyOfRange(i + 8, d.size)
            if (size < 0) break
            i += 8 + size + (size and 1)
        }
        return d.copyOfRange(44, d.size)
    }

    // MARK: - AAC (m4a) kodlama

    fun writeM4A(pcm: ByteArray, out: File) {
        val tmp = File(out.path + ".part")
        tmp.delete()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var inputDone = false
            var outputDone = false
            var ptsUs = 0L
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = min(buf.remaining(), pcm.size - offset) and 1.inv()
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, offset, n)
                            codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                            offset += n
                            ptsUs += (n / 2) * 1_000_000L / SAMPLE_RATE
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    o >= 0 -> {
                        val ob = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
        out.delete()
        if (!tmp.renameTo(out)) throw LLMException.BadResponse()
    }

    fun cacheSize(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}

/** Aylık üretilen Gemini sesi ve tahmini maliyet. */
object TTSUsage {
    private fun prefs(c: Context) = c.getSharedPreferences("tts_usage", Context.MODE_PRIVATE)

    private val monthKey: String
        get() {
            val c = Calendar.getInstance()
            return "ttsSeconds-%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        }

    fun secondsThisMonth(c: Context): Double = prefs(c).getFloat(monthKey, 0f).toDouble()

    fun add(c: Context, seconds: Double) {
        prefs(c).edit().putFloat(monthKey, (secondsThisMonth(c) + seconds).toFloat()).apply()
    }

    /** Ses çıktısı 25 token/sn; milyon token başına USD (resmi fiyat sayfasındaki değerler). */
    fun dollarsPerHour(model: String): Double {
        val after2027 = Calendar.getInstance().get(Calendar.YEAR) >= 2027
        val perMillion = when {
            model.contains("flash-lite-tts") -> if (after2027) 12.0 else 6.0
            model.contains("3.8-flash-tts") -> if (after2027) 18.0 else 9.0
            model.contains("pro") || model.contains("3.1") -> 20.0
            else -> 10.0
        }
        return 3600.0 * 25 / 1_000_000 * perMillion
    }

    fun dollarsThisMonth(c: Context, model: String): Double =
        secondsThisMonth(c) / 3600.0 * dollarsPerHour(model)
}
