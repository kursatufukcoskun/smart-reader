package com.kursat.reader.ui.shared

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.kursat.reader.model.LanguageDetect

/** Kelime ve cümle telaffuzu: sistem sesiyle, dili bağlam cümlesinden tahmin ederek okur. */
object Pronouncer {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    fun speak(context: Context, text: String, sample: String? = null) {
        val t = text.replace("…", " ").trim()
        if (t.isEmpty()) return
        val lang = LanguageDetect.detect(sample.orEmpty().ifEmpty { t }) ?: LanguageDetect.detect(t)
        val action = {
            tts?.let { engine ->
                if (lang != null) engine.setLanguage(lang.locale)
                engine.setSpeechRate(0.9f)
                engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, "pron")
            }
            Unit
        }
        if (tts == null) {
            pending = action
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) pending?.invoke()
                pending = null
            }
        } else if (ready) {
            action()
        } else {
            pending = action
        }
    }
}

/** 🔊 Küçük telaffuz düğmesi. */
@Composable
fun PronounceButton(text: String, sample: String? = null) {
    val context = LocalContext.current
    IconButton(onClick = { Pronouncer.speak(context, text, sample) }) {
        Icon(Icons.Default.VolumeUp, contentDescription = "Telaffuzu dinle")
    }
}
