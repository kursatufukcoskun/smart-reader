import Foundation

enum Prompts {
    /// Yanıtların/çevirilerin dili (ayarlardaki hedef dil).
    static var lang: String { TargetLanguage.current.englishName }

    static var assistantSystem: String {
        """
        You are a language assistant sitting next to a reader of foreign-language books. The reader's language is \(lang). \
        ALWAYS answer in \(lang) (including any section labels); keep source-language terms in their original form. \
        Do not use headings (#), tables or code blocks; only **bold**, *italic* and "• " bullets at the line start. \
        No filler introduction or closing sentence; go straight to the content.
        """
    }

    static var translatorSystem: String { translatorSystem(to: TargetLanguage.current) }

    static func translatorSystem(to target: TargetLanguage) -> String {
        """
        You are an expert literary translator. Translate the given text into \(target.englishName), faithfully but naturally, as a skilled native editor would: \
        keep the author's tone and register, render idioms by meaning, and never summarize or omit anything. Output only the requested format.
        """
    }

    static var summarySystem: String {
        """
        You are a meticulous reading assistant. Summarize the given text in \(lang). Do not use headings (#) or tables; use **bold** and "• " bullets at the line start.
        """
    }

    static func chatSystem(bookTitle: String) -> String {
        """
        You are the reading companion of a reader of \(bookTitle). The reader's language is \(lang); answer in \(lang). The part of the book the reader has read so far is given in the first message between <kitap> tags.
        The Turkish rules below apply regardless of the answer language.

        Sen, \(bookTitle) kitabını okuyan bir okurun yanındaki okuma arkadaşısın. Kullanıcının kitapta şu ana kadar okuduğu kısım ilk mesajda <kitap> etiketleri arasında verilir.

        KATI KURALLAR:
        • Yalnızca verilen metindeki bilgilere dayan. Kitabı veya olay örgüsünü başka kaynaklardan biliyor olsan bile, metinde olmayan hiçbir olayı, kişi gelişimini veya sonu anlatma, ima etme.
        • Soru, henüz okunmamış bir kısmı gerektiriyorsa "Okuduğun kısımda bu henüz anlatılmadı." de ve metinden bildiklerini söyle. Tahmin yürütmek istenirse bunu açıkça "tahmin" diye belirt.
        • Mümkünse kısa İngilizce alıntıyla (tırnak içinde) dayanağını göster.
        • Her zaman \(lang) dilinde yanıt ver; özel isimleri ve terimleri orijinal haliyle yaz.
        • Başlık (#), tablo ve kod bloğu kullanma; yalnızca **kalın**, *italik* ve satır başında "• " kullan. Kısa ve net ol.
        """
    }

    static func hardWords(text: String, bookTitle: String) -> String {
        """
        Kitap: \(bookTitle)

        Aşağıdaki metni okuyacak kişinin ana dili \(lang), metnin dilini orta-ileri (B2) seviyede biliyor. \
        Metinden, bu kişinin büyük olasılıkla bilmeyeceği 15-25 kelime/ifade seç (nadir, edebi, deyimsel, çok anlamlı). \
        Özel isimleri ve çok basit kelimeleri seçme. Kelimeleri metinde geçiş sırasına göre ver, sözlük (temel) biçimiyle yaz.

        Her satır tam olarak şu biçimde olsun, başka hiçbir metin yazma:
        kelime | tür (isim/fiil/sıfat/zarf/deyim; \(lang) dilinde yaz) | \(lang) karşılığı (bu bağlamdaki) | metinden kısa bir örnek cümle (metnin orijinal dilinde)

        \"\"\"
        \(text)
        \"\"\"
        """
    }

    static func study(text: String, bookTitle: String, chapter: String, language: String) -> String {
        let lang = language == "English" ? "İngilizce" : "Türkçe"
        return """
        Kitap: \(bookTitle)
        Bölüm: \(chapter)

        Aşağıdaki bölümü okuyan öğrenci için bir çalışma seti hazırla. Kitap teknik olabilir (kod, formül, terim); \
        bu durumda kavramları, terimleri ve mantığı sorgula, kod parçalarını yorumlat. \
        Yalnızca bölümde geçen bilgilere dayan. Özet ve açıklamalar \(lang), \
        quiz ve soru-cevap soruları ile cevapları \(lang) olsun. Teknik terimleri ve kodu orijinal haliyle bırak.

        Çıktı YALNIZCA geçerli bir JSON nesnesi olsun (açıklama, markdown çiti, ek metin yok). Şema:
        {
          "summary": ["Bu bölümde öğrendiklerimiz: 6-10 kısa madde, her biri bir öğrenilen şey; **kalın** kullanabilirsin"],
          "concepts": [{"term": "temel kavram", "definition": "1-2 cümlelik tanım"}],
          "quiz": [{"question": "soru (kod gerekiyorsa `satır içi kod` kullan)", "options": ["A","B","C","D"], "answer": 0, "explanation": "doğru cevabın nedeni ve yanlışların neden olmadığı"}],
          "qa": [{"question": "açık uçlu soru", "answer": "3-5 cümlelik örnek cevap"}]
        }
        Kurallar: concepts 5-10 adet; quiz tam 8 soru, her birinde 4 şık, "answer" doğru şıkkın 0 tabanlı sırası, \
        yanlış şıklar akla yatkın olsun, ezber değil anlamayı ölçsün; qa 5-6 soru, "neden/nasıl" ağırlıklı.

        \"\"\"
        \(text)
        \"\"\"
        """
    }

    static func grammar(text: String, bookTitle: String) -> String {
        """
        Kitap: \(bookTitle)

        Çözümlenecek İngilizce cümle/pasaj:
        \"\"\"
        \(text)
        \"\"\"

        \(lang) dilinde bir öğretmen gibi şunları yaz (başlıklar dahil her şey \(lang) dilinde):
        **Parça parça:** Cümleyi anlamlı parçalara böl; her parçanın altında \(lang) karşılığını ver.
        **Yapı:** Özne, yüklem, nesne; yan cümlecikler ve neye bağlandıkları.
        **Dikkat:** Deyim, phrasal verb, zaman/kip, devrik yapı gibi zorlayıcı noktalar.
        **Akıcı çeviri:** Cümlenin doğal \(lang) çevirisi.
        """
    }

    private static func wordCount(_ s: String) -> Int { s.split(whereSeparator: \.isWhitespace).count }

    /// Kelime/ifade: anlam + bağlam yorumu + paragraf çevirisi.
    static func explain(selection: String, paragraph: String, bookTitle: String) -> String {
        if wordCount(selection) > 6 {
            return """
            Kitap: \(bookTitle)

            Seçilen pasaj:
            \"\"\"
            \(selection)
            \"\"\"

            Pasajın geçtiği paragraf:
            \"\"\"
            \(paragraph)
            \"\"\"

            Şunları yaz:
            Tüm yanıtı (etiketler dahil) \(lang) dilinde yaz.
            **Çeviri:** Seçilen pasajın akıcı \(lang) çevirisi.
            **Yorum:** Pasajın anlamı, ima/üslup/deyim varsa açıklaması (kısa).
            """
        }
        return """
        Kitap: \(bookTitle)

        Seçilen kelime/ifade: «\(selection)»

        Geçtiği paragraf:
        \"\"\"
        \(paragraph)
        \"\"\"

        Şunları yaz:
        Tüm yanıtı (etiketler dahil) \(lang) dilinde yaz.
        **\(selection)** — türü (isim/fiil/…) ve \(lang) karşılığı. Gerekirse diğer yaygın anlamlarını bir satırda say.
        **Bu bağlamdaki anlamı:** Bu cümlede tam olarak ne anlama geldiğini, ton/ima/deyim yönünü kısaca yorumla.
        **Paragrafın çevirisi:** Paragrafın tamamının akıcı \(lang) çevirisi.
        """
    }

    static func translateParagraph(paragraph: String, to target: TargetLanguage = .current) -> String {
        """
        Translate the following paragraph into \(target.englishName). Write only the translation.

        \"\"\"
        \(paragraph)
        \"\"\"
        """
    }

    /// EPUB ekran çevirisi için etiketli toplu format.
    static func translateBatch(_ items: [(id: Int, text: String)]) -> String {
        let body = items.map { "<p id=\"\($0.id)\">\($0.text)</p>" }.joined(separator: "\n")
        return """
        Translate each paragraph below into \(lang). Return every paragraph in the same order, \
        wrapped as <t id="N">translation</t> using the same id. Output nothing else.

        \(body)
        """
    }

    static func translatePDFPage(_ text: String) -> String {
        """
        Aşağıdaki metin bir PDF sayfasından çıkarıldı; satır sonu tireleri, kesik satırlar, sayfa numarası/üst bilgi gibi artıklar olabilir. \
        Bunları düzelterek sayfanın tamamını akıcı \(lang) diline çevir. Paragraf yapısını koru. Sadece çeviriyi yaz.

        \"\"\"
        \(text)
        \"\"\"
        """
    }

    static func summarize(text: String, scope: String, bookTitle: String) -> String {
        """
        Kitap: \(bookTitle)
        Kapsam: \(scope)

        Aşağıdaki metni \(lang) dilinde özetle:
        • Önce 2-3 cümlelik genel özet.
        • Sonra 5-8 maddelik ana olay/fikir listesi.
        • Önemli kişi, kavram ve terimleri orijinal haliyle parantez içinde belirt.

        \"\"\"
        \(text)
        \"\"\"
        """
    }

    /// <t id="N">…</t> çıktısını ayrıştırır.
    static func parseBatch(_ output: String) -> [(id: Int, text: String)] {
        guard let re = try? NSRegularExpression(pattern: #"<t id="(\d+)">([\s\S]*?)</t>"#) else { return [] }
        let ns = output as NSString
        return re.matches(in: output, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            guard let id = Int(ns.substring(with: m.range(at: 1))) else { return nil }
            return (id, ns.substring(with: m.range(at: 2)).trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }
}
