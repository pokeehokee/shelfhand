package app.shelfhand.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shelfhand.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** A card at the top of the app when Shelfhand closed on its own since the user last looked. */
@Composable
fun CrashBanner(onOpenReports: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var unseen by remember { mutableStateOf<CrashLog.Report?>(null) }
    LaunchedEffect(Unit) { unseen = withContext(Dispatchers.IO) { runCatching { CrashLog.unseen(ctx) }.getOrNull() } }
    val r = unseen ?: return
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text("${r.title} on ${stamp(r.at)}.", style = MaterialTheme.typography.bodyMedium)
            Text("The details are saved on this phone. Share them to get it fixed.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onOpenReports() }) { Text("Details") }
                TextButton(onClick = { share(ctx, r) }) { Text("Share") }
                TextButton(onClick = { scope.launch(Dispatchers.IO) { CrashLog.markSeen(ctx) }; unseen = null }) { Text("Dismiss") }
            }
        }
    }
}

/** Every saved report, newest first, each one copyable or shareable as text. */
@Composable
fun CrashReportsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<CrashLog.Report>?>(null) }
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        reports = withContext(Dispatchers.IO) { runCatching { CrashLog.reports(ctx) }.getOrDefault(emptyList()) }
        withContext(Dispatchers.IO) { CrashLog.markSeen(ctx) }
    }
    val list = reports
    val r = list?.getOrNull(index)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (r == null) "Crash reports" else "${r.title}, ${stamp(r.at)}", style = MaterialTheme.typography.titleSmall) },
        text = {
            when {
                list == null -> Text("Loading...")
                r == null -> Text("No crashes recorded. If Shelfhand closes on its own, the details will show up here.")
                else -> Column {
                    if (list.size > 1) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        list.forEachIndexed { i, x ->
                            TextButton(onClick = { index = i }) { Text(if (i == index) "• ${short(x.at)}" else short(x.at), fontSize = 12.sp) }
                        }
                    }
                    Text(r.text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
                }
            }
        },
        confirmButton = {
            Row {
                if (r != null) {
                    TextButton(onClick = { share(ctx, r) }) { Text("Share") }
                    TextButton(onClick = { copy(ctx, r) }) { Text("Copy") }
                }
                if (!list.isNullOrEmpty()) TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { CrashLog.clear(ctx) }; reports = emptyList(); index = 0 }
                }) { Text("Clear") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

private fun stamp(at: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))
private fun short(at: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

private fun share(ctx: Context, r: CrashLog.Report) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Shelfhand crash report")
        .putExtra(Intent.EXTRA_TEXT, r.text.take(90_000))
    runCatching { ctx.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copy(ctx: Context, r: CrashLog.Report) {
    runCatching { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Shelfhand crash report", r.text)) }
}
