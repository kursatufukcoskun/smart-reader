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

    static func highlightSynthesis(text: String, scope: String) -> String {
        """
        Aşağıda bir okurun kitaplardan seçtiği vurgular ve yazdığı notlar var (kapsam: \(scope)). Şunları yap:
        1) Ortak temaları ve sık geçen fikirleri çıkar.
        2) Kitaplar arasında ilginç bağlantılar ya da çelişkiler varsa belirt.
        3) Notlarımdan yola çıkarak 3-5 maddelik bir sentez ve düşünmeye değer iki soru yaz.
        Yalnızca verilen metne dayan; kitap adlarını anarak hangi fikrin nereden geldiğini göster.

        <vurgular>
        \(text)
        </vurgular>
        """
    }

    static func critique(passage: String, paragraph: String, bookTitle: String) -> String {
        """
        Kitap: \(bookTitle)
        Aşağıdaki pasajı eleştirel okumak istiyorum. Kısa ve net yaz:
        • Yazarın burada ileri sürdüğü ana iddia/argüman nedir?
        • Dayandığı örtük varsayımlar neler?
        • Olası zayıf noktalar, eksikler ya da karşı argümanlar neler? (bilinen karşıt görüşleri/literatürü anabilirsin, ama bunun kitaptan değil genel bilgiden geldiğini belirt)
        • Bu argümanı sınamak için hangi soruyu sormalıyım?

        Pasaj: "\(passage)"
        Bağlam paragrafı: \(paragraph)
        """
    }

    static func flashcard(quote: String, note: String, bookTitle: String) -> String {
        """
        Kitap: \(bookTitle)
        Okur aşağıdaki pasajı vurguladı\(note.isEmpty ? "" : " ve şu notu yazdı: \"\(note)\""). Bu bilgiyi uzun vadede hatırlamak için TEK bir soru/cevap tekrar kartı yaz:
        • Soru, pasajı görmeden yanıtlanabilir ve tek bir bilgiyi/kavramı sorsun (evet/hayır sorusu olmasın).
        • Cevap kısa ve net olsun (1-3 cümle).
        • Dil: \(lang).

        Yalnızca şu biçimde yanıt ver:
        SORU: ...
        CEVAP: ...

        Pasaj: "\(quote)"
        """
    }

    /// "SORU: … CEVAP: …" çıktısını ayrıştırır.
    static func parseFlashcard(_ out: String) -> (question: String, answer: String)? {
        guard let q = out.range(of: "SORU:", options: .caseInsensitive),
              let a = out.range(of: "CEVAP:", options: .caseInsensitive), q.upperBound <= a.lowerBound else { return nil }
        let question = out[q.upperBound..<a.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
        let answer = out[a.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
        return question.isEmpty || answer.isEmpty ? nil : (question, answer)
    }

    /// Zor kelimelerin üstüne yazılacak kısa karşılıkları ister (paragraf başına en çok `perParagraph`).
    static func glossBatch(_ items: [(id: Int, text: String)], perParagraph: Int) -> String {
        let body = items.map { "[\($0.id)] \($0.text)" }.joined(separator: "\n\n")
        return """
        Aşağıdaki numaralı paragraflarda, ana dili \(lang) olan bir dil öğrencisi için ZOR ya da ileri düzey kelime ve deyimleri seç (sık kullanılan, basit kelimeleri seçme). Paragraf başına EN ÇOK \(perParagraph) tane. Her seçim için paragraftaki BAĞLAMA uygun, kısa (1-3 kelime) \(lang) karşılık ver. Kelimeyi metinde geçtiği gibi AYNEN yaz.
        Yalnızca JSON dizisi döndür, başka hiçbir şey yazma:
        [{"id":1,"words":[["kelime","karşılık"],["deyim","karşılık"]]}]

        \(body)
        """
    }

    /// `glossBatch` çıktısından [(paragraf, [[kelime, karşılık]])] çıkarır.
    static func parseGloss(_ out: String) -> [(id: Int, pairs: [[String]])] {
        guard let a = out.firstIndex(of: "["), let b = out.lastIndex(of: "]"), a < b,
              let data = String(out[a...b]).data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return arr.compactMap { o in
            guard let id = (o["id"] as? Int) ?? Int("\(o["id"] ?? "")"), let words = o["words"] as? [[Any]] else { return nil }
            let pairs = words.compactMap { w -> [String]? in
                guard w.count >= 2, let k = w[0] as? String, let g = w[1] as? String, !k.isEmpty, !g.isEmpty else { return nil }
                return [k, g]
            }
            return (id, pairs)
        }
    }

    static func wrapUp(title: String, author: String, highlights: String) -> String {
        """
        Bir okur "\(title)"\(author.isEmpty ? "" : " (\(author))") kitabını bitirdi. Aşağıda kitaptan yaptığı vurgular ve notlar var. Bunlardan yola çıkarak kişisel bir kapanış özeti yaz:
        1) Bu okura göre kitaptan kalan 4-6 ana fikir (maddeler).
        2) Vurguların ağırlık verdiği temalar.
        3) Hatırlanmaya değer 2-3 alıntı (olduğu gibi, tırnak içinde).
        4) Okura bir kapanış düşüncesi ya da üzerinde düşünmesi için bir soru.
        Yalnızca verilen vurgulara dayan.

        <vurgular>
        \(highlights)
        </vurgular>
        """
    }

    /// İlişki haritası için düğüm/kenar listesi ister (çizimi uygulama yapar).
    static func relationMap(context: String, bookTitle: String) -> String {
        """
        Aşağıda "\(bookTitle)" kitabının, okurun şu ana kadar okuduğu kısmı var.
        <kitap>
        \(context)
        </kitap>

        Yukarıdaki metne dayanarak bir ilişki haritası için düğüm ve kenar listesi çıkar. Kurguysa karakterler ve önemli yerler/gruplar, kurgu dışıysa ana kişiler ve kavramlar.
        Kurallar:
        • En çok 18 düğüm (en önemlileri) ve en çok 30 kenar. Yalnızca metinde geçenleri ekle; metinde olmayan hiçbir şeyi ve sonrasını ima etme.
        • "label" en çok 3 kelime; her düğüm bir kez.
        • "group": person (kişi/karakter), place (yer), concept (kavram), org (grup/kurum) değerlerinden biri.
        • "note": o düğümü \(lang) dilinde tek cümleyle anlat.
        • Kenar "label"ı 1-3 kelimelik \(lang) ilişki (ör. kardeşi, düşmanı, çalıştığı yer).
        Yalnızca şu biçimde JSON döndür, başka hiçbir şey yazma:
        {"nodes":[{"id":"n1","label":"...","group":"person","note":"..."}],"edges":[{"from":"n1","to":"n2","label":"..."}]}
        """
    }

    /// Bugün tekrar edilecek kelimelerin hepsini içeren mini hikâye/deneme + çevirisi.
    static func vocabStory(_ words: [(word: String, gloss: String)]) -> String {
        let list = words.map { "- \($0.word) — \($0.gloss)" }.joined(separator: "\n")
        return """
        Aşağıda bir dil öğrencisinin bugün tekrar edeceği kelimeler var (kelime — kısa karşılık). Kelimeler genelde aynı yabancı dilde.
        Bu kelimelerin HEPSİNİ kullanan, o yabancı dilde yazılmış, 2 paragraflık akıcı ve sürükleyici bir mini hikâye ya da deneme yaz.
        • Her kelimeyi metinde **kalın** işaretle (gerekirse çekimli hâliyle ama tanınır biçimde); kelimeler zorlama durmasın.
        • Kelimeler farklı dillerdense her dil için ayrı kısa bir paragraf yaz.
        • Sonra tek başına bir "---" satırı ekle ve metnin \(lang) çevirisini yaz (çeviride kalın kullanma).
        • Başlık ekleme.

        Kelimeler:
        \(list)
        """
    }

    /// Uzun aradan sonra kitaba dönen okur için: kaldığı yerden hemen önceyi kısaca hatırlatır.
    static let resume = "Kitaba uzun bir aradan sonra döndüm. Kaldığım yerden hemen öncesini (okuduğum son sayfa ya da bölümün sonunu) 3 cümleyle hatırlat: kimler vardı, ne oluyordu, nerede kalmıştı. Spoiler yok."

    /// Bir bölümden okurun hatırlaması gereken 3-5 içgörü ister (satır başına bir içgörü).
    static func insights(text: String, bookTitle: String, scope: String) -> String {
        """
        Kitap: \(bookTitle) — \(scope)
        Aşağıdaki bölümden okurun hatırlaması gereken 3-5 İÇGÖRÜ çıkar. İçgörü; bölümde geçen bir fikir, argüman, olay ya da ilişkinin "ne anlama geldiğini" 1-2 cümleyle söyleyen, kendi başına anlaşılır bir not olmalı (özet cümlesi ya da alıntı değil). Yalnızca bu metne dayan, sonrasını ima etme. Dil: \(lang).
        Her satıra bir içgörü yaz, satırı "- " ile başlat; başka hiçbir şey yazma.

        Metin:
        \(text)
        """
    }

    /// `insights` çıktısını madde madde ayırır.
    static func parseInsights(_ out: String) -> [String] {
        var marked: [String] = [], other: [String] = []
        for line in out.split(whereSeparator: \.isNewline) {
            var t = line.trimmingCharacters(in: .whitespaces)
            var isMarked = false
            // Yalnızca "- ", "• ", "* ", "1. " gibi madde işaretleri (boşluk ardından) soyulur; **kalın** metne dokunulmaz.
            if let r = t.range(of: #"^([-•–*]|\d+[.)])\s+"#, options: .regularExpression) { t.removeSubrange(r); isMarked = true }
            t = t.trimmingCharacters(in: .whitespaces)
            guard t.count >= 15 else { continue }
            if isMarked { marked.append(t) } else { other.append(t) }
        }
        // Model bir giriş cümlesi yazdıysa (madde işaretsiz) yalnızca maddeleri al
        return marked.isEmpty ? other : marked
    }

    // Okuyucudan tek dokunuşla sohbete gönderilen hazır sorular (sohbet zaten yalnızca okunan kısmı bilir).
    static let recap = "Kaldığım yere kadar olanları kısaca hatırlat: ana olay/argüman akışı, son bölümde ne oldu ve şu an geldiğimiz nokta. 6-8 madde, spoiler yok."
    static let cast = "Şu ana kadar okuduğum kısımdaki önemli karakterleri (kurgu dışıysa kavramları ve terimleri) listele. Her biri için bir cümleyle kim/ne olduğunu ve şimdiye kadar öğrendiklerimi yaz."
    static func xray(_ term: String) -> String {
        "\"\(term)\" kimdir/nedir? Okuduğum kısma göre: ilk nerede geçti ve şimdiye kadar onun hakkında ne biliyoruz? Kısa tut, spoiler verme."
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
