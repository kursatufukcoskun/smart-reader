package com.kursat.reader.model

import com.kursat.reader.epub.ZipWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Web makalesini indirip ana metnini ayıklar ve tek bölümlük bir EPUB dosyasına çevirir.
 * Böylece makale de kitaplıkta açılır: çeviri, vurgu, kelime defteri ve sohbet araçları aynen çalışır.
 */
object ArticleImporter {
    class ArticleException(message: String) : Exception(message)

    enum class Kind { heading, paragraph, quote, item, pre }
    data class Block(val kind: Kind, val text: String, val level: Int = 0)
    data class Article(val title: String, val author: String, val blocks: List<Block>, val url: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Paylaşılan metinden ilk web adresini çıkarır (yoksa null). */
    fun firstUrl(text: String): String? =
        Regex("https?://[^\\s<>\"']+").find(text)?.value?.trimEnd('.', ',', ')', ';')

    /**
     * Adresten içe aktarılabilir bir dosya hazırlar: adres PDF ya da EPUB ise dosya olduğu gibi indirilir;
     * HTML ise makale metni ayıklanıp tek bölümlük EPUB üretilir. Tür, uzantıya değil içeriğe bakılarak anlaşılır.
     */
    suspend fun fetch(raw: String, workDir: File): File = withContext(Dispatchers.IO) {
        val address = raw.trim()
        val uri = try { URI(address) } catch (_: Exception) { null }
        if (uri == null || uri.host == null || (uri.scheme != "http" && uri.scheme != "https")) {
            throw ArticleException("Geçerli bir web adresi gir (http veya https).")
        }
        val req = Request.Builder().url(address)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Tablet) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept", "application/pdf,application/epub+zip,text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            .build()
        workDir.mkdirs()
        val tmp = File(workDir, "dl-${UUID.randomUUID()}.tmp")
        val (charset, disposition, mime) = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ArticleException("Sayfa indirilemedi (HTTP ${resp.code}).")
            val body = resp.body ?: throw ArticleException("Sayfa boş geldi.")
            tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            Triple(body.contentType()?.charset() ?: Charsets.UTF_8, resp.header("Content-Disposition").orEmpty(), (resp.header("Content-Type") ?: "").lowercase())
        }
        val head = tmp.inputStream().use { ins -> ByteArray(256).let { buf -> buf.copyOf(maxOf(0, ins.read(buf))) } }
        val headText = String(head, Charsets.ISO_8859_1)
        val ext = when {
            headText.startsWith("%PDF") -> "pdf"
            headText.startsWith("PK") && (headText.contains("application/epub+zip") || mime.contains("epub")) -> "epub"
            else -> null
        }
        if (ext != null) {
            val out = File(workDir, fileName(address, disposition, ext))
            out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            return@withContext out
        }
        val html = try { String(tmp.readBytes(), charset) } finally { tmp.delete() }
        val article = parse(html, address, uri.host)
        writeEPUB(article, workDir)
    }

    /** İndirilen PDF/EPUB için dosya adı: Content-Disposition, yoksa adresin son parçası. */
    private fun fileName(address: String, disposition: String, ext: String): String {
        var base = Regex("filename\\*?=(?:UTF-8'')?[\"']?([^\"';]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()
        if (base.isEmpty()) {
            val last = runCatching { URI(address).path.orEmpty().substringAfterLast('/') }.getOrDefault("")
            base = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        }
        if (base.isEmpty()) base = runCatching { URI(address).host }.getOrNull() ?: "Belge"
        base = base.substringBeforeLast('.', base)
        val safe = base.replace(Regex("[/\\\\:?*\"<>|\\n]"), " ").trim().take(80).ifEmpty { "Belge" }
        return "$safe.$ext"
    }

    private fun first(pattern: String, s: String): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(s)?.groupValues?.getOrNull(1)

    private fun meta(key: String, html: String): String? {
        val k = Regex.escape(key)
        return first("<meta[^>]+(?:property|name)\\s*=\\s*[\"']$k[\"'][^>]*content\\s*=\\s*[\"']([^\"']*)[\"']", html)
            ?: first("<meta[^>]+content\\s*=\\s*[\"']([^\"']*)[\"'][^>]*(?:property|name)\\s*=\\s*[\"']$k[\"']", html)
    }

    private fun clean(s: String): String =
        HTMLText.plain(s).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun parse(html: String, url: String, host: String): Article {
        var title = clean(meta("og:title", html) ?: first("<title[^>]*>([\\s\\S]*?)</title>", html) ?: host)
        if (title.isEmpty()) title = host
        val author = clean(meta("author", html) ?: meta("article:author", html) ?: meta("og:site_name", html) ?: host)

        // Gürültüyü at: yorumlar, betikler, gezinme, başlık/altlık, yan içerik, formlar, görsel bloklar
        var s = html.replace(Regex("<!--[\\s\\S]*?-->"), " ")
        s = s.replace(
            Regex("<(script|style|noscript|svg|iframe|form|nav|header|footer|aside|figure|button|select|template)\\b[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE),
            " "
        )

        // Ana içerik: en uzun <article>, yoksa <main>, yoksa <body>
        fun longest(tag: String): String? =
            Regex("<$tag\\b[^>]*>([\\s\\S]*?)</$tag\\s*>", RegexOption.IGNORE_CASE).findAll(s)
                .map { it.groupValues[1] }.maxByOrNull { it.length }
        val root = longest("article") ?: longest("main") ?: longest("body") ?: s

        val blockRe = Regex("<(h[1-6]|p|blockquote|pre|li)\\b[^>]*>([\\s\\S]*?)</\\1\\s*>", RegexOption.IGNORE_CASE)
        val blocks = ArrayList<Block>()
        for (m in blockRe.findAll(root)) {
            val tag = m.groupValues[1].lowercase()
            val inner = m.groupValues[2]
            val text = if (tag == "pre") HTMLText.plain(inner) else clean(inner)
            val n = text.length
            val b: Block = when (tag) {
                "p" -> {
                    val ends = text.lastOrNull()?.let { it in ".!?:…”\"’)" } ?: false
                    if (!(n >= 30 || (n >= 15 && ends))) continue
                    Block(Kind.paragraph, text)
                }
                "li" -> { if (n < 20) continue; Block(Kind.item, text) }
                "blockquote" -> { if (n < 20) continue; Block(Kind.quote, text) }
                "pre" -> { if (n < 10) continue; Block(Kind.pre, text) }
                else -> { if (n !in 3..150) continue; Block(Kind.heading, text, tag.drop(1).toIntOrNull() ?: 2) }
            }
            if (blocks.lastOrNull()?.text == text) continue
            blocks.add(b)
        }

        // Başlıkta yinelenen ilk <h1>'i at (başlığı kendimiz ekliyoruz)
        val firstB = blocks.firstOrNull()
        if (firstB != null && firstB.kind == Kind.heading && firstB.level == 1 && firstB.text.equals(title, ignoreCase = true)) {
            blocks.removeAt(0)
        }
        if (blocks.sumOf { it.text.length } < 500) {
            throw ArticleException("Makale metni çıkarılamadı. Sayfa giriş ya da JavaScript gerektiriyor olabilir.")
        }
        return Article(title, author, blocks, url)
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun chapterXHTML(a: Article): String {
        val sb = StringBuilder()
        sb.append("<h1>").append(xml(a.title)).append("</h1>\n")
        if (a.author.isNotEmpty()) sb.append("<p><em>").append(xml(a.author)).append("</em></p>\n")
        var inList = false
        for (b in a.blocks) {
            if (b.kind != Kind.item && inList) { sb.append("</ul>\n"); inList = false }
            when (b.kind) {
                Kind.heading -> { val lv = b.level.coerceIn(2, 4); sb.append("<h$lv>").append(xml(b.text)).append("</h$lv>\n") }
                Kind.paragraph -> sb.append("<p>").append(xml(b.text)).append("</p>\n")
                Kind.quote -> sb.append("<blockquote><p>").append(xml(b.text)).append("</p></blockquote>\n")
                Kind.pre -> sb.append("<pre>").append(xml(b.text)).append("</pre>\n")
                Kind.item -> {
                    if (!inList) { sb.append("<ul>\n"); inList = true }
                    sb.append("<li>").append(xml(b.text)).append("</li>\n")
                }
            }
        }
        if (inList) sb.append("</ul>\n")
        val host = try { URI(a.url).host ?: a.url } catch (_: Exception) { a.url }
        sb.append("<p><a href=\"").append(xml(a.url)).append("\">").append(xml(host)).append("</a></p>\n")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta charset=\"utf-8\"/><title>${xml(a.title)}</title></head>\n" +
            "<body>\n$sb</body></html>"
    }

    private fun writeEPUB(a: Article, workParent: File): File {
        val work = File(workParent, "article-${UUID.randomUUID()}").apply { mkdirs() }
        File(work, "META-INF").mkdirs()
        File(work, "OEBPS").mkdirs()
        File(work, "mimetype").writeText("application/epub+zip", Charsets.US_ASCII)
        File(work, "META-INF/container.xml").writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                "  <rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles>\n" +
                "</container>"
        )
        File(work, "OEBPS/content.opf").writeText(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">\n" +
                "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
                "    <dc:identifier id=\"bookid\">urn:uuid:${UUID.randomUUID()}</dc:identifier>\n" +
                "    <dc:title>${xml(a.title)}</dc:title>\n" +
                "    <dc:creator>${xml(a.author)}</dc:creator>\n" +
                "    <dc:language>en</dc:language>\n" +
                "    <dc:source>${xml(a.url)}</dc:source>\n" +
                "  </metadata>\n" +
                "  <manifest><item id=\"ch1\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>\n" +
                "  <spine><itemref idref=\"ch1\"/></spine>\n" +
                "</package>"
        )
        File(work, "OEBPS/chapter.xhtml").writeText(chapterXHTML(a))

        val safe = a.title.replace(Regex("[/\\\\:?*\"<>|\\n]"), " ").trim().take(60).ifEmpty { "Makale" }
        val out = File(workParent, "$safe.epub")
        out.delete()
        ZipWriter.zipDirectory(work, out)
        work.deleteRecursively()
        return out
    }
}
