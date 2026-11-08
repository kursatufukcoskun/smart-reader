package com.kursat.reader.ui.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kursat.reader.model.ColumnMode
import com.kursat.reader.model.ReaderFont
import com.kursat.reader.model.ReaderSettings
import com.kursat.reader.model.ReaderTheme
import com.kursat.reader.model.ReadingMode
import com.kursat.reader.model.TranslationLayout
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun SelectionBar(
    onXray: () -> Unit,
    onDictionary: () -> Unit,
    onExplain: () -> Unit,
    onTranslate: () -> Unit,
    onHighlight: () -> Unit,
    onNote: () -> Unit,
    onGrammar: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            TextButton(onClick = onDictionary) {
                Text("Sözlük", fontWeight = FontWeight.Medium)
            }
            Text("·", color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = onXray) {
                Text("Kim/Ne?", fontWeight = FontWeight.Medium)
            }
            Text("·", color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = onExplain) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Açıkla", fontWeight = FontWeight.Medium)
            }
            Text("·", color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = onTranslate) {
                Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paragrafı Çevir", fontWeight = FontWeight.Medium)
            }
            Text("·", color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = onHighlight) {
                Text("Vurgula", fontWeight = FontWeight.Medium)
            }
            Text("·", color = MaterialTheme.colorScheme.outline)
            IconButton(onClick = onNote) {
                Icon(Icons.Default.EditNote, contentDescription = "Not al")
            }
            Text("·", color = MaterialTheme.colorScheme.outline)
            IconButton(onClick = onGrammar) {
                Icon(Icons.Default.Spellcheck, contentDescription = "Cümleyi çöz")
            }
        }
    }
}

@Composable
fun ThemePanel(
    settings: ReaderSettings,
    showsTextControls: Boolean = true,
    scrollable: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by settings.state.collectAsState()
    var fontMenuExpanded by remember { mutableStateOf(false) }
    val scrollModifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(scrollModifier)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Sistem görünümüyle eşle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sistem görünümüyle eşle", style = MaterialTheme.typography.bodyMedium)
                if (state.followSystem) {
                    Text(
                        "Şu an ${if (settings.systemDark) "koyu" else "açık"} görünüm: seçtiğin tema bu görünüm için kaydedilir; görünüm değişince diğeri otomatik gelir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.Switch(checked = state.followSystem, onCheckedChange = { settings.setFollowSystem(it) })
        }

        // Temalar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ReaderTheme.entries.forEach { t ->
                val selected = state.theme == t
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { settings.chooseTheme(t) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(t.color)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                    ) {
                        Text(
                            text = "Aa",
                            color = t.textColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(t.title, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Sıcak ışık filtresi
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WbSunny, contentDescription = "Sıcaklık", modifier = Modifier.size(20.dp))
            Slider(
                value = state.warmth.toFloat(),
                onValueChange = { v -> settings.update { it.copy(warmth = v.toDouble()) } },
                valueRange = 0f..1f,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = Color(0xFFFF9500),
                modifier = Modifier.size(20.dp)
            )
        }

        HorizontalDivider()

        // Çeviri düzeni
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Çeviri düzeni", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationLayout.entries.forEach { layout ->
                    FilterChip(
                        selected = state.translationLayout == layout,
                        onClick = { settings.update { it.copy(translationLayout = layout) } },
                        label = { Text(layout.title) }
                    )
                }
            }
            Text(
                "Altında/yan yana: orijinalin yanında görünür. Yerine: orijinal gizlenir, çeviri ikonuna dokununca orijinali görürsün (dar ekranda daha rahat).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Okuma yardımcıları (yalnızca EPUB: biyonik okuma, satır odaklayıcı, kelime ipucu yoğunluğu)
        if (showsTextControls) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Biyonik okuma (kelime başları kalın)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.bionic, onCheckedChange = { v -> settings.update { it.copy(bionic = v) } })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Satır odaklayıcı (şerit dışını karart)", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.readingRuler, onCheckedChange = { v -> settings.update { it.copy(readingRuler = v) } })
                }
                Text("Kelime ipucu yoğunluğu", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.kursat.reader.model.GlossLevel.entries.forEach { g ->
                        FilterChip(
                            selected = state.glossLevel == g,
                            onClick = { settings.update { it.copy(glossLevel = g) } },
                            label = { Text(g.title) }
                        )
                    }
                }
            }
        }

        // Hizalama ve tireleme
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hizalama", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.kursat.reader.model.TextAlignMode.entries.forEach { a ->
                    FilterChip(
                        selected = state.textAlign == a,
                        onClick = { settings.update { it.copy(textAlign = a) } },
                        label = { Text(a.title) }
                    )
                }
            }
            Text("Tireleme", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.kursat.reader.model.HyphenMode.entries.forEach { h ->
                    FilterChip(
                        selected = state.hyphenation == h,
                        onClick = { settings.update { it.copy(hyphenation = h) } },
                        label = { Text(h.title) }
                    )
                }
            }
        }

        // Kolon
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Kolon", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColumnMode.entries.forEach { col ->
                    FilterChip(
                        selected = state.columns == col,
                        onClick = { settings.update { it.copy(columns = col) } },
                        label = { Text(col.title) }
                    )
                }
            }
            Text(
                if (state.columns == ColumnMode.double)
                    "EPUB'da \"Sayfa sayfa\" modunda iki sütun yan yana; PDF'te iki sayfa yan yana görünür."
                else "Tek sütun / tek sayfa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Okuma modu
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Okuma modu", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadingMode.entries.forEach { m ->
                    FilterChip(
                        selected = state.readingMode == m,
                        onClick = { settings.update { it.copy(readingMode = m) } },
                        label = { Text(m.title) }
                    )
                }
            }
            if (state.readingMode == ReadingMode.scroll) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Kenara dokununca sayfa atla", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.edgeTap,
                        onCheckedChange = { v -> settings.update { it.copy(edgeTap = v) } }
                    )
                }
            } else {
                Text(
                    "Sağ/sol kenara dokunun veya yana kaydırın.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Okurken ekranı açık tut", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.keepAwake,
                    onCheckedChange = { v -> settings.update { it.copy(keepAwake = v) } }
                )
            }
            if (!showsTextControls) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Kenar boşluklarını kırp", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = state.cropMargins,
                        onCheckedChange = { v -> settings.update { it.copy(cropMargins = v) } }
                    )
                }
            }
        }

        if (showsTextControls) {
            HorizontalDivider()

            // Yazı tipi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Yazı tipi", style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton(onClick = { fontMenuExpanded = true }) {
                        Text(state.font.title)
                    }
                    DropdownMenu(
                        expanded = fontMenuExpanded,
                        onDismissRequest = { fontMenuExpanded = false }
                    ) {
                        ReaderFont.entries.forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.title) },
                                trailingIcon = {
                                    if (state.font == f) Icon(Icons.Default.Check, contentDescription = null)
                                },
                                onClick = {
                                    settings.update { it.copy(font = f) }
                                    fontMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Punto (fontScale)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Punto", style = MaterialTheme.typography.labelMedium)
                    Text("${(18.0 * state.fontScale).roundToInt()} px", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = state.fontScale.toFloat(),
                    onValueChange = { v ->
                        val stepped = (v * 20f).roundToInt() / 20.0
                        settings.update { it.copy(fontScale = stepped) }
                    },
                    valueRange = 0.7f..2.0f
                )
            }

            // Satır aralığı
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Satır aralığı", style = MaterialTheme.typography.labelMedium)
                    Text(String.format("%.2f", state.lineHeight), style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = state.lineHeight.toFloat(),
                    onValueChange = { v ->
                        val stepped = (v * 20f).roundToInt() / 20.0
                        settings.update { it.copy(lineHeight = stepped) }
                    },
                    valueRange = 1.2f..2.2f
                )
            }

            // Kenar boşluğu
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Kenar boşluğu", style = MaterialTheme.typography.labelMedium)
                    Text("%${state.margin.roundToInt()}", style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = state.margin.toFloat(),
                    onValueChange = { v ->
                        settings.update { it.copy(margin = v.roundToInt().toDouble()) }
                    },
                    valueRange = 2f..22f
                )
            }
        }
    }
}

/**
 * Göz konforu: E-ink gri tonlama + sıcak ışık filtresi
 */
@Composable
fun ReaderChromeBox(
    settings: ReaderSettings,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state by settings.state.collectAsState()
    val isEink = state.theme.isEink
    val warmth = state.warmth

    val grayscaleModifier = if (isEink) {
        Modifier.drawWithContent {
            val matrix = ColorMatrix().apply { setToSaturation(0f) }
            val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(matrix) }
            drawIntoCanvas { canvas ->
                canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), paint)
                drawContent()
                canvas.restore()
            }
        }
    } else Modifier

    // Kamera çentiği ve sistem çubuklarının altında kalmasın; boşluk tema renginde boyanır.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(state.theme.color)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .then(grayscaleModifier)
    ) {
        content()
        if (warmth > 0.001) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color(red = 1.0f, green = 0.62f, blue = 0.25f)
                            .copy(alpha = (0.08 + warmth * 0.30).toFloat())
                    )
            )
        }
    }
}

@Composable
fun ToastHost(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(1600)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(top = 56.dp)
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }
}



/** Okuyucu açıkken ekranın kendiliğinden kapanmasını engeller (ayardan kapatılabilir; sesli okurken ekran kapanabilir). */
@Composable
fun KeepScreenAwake(settings: ReaderSettings, speech: com.kursat.reader.speech.SpeechController) {
    val state by settings.state.collectAsState()
    val speechState by speech.state.collectAsState()
    val enabled = state.keepAwake && speechState == com.kursat.reader.speech.SpeechController.State.idle
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
