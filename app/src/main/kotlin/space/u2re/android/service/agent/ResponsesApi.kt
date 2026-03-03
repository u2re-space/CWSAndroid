package space.u2re.cws.agent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import space.u2re.cws.network.HttpResult
import space.u2re.cws.network.postJson

private const val RESPONSES_DEFAULT_MODEL = "gpt-5.2"
private val responsesGson = Gson()

fun buildResponsesRequest(prompt: String, model: String = RESPONSES_DEFAULT_MODEL): Map<String, Any> {
    return mapOf(
        "model" to model,
        "input" to prompt,
    )
}

suspend fun sendResponsesRequest(
    endpoint: String,
    apiKey: String,
    prompt: String,
    allowInsecureTls: Boolean,
    timeoutMs: Int = 15_000,
    model: String = RESPONSES_DEFAULT_MODEL
): HttpResult {
    return postJson(
        url = endpoint,
        json = buildResponsesRequest(prompt, model),
        allowInsecureTls = allowInsecureTls,
        timeoutMs = timeoutMs,
        headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}")
    )
}

fun parseResponsesText(raw: String): String {
    return try {
        val root = responsesGson.fromJson<Map<String, Any?>?>(
            raw,
            object : TypeToken<Map<String, Any?>>() {}.type
        ) ?: return raw

        val output = root["output"] as? List<*>
        if (output != null) {
            val outputText = extractTextFromOutputs(output)
            if (outputText.isNotBlank()) return outputText
        }

        val choices = root["choices"] as? List<*>
        if (choices != null) {
            val choiceText = extractTextFromChoices(choices)
            if (choiceText.isNotBlank()) return choiceText
        }

        val content = root["content"]
        when {
            content is String -> content.trim()
            content is List<*> -> extractTextFromOutputs(content).trim()
            else -> raw
        }
    } catch (_: Exception) {
        raw
    }
}

private fun extractTextFromOutputs(outputs: List<*>): String {
    return outputs.mapNotNull { output ->
        val outputObject = output as? Map<*, *> ?: return@mapNotNull null
        val content = outputObject["content"]
        readText(content)
    }.joinToString("\n").trim()
}

private fun extractTextFromChoices(choices: List<*>): String {
    val firstChoice = choices.firstOrNull() as? Map<*, *> ?: return ""
    val message = firstChoice["message"] as? Map<*, *> ?: return ""
    val content = message["content"]
    return readText(content)
}

private fun readText(value: Any?): String {
    return when {
        value == null -> ""
        value is String -> value.trim()
        value is Map<*, *> -> {
            val directText = value["text"]
            when {
                directText is String -> directText.trim()
                directText != null -> readText(directText)
                else -> readText(value["value"])
            }
        }
        value is List<*> -> value.mapNotNull { item ->
            val text = readText(item)
            if (text.isBlank()) null else text
        }.joinToString("\n").trim()
        else -> value.toString().trim()
    }
}
