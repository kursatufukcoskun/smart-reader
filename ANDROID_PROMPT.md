# Görev: "Reader" uygulamasının Android sürümü

Bu klasörde (`ipad_reader/`) çalışan bir **iPad uygulaması** (SwiftUI) var. Aynı uygulamanın **yerel Android
sürümünü** (Kotlin + Jetpack Compose) `ipad_reader/android/` klasöründe sıfırdan yap. Kişisel kullanım için,
markete konmayacak. Android tablet (ve telefon) için; tablet öncelikli.

iOS kodu referans uygulamadır: davranışı, istemleri (prompt) ve JavaScript'i oradan al, birebir taşıma;
arayüzü Android'e uygun (Material 3) yap. iOS kodunu **değiştirme**.

## Önce oku
1. `SYNC.md` — Google Drive senkron biçimi, kitap kimliği algoritması, birleştirme kuralları. **Bağlayıcı sözleşme.**
2. `Reader/Views/EPUBReaderModel.swift` — özellikle `baseScript` (WebView'e enjekte edilen JS: seçim, vurgu,
   sayfa modu, çeviri ekleme, yer imi, paragraf çıkarma). Bu JS'i neredeyse aynen kullan.
3. `Reader/Models/ReaderSettings.swift` — tema renkleri, yazı tipleri, WebView'e giden CSS (`css` özelliği).
4. `Reader/AI/Prompts.swift`, `ClaudeClient.swift`, `LLMClient.swift` — istemler, Claude ve Gemini API çağrıları
   (akış/streaming dahil), model listesi çekme (`ModelCatalog.swift`).
5. `Reader/EPUB/` — EPUB ayrıştırma (container.xml → OPF → spine, içindekiler: EPUB3 nav / NCX).
6. `Reader/AI/BookChat.swift`, `ChapterStudy.swift`, `HardWords.swift` — sohbet, bölüm pekiştirme, zor kelimeler.
7. `Reader/Models/` — `Book`, `HighlightStore`, `VocabStore`, `BookmarkStore`, `ReadingTracker`, `LibraryStore`,
   `BookIdentity`, `WidgetBridge`, `NotificationScheduler`.
8. `Reader/Sync/` — Google OAuth (PKCE) ve Drive appDataFolder istemcisi, `SyncManager`.

## Teknik tercihler
- Kotlin, Jetpack Compose, Material 3, minSdk 26, hedef güncel SDK. Tek modül yeterli.
- Mimari: ViewModel + StateFlow, Kotlin coroutines/Flow. Yerel veri: Room **veya** iOS'takine benzer JSON dosyaları
  (senkron biçimiyle uyumlu kalmak şartıyla; JSON daha basitse onu seç).
- EPUB'u okuyucuda **WebView** ile göster (iOS'taki gibi): EPUB zip'ini uygulama klasörüne aç, bölümleri
  `file://` ile yükle, CSS + JS enjekte et (`WebViewClient`, `evaluateJavascript`, `addJavascriptInterface` ile
  JS→Kotlin mesajları). Harici bir EPUB motoru (Readium) kullanma; bu proje JS'e dayalı.
- Ağ: OkHttp veya Ktor + kotlinx.serialization. Claude/Gemini akışını SSE ile işle.
- Anahtarlar (Claude/Gemini API, Google refresh token): `EncryptedSharedPreferences` / Keystore. Koda gömme.
- Arayüz dili **Türkçe** (iOS'taki metinleri kullan).
- Bağımlılık eklemeden önce gerekçe ver; küçük tut.

## Özellikler (iOS'ta olanlar)
**Kitaplık:** EPUB/PDF ekleme (Storage Access Framework; "birlikte aç/paylaş" ile de), kapak, son okunan en üstte,
"Okunmadı / Okunuyor / Bitti" durumu, sayfa tabanlı ilerleme (EPUB'da ~250 kelime = 1 sanal sayfa; kelime sayıları
bölüm bölüm hesaplanır), etiketler, arama, silerken onay (vurgu/defter kalsın mı seçeneğiyle), Open Library'den
kapak arama, günlük hedef kartı ve seri.

**Okuyucu (EPUB):** tema (açık, sepya, koyu, siyah, e-ink), yazı tipi (Bookerly **hariç**, bkz. aşağıda), punto,
satır aralığı, kenar boşluğu, sıcak ışık filtresi, tek/çift kolon, sonsuz kaydırma veya sayfa sayfa (CSS sütunları),
kenara dokunarak sayfa çevirme, ortaya dokununca araç çubuklarını aç/kapat (tam ekran okuma), içindekiler, yer imleri,
konum hatırlama, alt çubukta "Sayfa x/y · %z · bölüm ~n dk" (dokununca değişir).

**Yapay zekâ (kullanıcı kendi API anahtarını girer; sağlayıcı Claude veya Gemini, ikisi de desteklenir):**
- Metin seç → seçim çubuğu/ActionMode: **Açıkla** (anlam + bağlam yorumu + paragraf çevirisi, akışlı panel),
  **Paragrafı çevir**, **Vurgula**, **Not al**, **Cümleyi çöz** (dil bilgisi).
- Ekrandaki paragrafları çevir (altına veya yan yana ekle, gizle/göster/sil).
- Bölüm özeti; zor kelimeler listesi (deftere ekle).
- **Kitapla sohbet:** yalnızca okunan kısmı bağlam olarak gönderir (spoiler yok), çok turlu.
- **Bölümü pekiştir:** özet + kavramlar + 8 soruluk quiz + soru-cevap kartları (JSON çıktı, ayrıştırma `ChapterStudy.parse`).
- Hızlı/güçlü model seçimi, model adı hem yazılabilir hem listeden seçilebilir, "güncel modelleri getir".
- Yanıt önbelleği (aynı istek iki kez ödenmesin).

**Notlar:** vurgu (5 renk, not, EPUB'da karakter aralığıyla — `SYNC.md`'deki "temiz metin" tanımına uy),
yer imleri, vurgu listesi (kitaplıktan dokununca kitapta o yere git), Markdown dışa aktarma.
**Kelime defteri:** bağlamıyla kayıt, aralıklı tekrar kartları (Leitner 0-5 kutu), Anki'ye aktarma (sekmeli metin).
**İstatistikler:** aktif okuma süresi (2 dk boşta kalınca ve uygulama arka plandayken sayılmaz), okunan sayfa,
kelime/dk hız, seri, haftalık/aylık/yıllık grafik, kitap bazında filtre, "bitmesine ~x dk" tahmini.
**Hedef ve hatırlatma:** günlük dakika hedefi, günlük hatırlatma bildirimi, kelime tekrarı bildirimi.
**Senkron:** Google Drive `appDataFolder`, `SYNC.md`'ye birebir uy (aynı dosya adları, aynı kimlik algoritması,
aynı birleştirme). iOS ile iki yönlü çalışmalı. Sonda bu uyumu nasıl doğruladığını anlat.
**Ana ekran widget'ı:** son okunan kitap, ilerleme, hedef halkası (Jetpack Glance) — en son aşamada.

## Aşamalar (her aşamanın sonunda derle, çalıştır, kısaca test et ve kullanıcıya rapor ver)
1. **İskelet + kitaplık + EPUB okuyucu** (içe aktarma, ayrıştırma, tema/yazı ayarları, ilerleme kaydı, durum etiketleri).
2. **Yapay zekâ:** API anahtarı ayarları, seçim menüsü, Açıkla/Çevir/Cümleyi çöz, sayfa çevirisi, özet, önbellek.
3. **Notlar:** vurgu, yer imi, kelime defteri ve tekrar kartları, dışa aktarma.
4. **Sohbet, bölüm pekiştirme, zor kelimeler.**
5. **Google girişi ve senkron** (ayrıntılar aşağıda).
6. **İstatistik, hedef, bildirimler, widget.**
7. **PDF** (ayrı ve zor — aşağıda). Bu aşamaya geçmeden kullanıcıya sor.

## Kurallar ve tuzaklar (iOS'ta öğrendiklerimiz)
- Kitap kimliği **içerikten** türetilir (`SYNC.md`): EPUB = OPF baytlarının SHA-256'sı, UUID sürüm 5 biçimine çevrilir.
  Rastgele UUID kullanma; yoksa senkron kitapları eşleştiremez.
- Okuma konumu: EPUB için `position` = bölüm (spine) indeksi, `scrollFraction` = bölüm içi 0..1; PDF için sayfa indeksi.
- **İlerleme yüzdesini bölüm sayısına göre hesaplama**; kelime sayısına göre hesapla (iOS'ta bu hata düzeltildi).
- Okuma süresine **yalnızca** gerçek okuma sayılır; içindekiler/yer imiyle atlama "okunan kelime" olarak sayılmaz.
- Sayfa modunda soldan sağa kaydırma sistemin "geri" hareketiyle çakışabilir; geri hareketini bu modda engelle.
- Sayfa modunda son sütunun sağındaki boşluk kaydırma alanına dahil olmaz; iOS'ta görünmez bir ayırıcı eleman kullandık
  (`#llm-end`). Aynı sorunu Android WebView'de de test et.
- Aynı vurgu/kayıt iki cihazda eşzamanlı değişirse **güncellenme damgası yeni olan** kazanır; silmeler "mezar taşı"
  (`deleted`) olarak yayılır. Okuma süresi kayıtları cihaz bazlıdır (anahtar `gün|kitap|cihaz`).
- LLM yanıtları akışlıdır (SSE). Boş yanıt gelirse geçersiz mesaj dizisi göndermemek için son kullanıcı mesajını geri al.
- Model adlarını sabit kodlama; ayarlarda düzenlenebilir olsun. Varsayılanlar: Claude `claude-haiku-5-5` (hızlı),
  `claude-sonnet-5-5` (güçlü); Gemini `gemini-2.5-flash`, `gemini-2.5-pro`. Güncel olmayabilir, "listeyi getir" ile düzelt.
- **Bookerly yazı tipini projeye ekleme** (Amazon'a ait, lisanslı). Bunun yerine sistem serifi veya açık lisanslı
  Literata gibi bir yazı tipi ekle ve ayarlarda yazı tipi listesinden seçilsin.
- Çeviri/özet/quiz istemlerinde **Türkçe** çıktı iste; teknik terimleri ve kodu orijinal bırak.

## Google girişi ve senkron — kullanıcıdan gerekenler
Google Cloud'da (kullanıcının mevcut projesi, Drive API zaten etkin, kapsam `drive.appdata`, uygulama "In production")
**Android tipi OAuth istemcisi** gerekir (paket adı + imza SHA-1). Bunu **kullanıcı** oluşturur. Ona şunu söyle:
- Uygulama paket adı: `com.kursat.reader` (değiştirirsen bildir).
- Debug imza SHA-1: `./gradlew signingReport` veya `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`.
- Oluşan Android istemci kimliğini sana versin.
Giriş için **Credential Manager + Google Identity (AuthorizationClient)** ile `drive.appdata` yetkisi al
ve access token ile Drive REST'i doğrudan çağır (Google Play Services gerekir). Mevcut iOS istemci kimliği
(`263419260918-img09htao0pqbbt8c1cud1rjo1tn5ffe.apps.googleusercontent.com`) **iOS'a özeldir**, Android'de kullanılmaz.
Kimlik gelene kadar senkron katmanını hazırla ama oturum açma kısmını sahte/devre dışı bırak, kullanıcıya sor.

## PDF (7. aşama, ayrı karar)
Android'in `PdfRenderer`'ı yalnızca bitmap verir; metin seçimi/çeviri için metin katmanı gerekir. Seçenekler:
PdfBox-Android / MuPDF ile metin çıkarma + kendi seçim katmanı, ya da PDF.js'i WebView'de çalıştırma. Önce kısa bir
karşılaştırma yaz, kullanıcıya öner ve onay al; taranmış PDF'lerde metin olmayacağını hatırlat.

## Çalışma şekli
- Proje: `android/` altında, Android Studio'da açılır, `./gradlew assembleDebug` ile derlenir.
  Bu makinede Android SDK kısmen kurulu (`~/Library/Android/sdk`, yalnızca API 31 platformu). Eksik platform/Gradle/emülatör
  için gerekli indirmeleri **önce kullanıcıya söyle**, sonra yap.
- Her aşamada: kısa plan → uygula → derle → emülatörde çalıştır ve ekran görüntüsü/log ile doğrula → rapor.
  Test edemediğin şeyi **test ettim deme**; açıkça "denenmedi" yaz.
- Gizli bilgi (API anahtarı) isteme, koda yazma; ayarlar ekranından girilir.
- Her aşamada bir `android/README.md` bölümü güncelle: nasıl derlenir, ne yapıldı, ne eksik.
- Emin olmadığın bir tasarım kararında en basit çalışan seçeneği seç ve kullanıcıya not düş.
