package com.kursat.reader.ui.shared

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kursat.reader.ai.KeyStore
import com.kursat.reader.ai.LLMClient
import com.kursat.reader.ai.ModelCatalog
import com.kursat.reader.model.LLMTier
import com.kursat.reader.model.LLMProvider
import com.kursat.reader.model.ReaderSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: ReaderSettings,
    onRefreshNotifications: () -> Unit = {},
    syncSection: @Composable () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by settings.state.collectAsState()
    val catalog = remember { ModelCatalog(context) }
    val liveModels by catalog.live.collectAsState()

    var claudeKey by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf<LLMProvider?>(null) }

    LaunchedEffect(Unit) {
        claudeKey = KeyStore.get(KeyStore.API_KEY_CLAUDE).orEmpty()
        geminiKey = KeyStore.get(KeyStore.API_KEY_GEMINI).orEmpty()
    }

    fun saveKeys() {
        KeyStore.set(claudeKey.trim(), KeyStore.API_KEY_CLAUDE)
        KeyStore.set(geminiKey.trim(), KeyStore.API_KEY_GEMINI)
    }

    ModalBottomSheet(
        onDismissRequest = {
            saveKeys()
            onDismiss()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ayarlar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = {
                        saveKeys()
                        onDismiss()
                    }
                ) {
                    Text("Bitti", fontWeight = FontWeight.SemiBold)
                }
            }

            // Sağlayıcı seçimi
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Çeviri ve özet için kullanılacak model", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LLMProvider.entries.forEach { p ->
                            FilterChip(
                                selected = state.provider == p,
                                onClick = { settings.update { it.copy(provider = p) } },
                                label = { Text(p.title) }
                            )
                        }
                    }
                    Text(
                        "İstediğiniz zaman değiştirebilirsiniz. Önbellek sağlayıcıya göre ayrı tutulur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Claude (Anthropic)
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Claude (Anthropic)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = claudeKey,
                        onValueChange = { claudeKey = it },
                        placeholder = { Text("sk-ant-…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    ModelField(
                        title = "Hızlı model",
                        text = state.fastModel,
                        onTextChange = { m -> settings.update { it.copy(fastModel = m) } },
                        options = remember(liveModels) { catalog.models(LLMProvider.claude) }
                    )
                    ModelField(
                        title = "Güçlü model",
                        text = state.smartModel,
                        onTextChange = { m -> settings.update { it.copy(smartModel = m) } },
                        options = remember(liveModels) { catalog.models(LLMProvider.claude) }
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                refreshing = LLMProvider.claude
                                try {
                                    catalog.refresh(LLMProvider.claude, claudeKey)
                                    status = "Claude: ${catalog.models(LLMProvider.claude).size} model listelendi."
                                } catch (e: Exception) {
                                    status = e.message
                                } finally {
                                    refreshing = null
                                }
                            }
                        },
                        enabled = claudeKey.isNotBlank()
                    ) {
                        Text(if (catalog.isLive(LLMProvider.claude)) "Güncel modelleri yenile" else "Güncel modelleri getir")
                        if (refreshing == LLMProvider.claude) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    Text(
                        "Model adını elle yazabilir veya ⇅ ile listeden seçebilirsiniz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Gemini (Google)
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Gemini (Google)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it },
                        placeholder = { Text("AIza…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    ModelField(
                        title = "Hızlı model",
                        text = state.geminiFastModel,
                        onTextChange = { m -> settings.update { it.copy(geminiFastModel = m) } },
                        options = remember(liveModels) { catalog.models(LLMProvider.gemini) }
                    )
                    ModelField(
                        title = "Güçlü model",
                        text = state.geminiSmartModel,
                        onTextChange = { m -> settings.update { it.copy(geminiSmartModel = m) } },
                        options = remember(liveModels) { catalog.models(LLMProvider.gemini) }
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                refreshing = LLMProvider.gemini
                                try {
                                    catalog.refresh(LLMProvider.gemini, geminiKey)
                                    status = "Gemini: ${catalog.models(LLMProvider.gemini).size} model listelendi."
                                } catch (e: Exception) {
                                    status = e.message
                                } finally {
                                    refreshing = null
                                }
                            }
                        },
                        enabled = geminiKey.isNotBlank()
                    ) {
                        Text(if (catalog.isLive(LLMProvider.gemini)) "Güncel modelleri yenile" else "Güncel modelleri getir")
                        if (refreshing == LLMProvider.gemini) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    Text(
                        "Anahtarı Google AI Studio'dan alabilirsiniz. Liste eskiyse \"Güncel modelleri getir\" ile yenileyin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Diller ve seslendirme
            LanguageSettingsCard(settings = settings, state = state)

            // Kaydet ve bağlantıyı test et
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                saveKeys()
                                testing = true
                                try {
                                    val reply = LLMClient.complete(
                                        settings.current.config(LLMTier.fast),
                                        system = "",
                                        user = "Reply with exactly: OK",
                                        maxTokens = 16
                                    ).trim()
                                    status = "${state.provider.title} bağlantısı başarılı (${reply.ifEmpty { "boş yanıt" }})."
                                } catch (e: Exception) {
                                    status = e.message
                                } finally {
                                    testing = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Kaydet ve ${state.provider.title} bağlantısını test et")
                        if (testing) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        }
                    }
                    status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "Hızlı model: kelime, paragraf ve sayfa çevirisi. Güçlü model: özetler. Anahtarlar yalnızca bu cihazın şifreli deposunda saklanır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Hedef ve hatırlatmalar
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Hedef ve hatırlatmalar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Günlük hedef")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    settings.update { it.copy(dailyGoalMinutes = (it.dailyGoalMinutes - 5).coerceAtLeast(0)) }
                                    onRefreshNotifications()
                                }
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Azalt")
                            }
                            Text(
                                text = if (state.dailyGoalMinutes == 0) "Kapalı" else "${state.dailyGoalMinutes} dk",
                                fontWeight = FontWeight.Medium
                            )
                            IconButton(
                                onClick = {
                                    settings.update { it.copy(dailyGoalMinutes = (it.dailyGoalMinutes + 5).coerceAtMost(240)) }
                                    onRefreshNotifications()
                                }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Artır")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Okuma hatırlatması")
                        Switch(
                            checked = state.reminderEnabled,
                            onCheckedChange = { on ->
                                settings.update { it.copy(reminderEnabled = on) }
                                onRefreshNotifications()
                            }
                        )
                    }

                    if (state.reminderEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Saat")
                            OutlinedButton(
                                onClick = {
                                    TimePickerDialog(
                                        context,
                                        { _, h, m ->
                                            settings.update { it.copy(reminderHour = h, reminderMinute = m) }
                                            onRefreshNotifications()
                                        },
                                        state.reminderHour,
                                        state.reminderMinute,
                                        true
                                    ).show()
                                }
                            ) {
                                Text(String.format("%02d:%02d", state.reminderHour, state.reminderMinute))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Kelime tekrarı hatırlatması")
                        Switch(
                            checked = state.vocabReminderEnabled,
                            onCheckedChange = { on ->
                                settings.update { it.copy(vocabReminderEnabled = on) }
                                onRefreshNotifications()
                            }
                        )
                    }

                    Text(
                        "Hedefinizi tamamladığınız günün hatırlatması gönderilmez. Kelime hatırlatması her akşam 18:30'da, tekrarı gelmiş kart varsa gelir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Senkronizasyon (Google Drive)
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Senkronizasyon (Google Drive)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    syncSection()
                    Text(
                        "İlerleme, vurgular, notlar, kelime defteri, okuma istatistikleri ve okuma ayarları Drive'ınızdaki gizli uygulama klasörüne yazılır. Kitap dosyaları ve API anahtarları senkronlanmaz; kitabı her cihaza ayrıca ekleyin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Okuma görünümü
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Okuma görünümü",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp)
                    )
                    ThemePanel(settings = settings, showsTextControls = true, scrollable = false)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ModelField(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    options: List<String>
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("model adı") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.UnfoldMore, contentDescription = "Model seç")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        trailingIcon = {
                            if (m == text) Icon(Icons.Default.Check, contentDescription = null)
                        },
                        onClick = {
                            onTextChange(m)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}



private val geminiVoices = listOf("Kore", "Puck", "Charon", "Aoede", "Fenrir", "Leda", "Orus", "Zephyr", "Sulafat", "Gacrux", "Algieba", "Achernar")

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(label(selected)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(label(o)) }, onClick = { onSelect(o); open = false })
                }
            }
        }
    }
}

/** Hedef dil, seslendirme dili ve ses motoru ayarları. */
@Composable
private fun LanguageSettingsCard(settings: ReaderSettings, state: com.kursat.reader.model.ReaderSettingsState) {
    val context = LocalContext.current
    var cacheBytes by remember { mutableStateOf(com.kursat.reader.speech.GeminiTTS.cacheSize()) }
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Diller ve seslendirme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ChoiceRow(
                title = "Hedef dil",
                options = com.kursat.reader.model.TargetLanguage.entries,
                selected = state.targetLanguage,
                label = { it.title },
                onSelect = { l -> settings.update { it.copy(targetLanguage = l) } }
            )
            ChoiceRow(
                title = "Seslendirme dili",
                options = listOf("") + com.kursat.reader.model.TargetLanguage.entries.map { it.code },
                selected = state.ttsLanguageCode,
                label = { c ->
                    if (c.isEmpty()) "Hedefi izle (${state.targetLanguage.title})"
                    else com.kursat.reader.model.TargetLanguage.fromCode(c)?.title ?: c
                },
                onSelect = { c -> settings.update { it.copy(ttsLanguageCode = c) } }
            )
            ChoiceRow(
                title = "Ses motoru",
                options = com.kursat.reader.model.TTSEngine.entries,
                selected = state.ttsEngine,
                label = { it.title },
                onSelect = { e -> settings.update { it.copy(ttsEngine = e) } }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Çeviriyi sayfada da göster", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.ttsShowInPage,
                    onCheckedChange = { v -> settings.update { it.copy(ttsShowInPage = v) } }
                )
            }
            if (state.ttsEngine == com.kursat.reader.model.TTSEngine.gemini) {
                ChoiceRow(
                    title = "Gemini sesi",
                    options = geminiVoices,
                    selected = state.geminiVoice,
                    label = { it },
                    onSelect = { v -> settings.update { it.copy(geminiVoice = v) } }
                )
                OutlinedTextField(
                    value = state.geminiTTSModel,
                    onValueChange = { m -> settings.update { it.copy(geminiTTSModel = m.trim()) } },
                    label = { Text("Ses modeli") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (state.ttsBudgetUSD <= 0.0) "Aylık bütçe: sınırsız" else "Aylık bütçe: $${state.ttsBudgetUSD.toInt()}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { settings.update { it.copy(ttsBudgetUSD = (it.ttsBudgetUSD - 5).coerceAtLeast(0.0)) } }) {
                            Icon(Icons.Default.Remove, contentDescription = "Azalt")
                        }
                        IconButton(onClick = { settings.update { it.copy(ttsBudgetUSD = (it.ttsBudgetUSD + 5).coerceAtMost(100.0)) } }) {
                            Icon(Icons.Default.Add, contentDescription = "Artır")
                        }
                    }
                }
                val spent = com.kursat.reader.speech.TTSUsage.dollarsThisMonth(context, state.geminiTTSModel)
                val mins = (com.kursat.reader.speech.TTSUsage.secondsThisMonth(context) / 60).toInt()
                Text(
                    "Bu ay üretilen ses: ${mins} dk · tahmini $${"%.2f".format(spent)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Ses önbelleği: ${android.text.format.Formatter.formatShortFileSize(context, cacheBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        com.kursat.reader.speech.GeminiTTS.clearCache()
                        cacheBytes = com.kursat.reader.speech.GeminiTTS.cacheSize()
                    }) { Text("Temizle", color = MaterialTheme.colorScheme.error) }
                }
            }
            Text(
                "Hedef dil; sözlük, açıklama, çeviri ve özetlerin dilidir. Seslendirme dili farklıysa metin okunmadan önce o dile çevrilir. " +
                    "Sistem sesi ücretsizdir; Gemini sesi daha doğaldır, yalnızca okunan paragraflar üretilir ve cihazda saklanır.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
