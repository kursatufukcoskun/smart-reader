package com.kursat.reader

import com.kursat.reader.model.*
import com.kursat.reader.sync.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SyncContractTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testBookIdentityDeterministicV5UUID() {
        val tmpOpf = File.createTempFile("content", ".opf")
        try {
            val sampleOpf = "<package version=\"3.0\"><metadata><dc:title>Test</dc:title></metadata></package>"
            tmpOpf.writeBytes(sampleOpf.toByteArray(Charsets.UTF_8))

            val id1 = BookIdentity.epubID(tmpOpf)
            val id2 = BookIdentity.epubID(tmpOpf)
            assertNotNull(id1)
            assertEquals(id1, id2)

            // RFC 4122 v5 UUID format check: XXXXXXXX-XXXX-5XXX-[89AB]XXX-XXXXXXXXXXXX uppercase
            val regex = Regex("^[0-9A-F]{8}-[0-9A-F]{4}-5[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$")
            assertTrue("Expected v5 uppercase UUID, got $id1", regex.matches(id1!!))
        } finally {
            tmpOpf.delete()
        }
    }

    @Test
    fun testSyncMergeNewestStampAndTombstoneRules() {
        val h1Old = Highlight(
            id = "11111111-2222-3333-4444-555555555555",
            bookID = "AAAAAAAA-BBBB-5CCC-8DDD-EEEEEEEEEEEE",
            bookTitle = "Test Book",
            text = "First text",
            note = "Old note",
            color = HighlightColor.yellow,
            createdAt = "2026-11-06T08:00:00Z",
            updatedAt = "2026-11-06T08:00:00Z"
        )
        val h1New = h1Old.copy(
            note = "Updated note on iPad",
            color = HighlightColor.green,
            updatedAt = "2026-11-06T09:30:00Z"
        )
        val h2Deleted = Highlight(
            id = "22222222-2222-3333-4444-555555555555",
            bookID = "AAAAAAAA-BBBB-5CCC-8DDD-EEEEEEEEEEEE",
            bookTitle = "Test Book",
            text = "Deleted text",
            createdAt = "2026-11-06T07:00:00Z",
            updatedAt = "2026-11-06T07:00:00Z"
        )

        val (mergedItems, mergedDeleted) = SyncMerge.items(
            local = listOf(h1Old, h2Deleted),
            remote = listOf(h1New),
            localDeleted = emptyMap(),
            remoteDeleted = mapOf(h2Deleted.id to "2026-11-06T09:00:00Z"),
            idOf = { it.id },
            stampOf = { it.stamp }
        )

        assertEquals(1, mergedItems.size)
        assertEquals("Updated note on iPad", mergedItems[0].note)
        assertEquals(HighlightColor.green, mergedItems[0].color)
        assertTrue(mergedDeleted.containsKey(h2Deleted.id))
    }

    @Test
    fun testWireJsonCompatibilityWithIOSSyncManager() {
        // Sample JSON produced by iOS SyncManager.swift for progress.json and highlights.json
        val iosProgressJson = """
            {"books":{"AAAAAAAA-BBBB-5CCC-8DDD-EEEEEEEEEEEE":{"position":2,"progress":0.42,"scrollFraction":0.35,"updatedAt":"2026-11-06T09:00:00Z"}},"version":1}
        """.trimIndent()

        val progressFile = json.decodeFromString<ProgressFile>(iosProgressJson)
        assertEquals(1, progressFile.version)
        val rec = progressFile.books["AAAAAAAA-BBBB-5CCC-8DDD-EEEEEEEEEEEE"]
        assertNotNull(rec)
        assertEquals(2, rec!!.position)
        assertEquals(0.35, rec.scrollFraction, 1e-6)

        val iosHighlightsJson = """
            {"deleted":{"22222222-2222-3333-4444-555555555555":"2026-11-06T09:00:00Z"},"items":[{"bookID":"AAAAAAAA-BBBB-5CCC-8DDD-EEEEEEEEEEEE","bookTitle":"Sample","chapter":1,"color":"yellow","createdAt":"2026-11-06T08:00:00Z","end":45,"id":"11111111-2222-3333-4444-555555555555","location":"Bölüm 2","note":"Önemli","start":10,"text":"Merhaba dünya","updatedAt":"2026-11-06T08:30:00Z"}],"version":1}
        """.trimIndent()

        val hlFile = json.decodeFromString<ItemsFile<Highlight>>(iosHighlightsJson)
        assertEquals(1, hlFile.items.size)
        assertEquals("Önemli", hlFile.items[0].note)
        assertEquals(10, hlFile.items[0].start)
        assertEquals(45, hlFile.items[0].end)

        val reEncoded = json.encodeToString(hlFile)
        val roundTrip = json.decodeFromString<ItemsFile<Highlight>>(reEncoded)
        assertEquals(hlFile, roundTrip)
    }

    @Test
    fun testLibraryJsonFromIosAndZipRoundTrip() {
        // iOS (Codable, sortedKeys, nil alanlar yok) biçimi
        val ios = """{"deleted":{"AAAAAAAA-BBBB-5CCC-8DDD-EEEEEEEEEEEE":"2026-11-06T10:00:00Z"},"items":[{"addedAt":"2026-11-01T09:00:00Z","author":"Yazar","chapterWords":[100,200],"id":"11111111-2222-5333-8444-555555555555","kind":"epub","pagesTotal":2,"tags":["teknik"],"title":"Kitap","updatedAt":"2026-11-05T09:00:00Z"},{"addedAt":"2026-11-02T09:00:00Z","author":"","id":"22222222-2222-5333-8444-555555555555","kind":"pdf","title":"PDF","updatedAt":"2026-11-02T09:00:00Z"}],"version":1}"""
        val lenient = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
        val f = lenient.decodeFromString<ItemsFile<LibraryEntry>>(ios)
        assertEquals(2, f.items.size)
        assertEquals(listOf("teknik"), f.items[0].tags)
        assertNull(f.items[1].tags)
        // Android'in yazdığı JSON'da null alan bulunmamalı
        val out = lenient.encodeToString(f)
        assertFalse(out.contains("null"))
        assertEquals(
            lenient.parseToJsonElement(ios).toString().length,
            lenient.parseToJsonElement(out).toString().length
        )

        // EPUB yeniden paketleme: mimetype ilk girdi ve sıkıştırmasız
        val dir = java.nio.file.Files.createTempDirectory("epubdir").toFile()
        val zip = File.createTempFile("book", ".epub")
        try {
            File(dir, "mimetype").writeText("application/epub+zip")
            File(dir, "META-INF").mkdirs()
            File(dir, "META-INF/container.xml").writeText("<container/>".repeat(50))
            com.kursat.reader.epub.ZipWriter.zipDirectory(dir, zip)
            java.util.zip.ZipFile(zip).use { z ->
                val names = z.entries().toList().map { it.name }
                assertEquals("mimetype", names.first())
                assertEquals(java.util.zip.ZipEntry.STORED, z.getEntry("mimetype").method)
                assertEquals("<container/>".repeat(50), z.getInputStream(z.getEntry("META-INF/container.xml")).readBytes().toString(Charsets.UTF_8))
            }
        } finally {
            dir.deleteRecursively(); zip.delete()
        }
    }
}
