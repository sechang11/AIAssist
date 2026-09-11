package com.aitextassistant.generate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * One HTTP call to an Ollama server, shared by everything that needs a model.
 *
 * The schema is the important argument. Ollama constrains decoding to it, which
 * on the eval was worth roughly sixty points of validity on small models and is
 * the single reason a 1.5B is usable here. Pass one wherever the shape matters.
 */
class OllamaClient(private val host: String, private val model: String) {

    suspend fun chat(
        system: String,
        user: String,
        schema: JSONObject? = null,
        temperature: Double = 0.7,
        maxTokens: Int = 300,
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put(
                "options",
                JSONObject().put("temperature", temperature).put("num_predict", maxTokens),
            )
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        if (schema != null) body.put("format", schema)

        val raw = try {
            post("$host/api/chat", body.toString())
        } catch (e: GenerationException) {
            throw e
        } catch (e: Exception) {
            throw GenerationException(
                "Could not reach the model at $host. Same wifi? Is ollama serve running?", e,
            )
        }

        try {
            JSONObject(raw).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            throw GenerationException("The server replied with something unexpected.", e)
        }
    }

    private fun post(url: String, payload: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray()) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
                throw GenerationException(
                    "Server said ${connection.responseCode}. ${detail.orEmpty().take(200)}",
                )
            }
            return connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** An array of exactly [n] items of [items], which is most of what we need. */
        fun fixedArray(n: Int, items: JSONObject): JSONObject = JSONObject()
            .put("type", "array")
            .put("minItems", n)
            .put("maxItems", n)
            .put("items", items)

        fun obj(required: List<String>, properties: JSONObject): JSONObject = JSONObject()
            .put("type", "object")
            .put("required", JSONArray(required))
            .put("properties", properties)

        val string: JSONObject get() = JSONObject().put("type", "string")
        val boolean: JSONObject get() = JSONObject().put("type", "boolean")
    }
}
