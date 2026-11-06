package com.kursat.reader.model

import java.util.Locale

/** Çeviri, sözlük ve seslendirme için seçilebilen diller. */
enum class TargetLanguage(val code: String, val title: String, val englishName: String, val tag: String) {
    tr("tr", "Türkçe", "Turkish", "tr-TR"),
    en("en", "İngilizce", "English", "en-US"),
    de("de", "Almanca", "German", "de-DE"),
    fr("fr", "Fransızca", "French", "fr-FR"),
    es("es", "İspanyolca", "Spanish", "es-ES"),
    it("it", "İtalyanca", "Italian", "it-IT"),
    pt("pt", "Portekizce", "Portuguese", "pt-BR"),
    nl("nl", "Felemenkçe", "Dutch", "nl-NL"),
    ru("ru", "Rusça", "Russian", "ru-RU"),
    ar("ar", "Arapça", "Arabic", "ar-SA"),
    ja("ja", "Japonca", "Japanese", "ja-JP"),
    ko("ko", "Korece", "Korean", "ko-KR"),
    zh("zh", "Çince", "Chinese", "zh-CN");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    /** Önbellek anahtarına eklenen ek. Türkçe'de boş (eski önbellek korunur). */
    val cacheSuffix: String get() = if (this == tr) "" else ":$code"

    companion object {
        fun fromCode(c: String?): TargetLanguage? = entries.firstOrNull { it.code == c }
    }
}

/** Seçili hedef dil; istemler ve önbellek anahtarları her iş parçacığından okur. */
object LanguageState {
    @Volatile var target: TargetLanguage = TargetLanguage.tr
}

enum class TTSEngine(val title: String) {
    system("Sistem sesi"),
    gemini("Gemini (doğal ses)")
}

/** Hafif dil tespiti: yazı sistemi + yaygın sözcük sayımı. Emin değilse null. */
object LanguageDetect {
    private val stop: Map<TargetLanguage, Set<String>> = mapOf(
        TargetLanguage.en to setOf("the", "and", "of", "to", "in", "that", "was", "he", "she", "it", "with", "for", "his", "her", "had", "you", "not", "but", "is", "on", "as", "they"),
        TargetLanguage.tr to setOf("ve", "bir", "bu", "için", "ile", "de", "da", "o", "ama", "çok", "gibi", "daha", "ne", "var", "ben", "sen", "değil", "olarak", "kadar", "her", "mi", "ki"),
        TargetLanguage.de to setOf("der", "die", "das", "und", "nicht", "ist", "ich", "er", "sie", "es", "zu", "mit", "von", "den", "ein", "eine", "auf", "dem", "sich", "für", "war"),
        TargetLanguage.fr to setOf("le", "la", "les", "et", "de", "des", "un", "une", "que", "il", "elle", "est", "pas", "pour", "dans", "qui", "je", "vous", "avec", "sur", "du"),
        TargetLanguage.es to setOf("el", "la", "los", "las", "y", "de", "que", "en", "un", "una", "es", "no", "se", "por", "con", "su", "para", "como", "pero", "lo", "al"),
        TargetLanguage.it to setOf("il", "lo", "la", "gli", "le", "e", "di", "che", "un", "una", "è", "non", "per", "con", "si", "del", "della", "in", "ma", "sono", "come"),
        TargetLanguage.pt to setOf("o", "a", "os", "as", "e", "de", "que", "um", "uma", "não", "em", "para", "com", "se", "por", "mais", "mas", "como", "do", "da", "foi"),
        TargetLanguage.nl to setOf("de", "het", "een", "en", "van", "ik", "te", "dat", "die", "in", "is", "niet", "zijn", "op", "aan", "met", "als", "voor", "had", "maar")
    )

    fun detect(text: String): TargetLanguage? {
        var letters = 0; var cyr = 0; var arab = 0; var kana = 0; var hangul = 0; var han = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            letters++
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.CYRILLIC -> cyr++
                Character.UnicodeScript.ARABIC -> arab++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.HAN -> han++
                else -> {}
            }
        }
        if (letters < 12) return null
        val share = { n: Int -> n.toDouble() / letters }
        if (share(cyr) > 0.5) return TargetLanguage.ru
        if (share(arab) > 0.5) return TargetLanguage.ar
        if (share(kana) > 0.2) return TargetLanguage.ja
        if (share(hangul) > 0.5) return TargetLanguage.ko
        if (share(han) > 0.5) return TargetLanguage.zh

        val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.size < 4) return null
        val scores = stop.mapValues { (_, set) -> words.count { it in set } }.toMutableMap()
        // Türkçe'ye özgü harfler güçlü işaret
        val trChars = text.count { it in "ğĞışŞİı" }
        if (trChars > 0) scores[TargetLanguage.tr] = (scores[TargetLanguage.tr] ?: 0) + trChars.coerceAtMost(4)
        val best = scores.maxByOrNull { it.value } ?: return null
        return if (best.value >= 2) best.key else null
    }
}
