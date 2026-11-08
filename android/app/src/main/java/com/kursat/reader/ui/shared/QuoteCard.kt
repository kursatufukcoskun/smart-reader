package com.kursat.reader.ui.shared

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.kursat.reader.model.ReaderTheme
import java.io.File
import java.io.FileOutputStream

/** Vurguyu paylaşmak için estetik alıntı kartı: seçili okuma temasının renkleri, kitap adı ve yazar. */
object QuoteCard {
    fun render(text: String, title: String, author: String, theme: ReaderTheme): Bitmap {
        val w = 1080
        val pad = 90
        val bg = Color.parseColor(theme.bgHex)
        val fg = Color.parseColor(theme.textHex)
        val accent = Color.parseColor(theme.translationHex)
        val size = when {
            text.length < 120 -> 64f
            text.length < 260 -> 54f
            else -> 44f
        }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = size; typeface = Typeface.SERIF }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, body, w - 2 * pad)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.25f)
            .build()
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = 36f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; alpha = 180; textSize = 30f; typeface = Typeface.SERIF }
        val markPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; textSize = 220f; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }

        val footerH = 150
        val h = maxOf(1080, pad + 150 + layout.height + 60 + footerH + pad)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        c.drawText("“", pad.toFloat(), pad + 150f, markPaint)
        c.save()
        c.translate(pad.toFloat(), pad + 170f)
        layout.draw(c)
        c.restore()
        val footerTop = h - pad - footerH + 30f
        val line = Paint().apply { color = accent }
        c.drawRect(pad.toFloat(), footerTop, pad + 80f, footerTop + 5f, line)
        val t = if (title.length > 44) title.take(43) + "…" else title
        c.drawText(t, pad.toFloat(), footerTop + 52f, titlePaint)
        if (author.isNotEmpty()) c.drawText(author.take(48), pad.toFloat(), footerTop + 98f, authorPaint)
        return bmp
    }

    /** Kartı PNG olarak üretir ve paylaşım menüsünü açar. */
    fun share(context: Context, text: String, title: String, author: String, theme: ReaderTheme) {
        try {
            val bmp = render(text, title, author, theme)
            val file = File(context.cacheDir, "alinti-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Alıntı kartı").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
