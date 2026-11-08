package com.kursat.reader.ui.shared

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kursat.reader.ai.AIAssistant
import com.kursat.reader.model.VocabStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIPanelSheet(
    ai: AIAssistant,
    vocab: VocabStore
) {
    val isPresented by ai.isPresented.collectAsState()
    if (!isPresented) return

    val title by ai.title.collectAsState()
    val text by ai.text.collectAsState()
    val isStreaming by ai.isStreaming.collectAsState()
    val error by ai.error.collectAsState()
    val vocabDraft by ai.vocabDraft.collectAsState()
    val vocabEntries by vocab.entries.collectAsState()
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = { ai.dismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 640.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = { ai.dismiss() }) { Text("Kapat") }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isStreaming) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(
                        onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("AI", text))
                        },
                        enabled = text.isNotEmpty()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Kopyala")
                    }
                }
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                error?.let { err ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(err, color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = { ai.retry() }) {
                        Text("Tekrar dene")
                    }
                }

                if (text.isEmpty() && isStreaming && error == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Yapay zekâ düşünüyor…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (text.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = parseInlineMarkdown(text),
                            fontSize = 18.sp,
                            lineHeight = 25.sp
                        )
                    }
                }

                val draft = vocabDraft
                if (draft != null && !isStreaming && text.isNotEmpty() && error == null) {
                    val saved = vocabEntries.any {
                        it.bookID?.equals(draft.bookID, ignoreCase = true) == true &&
                            it.word.equals(draft.word, ignoreCase = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vocab.add(draft, explanation = text) },
                        colors = if (saved) {
                            ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759))
                        } else ButtonDefaults.buttonColors()
                    ) {
                        Icon(
                            imageVector = if (saved) Icons.Default.CheckCircle else Icons.Default.AddCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (saved) "Defterde (güncelle)" else "Deftere ekle")
                    }
                    PronounceButton(draft.word, draft.paragraph)
                    }
                }
            }
        }
    }
}

