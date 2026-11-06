package com.kursat.reader.epub

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object ZipArchive {
    fun extractAll(zipFile: File, destDir: File) {
        destDir.mkdirs()
        val canonicalRoot = destDir.canonicalPath + File.separator
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val target = File(destDir, entry.name)
                // zip-slip koruması
                val canonicalTarget = target.canonicalPath
                if (!canonicalTarget.startsWith(canonicalRoot)) continue
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

