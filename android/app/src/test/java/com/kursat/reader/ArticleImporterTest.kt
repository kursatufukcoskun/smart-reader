package com.kursat.reader

import com.kursat.reader.model.ArticleImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArticleImporterTest {
    private val para = "Bu, makalenin gövdesinde yer alan oldukça uzun bir paragraftır ve yeterli metin içerir. ".repeat(4)

    private val html = """
        <html><head><title>Site | Gerçek Başlık</title>
        <meta property="og:title" content="Gerçek Başlık &amp; Daha Fazlası"><meta name="author" content="Ayşe Yazar"></head>
        <body><nav><p>Ana sayfa Hakkımızda İletişim menü öğesi uzun uzun</p></nav>
        <script>var x = "<p>script içi paragraf olmamalı olmamalı olmamalı</p>";</script>
        <article><header><h1>Gerçek Başlık &amp; Daha Fazlası</h1></header>
        <p>$para</p><h2>Alt başlık</h2><p>$para Kısa &quot;alıntı&quot; ve <em>vurgu</em> &amp; daha.</p>
        <blockquote><p>Bu önemli bir alıntı cümlesidir ve yeterince uzundur.</p></blockquote>
        <ul><li>Birinci madde yeterince uzun olsun diye yazıldı</li><li>kısa</li></ul>
        <figure><img src="a.jpg"><figcaption>Görsel yazısı olmamalı olmamalı</figcaption></figure>
        <p>$para</p></article><footer><p>Telif hakkı bilgisi footer içinde olmamalı olmamalı</p></footer></body></html>
    """.trimIndent()

    @Test
    fun extractsTitleAuthorAndMainContent() {
        val a = ArticleImporter.parse(html, "https://example.com/yazi", "example.com")
        assertEquals("Gerçek Başlık & Daha Fazlası", a.title)
        assertEquals("Ayşe Yazar", a.author)
        // paragraf, alt başlık, paragraf, alıntı, madde, paragraf
        assertEquals(6, a.blocks.size)
        val x = ArticleImporter.chapterXHTML(a)
        assertTrue(x.contains("<h1>Gerçek Başlık &amp; Daha Fazlası</h1>"))
        assertFalse(x.contains("Telif"))
        assertFalse(x.contains("script içi"))
        assertFalse(x.contains("Görsel yazısı"))
    }

    @Test(expected = ArticleImporter.ArticleException::class)
    fun rejectsPagesWithoutArticleText() {
        ArticleImporter.parse("<html><body><p>Çok kısa.</p></body></html>", "https://example.com", "example.com")
    }

    @Test
    fun findsSharedUrl() {
        assertEquals("https://example.com/a?b=1", ArticleImporter.firstUrl("Bak şuna: https://example.com/a?b=1 güzel."))
    }

    @Test
    fun downloadsPdfEpubAndHtmlByContent() {
        val pdf = ("%PDF-1.4\n" + "x".repeat(300)).toByteArray(Charsets.ISO_8859_1)
        val epub = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                val bytes = "application/epub+zip".toByteArray()
                val e = ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong()
                    crc = java.util.zip.CRC32().also { it.update(bytes) }.value
                }
                z.putNextEntry(e); z.write(bytes); z.closeEntry()
            }
        }.toByteArray()
        // Küçük bir soket sunucusu: yola göre PDF / EPUB / HTML döner (Content-Type bilerek yanıltıcı)
        val server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        val htmlBytes = html.toByteArray()
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = try { server.accept() } catch (_: Exception) { break }
                sock.use { c ->
                    val line = c.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = line.split(" ").getOrNull(1).orEmpty()
                    var type = "text/html; charset=utf-8"
                    var extra = ""
                    val body = when {
                        path.startsWith("/makale") -> { type = "application/octet-stream"; extra = "Content-Disposition: attachment; filename=\"Bir Makale.pdf\"\r\n"; pdf }
                        path.startsWith("/kitap") -> { type = "application/epub+zip"; epub }
                        else -> htmlBytes
                    }
                    val out = c.getOutputStream()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n${extra}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(body); out.flush()
                }
            }
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "artest-${System.nanoTime()}").apply { mkdirs() }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val p = runBlocking { ArticleImporter.fetch("$base/makale", dir) }
            assertEquals("Bir Makale.pdf", p.name)
            assertTrue(p.readBytes().decodeToString(0, 4) == "%PDF")
            val e = runBlocking { ArticleImporter.fetch("$base/kitap", dir) }
            assertEquals("kitap.epub", e.name)
            val h = runBlocking { ArticleImporter.fetch("$base/sayfa", dir) }
            assertTrue(h.name.endsWith(".epub"))
            assertTrue(h.name.startsWith("Gerçek Başlık"))
        } finally {
            server.close()
            worker.join(2000)
            dir.deleteRecursively()
        }
    }
}
