package io.github.olrut.vocab.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.data.WordRepository

private val FILTERS = listOf(Word.LEARNING to "Learning", Word.LEARNED to "Learned", null to "All")

/** Every word, filtered by status and a search; tap one to edit it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordsScreen(
    repo: WordRepository,
    status: String?,
    onFilter: (String?) -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    val flow = remember(status) { repo.observe(status) }
    val words by flow.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Words") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add a word") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FILTERS.forEach { (value, label) -> FilterChip(selected = status == value, onClick = { onFilter(value) }, label = { Text(label) }) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = words
            when {
                list == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                else -> {
                    val shown = filterWords(list, query)
                    if (shown.isEmpty()) {
                        Text(
                            if (list.isEmpty()) "No words here yet." else "No word matches “${query.trim()}”.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.id }) { w ->
                            WordRow(w, onClick = { onOpen(w.id) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** Words whose text, definition or Finnish contains [query], ignoring case. */
fun filterWords(words: List<Word>, query: String): List<Word> {
    val q = query.trim()
    if (q.isEmpty()) return words
    return words.filter { w -> listOfNotNull(w.word, w.definition, w.finnish).any { it.contains(q, ignoreCase = true) } }
}

@Composable
private fun WordRow(w: Word, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (w.status == Word.LEARNED) Text("learned", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            w.definition ?: "No definition yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
