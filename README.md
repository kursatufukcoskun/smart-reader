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
- **Apple Pencil (iPad):** kalem modunda metnin üstünü/altını çizerek vurgula, altını çiz veya dalgalı çiz (5 renk); ardından çevir, açıkla ya da not al. Pencil 2 çift dokunuşu stili değiştirir. Parmakla kaydırma aynen çalışır.
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
