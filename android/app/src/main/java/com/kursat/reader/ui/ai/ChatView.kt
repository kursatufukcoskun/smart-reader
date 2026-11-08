package com.kursat.reader.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kursat.reader.ai.ChatModel
import com.kursat.reader.ai.ChatTurn
import com.kursat.reader.ui.shared.parseInlineMarkdown

private val SUGGESTIONS = listOf(
    "Şimdiye kadar ne oldu, özetle",
    "Bu bölümdeki ana fikir ne?",
    "Karakterleri/kavramları ve ilişkilerini açıkla",
    "Yazarın savunduğu argüman ne?",
    "Anlamadığım yerleri sadeleştir"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookChatSheet(
    model: ChatModel,
    onDismiss: () -> Unit
) {
    val messages by model.messages.collectAsState()
    val isStreaming by model.isStreaming.collectAsState()
    val isLoadingContext by model.isLoadingContext.collectAsState()
    val error by model.error.collectAsState()
    val info by model.info.collectAsState()
    val tier by model.tier.collectAsState()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        model.refreshContext()
        model.pendingPrompt?.let { p ->
            model.pendingPrompt = null
            model.send(p)
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.88f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.bookTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (isLoadingContext) "Okunan metin hazırlanıyor…"
                        else "${info.label} · ~${info.words} kelime (spoiler yok)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilterChip(
                    selected = tier == ChatModel.Tier.smart,
                    onClick = {
                        model.setTier(
                            if (tier == ChatModel.Tier.smart) ChatModel.Tier.fast else ChatModel.Tier.smart
                        )
                    },
                    label = { Text(tier.title) },
                    leadingIcon = {
                        Icon(
                            if (tier == ChatModel.Tier.smart) Icons.Default.Psychology else Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Spacer(Modifier.width(6.dp))
                if (messages.isNotEmpty()) {
                    IconButton(onClick = { model.clear() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Temizle")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Kapat")
                }
            }

            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.Forum,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text("Kitap hakkında soru sor", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Asistan yalnızca şu ana kadar okuduğun kısmı görür — ileriden spoiler vermez.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SUGGESTIONS.forEach { s ->
                                    Surface(
                                        onClick = { model.send(s) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(s, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    ChatBubble(msg, canSave = !isStreaming && model.saveNote != null, onSaveNote = { model.saveNote?.invoke(it) })
                }

                if (error != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { model.retry() }) {
                                Text("Tekrar Dene")
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Kitap hakkında sor…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )
                if (isStreaming) {
                    IconButton(onClick = { model.stop() }) {
                        Icon(
                            Icons.Default.StopCircle,
                            contentDescription = "Durdur",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            val q = input.trim()
                            if (q.isNotEmpty()) {
                                input = ""
                                model.send(q)
                            }
                        },
                        enabled = input.isNotBlank() && !isLoadingContext
                    ) {
                        Icon(
                            Icons.Default.ArrowCircleUp,
                            contentDescription = "Gönder",
                            tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatModel.Message, canSave: Boolean, onSaveNote: (String) -> Unit) {
    val isUser = message.role == ChatTurn.Role.user
    var saved by remember(message.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (isUser) {
                    Text(message.text, fontSize = 15.sp)
                } else if (message.text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Düşünüyor…", fontSize = 14.sp)
                    }
                } else {
                    Column {
                        SelectionContainer {
                            Text(parseInlineMarkdown(message.text), fontSize = 15.sp, lineHeight = 22.sp)
                        }
                        if (canSave) {
                            TextButton(
                                onClick = { onSaveNote(message.text); saved = true },
                                enabled = !saved
                            ) {
                                Text(if (saved) "✓ Konuma not olarak kaydedildi" else "📌 Bu konuma not olarak kaydet", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
