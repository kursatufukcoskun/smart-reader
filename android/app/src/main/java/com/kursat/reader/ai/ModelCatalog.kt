package com.kursat.reader.ai

import android.content.Context
import android.content.SharedPreferences
import com.kursat.reader.model.LLMProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class ModelCatalog(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    private val _live = MutableStateFlow<Map<LLMProvider, List<String>>>(loadSaved())
    val live: StateFlow<Map<LLMProvider, List<String>>> = _live.asStateFlow()

    companion object {
        val presets: Map<LLMProvider, List<String>> = mapOf(
            LLMProvider.claude to listOf(
                "claude-haiku-5-5",
                "claude-sonnet-5-5",
                "claude-opus-5-5",
                "claude-fable-5-1"
            ),
            LLMProvider.gemini to listOf(
                "gemini-2.5-flash-lite",
                "gemini-2.5-flash",
                "gemini-2.5-pro"
            )
        )
    }

    private fun loadSaved(): Map<LLMProvider, List<String>> {
        val map = mutableMapOf<LLMProvider, List<String>>()
        for (p in LLMProvider.entries) {
            val saved = prefs.getString("models.${p.rawValue}", null)
                ?.split(",")
                ?.filter { it.isNotBlank() }
            if (!saved.isNullOrEmpty()) {
                map[p] = saved
            }
        }
        return map
    }

    fun models(p: LLMProvider): List<String> {
        val l = _live.value[p]
        if (!l.isNullOrEmpty()) return l
        return presets[p].orEmpty()
    }

    fun isLive(p: LLMProvider): Boolean = !_live.value[p].isNullOrEmpty()

    suspend fun refresh(p: LLMProvider, keyRaw: String) = withContext(Dispatchers.IO) {
        val key = keyRaw.trim()
        if (key.isEmpty()) throw LLMException.MissingKey()

        val req = when (p) {
            LLMProvider.claude -> Request.Builder()
                .url("https://api.anthropic.com/v1/models?limit=100")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .build()
            LLMProvider.gemini -> Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .header("x-goog-api-key", key)
                .build()
        }

        ClaudeClient.httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (resp.code != 200) {
                throw LLMException.Http(resp.code, ClaudeClient.apiMessage(bodyStr))
            }
            val obj = JSONObject(bodyStr)
            val ids = mutableListOf<String>()
            when (p) {
                LLMProvider.claude -> {
                    val data = obj.optJSONArray("data")
                    if (data != null) {
                        for (i in 0 until data.length()) {
                            val id = data.optJSONObject(i)?.optString("id")
                            if (!id.isNullOrBlank()) ids.add(id)
                        }
                    }
                }
                LLMProvider.gemini -> {
                    val models = obj.optJSONArray("models")
                    if (models != null) {
                        for (i in 0 until models.length()) {
                            val m = models.optJSONObject(i) ?: continue
                            val name = m.optString("name")
                            val methods = m.optJSONArray("supportedGenerationMethods")
                            var supportsGenerate = false
                            if (methods != null) {
                                for (j in 0 until methods.length()) {
                                    if (methods.optString(j) == "generateContent") {
                                        supportsGenerate = true
                                        break
                                    }
                                }
                            }
                            if (name.startsWith("models/gemini") && supportsGenerate) {
                                ids.add(name.removePrefix("models/"))
                            }
                        }
                    }
                }
            }
            if (ids.isEmpty()) throw LLMException.BadResponse()
            withContext(Dispatchers.Main) {
                val next = _live.value.toMutableMap()
                next[p] = ids
                _live.value = next
                prefs.edit().putString("models.${p.rawValue}", ids.joinToString(",")).apply()
            }
        }
    }
}

