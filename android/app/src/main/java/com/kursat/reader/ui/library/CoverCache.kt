package com.kursat.reader.ui.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kütüphane kapakları: tam çözünürlük yerine kart boyutuna küçültülerek, ana iş parçacığı dışında çözülür ve
 * bellekte önbelleğe alınır; böylece kaydırırken kartlar ekrana girerken takılma olmaz.
 */
object CoverCache {
    private const val TARGET_WIDTH = 480
    // En çok ~24 MB (bayt cinsinden ölçülür)
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private fun key(file: File) = file.absolutePath + "#" + file.lastModified()

    /** Önbellekte varsa anında döner (kart ilk karede boş görünmesin). */
    fun peek(file: File?): ImageBitmap? = file?.let { cache.get(key(it)) }

    suspend fun load(file: File): ImageBitmap? {
        cache.get(key(file))?.let { return it }
        return withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
            if (bmp != null) cache.put(key(file), bmp)
            bmp
        }
    }
}
