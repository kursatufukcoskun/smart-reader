# Reader — Android Sürümü

iPad `Reader` uygulamasının yerel Android (`Kotlin` + `Jetpack Compose` + `Material 3`) sürümüdür.

## Derleme ve Çalıştırma

### Gereksinimler
- Android Studio (veya Android SDK `compileSdk 35`, `minSdk 26`)
- JDK 17 – 23 (`android/gradle.properties` içinde `corretto-23.0.2` tanımlıdır; kendi ortamınıza göre güncelleyebilirsiniz)

### Komut Satırından Derleme ve Birim Testleri
```bash
cd android
./gradlew assembleDebug testDebugUnitTest
```
Üretilen APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### Emülatörde / Cihazda Çalıştırma
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.kursat.reader/.MainActivity
```

---

## Google Girişi ve Drive Senkronu İçin Gerekenler (Aşama 5)

Google Cloud Console'da mevcut projenizde (`drive.appdata` kapsamı etkin, "In production") **Android** tipi OAuth 2.0 İstemci Kimliği oluşturmanız gerekir:
- **Uygulama paket adı (`applicationId`):** `com.kursat.reader`
- **Debug İmza SHA-1 (`~/.android/debug.keystore`):**
  ```
  5B:FD:67:58:0D:CC:BE:B4:47:F9:BB:AE:F8:1D:CB:D9:F2:59:EF:16
  ```
  *(Doğrulamak için: `./gradlew signingReport` veya `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android`)*

Google Cloud Console'da bu paket adı ve SHA-1 tanımlandığında, uygulama içindeki **Ayarlar ▸ Senkronizasyon (Google Drive) ▸ Google ile bağlan** düğmesi Google Play Services (`Identity.getAuthorizationClient`) üzerinden `drive.appdata` yetkisini doğrudan alır.

---

## Tamamlanan Aşamalar (1–6)

### Aşama 1: İskelet + Kitaplık + EPUB Okuyucu
- Tek modüllü (`:app`) Kotlin + Jetpack Compose + Material 3 mimarisi (`StateFlow`, Coroutines).
- `BookIdentity`: EPUB için OPF dosyasının ham baytlarının SHA-256 özetinden RFC 4122 sürüm 5 UUID (büyük harfli); PDF için ilk 2 MiB + ondalık dosya boyutu SHA-256'sından UUID v5.
- `ZipArchive` ve `EPUBPackage`: `META-INF/container.xml` → OPF → `spine`, EPUB 3 `nav` ve EPUB 2 `NCX` içindekiler ayrıştırması.
- Kelime sayısı tabanlı ilerleme (`~250 kelime = 1 sanal sayfa`), `Tümü / Okunuyor / Okunmadı / Bitti` ve etiket filtreleri, arama, Open Library kapak arama (`fetchCover`), silerken vurgu/kelime koruma seçeneği, günlük hedef halkası (`GoalBanner`).
- WebView tabanlı EPUB okuyucu: `ReaderSettings.css` ve iOS `baseScript` (`#llm-end` görünmez ayırıcı dahil) enjeksiyonu, 5 tema (`Açık`, `Sepya`, `Koyu`, `Siyah`, `E-ink`), açık lisanslı `Literata` fontu + sistem fontları, punto, satır aralığı, kenar boşluğu, sıcak ışık filtresi, tek/çift kolon, sonsuz kaydırma ve sayfa sayfa (CSS sütunları) modu, sayfa modunda geri hareketini engelleme (`BackHandler`), içindekiler/vurgular/yer imleri paneli.

### Aşama 2: Yapay Zekâ (Claude & Gemini)
- `KeyStore`: API anahtarları `EncryptedSharedPreferences` (`AES256_SIV` / `AES256_GCM`) içinde saklanır; koda gömülmez.
- `ClaudeClient` ve `GeminiClient`: OkHttp üzerinden SSE (Server-Sent Events) akışlı (`stream`, `streamChat`) ve tek seferlik (`complete`) çağrılar.
- `ModelCatalog`: Varsayılan hızlı/güçlü modeller (`claude-haiku-5-5`, `claude-sonnet-5-5`, `gemini-2.5-flash`, `gemini-2.5-pro`) hem elle yazılabilir hem listeden seçilebilir; `"Güncel modelleri getir"` ile API'den canlı model listesi çekilir.
- Metin seçimi çubuğu (`SelectionBar`) ve WebView `ActionMode`: **Açıkla**, **Paragrafı Çevir**, **Vurgula**, **Not al**, **Cümleyi çöz**.
- Ekrandaki paragrafları çevirme (`translateVisible`): Görünür paragrafları ~3000 karakter / en fazla 10'lu gruplar halinde çevirip altına veya yan yana ekler; gizle/göster/sil desteği.
- Bölüm özeti (`summarizeChapter`) ve SHA-256 anahtarlı disk önbelleği (`ResponseCache`).

### Aşama 3: Notlar ve Kelime Defteri
- `HighlightStore` ve `HighlightsView`: 5 renkli vurgu, not ekleme/düzenleme, `SYNC.md` "temiz metin" karakter aralığı (`cleanNodes` — `.llm-tr, script, style, #llm-end` hariç), kitaplıktan vurguya dokununca kitapta ilgili bölüme ve vurguya atlama (`pendingJump`), Markdown dışa aktarma.
- `BookmarkStore`: Bölüm ve konum kesriyle yer imi ekleme/kaldırma ve listeleme.
- `VocabStore` ve `VocabView`: Bağlamıyla kelime kaydı, Leitner 0–5 kutu aralıklı tekrar kartları (`Bilmiyorum`, `Zor`, `Biliyorum`, `Kolay`), Anki uyumlu sekmeli metin (`.txt`) dışa aktarma.

### Aşama 4: Kitapla Sohbet, Bölümü Pekiştir, Zor Kelimeler
- `BookChat` ve `BookChatSheet`: Yalnızca okunan bölümler + mevcut bölümde ekrana kadar olan metin (`window.__llmTextUpToViewport()`) bağlam olarak gönderilir (spoiler içermez); çok turlu sohbet, boş yanıtta son kullanıcı mesajını geri alma (`dropDanglingUser`).
- `ChapterStudy` ve `ChapterStudySheet`: Ana noktalar özeti, önemli kavramlar, şıkları karıştırılmış 8 soruluk çoktan seçmeli quiz ve çevrilebilir soru-cevap kartları (`ChapterStudyModel.parse`).
- `HardWords` ve `HardWordsSheet`: Bölümdeki zor kelimeleri tür, Türkçe anlam ve bağlam cümlesiyle çıkarma, tek tek veya topluca kelime defterine ekleme.

### Aşama 5: Google Girişi ve Drive Senkronu (`SYNC.md` Doğrulaması)
- `GoogleAuth` ve `SyncManager`: Google Drive v3 REST API (`spaces=appDataFolder`) üzerinden iki yönlü senkronizasyon.
- **`SYNC.md` Uyumunun Doğrulanması (`SyncContractTest`):**
  1. **Kitap Kimliği (`BookIdentity`):** OPF baytlarının SHA-256 özetinin ilk 16 baytından RFC 4122 v5 bit maskeleri (`version = 5`, `variant = 10xx`) uygulanarak büyük harfli UUID üretildiği birim testiyle doğrulandı.
  2. **Dosya Adları ve JSON Şemaları:** Drive `appDataFolder` içindeki `highlights.json`, `vocab.json`, `bookmarks.json`, `progress.json`, `reading-log.json` ve `settings.json` zarflarının iOS `SyncManager.swift` tarafından üretilen JSON çıktılarıyla birebir serileştirilip ayrıştırıldığı (`ItemsFile<T>`, `ProgressFile`, `LogFile`, `SettingsFile`) birim testiyle doğrulandı.
  3. **Birleştirme ve Mezar Taşı (`SyncMerge.items`):** Eşzamanlı değişikliklerde `updatedAt` damgası yeni olanın kazandığı ve `deleted` (mezar taşı) damgası kayıttan yeniyse kaydın düşürüldüğü birim testiyle doğrulandı.

### Aşama 6: İstatistikler, Hedef, Bildirimler ve Widget
- `ReadingTracker` ve `StatsView`: 5 saniyelik aktif okuma sayacı (2 dakika hareketsizlikte veya uygulama arka plandayken durur; içindekiler/bölüm atlamaları okunan kelime sayılmaz), 6 özet kartı (`Bugün`, `Bu Hafta`, `Seri`, `Okuma Hızı`, `Toplam Süre`, `Toplam Sayfa`), Süre/Sayfa ve Hafta/Ay/Yıl çubuk grafiği, kitap bazlı filtreleme ve bitiş süresi tahmini.
- `NotificationScheduler`: `WorkManager` ile günlük okuma hatırlatması (günlük hedef tamamlanmışsa gönderilmez) ve akşam 18:30 kelime tekrarı bildirimi.
- `WidgetBridge` ve `ReaderWidgetReceiver`: `Jetpack Glance` ana ekran widget'ı (son okunan kitap, ilerleme yüzdesi ve sayfası, günlük hedef ve seri, dokununca `reader://book/<id>` ile kitabı açar).

### Aşama 7: PDF Okuyucu (`PDFReader.kt`)
- `PdfRenderer` + `PdfBox-Android` (`com.tom-roush:pdfbox-android:2.0.27.0`) ile yüksek çözünürlüklü PDF sayfa çizimi, gömülü başlık/yazar çıkarma, `PDFText` paragraf gruplama ve kelime bazlı koordinat (`PDFWordBox`) çıkarma.
- PDF sayfası üzerinde basılı tutup sürükleyerek etkileşimli kelime/cümle seçimi: **Açıkla**, **Paragrafı Çevir**, **Vurgula**, **Not al**, **Cümleyi çöz**.
- Kayıtlı PDF vurgularının (`Highlight.rects` — `SYNC.md` ve iOS `PDFKit` sayfa koordinatlarıyla birebir uyumlu) sayfa üzerinde çizilmesi ve dokununca düzenlenmesi.
- Tek/çift kolon (yan yana iki sayfa kitap görünümü), sonsuz kaydırma ve sayfa sayfa (`HorizontalPager`) okuma modları, 5 tema renk filtresi (`PDFThemeModifier`), yer imi, sayfa çevirisi, 1/5/15 sayfa özeti, son 10/25 sayfa pekiştirme, 5 sayfa zor kelimeler ve spoiler'sız kitapla sohbet.
- *(Not: Taranmış/görüntü tabanlı PDF'lerde metin katmanı bulunmadığında kullanıcıya uyarı gösterilir.)*

---

## Doğrulama ve Test Durumu
- **Derleme ve Birim Testleri:** `./gradlew assembleDebug testDebugUnitTest` hatasız geçti.
- **Emülatörde ve Cihazda Doğrulananlar:**
  - Kitaplık ekranı, günlük hedef kartı, filtreler, örnek EPUB (`The Quiet Harbour`) içe aktarma ve otomatik açma.
  - EPUB okuyucuda `Literata` fontu ve Sepya temasıyla bölüm yükleme, sayfa sayfa (`paged`) modunda dokunma/kaydırma ile sarsıntısız sayfa geçişi, orta dokunuşla üst/alt araç çubuklarını açma (`Sayfa 1 / 6 · %0 · bölüm ~4 dk`).
  - İçindekiler / Vurgular / Yer İmleri alt sayfası (`Chapter 1: Morning Mist`, `Chapter 2: The Lighthouse`).
  - Kitapla Sohbet alt sayfası (`Bölüm 1/2 · ~784 kelime (spoiler yok)`).
  - Okuma İstatistikleri ekranı (`StatsView`), Kelime Defteri ekranı (`VocabView`), Ayarlar ekranı (`SettingsSheet`) ve Google Drive senkronizasyonu.
  - PDF okuyucu (`PDFLoader` / `PDFReaderView`).
- **Canlı Test Edilmeyenler (Denenmedi):**
  - **Canlı Claude / Gemini API akışı:** Koda API anahtarı gömülmediği ve kullanıcıdan gizli anahtar istenmediği için canlı LLM ağ çağrısı denenmedi (Ayarlar ekranından anahtar girilerek kullanılır).

