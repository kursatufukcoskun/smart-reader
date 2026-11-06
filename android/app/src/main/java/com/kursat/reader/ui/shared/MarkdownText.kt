package com.kursat.reader.ui.shared

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * iOS'taki inlineOnlyPreservingWhitespace Markdown ayrıştırıcısının karşılığı (**kalın**, *italik*, `kod`).
 */
fun parseInlineMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = raw.length
    while (i < n) {
        if (raw.startsWith("**", i)) {
            val end = raw.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(raw.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        }
        if (raw.startsWith("`", i)) {
            val end = raw.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(raw.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if (raw.startsWith("*", i) && !raw.startsWith("**", i)) {
            val end = raw.indexOf('*', i + 1)
            if (end > i + 1 && !raw.substring(i + 1, end).contains('\n')) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(raw.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(raw[i])
        i++
    }
}

/**
 * Bağlam cümlesinde kelimeyi kalın ve turuncu gösterir.
 */
fun highlightWordInContext(text: String, word: String): AnnotatedString = buildAnnotatedString {
    if (word.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val idx = text.indexOf(word, ignoreCase = true)
    if (idx < 0) {
        append(text)
    } else {
        append(text.substring(0, idx))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFF9500))) {
            append(text.substring(idx, idx + word.length))
        }
        append(text.substring(idx + word.length))
    }
}

