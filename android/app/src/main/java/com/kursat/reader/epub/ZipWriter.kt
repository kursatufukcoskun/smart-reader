package com.kursat.reader.epub

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Açılmış EPUB klasöründen yeniden EPUB (ZIP) üretir. `mimetype` ilk girdi ve sıkıştırmasızdır. */
object ZipWriter {
    fun zipDirectory(dir: File, out: File) {
        val root = dir.canonicalFile
        val rels = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toMutableList()
        rels.sortWith(compareBy<String>({ it != "mimetype" }, { it }))
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            for (rel in rels) {
                val f = File(root, rel)
                val entry = ZipEntry(rel)
                if (rel == "mimetype") {
                    val bytes = f.readBytes()
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                } else {
                    zos.putNextEntry(entry)
                    f.inputStream().use { it.copyTo(zos) }
                }
                zos.closeEntry()
            }
        }
    }
}
