package space.u2re.service.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import space.u2re.service.daemon.normalizeResponsesEndpoint
import space.u2re.service.daemon.postJson

@Serializable
data class ResponsesAssistantRoute(
    val apiEndpoint: String = "",
    val apiKey: String = "",
    val aiAllowInsecureTls: Boolean = false
)

private data class UiMessage(val role: String, val text: String)

@Composable
fun ResponsesAssistantScreen(
    route: ResponsesAssistantRoute,
    onClose: () -> Unit
) {
    val endpoint = remember(route.apiEndpoint) { normalizeResponsesEndpoint(route.apiEndpoint) ?: route.apiEndpoint }
    val apiKey = remember(route.apiKey) { route.apiKey }
    val allowInsecure = remember(route.aiAllowInsecureTls) { route.aiAllowInsecureTls }

    var prompt by rememberSaveable { mutableStateOf("") }
    var sending by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Ready") }
    val messages = remember { mutableStateOf(emptyList<UiMessage>()) }
    val scope = rememberCoroutineScope()

    fun sendPrompt(draft: String) {
        val normalizedPrompt = draft.trim()
        if (sending || normalizedPrompt.isBlank() || endpoint.isBlank() || apiKey.isBlank()) {
            return
        }

        scope.launch {
            messages.value = messages.value + UiMessage("user", normalizedPrompt)
            prompt = ""
            sending = true
            status = "Sending to /responses…"

            try {
                val response = withContext(Dispatchers.IO) {
                    postJson(
                        url = endpoint,
                        json = mapOf("input" to normalizedPrompt, "model" to "gpt-4o-mini"),
                        allowInsecureTls = allowInsecure,
                        timeoutMs = 15_000,
                        headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}")
                    )
                }
                if (response.ok) {
                    val content = extractResponsesText(response.body).ifBlank { response.body }
                    messages.value = messages.value + UiMessage("assistant", content)
                    status = "Response ${response.status}"
                } else {
                    messages.value = messages.value + UiMessage(
                        "assistant",
                        "Request failed with status: ${response.status}"
                    )
                    status = "Failed ${response.status}"
                }
            } catch (e: Exception) {
                messages.value = messages.value + UiMessage(
                    "assistant",
                    "Request error: ${e.message ?: "error"}"
                )
                status = "Error"
            } finally {
                sending = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Local AI", style = MaterialTheme.typography.titleLarge)
        Text("Endpoint: ${if (endpoint.isBlank()) "Not configured" else endpoint}")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(messages.value) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (item.role == "user") Arrangement.End else Arrangement.Start
                    ) {
                        val color = if (item.role == "user") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        Text(
                            text = "${item.role}: ${item.text}",
                            color = color,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("Type a message") },
                modifier = Modifier.fillMaxWidth(0.78f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    sendPrompt(prompt)
                })
            )
            Button(
                onClick = { sendPrompt(prompt) },
                enabled = !sending && prompt.isNotBlank() && endpoint.isNotBlank() && apiKey.isNotBlank()
            ) {
                Text(if (sending) "..." else "Send")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(status)
            Button(onClick = onClose) {
                Text("Close")
            }
        }
    }
}

private val responsesGson = Gson()

private fun extractResponsesText(raw: String): String {
    return try {
        val root = responsesGson.fromJson(raw, object : TypeToken<Map<String, Any>>() {}.type) as? Map<*, *> ?: return raw
        val output = root["output"] as? List<*> ?: run {
            val choices = root["choices"] as? List<*> ?: return raw
            val first = choices.firstOrNull() as? Map<*, *> ?: return raw
            val message = first["message"] as? Map<*, *> ?: return raw
            return (message["content"] as? String)?.trim().orEmpty().ifBlank { raw }
        }
        val firstMessage = output.firstOrNull() as? Map<*, *> ?: return raw
        val content = firstMessage["content"] as? List<*> ?: return raw
        val text = content.mapNotNull { item ->
            (item as? Map<*, *>)?.let { it["text"] as? String }
        }.joinToString("\n")
        text.ifBlank { raw }
    } catch (_: Exception) {
        raw
    }
}
