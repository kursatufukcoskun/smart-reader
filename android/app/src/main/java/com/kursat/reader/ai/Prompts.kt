package com.kursat.reader.ai

import com.kursat.reader.model.LanguageState
import com.kursat.reader.model.TargetLanguage
import kotlin.math.min

object Prompts {
    /** Yanıtların/çevirilerin dili (ayarlardaki hedef dil). */
    val lang: String get() = LanguageState.target.englishName

    val assistantSystem: String
        get() = "You are a language assistant sitting next to a reader of foreign-language books. The reader's language is $lang. " +
            "ALWAYS answer in $lang (including any section labels); keep source-language terms in their original form. " +
            "Do not use headings (#), tables or code blocks; only **bold**, *italic* and \"• \" bullets at the line start. " +
            "No filler introduction or closing sentence; go straight to the content."

    val translatorSystem: String get() = translatorSystem(LanguageState.target)

    fun translatorSystem(target: TargetLanguage): String =
        "You are an expert literary translator. Translate the given text into ${target.englishName}, faithfully but naturally, as a skilled native editor would: " +
            "keep the author's tone and register, render idioms by meaning, and never summarize or omit anything. Output only the requested format."

    val summarySystem: String
        get() = "You are a meticulous reading assistant. Summarize the given text in $lang. Do not use headings (#) or tables; use **bold** and \"• \" bullets at the line start."

    fun chatSystem(bookTitle: String): String = """
        You are the reading companion of a reader of $bookTitle. The reader's language is $lang; answer in $lang. The part of the book the reader has read so far is given in the first message between <kitap> tags.
        The Turkish rules below apply regardless of the answer language.

        Sen, $bookTitle kitabını okuyan bir okurun yanındaki okuma arkadaşısın. Kullanıcının kitapta şu ana kadar okuduğu kısım ilk mesajda <kitap> etiketleri arasında verilir.

        KATI KURALLAR:
        • Yalnızca verilen metindeki bilgilere dayan. Kitabı veya olay örgüsünü başka kaynaklardan biliyor olsan bile, metinde olmayan hiçbir olayı, kişi gelişimini veya sonu anlatma, ima etme.
        • Soru, henüz okunmamış bir kısmı gerektiriyorsa "Okuduğun kısımda bu henüz anlatılmadı." de ve metinden bildiklerini söyle. Tahmin yürütmek istenirse bunu açıkça "tahmin" diye belirt.
        • Mümkünse kısa İngilizce alıntıyla (tırnak içinde) dayanağını göster.
        • Her zaman $lang dilinde yanıt ver; özel isimleri ve terimleri orijinal haliyle yaz.
        • Başlık (#), tablo ve kod bloğu kullanma; yalnızca **kalın**, *italik* ve satır başında "• " kullan. Kısa ve net ol.
    """.trimIndent()

    fun hardWords(text: String, bookTitle: String): String = """
        Kitap: $bookTitle

        Aşağıdaki metni okuyacak kişinin ana dili $lang, metnin dilini orta-ileri (B2) seviyede biliyor. Metinden, bu kişinin büyük olasılıkla bilmeyeceği 15-25 kelime/ifade seç (nadir, edebi, deyimsel, çok anlamlı). Özel isimleri ve çok basit kelimeleri seçme. Kelimeleri metinde geçiş sırasına göre ver, sözlük (temel) biçimiyle yaz.

        Her satır tam olarak şu biçimde olsun, başka hiçbir metin yazma:
        kelime | tür (isim/fiil/sıfat/zarf/deyim; $lang dilinde yaz) | $lang karşılığı (bu bağlamdaki) | metinden kısa bir örnek cümle (metnin orijinal dilinde)

        ""${'"'}
        $text
        ""${'"'}
    """.trimIndent()

    fun study(text: String, bookTitle: String, chapter: String, language: String): String {
        val lang = if (language == "English") "İngilizce" else "Türkçe"
        return """
            Kitap: $bookTitle
            Bölüm: $chapter

            Aşağıdaki bölümü okuyan öğrenci için bir çalışma seti hazırla. Kitap teknik olabilir (kod, formül, terim); bu durumda kavramları, terimleri ve mantığı sorgula, kod parçalarını yorumlat. Yalnızca bölümde geçen bilgilere dayan. Özet ve açıklamalar $lang, quiz ve soru-cevap soruları ile cevapları $lang olsun. Teknik terimleri ve kodu orijinal haliyle bırak.

            Çıktı YALNIZCA geçerli bir JSON nesnesi olsun (açıklama, markdown çiti, ek metin yok). Şema:
            {
              "summary": ["Bu bölümde öğrendiklerimiz: 6-10 kısa madde, her biri bir öğrenilen şey; **kalın** kullanabilirsin"],
              "concepts": [{"term": "temel kavram", "definition": "1-2 cümlelik tanım"}],
              "quiz": [{"question": "soru (kod gerekiyorsa `satır içi kod` kullan)", "options": ["A","B","C","D"], "answer": 0, "explanation": "doğru cevabın nedeni ve yanlışların neden olmadığı"}],
              "qa": [{"question": "açık uçlu soru", "answer": "3-5 cümlelik örnek cevap"}]
            }
            Kurallar: concepts 5-10 adet; quiz tam 8 soru, her birinde 4 şık, "answer" doğru şıkkın 0 tabanlı sırası, yanlış şıklar akla yatkın olsun, ezber değil anlamayı ölçsün; qa 5-6 soru, "neden/nasıl" ağırlıklı.

            ""${'"'}
            $text
            ""${'"'}
        """.trimIndent()
    }

    fun grammar(text: String, bookTitle: String): String = """
        Kitap: $bookTitle

        Çözümlenecek İngilizce cümle/pasaj:
        ""${'"'}
        $text
        ""${'"'}

        $lang dilinde bir öğretmen gibi şunları yaz (başlıklar dahil her şey $lang dilinde):
        **Parça parça:** Cümleyi anlamlı parçalara böl; her parçanın altında $lang karşılığını ver.
        **Yapı:** Özne, yüklem, nesne; yan cümlecikler ve neye bağlandıkları.
        **Dikkat:** Deyim, phrasal verb, zaman/kip, devrik yapı gibi zorlayıcı noktalar.
        **Akıcı çeviri:** Cümlenin doğal $lang çevirisi.
    """.trimIndent()

    private fun wordCount(s: String): Int = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /** Kelime/ifade: anlam + bağlam yorumu + paragraf çevirisi. */
    fun explain(selection: String, paragraph: String, bookTitle: String): String {
        if (wordCount(selection) > 6) {
            return """
                Kitap: $bookTitle

                Seçilen pasaj:
                ""${'"'}
                $selection
                ""${'"'}

                Pasajın geçtiği paragraf:
                ""${'"'}
                $paragraph
                ""${'"'}

                Şunları yaz:
                Tüm yanıtı (etiketler dahil) $lang dilinde yaz.
                **Çeviri:** Seçilen pasajın akıcı $lang çevirisi.
                **Yorum:** Pasajın anlamı, ima/üslup/deyim varsa açıklaması (kısa).
            """.trimIndent()
        }
        return """
            Kitap: $bookTitle

            Seçilen kelime/ifade: «$selection»

            Geçtiği paragraf:
            ""${'"'}
            $paragraph
            ""${'"'}

            Şunları yaz:
            Tüm yanıtı (etiketler dahil) $lang dilinde yaz.
            **$selection** — türü (isim/fiil/…) ve $lang karşılığı. Gerekirse diğer yaygın anlamlarını bir satırda say.
            **Bu bağlamdaki anlamı:** Bu cümlede tam olarak ne anlama geldiğini, ton/ima/deyim yönünü kısaca yorumla.
            **Paragrafın çevirisi:** Paragrafın tamamının akıcı $lang çevirisi.
        """.trimIndent()
    }

    fun translateParagraph(paragraph: String, target: TargetLanguage = LanguageState.target): String = """
        Translate the following paragraph into ${target.englishName}. Write only the translation.

        ""${'"'}
        $paragraph
        ""${'"'}
    """.trimIndent()

    /** EPUB ekran çevirisi için etiketli toplu format. */
    fun translateBatch(items: List<Pair<Int, String>>): String {
        val body = items.joinToString("\n") { (id, text) -> "<p id=\"$id\">$text</p>" }
        return """
            Translate each paragraph below into $lang. Return every paragraph in the same order, wrapped as <t id="N">translation</t> using the same id. Output nothing else.

            $body
        """.trimIndent()
    }

    fun translatePDFPage(text: String): String = """
        Aşağıdaki metin bir PDF sayfasından çıkarıldı; satır sonu tireleri, kesik satırlar, sayfa numarası/üst bilgi gibi artıklar olabilir. Bunları düzelterek sayfanın tamamını akıcı $lang diline çevir. Paragraf yapısını koru. Sadece çeviriyi yaz.

        ""${'"'}
        $text
        ""${'"'}
    """.trimIndent()

    fun summarize(text: String, scope: String, bookTitle: String): String = """
        Kitap: $bookTitle
        Kapsam: $scope

        Aşağıdaki metni $lang dilinde özetle:
        • Önce 2-3 cümlelik genel özet.
        • Sonra 5-8 maddelik ana olay/fikir listesi.
        • Önemli kişi, kavram ve terimleri orijinal haliyle parantez içinde belirt.

        ""${'"'}
        $text
        ""${'"'}
    """.trimIndent()

    private val batchRegex = Regex("<t id=\"(\\d+)\">([\\s\\S]*?)</t>")

    /** <t id="N">…</t> çıktısını ayrıştırır. */
    fun parseBatch(output: String): List<Pair<Int, String>> {
        return batchRegex.findAll(output).mapNotNull { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            id to m.groupValues[2].trim()
        }.toList()
    }
}

