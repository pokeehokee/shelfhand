package app.shelfhand.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shelfhand.data.BookRow
import kotlinx.coroutines.delay

@Composable
fun ShelfhandRoot(model: PlayerModel, onPickFolder: () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var tab by remember { mutableStateOf(0) }
        val upNext by model.upNext.collectAsState()
        Scaffold(bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("📚") }, label = { Text("Library") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("▶") }, label = { Text("Player") })
            }
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (tab == 0) LibraryScreen(model, onPickFolder, onOpen = { model.open(it); tab = 1 })
                else PlayerScreen(model)
            }
        }
        if (upNext.isNotEmpty()) UpNextDialog(upNext, onPick = { model.open(it); tab = 1 }, onDismiss = model::dismissUpNext)
    }
}

@Composable
fun LibraryScreen(model: PlayerModel, onPickFolder: () -> Unit, onOpen: (Long) -> Unit) {
    val books by model.books.collectAsState()
    val msg by model.scanMessage.collectAsState()
    Column(Modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFolder) { Text(if (model.library.rootUri == null) "Choose audiobook folder" else "Change folder") }
            if (model.library.rootUri != null) OutlinedButton(onClick = { model.scan() }) { Text("Rescan") }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(8.dp))
        if (books.isEmpty()) Text("No books yet. Choose the folder your audiobooks live in, for example Download/audiobooks.")
        LazyColumn {
            items(books, key = { it.id }) { b -> BookLine(b, onClick = { onOpen(b.id) }); HorizontalDivider() }
        }
    }
}

@Composable
private fun BookLine(b: BookRow, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(b.author, b.series?.let { s -> b.seriesIndex?.let { "$s #${fmtIndex(it)}" } ?: s }).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
        val total = b.totalMs
        when {
            b.verdict == "FINISHED" -> Text("Finished", style = MaterialTheme.typography.labelSmall)
            total != null && total > 0 && (b.globalMs ?: 0) > 0 -> {
                LinearProgressIndicator(progress = { ((b.globalMs ?: 0).toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("${fmt(total - (b.globalMs ?: 0))} left", style = MaterialTheme.typography.labelSmall)
            }
            total == null -> Text("Length unknown", style = MaterialTheme.typography.labelSmall)
            else -> Text(fmt(total), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun PlayerScreen(model: PlayerModel) {
    val n by model.now.collectAsState()
    val np = n ?: run { Text("Pick a book in the Library.", Modifier.padding(16.dp)); return }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var sleepMenu by remember { mutableStateOf(false) }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        Text(np.title, style = MaterialTheme.typography.headlineSmall)
        np.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(12.dp))
        Text("Chapter ${np.chapterIdx + 1} of ${np.chapters.size} · ${np.chapters.getOrNull(np.chapterIdx)?.name ?: ""}",
            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val dur = np.chapterDurMs.coerceAtLeast(1)
        Slider(
            value = dragging ?: (np.chapterPosMs.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { model.seekInChapter((it * dur).toLong()) }; dragging = null },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(np.chapterPosMs), style = MaterialTheme.typography.labelSmall)
            Text("-" + fmt(np.chapterDurMs - np.chapterPosMs), style = MaterialTheme.typography.labelSmall)
        }
        if (np.bookTotalMs > 0) {
            val left = ((np.bookTotalMs - np.bookPosMs) / np.speed).toLong()
            Text("Book ${(100 * np.bookPosMs / np.bookTotalMs)}% · ${fmt(left)} left at ${np.speed}x", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::back30) { Icon(Icons.Filled.Replay30, "Back 30 seconds") }
            IconButton(onClick = model::toggle) { Icon(if (np.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (np.playing) "Pause" else "Play") }
            IconButton(onClick = model::fwd30) { Icon(Icons.Filled.Forward30, "Forward 30 seconds") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                FilterChip(selected = np.speed == s, onClick = { model.speed(s) }, label = { Text("${s}x") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { sleepMenu = true }) {
                Text(when {
                    np.sleepEndOfChapter -> "Sleep: end of chapter"
                    np.sleepEndsAt > nowMs -> "Sleep: ${fmt(np.sleepEndsAt - nowMs)}"
                    else -> "Sleep timer"
                })
            }
            if (np.sleepEndsAt > nowMs) TextButton(onClick = { model.sleep(-2) }) { Text("+5 min") }
            DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                listOf(10, 15, 30, 45, 60).forEach { m -> DropdownMenuItem(text = { Text("$m minutes") }, onClick = { model.sleep(m); sleepMenu = false }) }
                DropdownMenuItem(text = { Text("End of chapter") }, onClick = { model.sleep(-1); sleepMenu = false })
                DropdownMenuItem(text = { Text("Off") }, onClick = { model.sleep(0); sleepMenu = false })
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { model.markFinished(np.bookId, true) }) { Text("Mark finished") }
        }
        HorizontalDivider()
        LazyColumn {
            itemsIndexed(np.chapters) { i, ch ->
                Row(Modifier.fillMaxWidth().clickable { model.jumpTo(i) }.padding(vertical = 8.dp)) {
                    Text((if (i == np.chapterIdx) "▶ " else "") + ch.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ch.durationMs?.let(::fmt) ?: "?", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun UpNextDialog(picks: List<BookRow>, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finished. Up next?") },
        text = {
            Column {
                picks.forEach { b ->
                    Text(listOfNotNull(b.title, b.series?.let { s -> b.seriesIndex?.let { "($s #${fmtIndex(it)})" } }).joinToString(" "),
                        Modifier.fillMaxWidth().clickable { onPick(b.id) }.padding(vertical = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picks.first().id) }) { Text("Start ${picks.first().title}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

private fun fmtIndex(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

fun fmt(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
