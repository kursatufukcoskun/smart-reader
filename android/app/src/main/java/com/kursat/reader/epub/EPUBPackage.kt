package com.kursat.reader.epub

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.util.UUID

data class SpineItem(
    val id: String,
    val file: File
)

data class TOCEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val level: Int,
    val spineIndex: Int,
    val fragment: String? = null
)

private fun normalizedPath(file: File): String = try {
    file.canonicalPath
} catch (_: Exception) {
    file.absolutePath
}

private fun localName(name: String?): String {
    if (name == null) return ""
    val idx = name.lastIndexOf(':')
    return if (idx >= 0) name.substring(idx + 1) else name
}

data class EPUBPackage(
    val rootDir: File,
    val opfFile: File,
    val title: String,
    val author: String,
    val spine: List<SpineItem>,
    val toc: List<TOCEntry>,
    val coverFile: File?
) {
    class EPUBException(message: String) : Exception(message)

    companion object {
        fun load(rootDir: File): EPUBPackage {
            val containerFile = File(rootDir, "META-INF/container.xml")
            if (!containerFile.exists()) throw EPUBException("container.xml bulunamadı.")

            val opfRelPath = parseContainer(containerFile)
                ?: throw EPUBException("container.xml bulunamadı.")
            val decodedOpfRel = try {
                URLDecoder.decode(opfRelPath, "UTF-8")
            } catch (_: Exception) {
                opfRelPath
            }
            val opfFile = File(rootDir, decodedOpfRel)
            if (!opfFile.exists()) throw EPUBException("OPF dosyası okunamadı.")

            val opfData = parseOPF(opfFile)

            fun resolve(href: String, baseFile: File): Pair<File, String?> {
                val parts = href.split("#", limit = 2)
                val raw = parts.firstOrNull().orEmpty()
                val decoded = try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (_: Exception) {
                    raw
                }
                val fragment = if (parts.size > 1) parts[1] else null
                val resolved = File(baseFile.parentFile ?: rootDir, decoded)
                return resolved to fragment
            }

            val spine = mutableListOf<SpineItem>()
            for (idref in opfData.spineIDs) {
                val item = opfData.manifest[idref] ?: continue
                val (file, _) = resolve(item.href, opfFile)
                spine.add(SpineItem(id = idref, file = file))
            }
            if (spine.isEmpty()) throw EPUBException("Kitapta okunabilir bölüm yok.")

            // İçindekiler: önce EPUB3 nav, yoksa NCX
            var tocFile: File? = null
            val navItem = opfData.manifest.values.firstOrNull {
                it.props.split(Regex("\\s+")).contains("nav") || it.props.contains("nav")
            }
            if (navItem != null) {
                tocFile = resolve(navItem.href, opfFile).first
            } else if (opfData.spineTOCID != null && opfData.manifest[opfData.spineTOCID] != null) {
                tocFile = resolve(opfData.manifest[opfData.spineTOCID]!!.href, opfFile).first
            } else {
                val ncxItem = opfData.manifest.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
                if (ncxItem != null) {
                    tocFile = resolve(ncxItem.href, opfFile).first
                }
            }

            val toc = mutableListOf<TOCEntry>()
            if (tocFile != null && tocFile.exists()) {
                val rawEntries = parseTOC(tocFile)
                val spinePaths = spine.map { normalizedPath(it.file) }
                for (raw in rawEntries) {
                    if (raw.title.isBlank() || raw.src.isBlank()) continue
                    val (resolvedFile, frag) = resolve(raw.src, tocFile)
                    val targetPath = normalizedPath(resolvedFile)
                    val idx = spinePaths.indexOf(targetPath)
                    if (idx >= 0) {
                        toc.add(
                            TOCEntry(
                                title = raw.title,
                                level = raw.level,
                                spineIndex = idx,
                                fragment = frag
                            )
                        )
                    }
                }
            }
            if (toc.isEmpty()) {
                spine.forEachIndexed { index, _ ->
                    toc.add(TOCEntry(title = "Bölüm ${index + 1}", level = 0, spineIndex = index, fragment = null))
                }
            }

            // Kapak
            var cover: File? = null
            val coverPropItem = opfData.manifest.values.firstOrNull { it.props.contains("cover-image") }
            if (coverPropItem != null) {
                cover = resolve(coverPropItem.href, opfFile).first
            } else if (opfData.coverMetaID != null && opfData.manifest[opfData.coverMetaID] != null) {
                cover = resolve(opfData.manifest[opfData.coverMetaID]!!.href, opfFile).first
            }

            return EPUBPackage(
                rootDir = rootDir,
                opfFile = opfFile,
                title = opfData.title,
                author = opfData.author,
                spine = spine,
                toc = toc,
                coverFile = cover?.takeIf { it.exists() }
            )
        }

        private fun newParser(): XmlPullParser {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            return factory.newPullParser()
        }

        private fun parseContainer(file: File): String? {
            return try {
                FileInputStream(file).use { fis ->
                    val parser = newParser()
                    parser.setInput(fis, null)
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG && localName(parser.name) == "rootfile") {
                            for (i in 0 until parser.attributeCount) {
                                if (localName(parser.getAttributeName(i)) == "full-path") {
                                    return parser.getAttributeValue(i)
                                }
                            }
                        }
                        event = parser.next()
                    }
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        private data class ManifestItem(val href: String, val type: String, val props: String)
        private class OPFData {
            var title: String = ""
            var author: String = ""
            val manifest = mutableMapOf<String, ManifestItem>()
            val spineIDs = mutableListOf<String>()
            var spineTOCID: String? = null
            var coverMetaID: String? = null
        }

        private fun parseOPF(file: File): OPFData {
            val data = OPFData()
            try {
                FileInputStream(file).use { fis ->
                    val parser = newParser()
                    parser.setInput(fis, null)
                    var event = parser.eventType
                    val buffer = StringBuilder()
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                buffer.clear()
                                val tag = localName(parser.name)
                                fun attr(name: String): String? {
                                    for (i in 0 until parser.attributeCount) {
                                        if (localName(parser.getAttributeName(i)) == name || parser.getAttributeName(i) == name) {
                                            return parser.getAttributeValue(i)
                                        }
                                    }
                                    return null
                                }
                                when (tag) {
                                    "item" -> {
                                        val id = attr("id")
                                        val href = attr("href")
                                        if (id != null && href != null) {
                                            data.manifest[id] = ManifestItem(
                                                href = href,
                                                type = attr("media-type").orEmpty(),
                                                props = attr("properties").orEmpty()
                                            )
                                        }
                                    }
                                    "itemref" -> {
                                        attr("idref")?.let { data.spineIDs.add(it) }
                                    }
                                    "spine" -> {
                                        data.spineTOCID = attr("toc")
                                    }
                                    "meta" -> {
                                        if (attr("name") == "cover") {
                                            attr("content")?.let { data.coverMetaID = it }
                                        }
                                    }
                                }
                            }
                            XmlPullParser.TEXT -> {
                                buffer.append(parser.text.orEmpty())
                            }
                            XmlPullParser.END_TAG -> {
                                val text = buffer.toString().trim()
                                when (localName(parser.name)) {
                                    "title" -> if (data.title.isEmpty() && text.isNotEmpty()) data.title = text
                                    "creator" -> if (data.author.isEmpty() && text.isNotEmpty()) data.author = text
                                }
                                buffer.clear()
                            }
                        }
                        event = parser.next()
                    }
                }
            } catch (_: Exception) {
            }
            return data
        }

        private data class RawTOC(var title: String, var src: String, val level: Int)

        private fun parseTOC(file: File): List<RawTOC> {
            val entries = mutableListOf<RawTOC>()
            try {
                FileInputStream(file).use { fis ->
                    val parser = newParser()
                    parser.setInput(fis, null)
                    var event = parser.eventType

                    val navPointStack = mutableListOf<Int>()
                    val textBuf = StringBuilder()
                    var inTocNav = false
                    var tocNavDone = false
                    var olDepth = 0
                    var aHref: String? = null
                    val aText = StringBuilder()

                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                val tag = localName(parser.name)
                                fun attr(name: String): String? {
                                    for (i in 0 until parser.attributeCount) {
                                        if (parser.getAttributeName(i) == name || localName(parser.getAttributeName(i)) == name) {
                                            return parser.getAttributeValue(i)
                                        }
                                    }
                                    return null
                                }
                                when (tag) {
                                    "navPoint" -> {
                                        entries.add(RawTOC(title = "", src = "", level = navPointStack.size))
                                        navPointStack.add(entries.size - 1)
                                    }
                                    "text" -> textBuf.clear()
                                    "content" -> {
                                        val last = navPointStack.lastOrNull()
                                        if (last != null) {
                                            entries[last].src = attr("src").orEmpty()
                                        }
                                    }
                                    "nav" -> {
                                        val t = attr("epub:type") ?: attr("type") ?: ""
                                        if (t.contains("toc") && !tocNavDone) {
                                            inTocNav = true
                                        }
                                    }
                                    "ol" -> if (inTocNav) olDepth += 1
                                    "a" -> if (inTocNav) {
                                        aHref = attr("href")
                                        aText.clear()
                                    }
                                }
                            }
                            XmlPullParser.TEXT -> {
                                val t = parser.text.orEmpty()
                                textBuf.append(t)
                                if (aHref != null) aText.append(t)
                            }
                            XmlPullParser.END_TAG -> {
                                when (localName(parser.name)) {
                                    "text" -> {
                                        val last = navPointStack.lastOrNull()
                                        if (last != null && entries[last].title.isEmpty()) {
                                            entries[last].title = textBuf.toString().trim()
                                        }
                                    }
                                    "navPoint" -> {
                                        if (navPointStack.isNotEmpty()) navPointStack.removeAt(navPointStack.lastIndex)
                                    }
                                    "nav" -> {
                                        if (inTocNav) {
                                            inTocNav = false
                                            tocNavDone = true
                                        }
                                    }
                                    "ol" -> if (inTocNav) olDepth -= 1
                                    "a" -> {
                                        val h = aHref
                                        if (inTocNav && h != null) {
                                            val clean = aText.toString().trim().split(Regex("\\s+")).joinToString(" ")
                                            entries.add(RawTOC(title = clean, src = h, level = maxOf(olDepth - 1, 0)))
                                            aHref = null
                                        }
                                    }
                                }
                            }
                        }
                        event = parser.next()
                    }
                }
            } catch (_: Exception) {
            }
            return entries
        }
    }
}

