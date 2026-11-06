package com.kursat.reader.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.TTSEngine
import com.kursat.reader.speech.SpeechController

/** Seslendirme oynatıcı çubuğu. */
@Composable
fun SpeechBar(speech: SpeechController, settings: ReaderSettings, modifier: Modifier = Modifier) {
    val state by speech.state.collectAsState()
    val busy by speech.busy.collectAsState()
    val notice by speech.notice.collectAsState()
    val index by speech.index.collectAsState()
    val count by speech.count.collectAsState()
    val caption by speech.caption.collectAsState()
    val s by settings.state.collectAsState()
    var menu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.padding(horizontal = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 4,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable { speech.dismissNotice() }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = { speech.stop() }) { Icon(Icons.Default.Close, contentDescription = "Kapat") }
                IconButton(onClick = { speech.skip(-1) }) { Icon(Icons.Default.SkipPrevious, contentDescription = "Önceki paragraf") }
                IconButton(onClick = { speech.toggle() }) {
                    Icon(
                        if (state == SpeechController.State.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Oynat/duraklat"
                    )
                }
                IconButton(onClick = { speech.skip(1) }) { Icon(Icons.Default.SkipNext, contentDescription = "Sonraki paragraf") }
                androidx.compose.foundation.layout.Box {
                    Text(
                        "%.2g×".format(s.ttsRate),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { menu = true }.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(0.8, 1.0, 1.15, 1.3, 1.5, 1.75, 2.0).forEach { r ->
                            DropdownMenuItem(
                                text = { Text("%.2g×".format(r) + if (r == s.ttsRate) "  ✓" else "") },
                                onClick = { speech.setRate(r); menu = false }
                            )
                        }
                        TTSEngine.entries.forEach { e ->
                            DropdownMenuItem(
                                text = { Text(e.title + if (e == s.ttsEngine) "  ✓" else "") },
                                onClick = {
                                    settings.update { it.copy(ttsEngine = e) }
                                    speech.settingsChanged()
                                    menu = false
                                }
                            )
                        }
                    }
                }
                if (busy != null) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(busy.orEmpty(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, end = 6.dp))
                } else if (count > 0) {
                    Text(
                        "${minOf(index + 1, count)}/$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }
}
