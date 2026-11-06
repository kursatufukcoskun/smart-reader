package com.kursat.reader.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kursat.reader.model.LLMConfig
import com.kursat.reader.model.LLMProvider
import com.kursat.reader.model.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * API anahtarlarını ve Google yenileme anahtarını EncryptedSharedPreferences ile güvenli saklar.
 */
object KeyStore {
    const val API_KEY_CLAUDE = "anthropic_api_key"
    const val API_KEY_GEMINI = "gemini_api_key"
    const val GOOGLE_REFRESH_TOKEN = "google_refresh_token"
    const val GOOGLE_ACCESS_TOKEN = "google_access_token"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "reader_secure_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences("reader_secure_keys_fallback", Context.MODE_PRIVATE)
        }
    }

    fun set(value: String, key: String) {
        val p = prefs ?: return
        if (value.isBlank()) {
            p.edit().remove(key).apply()
        } else {
            p.edit().putString(key, value.trim()).apply()
        }
    }

    fun get(key: String): String? = prefs?.getString(key, null)?.takeIf { it.isNotBlank() }
}

sealed class LLMException(message: String) : Exception(message) {
    class MissingKey : LLMException("Seçili sağlayıcının API anahtarı yok. Kitaplık ▸ Ayarlar bölümünden ekleyin.")
    class Http(val code: Int, val msg: String) : LLMException("API hatası ($code): $msg")
    class BadResponse : LLMException("API'den beklenmeyen yanıt geldi.")
}

data class ChatTurn(
    val role: Role,
    val text: String
) {
    enum class Role { user, assistant }
}

object ClaudeClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun apiMessage(body: String): String {
        return try {
            val obj = JSONObject(body)
            val err = obj.optJSONObject("error")
            val msg = err?.optString("message")
            if (!msg.isNullOrBlank()) msg else body.take(300)
        } catch (_: Exception) {
            body.take(300)
        }
    }

    private fun makeRequest(
        model: String,
        system: String,
        messages: JSONArray,
        maxTokens: Int,
        stream: Boolean
    ): Request {
        val key = KeyStore.get(KeyStore.API_KEY_CLAUDE) ?: throw LLMException.MissingKey()
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", stream)
            put("messages", messages)
            if (system.isNotEmpty()) put("system", system)
        }
        return Request.Builder()
            .url(ENDPOINT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .build()
    }

    fun stream(model: String, system: String, user: String, maxTokens: Int = 2048): Flow<String> {
        val msgs = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", user)
        })
        return streamMessages(model, system, msgs, maxTokens)
    }

    fun streamChat(
        model: String,
        system: String,
        context: String,
        turns: List<ChatTurn>,
        maxTokens: Int = 2048
    ): Flow<String> {
        val msgs = JSONArray()
        turns.forEachIndexed { i, t ->
            if (t.role == ChatTurn.Role.user && i == 0) {
                val contentArr = JSONArray()
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", context)
                        put("cache_control", JSONObject().put("type", "ephemeral"))
                    })
                    .put(JSONObject().apply {
                        put("type", "text")
                        put("text", t.text)
                    })
                msgs.put(JSONObject().apply {
                    put("role", "user")
                    put("content", contentArr)
                })
            } else {
                msgs.put(JSONObject().apply {
                    put("role", if (t.role == ChatTurn.Role.user) "user" else "assistant")
                    put("content", t.text)
                })
            }
        }
        return streamMessages(model, system, msgs, maxTokens)
    }

    private fun streamMessages(
        model: String,
        system: String,
        messages: JSONArray,
        maxTokens: Int
    ): Flow<String> = callbackFlow {
        val call = try {
            val req = makeRequest(model, system, messages, maxTokens, stream = true)
            httpClient.newCall(req)
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        try {
            call.execute().use { resp ->
                if (resp.code != 200) {
                    val errBody = resp.body?.string().orEmpty()
                    throw LLMException.Http(resp.code, apiMessage(errBody))
                }
                val source = resp.body?.source() ?: throw LLMException.BadResponse()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val obj = try {
                        JSONObject(payload)
                    } catch (_: Exception) {
                        continue
                    }
                    when (obj.optString("type")) {
                        "content_block_delta" -> {
                            val text = obj.optJSONObject("delta")?.optString("text").orEmpty()
                            if (text.isNotEmpty()) trySend(text)
                        }
                        "error" -> {
                            val msg = obj.optJSONObject("error")?.optString("message") ?: "bilinmeyen hata"
                            throw LLMException.Http(0, msg)
                        }
                    }
                }
            }
            close()
        } catch (e: Exception) {
            close(e)
        }

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun complete(model: String, system: String, user: String, maxTokens: Int = 4096): String =
        withContext(Dispatchers.IO) {
            val msgs = JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", user)
            })
            val req = makeRequest(model, system, msgs, maxTokens, stream = false)
            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.code != 200) {
                    throw LLMException.Http(resp.code, apiMessage(bodyStr))
                }
                val obj = JSONObject(bodyStr)
                val content = obj.optJSONArray("content") ?: throw LLMException.BadResponse()
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    val t = item.optString("text")
                    if (t.isNotEmpty()) sb.append(t)
                }
                sb.toString()
            }
        }
}

object GeminiClient {
    private fun makeRequest(
        model: String,
        system: String,
        contents: JSONArray,
        maxTokens: Int,
        stream: Boolean
    ): Request {
        val key = KeyStore.get(KeyStore.API_KEY_GEMINI) ?: throw LLMException.MissingKey()
        val name = if (model.startsWith("models/")) model.removePrefix("models/") else model
        val method = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$name:$method"
        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().put("maxOutputTokens", min(maxTokens * 4, 32768)))
            if (system.isNotEmpty()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
                )
            }
        }
        return Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("x-goog-api-key", key)
            .header("content-type", "application/json")
            .build()
    }

    private fun extractText(obj: JSONObject): String {
        val fb = obj.optJSONObject("promptFeedback")
        val reason = fb?.optString("blockReason")
        if (!reason.isNullOrBlank()) {
            throw LLMException.Http(0, "İstek Gemini tarafından engellendi ($reason).")
        }
        val cands = obj.optJSONArray("candidates") ?: return ""
        val first = cands.optJSONObject(0) ?: return ""
        val content = first.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val t = part.optString("text")
            if (t.isNotEmpty()) sb.append(t)
        }
        return sb.toString()
    }

    fun stream(model: String, system: String, user: String, maxTokens: Int = 2048): Flow<String> {
        val contents = JSONArray().put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", user)))
            }
        )
        return streamContents(model, system, contents, maxTokens)
    }

    fun streamChat(
        model: String,
        system: String,
        context: String,
        turns: List<ChatTurn>,
        maxTokens: Int = 2048
    ): Flow<String> {
        val contents = JSONArray()
        turns.forEachIndexed { i, t ->
            if (t.role == ChatTurn.Role.user && i == 0) {
                contents.put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", context))
                                .put(JSONObject().put("text", t.text))
                        )
                    }
                )
            } else {
                contents.put(
                    JSONObject().apply {
                        put("role", if (t.role == ChatTurn.Role.user) "user" else "model")
                        put("parts", JSONArray().put(JSONObject().put("text", t.text)))
                    }
                )
            }
        }
        return streamContents(model, system, contents, maxTokens)
    }

    private fun streamContents(
        model: String,
        system: String,
        contents: JSONArray,
        maxTokens: Int
    ): Flow<String> = callbackFlow {
        val call = try {
            val req = makeRequest(model, system, contents, maxTokens, stream = true)
            ClaudeClient.httpClient.newCall(req)
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        try {
            call.execute().use { resp ->
                if (resp.code != 200) {
                    val errBody = resp.body?.string().orEmpty()
                    throw LLMException.Http(resp.code, ClaudeClient.apiMessage(errBody))
                }
                val source = resp.body?.source() ?: throw LLMException.BadResponse()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    val obj = try {
                        JSONObject(payload)
                    } catch (_: Exception) {
                        continue
                    }
                    val chunk = extractText(obj)
                    if (chunk.isNotEmpty()) trySend(chunk)
                }
            }
            close()
        } catch (e: Exception) {
            close(e)
        }

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun complete(model: String, system: String, user: String, maxTokens: Int = 4096): String =
        withContext(Dispatchers.IO) {
            val contents = JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", user)))
                }
            )
            val req = makeRequest(model, system, contents, maxTokens, stream = false)
            ClaudeClient.httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.code != 200) {
                    throw LLMException.Http(resp.code, ClaudeClient.apiMessage(bodyStr))
                }
                val obj = JSONObject(bodyStr)
                extractText(obj)
            }
        }
}

object LLMClient {
    fun stream(c: LLMConfig, system: String, user: String, maxTokens: Int = 2048): Flow<String> =
        when (c.provider) {
            LLMProvider.claude -> ClaudeClient.stream(c.model, system, user, maxTokens)
            LLMProvider.gemini -> GeminiClient.stream(c.model, system, user, maxTokens)
        }

    fun streamChat(
        c: LLMConfig,
        system: String,
        context: String,
        turns: List<ChatTurn>,
        maxTokens: Int = 2048
    ): Flow<String> = when (c.provider) {
        LLMProvider.claude -> ClaudeClient.streamChat(c.model, system, context, turns, maxTokens)
        LLMProvider.gemini -> GeminiClient.streamChat(c.model, system, context, turns, maxTokens)
    }

    suspend fun complete(c: LLMConfig, system: String, user: String, maxTokens: Int = 4096): String =
        when (c.provider) {
            LLMProvider.claude -> ClaudeClient.complete(c.model, system, user, maxTokens)
            LLMProvider.gemini -> GeminiClient.complete(c.model, system, user, maxTokens)
        }
}

/**
 * Aynı isteği iki kez ödememek için disk önbelleği (llm-cache.json).
 */
object ResponseCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var store: MutableMap<String, String> = mutableMapOf()
    private val file: File get() = File(Paths.base, "llm-cache.json")
    private var loaded = false

    private fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                if (file.exists()) {
                    store = json.decodeFromString<Map<String, String>>(file.readText(Charsets.UTF_8)).toMutableMap()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun key(vararg parts: String): String {
        val joined = parts.joinToString("\u001F")
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(joined.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): String? {
        ensureLoaded()
        return synchronized(lock) { store[key] }
    }

    fun set(key: String, value: String) {
        ensureLoaded()
        val snapshot: Map<String, String> = synchronized(lock) {
            store[key] = value
            store.toMap()
        }
        scope.launch {
            try {
                val tmp = File(Paths.base, "llm-cache.json.tmp")
                tmp.writeText(json.encodeToString(snapshot), Charsets.UTF_8)
                tmp.renameTo(file)
            } catch (_: Exception) {
            }
        }
    }
}

