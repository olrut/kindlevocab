package io.github.olrut.vocab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.olrut.vocab.claude.ClaudeConfig
import io.github.olrut.vocab.claude.ClaudeModel
import io.github.olrut.vocab.claude.ClaudeSettings

/**
 * The user's own Claude API key and model. [onSave] returns why the key was refused, or null.
 * The key is never shown again after it is saved, only its last characters.
 */
@Composable
fun ClaudeDialog(
    config: ClaudeConfig?,
    onSave: (String, ClaudeModel) -> String?,
    onModel: (ClaudeModel) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(config?.model ?: ClaudeModel.Opus) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Claude lookups") },
        confirmButton = {
            if (config == null) {
                TextButton(onClick = { error = onSave(key, model); if (error == null) onDismiss() }) { Text("Save") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (config == null) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            } else {
                TextButton(onClick = { onRemove(); key = "" }) { Text("Remove key") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "With your own Anthropic API key, lookups use Claude: definitions, examples and the Finnish equivalent, " +
                        "and for Kindle words the meaning that fits the book's sentence. The dictionary still gives the " +
                        "pronunciation. Lookups are billed to your Anthropic account (console.anthropic.com). " +
                        "The key is stored encrypted on this phone, left out of backups, and sent only to Anthropic.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (config == null) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it; error = null },
                        label = { Text("API key (sk-ant-…)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                } else {
                    Text("Key: ${ClaudeSettings.masked(config.apiKey)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                }
                Text("Model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                ClaudeModel.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = model == m, role = Role.RadioButton) {
                            model = m
                            if (config != null) onModel(m)
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model == m, onClick = null)
                        Text(m.label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
    )
}
