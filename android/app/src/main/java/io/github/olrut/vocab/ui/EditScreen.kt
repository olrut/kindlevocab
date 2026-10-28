package io.github.olrut.vocab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.dictionary.Sense
import io.github.olrut.vocab.edit.LookupState
import io.github.olrut.vocab.edit.WordEditor
import io.github.olrut.vocab.edit.WordForm

/** Add a word (look it up, pick a sense, adjust) or edit one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(editor: WordEditor, onPlay: (Word) -> Unit, onClose: () -> Unit) {
    val state by editor.state.collectAsStateWithLifecycle()
    val form = state.form
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.done) { if (state.done) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.id == null) "Add a word" else "Edit") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (form.id != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.word,
                onValueChange = editor::setWord,
                label = { Text("Word") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { editor.lookUp() }),
                trailingIcon = { IconButton(onClick = editor::lookUp, enabled = form.word.isNotBlank()) { Icon(Icons.Filled.Search, contentDescription = "Look up") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Lookup(state.lookup, onPick = editor::pick, onOwn = editor::dismissLookup)
            if (form.ipa != null || form.audioUrl != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(form.ipa ?: "", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (form.audioUrl != null) IconButton(onClick = { onPlay(form.toWord()) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play") }
                }
            }
            Field("Definition", form.definition) { v -> editor.update { it.copy(definition = v) } }
            Field("Example", form.example) { v -> editor.update { it.copy(example = v) } }
            Field("Finnish", form.finnish) { v -> editor.update { it.copy(finnish = v) } }
            Field("Notes", form.notes, minLines = 3) { v -> editor.update { it.copy(notes = v) } }
            form.bookTitle?.let { Text("From $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Learned", modifier = Modifier.weight(1f))
                Switch(checked = form.status == Word.LEARNED, onCheckedChange = { editor.setStatus(if (it) Word.LEARNED else Word.LEARNING) })
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = editor::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${form.word}”?") },
            text = { Text("It is removed from this phone for good.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; editor.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Lookup(state: LookupState, onPick: (Sense) -> Unit, onOwn: () -> Unit) {
    when (state) {
        LookupState.Idle -> {}
        LookupState.Busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
        LookupState.NotFound -> Text("Not in the dictionary. Write your own definition below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        is LookupState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
        is LookupState.Senses -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pick a meaning", style = MaterialTheme.typography.titleSmall)
            state.senses.forEach { sense ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { onPick(sense) }) {
                    Column(Modifier.padding(12.dp)) {
                        sense.partOfSpeech?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        Text(sense.definition, style = MaterialTheme.typography.bodyLarge)
                        sense.example?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic) }
                    }
                }
            }
            TextButton(onClick = onOwn) { Text("Write my own") }
        }
    }
}

@Composable
private fun Field(label: String, value: String?, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value ?: "",
        onValueChange = onChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** For playing the form's recording; an unsaved word has id 0. */
private fun WordForm.toWord() = Word(id = id ?: 0, word = word, audioUrl = audioUrl)
