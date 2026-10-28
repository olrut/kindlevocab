package io.github.olrut.vocab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.olrut.vocab.data.Word
import io.github.olrut.vocab.review.ReviewController
import io.github.olrut.vocab.review.Swipe
import io.github.olrut.vocab.review.decideSwipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long "Marked as learned — Undo" stays up (docs/SPEC.md § 6). */
private const val UNDO_MS = 3000L

@Composable
fun ReviewScreen(
    controller: ReviewController,
    onPlay: (Word) -> Unit,
    onAdd: () -> Unit,
    onWords: () -> Unit,
    display: Display,
    onTheme: (ThemeMode) -> Unit,
    onSize: (TextSize) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showDisplay by remember { mutableStateOf(false) }

    // Read the words again whenever the screen comes back: they may have been added or edited.
    LifecycleResumeEffect(controller) {
        controller.load()
        onPauseOrDispose {}
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state.undo) {
        val word = state.undo ?: return@LaunchedEffect
        launch {
            val result = snackbar.showSnackbar("Marked as learned", actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
            if (result == SnackbarResult.ActionPerformed) controller.undo()
        }
        delay(UNDO_MS)
        snackbar.currentSnackbarData?.dismiss()
        if (controller.state.value.undo?.id == word.id) controller.dismissUndo()
    }
    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(error, withDismissAction = true, duration = SnackbarDuration.Long)
        controller.clearError()
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val word = state.deck.current
            when {
                word != null -> Card(word, state.revealed, display.size, controller, onPlay)
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Empty(onAdd = onAdd, onWords = onWords)
            }
            Menu(
                onAdd = onAdd,
                onWords = onWords,
                onDisplay = { showDisplay = true },
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding(),
            )
        }
    }
    if (showDisplay) DisplayDialog(display, onTheme, onSize, onDismiss = { showDisplay = false })
}

@Composable
private fun Card(word: Word, revealed: Boolean, textSize: TextSize, controller: ReviewController, onPlay: (Word) -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val fling = with(LocalDensity.current) { 1000.dp.toPx() }
    var size by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(word.id) {
                size = Offset(this.size.width.toFloat(), this.size.height.toFloat())
                detectTapGestures(onTap = { controller.reveal() })
            }
            .pointerInput(word.id) {
                val tracker = VelocityTracker()
                var drag = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        drag = Offset.Zero
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag += amount
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { offset.snapTo(drag) }
                    },
                    onDragEnd = {
                        val v = tracker.calculateVelocity()
                        val swipe = decideSwipe(drag.x, drag.y, v.x, v.y, size.x, size.y, fling)
                        scope.launch { offset.snapTo(Offset.Zero) }
                        when (swipe) {
                            Swipe.Next -> controller.next()
                            Swipe.Learned -> controller.markLearned()
                            Swipe.None -> scope.launch { offset.animateTo(Offset.Zero) }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(Offset.Zero) } },
                )
            }
            .graphicsLayer {
                translationX = offset.value.x
                translationY = offset.value.y.coerceAtLeast(0f)
            }
            .semantics { contentDescription = "Tap to reveal, swipe sideways for the next word, swipe down when learned" },
    ) {
        // The chosen size scales every text on the card, on top of the system font size. (Scaling
        // the density's fontScale instead does little for the word: Android 14+ scales large text
        // nonlinearly.)
        val k = textSize.scale
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FittedWord(word.word, MaterialTheme.typography.displayMedium.copy(fontSize = 44.sp).scaled(k))
            if (revealed) {
                if (word.ipa != null) Text(word.ipa, style = MaterialTheme.typography.titleMedium.scaled(k), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalIconButton(onClick = { onPlay(word) }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play") }
                Text(
                    word.definition ?: "No definition yet. Add one under Words.",
                    style = MaterialTheme.typography.titleLarge.scaled(k),
                    color = if (word.definition == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    textAlign = TextAlign.Center,
                )
                if (word.example != null) Text(word.example, style = MaterialTheme.typography.bodyLarge.scaled(k), fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
                if (word.finnish != null) Text(word.finnish, style = MaterialTheme.typography.titleMedium.scaled(k), color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            }
        }
        if (!revealed) {
            Text(
                "Tap to reveal · swipe sideways for next · swipe down when learned",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
        }
    }
}

@Composable
private fun Empty(onAdd: () -> Unit, onWords: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("No words to learn. Add a word, or import your Kindle's vocabulary.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) { Text("Add a word") }
        TextButton(onClick = onWords) { Text("All words") }
    }
}

@Composable
private fun Menu(onAdd: () -> Unit, onWords: () -> Unit, onDisplay: () -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Add a word") }, onClick = { open = false; onAdd() })
            DropdownMenuItem(text = { Text("Words") }, onClick = { open = false; onWords() })
            DropdownMenuItem(text = { Text("Display…") }, onClick = { open = false; onDisplay() })
        }
    }
}

/** Theme and word size; each choice applies at once. */
@Composable
private fun DisplayDialog(display: Display, onTheme: (ThemeMode) -> Unit, onSize: (TextSize) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Display") },
        text = {
            Column {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                ThemeMode.entries.forEach { mode -> Choice(mode.label, display.theme == mode) { onTheme(mode) } }
                Text("Word size", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                TextSize.entries.forEach { size -> Choice(size.label, display.size == size) { onSize(size) } }
            }
        },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun TextStyle.scaled(k: Float) = copy(fontSize = fontSize * k, lineHeight = lineHeight * k)

/** The word on one line when it can be: shrunk (down to [MIN_WORD_SP]) until it fits the width. */
@Composable
private fun FittedWord(text: String, style: TextStyle) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val width = constraints.maxWidth
        val fitted = remember(text, style, width) {
            var s = style
            while (s.fontSize.value > MIN_WORD_SP && measurer.measure(text, s, softWrap = false, maxLines = 1).size.width > width) {
                s = s.scaled(0.9f)
            }
            s
        }
        Text(text, style = fitted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

private const val MIN_WORD_SP = 24f
