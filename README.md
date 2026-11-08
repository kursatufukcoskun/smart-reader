# Smart Reader

Yapay zekâ destekli, kişisel kullanım için geliştirilmiş **EPUB / PDF okuyucu**. Aynı uygulamanın iki yerel sürümü bu depoda yer alır:

| Platform | Teknoloji | Klasör |
|---|---|---|
| iPad / iOS (17+) | Swift, SwiftUI, WidgetKit | [`ios/`](ios) |
| Mac (Mac Catalyst, iPad kodundan) | aynı SwiftUI kodu | [`ios/`](ios) |
| Android (minSdk 26) | Kotlin, Jetpack Compose, Material 3 | [`android/`](android) |

Her iki sürüm de aynı Google Drive senkron biçimini kullanır ([`SYNC.md`](SYNC.md)); böylece kitaplık, vurgular, kelime defteri ve okuma ilerlemesi cihazlar arasında eşlenir.

## Özellikler

**Okuma**
- EPUB (WebView tabanlı) ve PDF okuyucu
- 5 tema: Açık, Sepya, Koyu, Siyah, E-ink
- Yazı tipi (Bookerly / Literata ve sistem fontları), punto, satır aralığı, kenar boşluğu, sıcak ışık filtresi
- Tek/çift kolon; sonsuz kaydırma veya sayfa sayfa mod
- İçindekiler, yer imleri, vurgular paneli

**Kitaplık**
- EPUB/PDF ekleme, kapak (Open Library'den arama), etiketler, arama
- Okunmadı / Okunuyor / Bitti filtreleri, sayfa tabanlı ilerleme (~250 kelime = 1 sanal sayfa)
- Günlük hedef halkası ve okuma serisi

**Yapay zekâ (Claude ve Gemini)**
- Seçili metni **açıkla**, **paragrafı çevir**, **cümleyi çöz**
- Ekrandaki paragrafları toplu çevirme (altına ya da yan yana)
- **Kitapla sohbet:** yalnızca okuduğun kısım bağlam olarak gönderilir (spoiler yok)
- **Bölümü pekiştir:** ana noktalar, önemli kavramlar, 8 soruluk quiz
- **Zor kelimeler:** bölümdeki kelimeleri anlam ve bağlamla çıkarıp deftere ekleme
- Gemini TTS ile sesli okuma
- Model listesi API'den canlı çekilebilir veya elle girilebilir

**Notlar ve öğrenme**
- 5 renkli vurgu, not ekleme, Markdown dışa aktarma
- **Kalem (Apple Pencil / stylus):** kalem modunda metnin üstünü/altını çizerek vurgula, altını çiz veya dalgalı çiz (5 renk); ardından sözlük, çevir, açıkla ya da not al. iPad'de Pencil 2 çift dokunuşu stili değiştirir; Android'de stylus (S Pen vb.) desteklenir. Parmakla kaydırma aynen çalışır. Mac'te kalem modu gizlidir.
- **Kitapta arama** (EPUB + PDF), **dipnot penceresi** (EPUB: dipnota dokununca bölüm değiştirmeden küçük pencerede), **hızlı sözlük** (iOS/Mac: sistem sözlüğü; Android: cihazdaki sözlük uygulaması; yoksa yapay zekâ açıklaması)
- **Yapay zekâ içgörüleri:** vurgular listesinde bölümlerin yanında ✨ ile ayrı görünen içgörüler; "Bu bölümün içgörülerini çıkar" düğmesiyle ya da bölüm pekiştirmedeki ana nokta/kavramlara "Vurgulara ekle" diyerek eklenir. Senin vurgularından ayrıdır: günün vurgusu, kitap karnesi özeti ve vurgu sentezi onları saymaz, dışa aktarmada "yapay zekâ içgörüsü" diye etiketlenir
- **Çalışma → tekrar kartları:** bölüm pekiştirmedeki kavramlar, soru-cevap kartları ve quizde yanlış yaptığın sorular tek dokunuşla kelime defterindeki Leitner kutularına eklenir (aynı soru iki kez eklenmez)
- **Telaffuz 🔊:** kelime kartında, kelime detayında ve açıklama panelinde kelimeyi (ve bağlam cümlesini) sistem sesiyle, dili bağlamdan tahmin ederek okur
- **Yer imi simgesi** konumda yer imi varsa dolu görünür; **sohbet yanıtını nota kaydet**: yanıt, bulunduğun bölüm/sayfaya not olarak yer imlerine girer
- **Okuma takvimi:** istatistiklerde son 52 haftanın her günü için GitHub tarzı ısı haritası
- **Karakter ve ilişki haritası:** okuduğun yere kadar karakterler/kavramlar ve ilişkileri; yapay zekâ yalnızca düğüm-kenar listesi verir, yerleşim ve çizim uygulamada yapılır (kaydır, yakınlaştır, düğüme dokun)
- **Bugünkü kelimelerle hikâye:** kelime defterinde tekrarı gelen 15'e kadar kelimeyi içeren, kalın işaretli mini hikâye + çevirisi
- **Aradan sonra hatırlatma:** kitaba 3+ gün sonra dönünce "Kaldığın yeri 3 cümleyle hatırlat" önerisi (spoiler'sız)
- **Yıllık kitap hedefi:** İstatistiklerde "Bu yıl X / Y kitap" ve bitirilen kitaplar listesi
- **Kelime ipuçları:** ekrandaki paragraflarda zor kelimelerin üstüne küçük `<ruby>` karşılığı (yoğunluk: az/orta/çok); çeviriyi okumayı bölmeden yapar, gizlenebilir/silinebilir (EPUB)
- **Biyonik okuma** ve **satır odaklayıcı** (okunan şerit dışını karartır, tutamaçla taşınır): ayarlardan açılıp kapatılır (EPUB)
- **Kitap karnesi:** kitabı bitirince (ya da kitaplıkta uzun basınca) okuma süresi, gün/oturum, vurgu-not-kelime-kart sayıları ve vurgularından yapay zekâ kapanış özeti
- **Günün vurgusu:** kitaplıkta her gün eski bir vurgu; kitapta aç ya da **alıntı kartı** (kitap adı, yazar, okuma temasıyla görsel) olarak paylaş
- **PDF kenar kırpma:** sayfa kenarlarındaki boş beyaz boşluğu belgeden örnekleyip tek ayarla kırpar
- **Web adresinden ekle:** adresi yapıştır (Android'de tarayıcıdan Paylaş ile de olur). Bağlantı **PDF ya da EPUB** ise dosya olduğu gibi kitaplığa alınır (tür, uzantıya değil içeriğe bakılarak anlaşılır; arXiv gibi sitelerin doğrudan PDF bağlantıları dahil); web sayfasıysa metni ayıklanıp tek bölümlük kitap olarak eklenir, çeviri/vurgu/sohbet araçlarının hepsi çalışır
- **Vurgudan tekrar kartı:** vurgu ekranında "Bu vurgudan kart yap" yapay zekâ ile soru/cevap kartı üretir ve kelime defterindeki Leitner kutularında tekrar edilir
- **Kitaplık:** sıralama (son okunan, eklenme, başlık, yazar, ilerleme) ve bozuk başlık/yazarı düzenleme (diğer cihazlara senkronlanır)
- **Tipografi ve tema:** hizalama (kitaba göre/sola/iki yana), tireleme, sistem açık/koyu görünümüyle otomatik tema eşleme
- **Eleştirel okuma:** seçili pasaj için iddia, örtük varsayımlar ve karşı argümanlar
- **Konum uyarısı:** başka cihazda daha yeni/farklı bir konum varsa senkron sessizce üzerine yazmaz; okuyucuda "Diğer cihazda kaldığın yer · Oraya geç / Burada kal" sorulur
- **Görsel büyütme** (EPUB): görsele dokununca tam ekran, iki parmakla yakınlaştırma, çift dokunmayla büyütme
- **Yapay zekâ kısayolları:** "Şimdiye kadar ne oldu?", karakter/kavram rehberi ve seçili kelime için "Kim/Ne bu?" (hepsi yalnızca okuduğun kısma dayanır, spoiler yok); vurgular ekranında etikete göre süzüp yapay zekâ ile sentez
- **Sesli okuma uyku zamanlayıcısı:** 15/30/45/60 dk ya da bölüm sonunda durdur
- **Atlama çubuğu:** içindekiler/yer imi/vurgu/arama/dipnot ile bir yere atlayınca "Kalıcı olsun mu?" sorulur; "Geri dön" atlamadan önceki yere götürür (kitap kapansa da saklanır, çubuk sürüklenebilir)
- Çeviri düzeni: altında, yan yana veya **yerine** (orijinal gizlenir, ikona dokununca orijinal görünür; EPUB)
- Kelime defteri: Leitner (0–5 kutu) aralıklı tekrar, Anki uyumlu `.txt` dışa aktarma

**İstatistik ve hatırlatmalar**
- Aktif okuma süresi, okuma hızı, seri, süre/sayfa grafikleri, bitiş tahmini
- Günlük okuma ve kelime tekrarı bildirimleri
- Ana ekran widget'ı (son kitap, ilerleme, günlük hedef)

**Senkronizasyon**
- Google Drive `appDataFolder` (yalnızca `drive.appdata` kapsamı), OAuth 2.0 + PKCE
- Kayıt bazlı `updatedAt` birleştirme ve silme için mezar taşı (tombstone) kuralları

## Proje Yapısı

```
smart-reader/
├── ios/                         # iOS uygulaması
│   ├── Reader.xcodeproj
│   ├── Reader/
│   │   ├── AI/                  # Claude/Gemini istemcileri, istemler, sohbet, çalışma modu
│   │   ├── EPUB/                # container.xml → OPF → spine ayrıştırma, ZIP okuma/yazma
│   │   ├── Models/              # Kitap, vurgu, yer imi, kelime, ilerleme, bildirim
│   │   ├── Speech/              # Gemini TTS ve konuşma denetimi
│   │   ├── Sync/                # Google OAuth ve Drive senkronu
│   │   └── Views/               # SwiftUI ekranları
│   ├── ReaderWidget/            # iOS widget
│   └── Config/                  # Info.plist ve entitlements
├── android/                     # Android uygulaması (ayrıntı: android/README.md)
├── SYNC.md                      # Senkron biçimi (iOS ve Android için bağlayıcı sözleşme)
└── ANDROID_PROMPT.md            # Android portu için hazırlanan görev tanımı
```

## Derleme

### iOS
1. `ios/Reader.xcodeproj` dosyasını Xcode ile aç.
2. `ios/Config/` altındaki entitlements ve Info.plist değerlerini kendi geliştirici hesabına göre ayarla (bundle ID, App Group, Google istemci kimliği).
3. `Reader` şemasını seç ve iPad simülatöründe/cihazda çalıştır (iOS 17.0+).

### Android
Android Studio ile `android/` klasörünü aç veya komut satırından:

```bash
cd android
./gradlew assembleDebug testDebugUnitTest
```

Ayrıntılar, Google girişi kurulumu ve aşama notları için [`android/README.md`](android/README.md) dosyasına bak.

## Yapılandırma

- **API anahtarları:** Claude ve Gemini anahtarları koda gömülmez; uygulamada **Ayarlar** ekranından girilir. iOS'ta Keychain, Android'de `EncryptedSharedPreferences` içinde saklanır.
- **Google Drive senkronu:** Google Cloud Console'da `drive.appdata` kapsamlı bir OAuth projesi gerekir. iOS için *iOS* tipi, Android için paket adı + SHA-1 ile *Android* tipi istemci kimliği oluşturulur (bkz. [`SYNC.md`](SYNC.md)).
- **Senkronlanmayanlar:** API anahtarları, günlük hedef/hatırlatma ayarları, LLM ve çeviri önbellekleri ile ekrana özgü okuma ayarları (tema, yazı tipi vb.) cihazda kalır.

## Not

Bu proje kişisel kullanım içindir; mağazada yayımlanması planlanmamıştır. Birlikte gelen Bookerly yazı tipi dosyaları kendi lisanslarına tabidir.

## Mac

iPad uygulaması Mac Catalyst ile Mac'te de çalışır (aynı kod tabanı). Xcode'da hedef olarak **My Mac (Mac Catalyst)** seçilir; imza için Apple geliştirici hesabı gerekir. Klavye: **→ / Boşluk / Page Down** ileri, **← / Shift+Boşluk / Page Up** geri (okuyucuya tıklayınca). Kalem modu Mac'te gizlidir; fareyle metin seçip vurgulama aynen çalışır.
