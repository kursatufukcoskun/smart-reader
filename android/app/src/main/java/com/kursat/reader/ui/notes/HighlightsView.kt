package com.kursat.reader.ui.notes

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kursat.reader.model.Highlight
import com.kursat.reader.model.HighlightStore
import com.kursat.reader.ui.reader.HighlightEditorSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsView(
    store: HighlightStore,
    onJumpToBook: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val items by store.items.collectAsState()
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Highlight?>(null) }

    val filtered = remember(items, search) {
        items.filter { h ->
            search.isBlank() ||
                h.text.contains(search, ignoreCase = true) ||
                h.note.contains(search, ignoreCase = true) ||
                h.bookTitle.contains(search, ignoreCase = true)
        }
    }

    val groups = remember(filtered) {
        filtered.groupBy { it.bookID.uppercase() }
            .map { (_, list) ->
                val title = list.firstOrNull()?.bookTitle.orEmpty()
                val sorted = list.sortedWith(compareBy({ it.sortKey.first }, { it.sortKey.second }))
                title to sorted
            }
            .sortedBy { it.first }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vurgular ve notlar", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val md = store.exportMarkdown()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_SUBJECT, "Vurgular ve notlar")
                                putExtra(Intent.EXTRA_TEXT, md)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Vurgular ve notlar"))
                        },
                        enabled = items.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Paylaş")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Vurgu, not veya kitap ara") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { search = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Temizle")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Default.DriveFileRenameOutline,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("Henüz vurgu yok", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Okurken bir metni seçip fosforlu kalem düğmesine dokunun.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groups.forEach { (bookTitle, bookItems) ->
                        item {
                            Text(
                                text = bookTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                            )
                        }
                        items(bookItems, key = { it.id }) { h ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        store.pendingJump = h
                                        onJumpToBook(h.bookID)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(52.dp)
                                        .background(h.color.color, RoundedCornerShape(2.dp))
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = h.text,
                                        fontFamily = FontFamily.Serif,
                                        fontSize = 16.sp,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (h.note.isNotEmpty()) {
                                        Text(
                                            text = "📝 ${h.note}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (h.location.isNotEmpty()) {
                                        Text(
                                            text = h.location,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                IconButton(onClick = { editing = h }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Düzenle")
                                }
                                IconButton(onClick = { store.delete(listOf(h.id)) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Sil")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { h ->
        HighlightEditorSheet(
            initial = h,
            onSave = {
                store.update(it)
                editing = null
            },
            onDelete = {
                store.delete(listOf(it.id))
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

