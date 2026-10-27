package io.github.olrut.vocab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.olrut.vocab.Screen
import io.github.olrut.vocab.api.User

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
}

/** First run: where the server is (its Tailscale HTTPS address). */
@Composable
fun ServerScreen(state: Screen.Server, onConnect: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf(state.input) }
    Page {
        Text("Vocab", style = MaterialTheme.typography.headlineMedium)
        Text("The server's address, e.g. https://vocab.tail1234.ts.net (from tailscale serve on the server).")
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Server") },
            singleLine = true,
            enabled = !state.checking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onConnect(input) }),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(state.error)
        Button(onClick = { onConnect(input) }, enabled = !state.checking, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.checking) "Connecting…" else "Connect")
        }
    }
}

/** The same user list as the web login; a PIN field opens for users that have one. */
@Composable
fun UserPickerScreen(state: Screen.Users, onLogin: (User, String?) -> Unit, onRetry: () -> Unit, onChangeServer: () -> Unit) {
    var pinFor by remember { mutableStateOf<User?>(null) }
    var pin by rememberSaveable { mutableStateOf("") }
    Page {
        Text("Who is learning?", style = MaterialTheme.typography.headlineMedium)
        val users = state.users
        when {
            users == null && state.error == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            users != null && users.isEmpty() -> Text("No users yet. Create one in the web app.")
        }
        users?.forEach { user ->
            OutlinedButton(
                onClick = {
                    if (user.hasPin) {
                        pinFor = user
                        pin = ""
                    } else {
                        onLogin(user, null)
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(user.name) }
            if (pinFor == user) {
                // Straight into the PIN field, keyboard up; Go on the keyboard logs in.
                val focus = remember { FocusRequester() }
                LaunchedEffect(user) { focus.requestFocus() }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("PIN for ${user.name}") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (pin.isNotEmpty()) onLogin(user, pin) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Button(onClick = { onLogin(user, pin) }, enabled = !state.busy && pin.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Log in")
                }
            }
        }
        ErrorText(state.error)
        if (state.error != null && users == null) OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onChangeServer, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Change server") }
    }
}
