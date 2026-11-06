package com.kursat.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kursat.reader.model.Book
import com.kursat.reader.sync.SyncManager

/** Bulutta duran kitap açılınca otomatik indirir; iner inmez kitap kendiliğinden açılır. */
@Composable
fun CloudDownloadScreen(book: Book, sync: SyncManager, onBack: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(book.id, attempt) {
        error = null
        try {
            sync.downloadBook(book.id)
        } catch (e: Exception) {
            error = e.message ?: "İndirilemedi"
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Button(onClick = { attempt++ }) { Text("Tekrar dene") }
                TextButton(onClick = onBack) { Text("Geri dön") }
            } else {
                CircularProgressIndicator()
                Text("“${book.title}” Drive'dan indiriliyor…", textAlign = TextAlign.Center)
                TextButton(onClick = onBack) { Text("Vazgeç") }
            }
        }
    }
}
