package com.lian.plus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lian.plus.core.LianRuntime
import com.lian.plus.data.AppSettings
import com.lian.plus.server.ServerService
import com.lian.plus.ui.theme.Lian
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Turns the phone into an OpenAI-compatible endpoint that other apps on the
 * device — or, if the user opts in, on the same network — can call.
 */
@Composable
fun ServerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { LianRuntime.get(context) }
    val settings by runtime.settingsStore.settings
        .map { it }
        .collectAsState(initial = AppSettings())
    val loaded by runtime.llm.loaded.collectAsState()
    val scope = rememberCoroutineScopeSafe()

    var port by remember(settings.serverPort) { mutableStateOf(settings.serverPort.toString()) }
    var apiKey by remember(settings.serverApiKey) { mutableStateOf(settings.serverApiKey) }

    val host = if (settings.serverLanExposed) {
        ServerService.localIpAddress() ?: "0.0.0.0"
    } else "127.0.0.1"
    val baseUrl = "http://$host:${settings.serverPort}/v1"

    Column(Modifier.fillMaxSize().background(Lian.Background)) {
    LianTopBar(title = "Local API server", onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Serves the loaded model over an OpenAI-compatible HTTP API. Point any " +
                "client at the base URL below and nothing leaves this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (settings.serverEnabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            if (settings.serverEnabled) "Running" else "Stopped",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(baseUrl, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = settings.serverEnabled,
                        enabled = loaded != null || settings.serverEnabled,
                        onCheckedChange = { on ->
                            scope.launch {
                                runtime.settingsStore.update { it.copy(serverEnabled = on) }
                                if (on) ServerService.start(context) else ServerService.stop(context)
                            }
                        },
                    )
                }
                if (loaded == null) {
                    Text(
                        "Load a text model before starting the server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key (optional)") },
            supportingText = {
                Text("When set, callers must send it as an Authorization: Bearer header.")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Expose to the local network", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Off means loopback only — nothing outside this phone can connect. " +
                        "Turning it on lets any device on the same Wi-Fi reach the API, so " +
                        "set an API key first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.serverLanExposed,
                onCheckedChange = { on ->
                    scope.launch { runtime.settingsStore.update { it.copy(serverLanExposed = on) } }
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                scope.launch {
                    runtime.settingsStore.update {
                        it.copy(
                            serverPort = port.toIntOrNull()?.coerceIn(1024, 65535) ?: 8080,
                            serverApiKey = apiKey.trim(),
                        )
                    }
                    if (settings.serverEnabled) {
                        ServerService.stop(context)
                        ServerService.start(context)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and restart the server") }

        Spacer(Modifier.height(24.dp))
        Text("Endpoints", style = MaterialTheme.typography.titleMedium)
        listOf(
            "GET  /v1/models" to "List the models installed on this device",
            "POST /v1/chat/completions" to "Chat, with optional streaming, tools and retrieval",
            "POST /v1/completions" to "Raw completion with no chat template",
            "POST /v1/embeddings" to "Vectors from the loaded embedding model",
            "POST /v1/images/generations" to "Diffusion, returned as base64 PNG",
            "GET  /health" to "Liveness and which model is loaded",
        ).forEach { (route, desc) ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(route, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Example", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Text(
                """
                curl $baseUrl/chat/completions \
                  -H 'Content-Type: application/json' \
                  -d '{"model":"local","stream":true,
                       "messages":[{"role":"user","content":"Hello"}]}'
                """.trimIndent(),
                Modifier.padding(12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { ServerService.stop(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Force stop the server") }

        Spacer(Modifier.height(32.dp))
    }
    }
}
