# Senkron biçimi (v1)

iOS ve ileride Android aynı biçimi kullanır. Veri, kullanıcının Google Drive hesabındaki
**uygulamaya özel gizli klasörde** (`appDataFolder`) saklanır. İzin kapsamı yalnızca
`https://www.googleapis.com/auth/drive.appdata`.

## Kimlik doğrulama
OAuth 2.0 + PKCE (S256), gizli anahtarsız genel istemci. iOS: iOS tipi istemci kimliği,
geri dönüş `com.googleusercontent.apps.<kimlik>:/oauth2redirect`. Android için Google Cloud'da
ayrıca "Android" tipi bir istemci (paket adı + SHA-1) oluşturulur. `access_type=offline`,
yenileme anahtarı cihazda güvenli depoda tutulur.

## Kitap kimliği
Aynı kitabı iki cihazda eşlemek için kimlik içerikten türetilir:

* **EPUB:** OPF dosyasının (container.xml'in gösterdiği paket dosyası) ham baytlarının SHA-256'sı.
* **PDF:** dosyanın ilk 2 MiB'ı + dosya boyutunun ondalık metni (ör. `"1234567"`) art arda eklenip SHA-256.
* Özetin ilk 16 baytı alınır; bayt 6'nın üst yarısı `0x5`, bayt 8'in üst iki biti `10` yapılır
  (UUID sürüm 5 / RFC 4122 varyantı). Metin hâli **büyük harfli** UUID'dir
  (`XXXXXXXX-XXXX-5XXX-[89AB]XXX-XXXXXXXXXXXX`).

## Dosyalar
Hepsi UTF-8 JSON, tarihler ISO-8601 (`2026-11-06T01:30:00Z`), anahtarlar sıralı.

| Dosya | İçerik | Birleştirme |
|---|---|---|
| `highlights.json` | `{version, items:[Highlight], deleted:{id: tarih}}` | Kayıt başına `updatedAt` (yoksa `createdAt`) yeni olan kazanır. `deleted` damgası kayıttan yeniyse kayıt silinir. |
| `vocab.json` | `{version, items:[VocabEntry], deleted:{id: tarih}}` | Aynı kural (`updatedAt`, yoksa `addedAt`). |
| `bookmarks.json` | `{version, items:[Bookmark], deleted:{id: tarih}}` | Aynı kural (`updatedAt`, yoksa `createdAt`). |
| `progress.json` | `{version, books:{kitapId:{position, scrollFraction, progress, updatedAt}}}` | `updatedAt` yeni olan kazanır. `position`: EPUB'da bölüm (spine) indeksi, PDF'te sayfa indeksi (0'dan). `scrollFraction`: EPUB bölümü içinde 0..1. |
| `reading-log.json` | `{version, entries:[{day, bookID, device, seconds, words, sessions}]}` | Anahtar `day\|bookID\|device`. Aynı anahtarda büyük `seconds` kazanır (sayaç yalnızca artar). Toplamlarda tüm cihazlar toplanır. |
| `library.json` | `{version, items:[LibraryEntry], deleted:{kitapId: tarih}}` | Kayıt başına `updatedAt` yeni olan kazanır (`updatedAt` = etiketlerin son değişimi, yoksa `addedAt`). `deleted` damgası kayıttan yeniyse kitap her cihazdan kaldırılır. |
| `book-<KİTAPID>.epub` / `.pdf` | Kitabın kendisi (kimlik büyük harfli UUID) | Yoksa yükle; başka cihazda kitaba dokununca indir. EPUB, açılmış klasörden yeniden paketlenebilir (`mimetype` ilk girdi, sıkıştırmasız). |
| `cover-<KİTAPID>.jpg` | Kapak | Yoksa yükle; kapağı olmayan cihaz indirir. |
| `settings.json` | `updatedAt` + ayarlar | `updatedAt` yeni olan kazanır. **Yalnızca `highlightColor` uygulanır**; tema, yazı tipi/boyutu, satır aralığı, kenar boşluğu, kolon ve okuma modu cihaza özeldir (ekranlar farklı), dosyada yer alsa da diğer cihaza uygulanmaz. |

Her senkronda sıra: uzak dosyayı indir → yerelle birleştir → yereli güncelle → birleşik sonucu,
uzaktakinden farklıysa yükle. Yerelde bulunmayan kitaplara ait uzak kayıtlar silinmez, korunur.

### LibraryEntry
`id, title, author, kind(epub|pdf), addedAt, updatedAt, tags?, chapterWords?, pagesTotal?`.
Yerelde olmayan kayıt kitaplıkta "bulutta" (indirilmemiş) olarak görünür; dokununca `book-<id>.<kind>` indirilir.
Kitabı yalnızca bir cihazdan kaldırmak `library.json`'a dokunmaz (kitap "bulutta" kalır). "Her yerden sil"
`deleted[id]` damgası koyar ve Drive'daki `book-*`/`cover-*` dosyalarını siler. Kitap sonradan yeniden eklenirse `deleted` kaydı düşer.
Büyük dosyalar Drive'a *resumable* yükleme (`uploadType=resumable`) ile gönderilir. Dosya adları sorgu için sayfalanarak (`nextPageToken`) listelenir.

### Highlight
`id, bookID, bookTitle, text, note, color(yellow|green|blue|pink|orange), style?(highlight|underline|wavy; yoksa highlight), createdAt, updatedAt, location`
ve konum: EPUB için `chapter` + `start`/`end` (bölümün **temiz metni** içindeki karakter aralığı;
`script/style` ve çeviri blokları hariç, tüm metin düğümleri sırayla birleştirilir),
PDF için `page` + `rects` (`[x, y, genişlik, yükseklik]`, sayfa koordinatı, sol-alt orijin).

### Bookmark
`id, bookID, bookTitle, snippet, location, createdAt, updatedAt` ve konum: EPUB için `chapter` + `fraction` (0..1), PDF için `page` (0'dan).

### VocabEntry
`id, word, gloss, explanation, paragraph, bookID, bookTitle, addedAt, box(0-5), dueAt, reviews, updatedAt`.

## Senkronlanmayanlar
Günlük hedef ve hatırlatma ayarları, API anahtarları (Claude/Gemini), LLM önbelleği, çeviri önbelleği.
