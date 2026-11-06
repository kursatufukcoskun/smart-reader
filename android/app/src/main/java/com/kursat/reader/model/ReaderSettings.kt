package com.kursat.reader.model

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

fun colorFromHex(hex: String): Color {
    val clean = hex.trim().removePrefix("#")
    val expanded = if (clean.length == 3) {
        clean.map { "$it$it" }.joinToString("")
    } else clean
    val v = expanded.toLongOrNull(16) ?: 0xFFFFFFL
    return Color(
        red = ((v shr 16) and 0xFF).toInt(),
        green = ((v shr 8) and 0xFF).toInt(),
        blue = (v and 0xFF).toInt(),
        alpha = 0xFF
    )
}

@Serializable
enum class ReaderTheme {
    @SerialName("light") light,
    @SerialName("sepia") sepia,
    @SerialName("dark") dark,
    @SerialName("black") black,
    @SerialName("eink") eink;

    val title: String
        get() = when (this) {
            light -> "Açık"
            sepia -> "Sepya"
            dark -> "Koyu"
            black -> "Siyah"
            eink -> "E-ink"
        }

    val bgHex: String
        get() = when (this) {
            light -> "#FFFFFF"
            sepia -> "#F4ECD8"
            dark -> "#1C1C1E"
            black -> "#000000"
            eink -> "#E8E7E2"
        }

    val textHex: String
        get() = when (this) {
            light -> "#1A1A1A"
            sepia -> "#3B2F1E"
            dark -> "#D8D8DA"
            black -> "#B8B8BC"
            eink -> "#161616"
        }

    val linkHex: String
        get() = when (this) {
            light -> "#0A66C2"
            sepia -> "#8A4B08"
            dark -> "#7DB7FF"
            black -> "#6AA8F5"
            eink -> "#000000"
        }

    val translationHex: String
        get() = when (this) {
            light -> "#9A3412"
            sepia -> "#7C2D12"
            dark -> "#FDBA74"
            black -> "#FDBA74"
            eink -> "#2E2E2E"
        }

    val isDark: Boolean get() = this == dark || this == black
    val isEink: Boolean get() = this == eink
    val color: Color get() = colorFromHex(bgHex)
    val textColor: Color get() = colorFromHex(textHex)
}

@Serializable
enum class ReadingMode {
    @SerialName("scroll") scroll,
    @SerialName("paged") paged;

    val title: String
        get() = when (this) {
            scroll -> "Sonsuz kaydırma"
            paged -> "Sayfa sayfa"
        }
}

@Serializable
enum class TranslationLayout {
    @SerialName("below") below,
    @SerialName("side") side;

    val title: String get() = if (this == below) "Altında" else "Yan yana"
}

@Serializable
enum class ColumnMode {
    @SerialName("single") single,
    @SerialName("double") double;

    val title: String get() = if (this == single) "Tek kolon" else "Çift kolon"
}

/** Bookerly kişisel kullanım için iPad uygulamasındaki font dosyalarıyla birlikte gelir. */
@Serializable
enum class ReaderFont(val rawValue: String) {
    @SerialName("bookerly") bookerly("bookerly"),
    @SerialName("literata") literata("literata"),
    @SerialName("georgia") georgia("georgia"),
    @SerialName("palatino") palatino("palatino"),
    @SerialName("serif") serif("serif"),
    @SerialName("sans") sans("sans");

    val title: String
        get() = when (this) {
            bookerly -> "Bookerly"
            literata -> "Literata"
            georgia -> "Georgia"
            palatino -> "Palatino"
            serif -> "Noto Serif"
            sans -> "Roboto (Sans)"
        }

    val css: String
        get() = when (this) {
            bookerly -> "'BookerlyReader', 'LiterataReader', Georgia, 'Noto Serif', serif"
            literata -> "'LiterataReader', 'Literata', Georgia, 'Noto Serif', serif"
            georgia -> "Georgia, 'Noto Serif', 'Times New Roman', serif"
            palatino -> "Palatino, 'Palatino Linotype', 'Noto Serif', serif"
            serif -> "'Noto Serif', Georgia, serif"
            sans -> "Roboto, 'Noto Sans', sans-serif"
        }

    companion object {
        fun fromRaw(raw: String?): ReaderFont = when (raw) {
            "bookerly" -> bookerly
            "georgia" -> georgia
            "palatino" -> palatino
            "serif", "iowan", "newYork" -> serif
            "sans", "avenir" -> sans
            else -> literata
        }
    }
}

@Serializable
enum class HighlightColor {
    @SerialName("yellow") yellow,
    @SerialName("green") green,
    @SerialName("blue") blue,
    @SerialName("pink") pink,
    @SerialName("orange") orange;

    val title: String
        get() = when (this) {
            yellow -> "Sarı"
            green -> "Yeşil"
            blue -> "Mavi"
            pink -> "Pembe"
            orange -> "Turuncu"
        }

    val color: Color
        get() = when (this) {
            yellow -> Color(red = 1.0f, green = 0.84f, blue = 0.04f)
            green -> Color(red = 0.2f, green = 0.78f, blue = 0.35f)
            blue -> Color(red = 0.04f, green = 0.52f, blue = 1.0f)
            pink -> Color(red = 1.0f, green = 0.22f, blue = 0.37f)
            orange -> Color(red = 1.0f, green = 0.58f, blue = 0.0f)
        }
}

enum class LLMProvider(val rawValue: String) {
    claude("claude"),
    gemini("gemini");

    val title: String
        get() = when (this) {
            claude -> "Claude"
            gemini -> "Gemini"
        }
}

enum class LLMTier { fast, smart }

data class LLMConfig(
    val provider: LLMProvider,
    val model: String
) {
    /** Dilden bağımsız kimlik. */
    val baseID: String get() = "${provider.rawValue}:$model"
    /** Yanıtın dili hedef dile bağlı olduğundan önbellek anahtarına dil eklenir (Türkçe'de eski anahtar korunur). */
    val cacheID: String get() = baseID + LanguageState.target.cacheSuffix
}

data class ReaderSettingsState(
    val theme: ReaderTheme = ReaderTheme.sepia,
    val font: ReaderFont = ReaderFont.literata,
    val fontScale: Double = 1.1,
    val lineHeight: Double = 1.55,
    val margin: Double = 8.0,
    val provider: LLMProvider = LLMProvider.claude,
    val fastModel: String = "claude-haiku-5-5",
    val smartModel: String = "claude-sonnet-5-5",
    val geminiFastModel: String = "gemini-2.5-flash",
    val geminiSmartModel: String = "gemini-2.5-pro",
    val translationLayout: TranslationLayout = TranslationLayout.below,
    val dailyGoalMinutes: Int = 20,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val vocabReminderEnabled: Boolean = false,
    val columns: ColumnMode = ColumnMode.single,
    val highlightColor: HighlightColor = HighlightColor.yellow,
    val readingMode: ReadingMode = ReadingMode.scroll,
    val edgeTap: Boolean = false,
    val warmth: Double = 0.0,
    /** Çeviri, sözlük ve açıklamaların dili */
    val targetLanguage: TargetLanguage = TargetLanguage.tr,
    /** Seslendirme dili; boşsa hedef dili izler */
    val ttsLanguageCode: String = "",
    val ttsEngine: TTSEngine = TTSEngine.system,
    /** Konuşma hızı çarpanı (1.0 = normal) */
    val ttsRate: Double = 1.0,
    val geminiVoice: String = "Kore",
    val geminiTTSModel: String = "gemini-3.8-flash-tts",
    /** Aylık Gemini ses bütçesi (USD), 0 = sınırsız */
    val ttsBudgetUSD: Double = 10.0,
    /** Çevrilerek okunan paragrafın çevirisini sayfaya da ekle (sayfa düzenini kaydırır, varsayılan kapalı) */
    val ttsShowInPage: Boolean = false
) {
    /** Seslendirmenin gerçek dili. */
    val ttsLanguage: TargetLanguage get() = TargetLanguage.fromCode(ttsLanguageCode) ?: targetLanguage

    fun config(tier: LLMTier): LLMConfig = when (provider to tier) {
        LLMProvider.claude to LLMTier.fast -> LLMConfig(LLMProvider.claude, fastModel)
        LLMProvider.claude to LLMTier.smart -> LLMConfig(LLMProvider.claude, smartModel)
        LLMProvider.gemini to LLMTier.fast -> LLMConfig(LLMProvider.gemini, geminiFastModel)
        LLMProvider.gemini to LLMTier.smart -> LLMConfig(LLMProvider.gemini, geminiSmartModel)
        else -> LLMConfig(LLMProvider.claude, fastModel)
    }

    val css: String
        get() {
            val t = theme
            val imgFilter = when {
                t.isEink -> "img, svg { filter: grayscale(1) contrast(1.2) !important; }"
                t.isDark -> "img { filter: brightness(0.85) contrast(1.05); }"
                else -> ""
            }
            val noteLine = if (t.isDark) "rgba(255,255,255,0.6)" else "rgba(0,0,0,0.45)"
            val einkCSS = if (t.isEink) """
                * { transition: none !important; animation: none !important; scroll-behavior: auto !important; }
                body a, body a * { text-decoration: underline !important; }
                ::selection { background: rgba(0, 0, 0, 0.25) !important; }
            """.trimIndent() else ""
            val fontFace = when (font) {
                ReaderFont.literata -> FontInstaller.literataCSS
                ReaderFont.bookerly -> FontInstaller.bookerlyCSS + FontInstaller.literataCSS
                else -> ""
            }
            val speakBg = if (t.isDark) "rgba(125,183,255,0.20)" else "rgba(255,196,0,0.24)"
            val px = (18.0 * fontScale).roundToInt()
            return """
                $fontFace
                html { -webkit-text-size-adjust: none !important; font-size: ${px}px !important; background: ${t.bgHex} !important; }
                body { background: ${t.bgHex} !important; color: ${t.textHex} !important;
                       font-family: ${font.css} !important; font-size: 1rem !important;
                       line-height: $lineHeight !important;
                       margin: 0 !important; padding: 1.5rem ${margin}% 5rem !important; max-width: none !important; }
                body * { color: inherit !important; background-color: transparent !important;
                         font-family: inherit !important; line-height: inherit !important; }
                body a, body a * { color: ${t.linkHex} !important; }
                p { margin: 0 0 0.9em; }
                img, svg, video { max-width: 100% !important; height: auto !important; }
                $imgFilter
                ::selection { background: rgba(255, 190, 60, 0.45); }
                body .llm-tr { color: ${t.translationHex} !important; font-size: 0.93em; margin: -0.2em 0 1.1em; padding-left: 0.8em;
                               border-left: 3px solid ${t.translationHex}; font-family: Roboto, sans-serif !important; }
                .llm-hide .llm-tr { display: none !important; }
                .llm-row { display: grid; grid-template-columns: 1fr 1fr; gap: 1.4em; align-items: start; margin: 0 0 1.1em; }
                .llm-row > :first-child { margin: 0 !important; }
                body .llm-row > .llm-tr { margin: 0; }
                .llm-hide .llm-row { display: block; }
                body .llm-speak { background-color: $speakBg !important; border-radius: 4px; box-shadow: 0 0 0 4px $speakBg; }
                .llm-hl { border-radius: 2px; }
                body .llm-hl[data-color="yellow"] { background-color: rgba(255,214,10,0.42) !important; }
                body .llm-hl[data-color="green"] { background-color: rgba(52,199,89,0.38) !important; }
                body .llm-hl[data-color="blue"] { background-color: rgba(10,132,255,0.32) !important; }
                body .llm-hl[data-color="pink"] { background-color: rgba(255,55,95,0.32) !important; }
                body .llm-hl[data-color="orange"] { background-color: rgba(255,149,0,0.40) !important; }
                body .llm-hl[data-note="1"] { border-bottom: 2px solid $noteLine; }
                $einkCSS
            """.trimIndent()
        }
}

class ReaderSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load()).also { LanguageState.target = it.value.targetLanguage }
    val state: StateFlow<ReaderSettingsState> = _state.asStateFlow()

    val current: ReaderSettingsState get() = _state.value

    var onSettingsChanged: (() -> Unit)? = null
    private var applyingRemote = false

    private fun load(): ReaderSettingsState {
        val theme = ReaderTheme.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ReaderTheme.sepia
        val font = ReaderFont.fromRaw(prefs.getString("font", null))
        val fs = prefs.getFloat("fontScale", 1.1f).toDouble().let { if (it <= 0.0) 1.1 else it }
        val lh = prefs.getFloat("lineHeight", 1.55f).toDouble().let { if (it <= 0.0) 1.55 else it }
        val m = prefs.getFloat("margin", 8.0f).toDouble().let { if (it <= 0.0) 8.0 else it }
        val fastModel = prefs.getString("fastModel", "claude-haiku-5-5") ?: "claude-haiku-5-5"
        val smartModel = prefs.getString("smartModel", "claude-sonnet-5-5") ?: "claude-sonnet-5-5"
        val provider = LLMProvider.entries.firstOrNull { it.rawValue == prefs.getString("provider", null) } ?: LLMProvider.claude
        val geminiFast = prefs.getString("geminiFastModel", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        val geminiSmart = prefs.getString("geminiSmartModel", "gemini-2.5-pro") ?: "gemini-2.5-pro"
        val warmth = prefs.getFloat("warmth", 0f).toDouble()
        val readingMode = ReadingMode.entries.firstOrNull { it.name == prefs.getString("readingMode", null) } ?: ReadingMode.scroll
        val edgeTap = prefs.getBoolean("edgeTap", false)
        val columns = ColumnMode.entries.firstOrNull { it.name == prefs.getString("columns", null) } ?: ColumnMode.single
        val translationLayout = TranslationLayout.entries.firstOrNull { it.name == prefs.getString("translationLayout", null) } ?: TranslationLayout.below
        val dailyGoalMinutes = if (prefs.contains("dailyGoalMinutes")) prefs.getInt("dailyGoalMinutes", 20) else 20
        val reminderEnabled = prefs.getBoolean("reminderEnabled", false)
        val reminderHour = prefs.getInt("reminderHour", 20)
        val reminderMinute = prefs.getInt("reminderMinute", 0)
        val vocabReminderEnabled = prefs.getBoolean("vocabReminderEnabled", false)
        val highlightColor = HighlightColor.entries.firstOrNull { it.name == prefs.getString("highlightColor", null) } ?: HighlightColor.yellow

        return ReaderSettingsState(
            theme = theme,
            font = font,
            fontScale = fs,
            lineHeight = lh,
            margin = m,
            provider = provider,
            fastModel = fastModel,
            smartModel = smartModel,
            geminiFastModel = geminiFast,
            geminiSmartModel = geminiSmart,
            translationLayout = translationLayout,
            dailyGoalMinutes = dailyGoalMinutes,
            reminderEnabled = reminderEnabled,
            reminderHour = reminderHour,
            reminderMinute = reminderMinute,
            vocabReminderEnabled = vocabReminderEnabled,
            columns = columns,
            highlightColor = highlightColor,
            readingMode = readingMode,
            edgeTap = edgeTap,
            warmth = warmth,
            targetLanguage = TargetLanguage.fromCode(prefs.getString("targetLanguage", null)) ?: TargetLanguage.tr,
            ttsLanguageCode = prefs.getString("ttsLanguageCode", "") ?: "",
            ttsEngine = TTSEngine.entries.firstOrNull { it.name == prefs.getString("ttsEngine", null) } ?: TTSEngine.system,
            ttsRate = prefs.getFloat("ttsRate", 1.0f).toDouble().let { if (it <= 0.0) 1.0 else it },
            geminiVoice = prefs.getString("geminiVoice", "Kore") ?: "Kore",
            geminiTTSModel = prefs.getString("geminiTTSModel", "gemini-3.8-flash-tts") ?: "gemini-3.8-flash-tts",
            ttsBudgetUSD = prefs.getFloat("ttsBudgetUSD", 10f).toDouble(),
            ttsShowInPage = prefs.getBoolean("ttsShowInPage", false)
        )
    }

    fun update(transform: (ReaderSettingsState) -> ReaderSettingsState) {
        val old = _state.value
        val next = transform(old)
        if (next == old) return
        LanguageState.target = next.targetLanguage
        _state.value = next
        saveToPrefs(next)
        // Yalnızca vurgu rengi cihazlar arası senkronlanır; yazı boyutu, tema vb. cihaza özeldir.
        if (!applyingRemote && next.highlightColor != old.highlightColor) {
            prefs.edit().putString("settingsUpdatedAt", IsoDate.now()).apply()
            onSettingsChanged?.invoke()
        }
    }

    fun applyFromRemote(stamp: String, transform: (ReaderSettingsState) -> ReaderSettingsState) {
        applyingRemote = true
        try {
            val next = transform(_state.value)
            _state.value = next
            saveToPrefs(next)
            prefs.edit().putString("settingsUpdatedAt", stamp).apply()
        } finally {
            applyingRemote = false
        }
    }

    var settingsStamp: String?
        get() = prefs.getString("settingsUpdatedAt", null)
        set(value) { prefs.edit().putString("settingsUpdatedAt", value).apply() }

    private fun saveToPrefs(s: ReaderSettingsState) {
        prefs.edit()
            .putString("theme", s.theme.name)
            .putString("font", s.font.rawValue)
            .putFloat("fontScale", s.fontScale.toFloat())
            .putFloat("lineHeight", s.lineHeight.toFloat())
            .putFloat("margin", s.margin.toFloat())
            .putString("provider", s.provider.rawValue)
            .putString("fastModel", s.fastModel)
            .putString("smartModel", s.smartModel)
            .putString("geminiFastModel", s.geminiFastModel)
            .putString("geminiSmartModel", s.geminiSmartModel)
            .putString("translationLayout", s.translationLayout.name)
            .putInt("dailyGoalMinutes", s.dailyGoalMinutes)
            .putBoolean("reminderEnabled", s.reminderEnabled)
            .putInt("reminderHour", s.reminderHour)
            .putInt("reminderMinute", s.reminderMinute)
            .putBoolean("vocabReminderEnabled", s.vocabReminderEnabled)
            .putString("columns", s.columns.name)
            .putString("highlightColor", s.highlightColor.name)
            .putString("readingMode", s.readingMode.name)
            .putBoolean("edgeTap", s.edgeTap)
            .putFloat("warmth", s.warmth.toFloat())
            .putString("targetLanguage", s.targetLanguage.code)
            .putString("ttsLanguageCode", s.ttsLanguageCode)
            .putString("ttsEngine", s.ttsEngine.name)
            .putFloat("ttsRate", s.ttsRate.toFloat())
            .putString("geminiVoice", s.geminiVoice)
            .putString("geminiTTSModel", s.geminiTTSModel)
            .putFloat("ttsBudgetUSD", s.ttsBudgetUSD.toFloat())
            .putBoolean("ttsShowInPage", s.ttsShowInPage)
            .apply()
    }
}

object FontInstaller {
    private val dir: java.io.File
        get() = java.io.File(Paths.base, "Fonts").apply { mkdirs() }

    fun install(context: Context) {
        try {
            val files = context.assets.list("fonts") ?: return
            for (name in files) {
                val dest = java.io.File(dir, name)
                if (dest.exists()) continue
                context.assets.open("fonts/$name").use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
            }
        } catch (_: Exception) {
        }
    }

    val bookerlyCSS: String
        get() {
            fun face(file: String, w: Int, style: String): String {
                val f = java.io.File(dir, file)
                return if (f.exists()) "@font-face { font-family: 'BookerlyReader'; src: url('file://${f.absolutePath}'); font-weight: $w; font-style: $style; }\n" else ""
            }
            return face("BookerlyLCD_Rg.ttf", 400, "normal") + face("BookerlyLCD_Bd.ttf", 700, "normal") +
                face("BookerlyLCD_It.ttf", 400, "italic") + face("BookerlyLCD_BdIt.ttf", 700, "italic")
        }

    val literataCSS: String
        get() {
            val reg = java.io.File(dir, "Literata-Regular.ttf")
            if (!reg.exists()) return ""
            return "@font-face { font-family: 'LiterataReader'; src: url('file://${reg.absolutePath}'); font-weight: 200 900; font-style: normal; }"
        }
}

