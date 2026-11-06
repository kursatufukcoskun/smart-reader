package com.kursat.reader.model

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Kitabın cihazdan bağımsız, içerikten türetilen kimliği (senkron için, bkz. SYNC.md).
 * EPUB: OPF dosyasının SHA-256'sı. PDF: ilk 2 MiB + dosya boyutunun (ondalık metin) SHA-256'sı.
 * Her ikisinde de özetin ilk 16 baytı UUID yapılır (sürüm 5, RFC 4122 varyantı, büyük harfli).
 */
object BookIdentity {
    fun uuidFromDigest(digest: ByteArray): String {
        val b = digest.copyOf(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x50).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        val buf = ByteBuffer.wrap(b)
        val msb = buf.long
        val lsb = buf.long
        return UUID(msb, lsb).toString().uppercase()
    }

    fun epubID(opfFile: File): String? {
        if (!opfFile.exists()) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(opfFile.readBytes())
            uuidFromDigest(md.digest())
        } catch (_: Exception) {
            null
        }
    }

    fun pdfID(file: File): String? {
        if (!file.exists()) return null
        return try {
            val size = file.length()
            val headSize = minOf(size, 2L * 1024L * 1024L).toInt()
            val head = ByteArray(headSize)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(head)
            }
            val md = MessageDigest.getInstance("SHA-256")
            md.update(head)
            md.update(size.toString().toByteArray(Charsets.UTF_8))
            uuidFromDigest(md.digest())
        } catch (_: Exception) {
            null
        }
    }
}

object DeviceID {
    private var cached: String? = null

    fun current(context: Context): String {
        cached?.let { return it }
        val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("deviceID", null)
        if (!existing.isNullOrBlank()) {
            cached = existing
            return existing
        }
        val created = UUID.randomUUID().toString().uppercase()
        prefs.edit().putString("deviceID", created).apply()
        cached = created
        return created
    }
}

