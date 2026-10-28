package io.github.olrut.vocab.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.olrut.vocab.kindle.ImportProgress
import io.github.olrut.vocab.kindle.KindleCandidate
import io.github.olrut.vocab.transfer.FileFormat
import io.github.olrut.vocab.transfer.ImportController
import io.github.olrut.vocab.transfer.ImportState
import java.time.LocalDate

/** Kindle import, CSV/JSON import and export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    controller: ImportController,
    onKindle: (Uri) -> Unit,
    onFile: (Uri) -> Unit,
    onExport: (Uri, FileFormat) -> Unit,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    // Back from a preview or a summary returns to the start of this screen.
    BackHandler(enabled = state is ImportState.Kindle || state is ImportState.Finished) { controller.close() }

    val kindlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onKindle) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onFile) }
    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FileFormat.Csv.mimeType)) { it?.let { uri -> onExport(uri, FileFormat.Csv) } }
    val jsonSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FileFormat.Json.mimeType)) { it?.let { uri -> onExport(uri, FileFormat.Json) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import / export") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is ImportState.Idle -> Start(
                    s,
                    onKindle = { kindlePicker.launch(arrayOf("*/*")) },
                    onFile = { filePicker.launch(arrayOf("*/*")) },
                    onCsv = { csvSaver.launch("vocab-${LocalDate.now()}.csv") },
                    onJson = { jsonSaver.launch("vocab-${LocalDate.now()}.json") },
                )
                ImportState.Busy -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ImportState.Kindle -> Preview(s, controller)
                is ImportState.Running -> Running(s.progress)
                is ImportState.Finished -> Finished(s.progress, onDone = controller::close)
            }
        }
    }
}

@Composable
private fun Start(s: ImportState.Idle, onKindle: () -> Unit, onFile: () -> Unit, onCsv: () -> Unit, onJson: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.message?.let { Text(it, color = if (s.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }

        Text("From your Kindle", style = MaterialTheme.typography.titleMedium)
        Text(
            "The Kindle keeps the words you looked up in system/vocabulary/vocab.db (the system folder may be hidden). " +
                "Connect the Kindle to a computer with USB and copy that file to the phone, or open it straight from the Kindle " +
                "with a USB-OTG cable. Words come in with the sentence from the book.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onKindle) { Text("Choose vocab.db") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("From a file", style = MaterialTheme.typography.titleMedium)
        Text("A CSV or JSON export of this app (or of the old web version). Words already on your list are skipped.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onFile) { Text("Import CSV or JSON") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Export", style = MaterialTheme.typography.titleMedium)
        Text("Every word, to keep a copy or move to another phone.", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCsv) { Text("Export CSV") }
            OutlinedButton(onClick = onJson) { Text("Export JSON") }
        }
    }
}

@Composable
private fun Preview(s: ImportState.Kindle, controller: ImportController) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Toggle("Only words new since the last import", s.onlyNew) { controller.setOnlyNew(it) }
            Toggle("Words marked mastered on the Kindle are learned", s.markMastered, controller::setMarkMastered)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${s.candidates.size} words · ${s.selected.size} picked", modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.selectAll(true) }) { Text("All") }
                TextButton(onClick = { controller.selectAll(false) }) { Text("None") }
            }
        }
        HorizontalDivider()
        if (s.candidates.isEmpty()) {
            Text("No new words in this file.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(s.candidates, key = { it.word.word }) { c -> CandidateRow(c, c.word.word in s.selected) { controller.toggle(c.word.word) } }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::close, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(onClick = { controller.startKindle() }, enabled = s.selected.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Import ${s.selected.size}") }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CandidateRow(c: KindleCandidate, picked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = picked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.word.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (c.exists) Text("  on your list", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.word.mastered) Text("  mastered", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            c.word.usage?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            c.word.bookTitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Running(p: ImportProgress) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Text("Looking up ${p.done} / ${p.total}", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
        Text(counts(p), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Finished(p: ImportProgress, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Import finished", style = MaterialTheme.typography.titleLarge)
        Text(counts(p))
        if (p.errors.isNotEmpty()) {
            Text("Not imported (try again later: they are offered as new next time):", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(p.errors) { e -> Text("${e.word}: ${e.error}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Button(onClick = onDone) { Text("Done") }
    }
}

private fun counts(p: ImportProgress) =
    "${p.imported} imported · ${p.skipped} already on your list" + if (p.errors.isNotEmpty()) " · ${p.errors.size} failed" else ""
